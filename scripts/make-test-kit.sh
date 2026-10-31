#!/usr/bin/env bash
# Builds BiteCheck-TestKit.zip: the launcher, three harmless test mods and a signed test index.
#
# The test index is produced by the real tool chain on a throwaway registry: keys, signed
# release, build records, reviews, a critical flag, lint, index, signature. The throwaway
# private keys are deleted at the end, so the kit can only ever vouch for the test mods.
#
# Usage: scripts/make-test-kit.sh [output-dir]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/build/test-kit}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cd "$ROOT"
GRADLE="./gradlew"
if [ -n "${BITECHECK_GRADLE:-}" ]; then GRADLE="$BITECHECK_GRADLE"; fi
$GRADLE -q :cli:installDist :launcher:launcherZip :examples:test-mods:testModsZip
BCZ="$ROOT/cli/build/install/bcz/bin/bcz"

# ---- throwaway registry ----
REG="$WORK/registry"
KEYS="$WORK/private-keys"
mkdir -p "$REG/keys/authors" "$REG/keys/reviewers" "$KEYS"
(cd "$REG/keys" && "$BCZ" keygen registry --dir "$KEYS" >/dev/null)
(cd "$REG/keys/authors" && "$BCZ" keygen test-author --dir "$KEYS" >/dev/null)
(cd "$REG/keys/reviewers" && "$BCZ" keygen test-reviewer-one --dir "$KEYS" >/dev/null \
    && "$BCZ" keygen test-reviewer-two --dir "$KEYS" >/dev/null)
cat > "$REG/keys/trust.toml" <<'EOF'
[reviewers.test-reviewer-one]
level = "trusted"
[reviewers.test-reviewer-two]
level = "reviewer"
EOF

MODS="$WORK/mods"
mkdir -p "$MODS"
unzip -q "$ROOT/examples/test-mods/build/distributions/BiteCheckTestMods.zip" -d "$MODS"

register() { # register <registry id> <kind>
    local id="$1" kind="$2"
    local jar="$MODS/BiteCheckTest$kind/42/media/java/BiteCheckTest$kind.jar"
    local dir="$REG/registry/mods/$id"
    mkdir -p "$dir/releases"
    cat > "$dir/mod.toml" <<EOF
id = "$id"
name = "BiteCheck Test: $kind"
author = "test-author"
source = "https://github.com/SmokerDoker/bitecheck-pz"
license = "GPL-3.0-only"
status = "active"
EOF
    cat > "$dir/releases/1.0.0.toml" <<EOF
mod = "$id"
version = "1.0.0"
commit = "$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || echo 0000000000000000000000000000000000000000)"

[build]
jdk = "temurin-21"
command = "./gradlew --no-daemon :examples:test-mods:jar$kind"
artifact = "examples/test-mods/build/libs/BiteCheckTest$kind.jar"
EOF
    "$BCZ" sign "$dir/releases/1.0.0.toml" --key "$KEYS/test-author.key" >/dev/null
    "$BCZ" build-record "$dir/releases/1.0.0.toml" "$jar" --builder test-a --builder test-b >/dev/null
    "$BCZ" sign "$dir/releases/1.0.0.build.toml" --key "$KEYS/registry.key" >/dev/null
    for reviewer in test-reviewer-one test-reviewer-two; do
        "$BCZ" review "$dir/releases/1.0.0.toml" "$jar" --reviewer "$reviewer" --verdict approve \
            --notes "Test mod: prints one line." >/dev/null
        "$BCZ" sign "$dir/reviews/1.0.0/$reviewer.toml" --key "$KEYS/$reviewer.key" >/dev/null
    done
}
register bitecheck-test-verified Verified
register bitecheck-test-flagged Flagged
# The unverified mod is deliberately not registered.

mkdir -p "$REG/registry/mods/bitecheck-test-flagged/flags"
cat > "$REG/registry/mods/bitecheck-test-flagged/flags/test-flag.toml" <<EOF
mod = "bitecheck-test-flagged"
version = "1.0.0"
jar_sha256 = "$(sha256sum "$MODS/BiteCheckTestFlagged/42/media/java/BiteCheckTestFlagged.jar" | cut -d' ' -f1)"
severity = "critical"
status = "open"
description = "TEST FLAG - this mod is harmless, the flag only demonstrates how BiteCheck handles flagged mods"
EOF

"$BCZ" lint --registry "$REG" --registry-key "$REG/keys/registry.pub"
for kind in Verified Flagged Unverified; do
    "$BCZ" scan "$MODS/BiteCheckTest$kind/42/media/java/BiteCheckTest$kind.jar" >/dev/null
done

# ---- kit ----
KIT="$WORK/BiteCheck-TestKit"
mkdir -p "$KIT/test-registry"
unzip -q "$ROOT/launcher/build/distributions/"BiteCheck-*.zip -d "$KIT"
cp -r "$MODS" "$KIT/TestMods"
"$BCZ" index --registry "$REG" --registry-key "$REG/keys/registry.pub" --out "$KIT/test-registry/index.toml" \
    --commit test-kit >/dev/null
"$BCZ" sign "$KIT/test-registry/index.toml" --key "$KEYS/registry.key" >/dev/null
cp "$REG/keys/registry.pub" "$KIT/test-registry/registry.pub"
cat > "$KIT/test-registry/config.toml" <<'EOF'
# Test setup: uses the test index and test key in this folder instead of the real registry.
index_url = "index.toml"
registry_key = "registry.pub"
EOF
rm -rf "$KEYS" # the throwaway private keys must not survive

printf '@echo off\r\ncall "%%~dp0BiteCheck\\BiteCheck.bat" --dir "%%~dp0test-registry" %%*\r\n' > "$KIT/Start-Test-Windows.bat"
cat > "$KIT/Start-Test-macOS.command" <<'EOF'
#!/bin/bash
DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$DIR/BiteCheck/BiteCheck.command" --dir "$DIR/test-registry" "$@"
EOF
cat > "$KIT/start-test-linux.sh" <<'EOF'
#!/usr/bin/env bash
DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$DIR/BiteCheck/bitecheck.sh" --dir "$DIR/test-registry" "$@"
EOF
chmod +x "$KIT/Start-Test-macOS.command" "$KIT/start-test-linux.sh" "$KIT/BiteCheck/"*.sh "$KIT/BiteCheck/"*.command
VALID_UNTIL="$(date -u -d '+7 days' '+%d.%m.%Y %H:%M UTC' 2>/dev/null || date -u -v+7d '+%d.%m.%Y %H:%M UTC')"
sed "s/__VALID_UNTIL__/$VALID_UNTIL/" "$ROOT/docs/TEST-ANLEITUNG.txt" | sed 's/$/\r/' > "$KIT/TEST-ANLEITUNG.txt"

mkdir -p "$OUT"
rm -f "$OUT/BiteCheck-TestKit.zip"
(cd "$WORK" && zip -qr -X "$OUT/BiteCheck-TestKit.zip" BiteCheck-TestKit)
echo "Test kit: $OUT/BiteCheck-TestKit.zip"
grep -E '^\[|^[0-9a-f]{64}' "$KIT/test-registry/index.toml"
