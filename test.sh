#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd)"
cd "$root"
python3 -B scripts/sync.py check
./build.sh
python3 -B sample-suite/audit.py --std vendor/lmcode-java/std/lmcode-std.schema.json
bash sample-suite/run-suite.sh java -jar "$root/target/lmcode-java-sample.jar"
if [[ "${RUN_NATIVE:-0}" == "1" ]]; then
  ./scripts/build-native.sh
  bash sample-suite/run-suite.sh "$root/build/lmcode-java-sample-native"
fi
python3 -B scripts/check-bytecode.py
python3 -B sample-suite/test_runner.py
