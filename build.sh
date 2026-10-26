#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd)"
cd "$root"
mvn -q -DskipTests package
mkdir -p "$root/build"
cat > "$root/build/lmcode-java-sample" <<'EOF'
#!/usr/bin/env bash
dir="$(cd "$(dirname "$0")/.." && pwd)"
exec java -jar "$dir/target/lmcode-java-sample.jar" "$@"
EOF
chmod +x "$root/build/lmcode-java-sample"
cat > "$root/build/lmcode-java-sample.cmd" <<'EOF'
@echo off
java -jar "%~dp0..\target\lmcode-java-sample.jar" %*
EOF
