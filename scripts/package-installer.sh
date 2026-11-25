#!/usr/bin/env bash
# Builds a native BiteCheck installer with jpackage (JDK 21+) for the current OS.
# The installer brings its own small Java runtime, so players need nothing else.
#
#   scripts/package-installer.sh <BiteCheck.jar> <version> <type> <outdir>
#
#   type: msi (Windows, into the player's folder) | msi-system (Windows, into Program Files)
#         | dmg (macOS) | deb | rpm (Linux)
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

# jpackage derives an MSI's ProductCode from vendor, name and version. The per-user and the Program
# Files MSI of one version must not share it: with an old per-user copy of the same version installed,
# Windows refuses the other one (error 1638) and the protected BiteCheck could never update.
VENDOR="BiteCheck project"
if [ "$TYPE" = "msi-system" ]; then
    VENDOR="BiteCheck project (all users)"
fi

common=(
    --vendor "$VENDOR"
    --dest "$(win "$WORK/out")"
    --app-version "$APP_VERSION"
    --input "$(win "$WORK/input")"
    --main-jar BiteCheck.jar
    --main-class io.github.bitecheckpz.launcher.LauncherMain
    --description "Verified Java mod launcher for Project Zomboid"
    --copyright "GPL-3.0-or-later, https://github.com/SmokerDoker/bitecheck-pz"
    --license-file "$(win "$ROOT/LICENSE")"
    # jdk.crypto.ec holds Ed25519 (signature checks) and the EC ciphers for HTTPS on JDK 21
    --add-modules java.base,java.desktop,jdk.crypto.ec
    --jlink-options "--strip-debug --no-header-files --no-man-pages"
)

# The BiteCheck logo (launcher/src/packaging) as the app icon.
ICONS="$ROOT/launcher/src/packaging"

case "$TYPE" in
    msi)
        # A second launcher with a console, for command line use (BiteCheckConsole.exe --status).
        # Only the .exe in the install folder: no Start menu entry or desktop shortcut, so players
        # see a single BiteCheck app.
        printf 'win-console=true\nwin-menu=false\nwin-shortcut=false\nicon=%s\n' "$(win "$ICONS/bitecheck.ico")" \
            > "$WORK/console.properties"
        jpackage --type msi --name BiteCheck "${common[@]}" --icon "$(win "$ICONS/bitecheck.ico")" \
            --win-per-user-install --win-menu --win-menu-group BiteCheck --win-shortcut --win-dir-chooser \
            --win-upgrade-uuid ac45ad52-0986-4971-8a82-729276898e41 \
            --add-launcher BiteCheckConsole="$(win "$WORK/console.properties")"
        ;;
    msi-system)
        # Into Program Files for all users: changing BiteCheck then needs administrator rights, so a
        # program running as the player cannot. No folder choice, so it always lands there.
        printf 'win-console=true\nwin-menu=false\nwin-shortcut=false\nicon=%s\n' "$(win "$ICONS/bitecheck.ico")" \
            > "$WORK/console.properties"
        jpackage --type msi --name BiteCheck "${common[@]}" --icon "$(win "$ICONS/bitecheck.ico")" \
            --win-menu --win-menu-group BiteCheck --win-shortcut \
            --win-upgrade-uuid 6f1d2c8e-5a37-4b8e-9c41-2d7e0b9a3f15 \
            --add-launcher BiteCheckConsole="$(win "$WORK/console.properties")"
        ;;
    dmg)
        # macOS wants an .icns; build it from the 1024 px logo with the system tools.
        mkdir -p "$WORK/BiteCheck.iconset"
        for s in 16 32 128 256 512; do
            sips -z "$s" "$s" "$ICONS/bitecheck-1024.png" --out "$WORK/BiteCheck.iconset/icon_${s}x${s}.png" >/dev/null
            sips -z $((s * 2)) $((s * 2)) "$ICONS/bitecheck-1024.png" \
                --out "$WORK/BiteCheck.iconset/icon_${s}x${s}@2x.png" >/dev/null
        done
        iconutil -c icns "$WORK/BiteCheck.iconset" -o "$WORK/BiteCheck.icns"
        jpackage --type dmg --name BiteCheck "${common[@]}" --icon "$WORK/BiteCheck.icns" \
            --mac-package-identifier io.github.bitecheckpz.launcher --mac-package-name BiteCheck
        ;;
    deb | rpm)
        jpackage --type "$TYPE" --name bitecheck "${common[@]}" --icon "$ICONS/bitecheck.png" \
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
