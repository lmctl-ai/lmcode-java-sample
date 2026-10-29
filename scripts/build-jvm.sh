#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p target/generated-resources build
printf '%s\n' "${SAMPLE_VERSION:-0.4.0}" > target/generated-resources/sample-version.txt
mvn -q -DskipTests package
cat > build/lmcode-java-sample <<'LAUNCH'
#!/usr/bin/env bash
root="$(cd "$(dirname "$0")/.." && pwd)"
exec java -jar "$root/target/lmcode-java-sample.jar" "$@"
LAUNCH
chmod +x build/lmcode-java-sample
