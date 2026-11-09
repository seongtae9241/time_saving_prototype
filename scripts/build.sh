#!/usr/bin/env bash
# 프론트를 빌드해 백엔드 정적 폴더에 넣고, 실행 가능한 jar 하나로 묶는다.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/frontend"
npm ci
npm run build
rm -rf "$ROOT/backend/src/main/resources/static"
mkdir -p "$ROOT/backend/src/main/resources/static"
cp -r dist/. "$ROOT/backend/src/main/resources/static/"
cd "$ROOT/backend"
sh ./mvnw -q -DskipTests package
echo "완료: java -jar backend/target/time-savings-0.1.0.jar  → http://localhost:8080"
