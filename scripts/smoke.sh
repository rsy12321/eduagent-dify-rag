#!/usr/bin/env bash
set -euo pipefail

# Only call the eduagent service. Credentials belong in the service environment.
export SERVICE_BASE_URL="${SERVICE_BASE_URL:-http://127.0.0.1:8080}"
export SMOKE_USER_ID="${SMOKE_USER_ID:-smoke-$(date +%s)}"
export SMOKE_QUESTION="${SMOKE_QUESTION:-惯性是什么？}"
export SMOKE_STREAM_QUESTION="${SMOKE_STREAM_QUESTION:-请出两道关于浮力的判断题并附答案。}"
SMOKE_RUN_EVAL="${SMOKE_RUN_EVAL:-1}"
command -v curl >/dev/null
command -v python3 >/dev/null
json_tool_supports_unicode=0
if python3 -m json.tool --help 2>/dev/null | grep -q -- '--no-ensure-ascii'; then
  json_tool_supports_unicode=1
fi
pretty_json() {
  if [ "$json_tool_supports_unicode" = 1 ]; then
    python3 -m json.tool --no-ensure-ascii "$1"
  else
    python3 -m json.tool "$1"
  fi
}
smoke_dir=$(mktemp -d)
trap 'rm -rf "$smoke_dir"' EXIT
base="${SERVICE_BASE_URL%/}"

python3 - <<'PY' > "$smoke_dir/request.json"
import json, os
print(json.dumps({'question': os.environ['SMOKE_QUESTION'], 'userId': os.environ['SMOKE_USER_ID']}, ensure_ascii=False))
PY
printf 'POST /api/chat\n'
curl --fail --silent --show-error --max-time "${SMOKE_CHAT_TIMEOUT:-130}" \
  -H 'Content-Type: application/json' --data-binary "@$smoke_dir/request.json" \
  "$base/api/chat" > "$smoke_dir/reply.json"
pretty_json "$smoke_dir/reply.json"
conversation_id=$(python3 - "$smoke_dir/reply.json" <<'PY'
import json, sys
reply = json.load(open(sys.argv[1], encoding='utf-8'))
value = reply['conversationId']
if not isinstance(value, str) or not value:
    raise SystemExit('Missing conversationId')
print(value)
PY
)
export SMOKE_CONVERSATION_ID="$conversation_id"
python3 - <<'PY' > "$smoke_dir/followup.json"
import json, os
print(json.dumps({'question': '请给一个生活中的例子。', 'userId': os.environ['SMOKE_USER_ID'],
                  'conversationId': os.environ['SMOKE_CONVERSATION_ID']}, ensure_ascii=False))
PY
printf '\nPOST /api/chat（同一会话追问）\n'
curl --fail --silent --show-error --max-time "${SMOKE_CHAT_TIMEOUT:-130}" \
  -H 'Content-Type: application/json' --data-binary "@$smoke_dir/followup.json" \
  "$base/api/chat" > "$smoke_dir/followup-reply.json"
pretty_json "$smoke_dir/followup-reply.json"

python3 - <<'PY' > "$smoke_dir/stream.json"
import json, os
print(json.dumps({'question': os.environ['SMOKE_STREAM_QUESTION'], 'userId': os.environ['SMOKE_USER_ID']}, ensure_ascii=False))
PY
printf '\nPOST /api/chat/stream\n'
curl --fail --silent --show-error --no-buffer --max-time "${SMOKE_STREAM_TIMEOUT:-200}" \
  -H 'Content-Type: application/json' --data-binary "@$smoke_dir/stream.json" \
  "$base/api/chat/stream" | tee "$smoke_dir/stream.txt"
python3 - "$smoke_dir/stream.txt" <<'PY'
import sys
text = open(sys.argv[1], encoding='utf-8').read()
if 'event:error' in text or 'event:done' not in text:
    raise SystemExit('Stream failed or did not emit done')
PY
encoded_id=$(python3 - <<'PY'
import os, urllib.parse
print(urllib.parse.quote(os.environ['SMOKE_CONVERSATION_ID'], safe=''))
PY
)
printf '\nGET /api/conversations/{conversationId}/messages\n'
curl --fail --silent --show-error --max-time 30 "$base/api/conversations/$encoded_id/messages" \
  > "$smoke_dir/history.json"
pretty_json "$smoke_dir/history.json"

if [ "$SMOKE_RUN_EVAL" = 1 ]; then
  printf '\nPOST /api/eval/run（串行评测，可能需要较长时间）\n'
  curl --fail --silent --show-error --max-time "${SMOKE_EVAL_TIMEOUT:-4000}" \
    -X POST "$base/api/eval/run" > "$smoke_dir/eval.json"
  pretty_json "$smoke_dir/eval.json"
else
  printf '\nSMOKE_RUN_EVAL != 1，跳过批量评测。\n'
fi
