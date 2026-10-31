#!/usr/bin/env bash
# BiteCheck launcher for Linux (including Steam Deck desktop mode).
# Uses the Java runtime that comes with Project Zomboid, so no separate Java install is needed.
DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$DIR/BiteCheck.jar"

find_java() {
    local roots=("$HOME/.steam/steam" "$HOME/.local/share/Steam"
                 "$HOME/.var/app/com.valvesoftware.Steam/.local/share/Steam"
                 "$HOME/snap/steam/common/.local/share/Steam")
    local libs=()
    for r in "${roots[@]}"; do
        [ -d "$r/steamapps" ] || continue
        libs+=("$r")
        if [ -f "$r/steamapps/libraryfolders.vdf" ]; then
            while IFS= read -r p; do libs+=("$p"); done < <(sed -n 's/.*"path"[[:space:]]*"\(.*\)".*/\1/p' "$r/steamapps/libraryfolders.vdf")
        fi
    done
    for lib in "${libs[@]}"; do
        local game="$lib/steamapps/common/ProjectZomboid"
        [ -d "$game" ] || continue
        local java
        java="$(find -L "$game" -maxdepth 5 -type f -path "*/bin/java" -perm -u+x 2>/dev/null | head -n 1)"
        if [ -n "$java" ]; then echo "$java"; return 0; fi
    done
    command -v java && return 0
    return 1
}

JAVA="$(find_java)"
if [ -z "$JAVA" ]; then
    echo "BiteCheck: could not find Project Zomboid's Java runtime or Java 21+." >&2
    echo "Install Project Zomboid through Steam, or install Java 21 or newer." >&2
    exit 1
fi
exec "$JAVA" -jar "$JAR" "$@"
