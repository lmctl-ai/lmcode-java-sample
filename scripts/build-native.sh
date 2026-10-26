#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
if ! command -v native-image >/dev/null 2>&1; then
  echo "native-image is required" >&2
  exit 2
fi
mvn -q -DskipTests package
mkdir -p "$root/build"
out="$root/build/lmcode-java-sample-native"
flags=(
  -H:+UnlockExperimentalVMOptions
  -J-Xmx4g
  -H:NumberOfThreads=2
  --no-fallback
  -march=compatibility
  -H:+ReportExceptionStackTraces
  -H:IncludeResources=sample.dsl.json
  -cp "$root/target/classes"
)
if [[ "$(uname -s)" == Linux* ]]; then
  flags+=("-H:+StaticExecutableWithDynamicLibC" "-H:NativeLinkerOption=-static-libstdc++" "-H:NativeLinkerOption=-static-libgcc")
fi
native-image "${flags[@]}" io.lmctl.sample.Main "$out"
