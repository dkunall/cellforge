#!/usr/bin/env bash
# Walks CellForge end to end against the compose stack: a workspace with a teammate, a board with
# concurrent edits and idempotent retries, isolation between workspaces, billing on a trial, a noisy
# neighbour hitting its rate limit, and a live move of a workspace to the other cell. Needs curl and jq.
set -euo pipefail
source "$(dirname "$0")/lib.sh"

RUN=$(date +%s)
OWNER=$(token "asha-$RUN" cellforge.user)
MATE=$(token "ravi-$RUN" cellforge.user)
STRANGER=$(token "sonu-$RUN" cellforge.user)
ADMIN=$(token "ops-$RUN" cellforge.admin)
SLUG="acme-$RUN"

echo "workspace and team"
NEW=$(jq -cn --arg s "$SLUG" '{slug: $s, name: "Acme", plan: "PRO"}')
TENANT=$(expect 201 "$OWNER" "" POST /v1/tenants "$NEW" | jq -r .id)
check "creating it again returns the same workspace" "$(expect 200 "$OWNER" "" POST /v1/tenants "$NEW" | jq -r .id)" "$TENANT"
INVITE=$(expect 201 "$OWNER" "$SLUG" POST /v1/invites "{\"email\": \"ravi-$RUN@example.test\", \"role\": \"MEMBER\"}" | jq -r .token)
expect 200 "$MATE" "$SLUG" POST /v1/invites/accept "{\"token\": \"$INVITE\"}" > /dev/null
check "two members" "$(expect 200 "$OWNER" "$SLUG" GET /v1/members | jq length)" 2

echo "board"
PROJECT=$(expect 201 "$OWNER" "$SLUG" POST /v1/projects '{"key": "WEB", "name": "Website"}' | jq -r .id)
TASK=$(expect 201 "$OWNER" "$SLUG" POST "/v1/projects/$PROJECT/tasks" '{"title": "Launch page"}' | jq -r .id)
expect 201 "$OWNER" "$SLUG" POST "/v1/projects/$PROJECT/tasks" '{"title": "Pricing page"}' > /dev/null
expect 200 "$OWNER" "$SLUG" POST "/v1/tasks/$TASK/move" '{"status": "IN_PROGRESS"}' > /dev/null
expect 201 "$OWNER" "$SLUG" POST "/v1/tasks/$TASK/comments" "{\"body\": \"@ravi-$RUN can you review?\"}" > /dev/null
check "ravi sees the mention" "$(expect 200 "$MATE" "$SLUG" GET /v1/me/digest | jq '.unread | map(.kind) | index("MENTIONED") != null')" true

ETAG=$(curl -s -o /dev/null -D - "$BASE/v1/tasks/$TASK" -H "Authorization: Bearer $OWNER" -H "X-Tenant: $SLUG" \
  | awk -F': ' 'tolower($1) == "etag" {print $2}' | tr -d '\r')
OUT=$(mktemp -d)
for i in 1 2 3 4 5; do
  status "$OWNER" "$SLUG" PATCH "/v1/tasks/$TASK" "{\"title\": \"Edit $i\"}" -H "If-Match: $ETAG" > "$OUT/$i" &
done
wait
check "5 edits against one version: one wins" "$(cat "$OUT"/* | sort | uniq -c | awk '{printf "%s×%s ", $1, $2}')" "1×200 4×412 "
for i in 1 2 3 4; do
  expect 201 "$OWNER" "$SLUG" POST "/v1/projects/$PROJECT/tasks" '{"title": "Once"}' -H "Idempotency-Key: once-$RUN" \
    | jq -r .id > "$OUT/k$i" &
done
wait
check "4 retries with one Idempotency-Key make one task" "$(cat "$OUT"/k* | sort -u | wc -l)" 1

echo "isolation"
OTHER="other-$RUN"
expect 201 "$STRANGER" "" POST /v1/tenants "{\"slug\": \"$OTHER\", \"name\": \"Other\"}" > /dev/null
check "acme's task from another workspace" "$(status "$STRANGER" "$OTHER" GET "/v1/tasks/$TASK")" 404
check "a stranger inside acme" "$(expect 403 "$STRANGER" "$SLUG" GET "/v1/projects/$PROJECT/board" | jq -r .code)" not-a-member
check "a user on the internal API" "$(status "$OWNER" "" POST "/internal/tenants/$TENANT/move" '{"targetCell": "cell-a"}')" 403

echo "billing"
check "a new PRO workspace is trialing" "$(expect 200 "$OWNER" "$SLUG" GET /v1/billing | jq -r .status)" TRIALING
check "members cannot see billing" "$(expect 403 "$MATE" "$SLUG" GET /v1/billing | jq -r .code)" permission-denied
expect 200 "$OWNER" "$SLUG" PUT /v1/billing/payment-method '{"token": "tok_visa"}' > /dev/null
check "card on file" "$(expect 200 "$OWNER" "$SLUG" GET /v1/billing | jq -r .cardLast4)" 4242
expect 200 "$OWNER" "$SLUG" POST /v1/billing/plan '{"plan": "ENTERPRISE"}' -H "Idempotency-Key: up-$RUN" > /dev/null
REPLAY=$(curl -s -o /dev/null -D - -X POST "$BASE/v1/billing/plan" -H "Authorization: Bearer $OWNER" -H "X-Tenant: $SLUG" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: up-$RUN" -d '{"plan": "ENTERPRISE"}' \
  | awk -F': ' 'tolower($1) == "idempotent-replayed" {print $2}' | tr -d '\r')
check "the retried plan change is a replay" "$REPLAY" true
check "now on ENTERPRISE" "$(expect 200 "$OWNER" "$SLUG" GET /v1/billing | jq -r .plan)" ENTERPRISE
check "an unsigned payment webhook" "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/webhooks/payments" \
  -H 'Content-Type: application/json' -H 'Payment-Signature: t=1,v1=00' -d '{}')" 400

echo "noisy neighbour"
NOISY="noisy-$RUN"
expect 201 "$STRANGER" "" POST /v1/tenants "{\"slug\": \"$NOISY\", \"name\": \"Noisy\", \"plan\": \"FREE\"}" > /dev/null
for i in $(seq 1 80); do status "$STRANGER" "$NOISY" GET /v1/projects > "$OUT/n$i" & done
wait
LIMITED=$(cat "$OUT"/n* | grep -c 429 || true)
echo "  $LIMITED of 80 burst requests from the FREE workspace got 429"
[[ $LIMITED -gt 0 ]] || { echo "  FAIL expected some 429s" >&2; exit 1; }
check "acme is unaffected" "$(status "$OWNER" "$SLUG" GET "/v1/projects/$PROJECT/board")" 200

echo "moving acme to the other cell"
BOARD=$(expect 200 "$OWNER" "$SLUG" GET "/v1/projects/$PROJECT/board" | jq -S .)
MOVED=$(call "$ADMIN" "" POST "/internal/tenants/$TENANT/move" '{"targetCell": "cell-b"}')
if [[ ${MOVED##*$'\n'} == 409 ]]; then
  MOVED=$(call "$ADMIN" "" POST "/internal/tenants/$TENANT/move" '{"targetCell": "cell-a"}')
fi
[[ ${MOVED##*$'\n'} == 200 ]] || { echo "  FAIL move: $MOVED" >&2; exit 1; }
echo "  $(jq -r '"moved from \(.from) to \(.to); writes paused \(.writesPausedMillis) ms; \(.digest.tasks.rows) tasks copied"' <<<"${MOVED%$'\n'*}")"
check "the board is the same after the move" "$(expect 200 "$OWNER" "$SLUG" GET "/v1/projects/$PROJECT/board" | jq -S .)" "$BOARD"
expect 201 "$OWNER" "$SLUG" POST "/v1/projects/$PROJECT/tasks" '{"title": "Written in the new cell"}' > /dev/null
echo "  ok   writes work in the new cell"
rm -rf "$OUT"
echo "demo passed"
