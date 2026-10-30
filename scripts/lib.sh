# Shared helpers for demo.sh and bench.sh. Source, don't run.
BASE=${BASE:-http://localhost:7890}
OAUTH=${OAUTH:-http://localhost:7891}

# The services validate the issuer as http://oauth:8080/cellforge (its in-network name), and
# mock-oauth2-server derives the issuer from the Host header. The client_id becomes the subject.
token() {
  curl -sf -H "Host: oauth:8080" -d grant_type=client_credentials -d "client_id=$1" -d client_secret=unused \
    -d "scope=$2" "$OAUTH/cellforge/token" | jq -r .access_token
}

# call TOKEN SLUG METHOD PATH [BODY] [extra curl args...]; prints the body, then the status on its own line.
# SLUG may be empty for calls outside a workspace.
call() {
  local tok=$1 slug=$2 method=$3 path=$4 body=${5:-}
  shift 5 || shift $#
  local extra=()
  [[ -n $slug ]] && extra+=(-H "X-Tenant: $slug")
  curl -s -w '\n%{http_code}' -X "$method" "$BASE$path" -H "Authorization: Bearer $tok" \
    -H 'Content-Type: application/json' "${extra[@]}" "$@" ${body:+-d "$body"}
}

# expect STATUS TOKEN SLUG METHOD PATH [BODY] [extra...]; prints the body or fails loudly.
expect() {
  local want=$1 out status
  shift
  out=$(call "$@")
  status=${out##*$'\n'}
  if [[ $status != "$want" ]]; then
    echo "FAIL: $3 $4 -> $status (wanted $want): ${out%$'\n'*}" >&2
    exit 1
  fi
  printf '%s' "${out%$'\n'*}"
}

# status TOKEN SLUG METHOD PATH [BODY] [extra...]; prints only the HTTP status.
status() {
  local out
  out=$(call "$@")
  echo "${out##*$'\n'}"
}

check() {
  if [[ $2 == "$3" ]]; then echo "  ok   $1"; else echo "  FAIL $1: got '$2', wanted '$3'" >&2; exit 1; fi
}

# percentile FILE P -> the P-th percentile of one number per line (nearest rank).
percentile() {
  sort -n "$1" | awk -v p="$2" '{a[NR]=$1} END {i=int((p/100)*NR+0.999); if (i<1) i=1; print a[i]}'
}
