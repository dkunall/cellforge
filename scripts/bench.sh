#!/usr/bin/env bash
# Rough numbers against the compose stack, not a load test.
# 1. Noisy neighbour: a quiet workspace reads its board N times on its own, then again while a FREE
#    workspace hammers the API from C parallel loops. Prints p50/p99 for both runs and what the noisy
#    workspace got back.
# 2. Move: seeds a workspace with T tasks and moves it to the other cell, printing how long writes paused.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
N=${N:-200}
C=${C:-8}
T=${T:-200}
RUN=$(date +%s)
QUIET_USER=$(token "quiet-$RUN" cellforge.user)
NOISY_USER=$(token "noisy-$RUN" cellforge.user)
ADMIN=$(token "ops-$RUN" cellforge.admin)
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

expect 201 "$QUIET_USER" "" POST /v1/tenants "{\"slug\": \"quiet-$RUN\", \"name\": \"Quiet\", \"plan\": \"ENTERPRISE\"}" > /dev/null
expect 201 "$NOISY_USER" "" POST /v1/tenants "{\"slug\": \"noisy-$RUN\", \"name\": \"Noisy\", \"plan\": \"FREE\"}" > /dev/null
PROJECT=$(expect 201 "$QUIET_USER" "quiet-$RUN" POST /v1/projects '{"key": "QT", "name": "Quiet"}' | jq -r .id)
for i in $(seq 1 20); do
  expect 201 "$QUIET_USER" "quiet-$RUN" POST "/v1/projects/$PROJECT/tasks" "{\"title\": \"Task $i\"}" > /dev/null
done

# Milliseconds per request, one per line.
reads() {
  for _ in $(seq 1 "$N"); do
    curl -s -o /dev/null -w '%{http_code} %{time_total}\n' "$BASE/v1/projects/$PROJECT/board" \
      -H "Authorization: Bearer $QUIET_USER" -H "X-Tenant: quiet-$RUN"
  done | awk '$1 != 200 {bad++} {printf "%.1f\n", $2 * 1000 > "/dev/stderr"} END {if (bad) print bad " non-200" }' 2> "$1"
}

hammer() {
  while [[ ! -f $OUT/stop ]]; do
    curl -s -o /dev/null -w '%{http_code}\n' "$BASE/v1/projects" -H "Authorization: Bearer $NOISY_USER" \
      -H "X-Tenant: noisy-$RUN"
  done >> "$OUT/noisy-$1"
}

for _ in $(seq 1 20); do curl -s -o /dev/null "$BASE/v1/projects/$PROJECT/board" -H "Authorization: Bearer $QUIET_USER" -H "X-Tenant: quiet-$RUN"; done
reads "$OUT/alone"
for c in $(seq 1 "$C"); do hammer "$c" & done
sleep 1
reads "$OUT/noisy"
touch "$OUT/stop"
wait
echo "quiet workspace, $N sequential board reads (ms):"
echo "  alone:              p50 $(percentile "$OUT/alone" 50)  p99 $(percentile "$OUT/alone" 99)"
echo "  with $C noisy loops: p50 $(percentile "$OUT/noisy" 50)  p99 $(percentile "$OUT/noisy" 99)"
echo "noisy FREE workspace responses: $(cat "$OUT"/noisy-* | sort | uniq -c | awk '{printf "%s×%s ", $1, $2}')"

MOVER=$(token "mover-$RUN" cellforge.user)
TENANT=$(expect 201 "$MOVER" "" POST /v1/tenants "{\"slug\": \"mover-$RUN\", \"name\": \"Mover\", \"plan\": \"ENTERPRISE\"}" | jq -r .id)
MP=$(expect 201 "$MOVER" "mover-$RUN" POST /v1/projects '{"key": "MV", "name": "Move me"}' | jq -r .id)
for i in $(seq 1 "$T"); do
  expect 201 "$MOVER" "mover-$RUN" POST "/v1/projects/$MP/tasks" "{\"title\": \"Task $i\"}" > /dev/null
done
MOVED=$(call "$ADMIN" "" POST "/internal/tenants/$TENANT/move" '{"targetCell": "cell-b"}')
[[ ${MOVED##*$'\n'} == 409 ]] && MOVED=$(call "$ADMIN" "" POST "/internal/tenants/$TENANT/move" '{"targetCell": "cell-a"}')
echo "move of a workspace with $T tasks: $(jq -r '"\(.from) -> \(.to), writes paused \(.writesPausedMillis) ms, \(.digest.activity.rows) activity rows"' <<<"${MOVED%$'\n'*}")"
