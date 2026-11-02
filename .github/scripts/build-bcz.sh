#!/usr/bin/env bash
# Builds the bcz tool for CI jobs with javac alone: core and cli have no dependencies and no
# resources, so Gradle (download + daemon start, ~40 s) is not needed and nothing is cached.
# Usage: build-bcz.sh [checkout]   (default: .)
# Result: <checkout>/cli/build/install/bcz/bin/bcz, the same path as ./gradlew :cli:installDist.
set -euo pipefail
root="${1:-.}"
out="$root/cli/build/install/bcz"
rm -rf "$out"
mkdir -p "$out/bin" "$out/classes"
find "$root/core/src/main/java" "$root/cli/src/main/java" -name '*.java' > "$out/sources.txt"
javac --release 17 -encoding UTF-8 -nowarn -d "$out/classes" @"$out/sources.txt"
rm "$out/sources.txt"
cat > "$out/bin/bcz" <<'EOF'
#!/usr/bin/env bash
exec java -cp "$(dirname "$(readlink -f "$0")")/../classes" io.github.bitecheckpz.cli.Main "$@"
EOF
chmod +x "$out/bin/bcz"
