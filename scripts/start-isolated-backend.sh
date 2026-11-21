#!/usr/bin/env bash
# Starts the shared synthetic Samford mock backend in isolated mode (throwaway database, loopback
# only) at the pinned commit. Used by CI; works locally on Linux/macOS too.
set -euo pipefail

SHA="${SAMFORD_BACKEND_SHA:-83be4ae028d9f995cd96cbdf00b430ea61bc3795}"
PORT="${SAMFORD_BACKEND_PORT:-4321}"
WORK="${RUNNER_TEMP:-/tmp}"

git clone --quiet https://github.com/shivam098/samford-web-app.git "$WORK/samford-backend"
cd "$WORK/samford-backend"
git checkout --quiet "$SHA"
npm ci --no-audit --no-fund
nohup npm run mobile:isolated -- --port "$PORT" --data "$WORK/samford-db" --reset --relaxed-limits --test-support \
  > "$WORK/samford.log" 2>&1 &

for _ in $(seq 1 120); do
  if curl -fsS "http://127.0.0.1:$PORT/api/mobile/v1/meta"; then
    echo
    echo "Samford backend ready on port $PORT (commit $SHA)"
    exit 0
  fi
  sleep 1
done
echo "Backend did not start" >&2
cat "$WORK/samford.log" >&2
exit 1
