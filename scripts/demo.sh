#!/usr/bin/env bash
# 录屏用：调用本服务的流式接口，把 SSE 整理成可读输出。
# 用法：bash scripts/demo.sh "你的问题"
set -euo pipefail
BASE="${SERVICE_BASE_URL:-http://127.0.0.1:8080}"
Q="${1:?用法: bash scripts/demo.sh \"问题\"}"
printf '\n\033[1;36m[提问]\033[0m %s\n\n' "$Q"
BODY=$(python3 -c 'import json,sys; print(json.dumps({"question":sys.argv[1],"userId":"demo"}, ensure_ascii=False))' "$Q")
curl -sN -X POST "$BASE/api/chat/stream" -H 'Content-Type: application/json' -d "$BODY" | python3 -u -c '
import sys, json
ev = None
for line in sys.stdin:
    line = line.rstrip("\n")
    if line.startswith("event:"):
        ev = line[6:].strip()
    elif line.startswith("data:"):
        d = json.loads(line[5:])
        if ev == "message":
            print(d.get("answer", ""), end="", flush=True)
        elif ev == "done":
            print("\n")
            print("\033[1;33m[路由]\033[0m", d.get("route"), "   \033[1;33m[耗时]\033[0m", d.get("latencyMs"), "ms")
            cs = d.get("citations") or []
            if not cs:
                print("\033[1;33m[引用]\033[0m 无")
            for c in cs:
                print("\033[1;33m[引用]\033[0m", c.get("documentName"), "#" + str(c.get("segmentPosition")), "score=%.2f" % (c.get("score") or 0))
        elif ev == "error":
            print("\n\033[1;31m[错误]\033[0m", d)
'
