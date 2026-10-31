#!/bin/bash
# BiteCheck launcher for macOS. Double-click in Finder.
# Uses the Java runtime that comes with Project Zomboid, so no separate Java install is needed.
DIR="$(cd "$(dirname "$0")" && pwd)"
JAR="$DIR/BiteCheck.jar"

# BITECHECK_JAVA=/path/to/java overrides the search.
find_java() {
    if [ -n "$BITECHECK_JAVA" ] && [ -x "$BITECHECK_JAVA" ]; then echo "$BITECHECK_JAVA"; return 0; fi
    local steam="$HOME/Library/Application Support/Steam"
    local libs=("$steam")
    if [ -f "$steam/steamapps/libraryfolders.vdf" ]; then
        while IFS= read -r p; do libs+=("$p"); done < <(sed -n 's/.*"path"[[:space:]]*"\(.*\)".*/\1/p' "$steam/steamapps/libraryfolders.vdf")
    fi
    for lib in "${libs[@]}"; do
        local app="$lib/steamapps/common/ProjectZomboid/Project Zomboid.app"
        if [ -d "$app" ]; then
            local java
            java="$(find -L "$app" -type f -path "*/bin/java" -perm -u+x 2>/dev/null | head -n 1)"
            if [ -n "$java" ]; then echo "$java"; return 0; fi
        fi
    done
    if [ -x /usr/libexec/java_home ] && /usr/libexec/java_home -v 21+ >/dev/null 2>&1; then
        echo "$(/usr/libexec/java_home -v 21+)/bin/java"; return 0
    fi
    command -v java && return 0
    return 1
}

JAVA="$(find_java)"
if [ -z "$JAVA" ] && [ "$1" = "--wrap" ]; then
    # Started from Steam: never keep the game from starting, run it without the check.
    echo "BiteCheck: no Java runtime found, starting the game without the check." >&2
    shift
    exec "$@"
fi
if [ -z "$JAVA" ]; then
    osascript -e 'display alert "BiteCheck" message "Could not find Project Zomboid'"'"'s Java runtime or Java 21+. Install Project Zomboid through Steam."' >/dev/null 2>&1
    echo "BiteCheck: no Java runtime found." >&2
    exit 1
fi
exec "$JAVA" -Dapple.awt.application.name=BiteCheck -Dbitecheck.script="$DIR/BiteCheck.command" -jar "$JAR" "$@"
