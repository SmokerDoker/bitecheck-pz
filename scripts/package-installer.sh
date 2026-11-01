#!/usr/bin/env bash
# Builds a native BiteCheck installer with jpackage (JDK 21+) for the current OS.
# The installer brings its own small Java runtime, so players need nothing else.
#
#   scripts/package-installer.sh <BiteCheck.jar> <version> <type> <outdir>
#
#   type: msi (Windows) | dmg (macOS) | deb | rpm (Linux)
#
# Runs in CI (release.yml) on a runner of the matching OS. The installers are not code-signed:
# Windows SmartScreen and macOS Gatekeeper show a warning on first start.
set -euo pipefail

JAR="$1"
VERSION="$2"
TYPE="$3"
OUT="$4"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# jpackage wants a plain x.y.z; drop a pre-release suffix like -alpha.1.
APP_VERSION="${VERSION%%-*}"
APP_VERSION="${APP_VERSION%%+*}"
if [ "$TYPE" = "dmg" ] && [ "${APP_VERSION%%.*}" = "0" ]; then
    # macOS bundles need a first version number of at least 1; 0.x.y is stored as 1.x.y in the
    # bundle only. The version BiteCheck shows (--version) comes from the jar.
    APP_VERSION="1.${APP_VERSION#0.}"
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/input" "$OUT"
cp "$JAR" "$WORK/input/BiteCheck.jar"

# On Windows (Git Bash) jpackage.exe needs Windows paths.
win() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

common=(
    --dest "$(win "$WORK/out")"
    --app-version "$APP_VERSION"
    --input "$(win "$WORK/input")"
    --main-jar BiteCheck.jar
    --main-class io.github.bitecheckpz.launcher.LauncherMain
    --vendor "BiteCheck project"
    --description "Verified Java mod launcher for Project Zomboid"
    --copyright "GPL-3.0-or-later, https://github.com/SmokerDoker/bitecheck-pz"
    --license-file "$(win "$ROOT/LICENSE")"
    # jdk.crypto.ec holds Ed25519 (signature checks) and the EC ciphers for HTTPS on JDK 21
    --add-modules java.base,java.desktop,jdk.crypto.ec
    --jlink-options "--strip-debug --no-header-files --no-man-pages"
)

case "$TYPE" in
    msi)
        # A second launcher with a console, for command line use (BiteCheckConsole.exe --status).
        printf 'win-console=true\n' > "$WORK/console.properties"
        jpackage --type msi --name BiteCheck "${common[@]}" \
            --win-per-user-install --win-menu --win-menu-group BiteCheck --win-shortcut --win-dir-chooser \
            --win-upgrade-uuid ac45ad52-0986-4971-8a82-729276898e41 \
            --add-launcher BiteCheckConsole="$(win "$WORK/console.properties")"
        ;;
    dmg)
        jpackage --type dmg --name BiteCheck "${common[@]}" \
            --mac-package-identifier io.github.bitecheckpz.launcher --mac-package-name BiteCheck
        ;;
    deb | rpm)
        jpackage --type "$TYPE" --name bitecheck "${common[@]}" \
            --linux-package-name bitecheck --linux-shortcut --linux-menu-group Game \
            --linux-app-category games
        ;;
    *)
        echo "unknown installer type: $TYPE" >&2
        exit 2
        ;;
esac

ls -la "$WORK/out"
cp "$WORK/out"/* "$OUT/"
