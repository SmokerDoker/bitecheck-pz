#!/usr/bin/env bash
# Downloads the jars of a "workshop" release from its Steam Workshop item (anonymous login) into
# <out>/<path inside the item>, as listed in [build] workshop_artifacts. Runs only in jobs that do
# not run the mod's build command.
# Usage: fetch-workshop.sh <bcz> <release.toml> <registry dir> <out dir>
set -euo pipefail
bcz="$1" release="$2" registry="$3" out="$4"
export DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1
info="$("$bcz" release-info "$release" --registry "$registry")"
id="$(sed -n 's/^workshop_id=//p' <<<"$info")"
read -ra paths <<<"$(sed -n 's/^workshop_artifacts=//p' <<<"$info")"
dd="$(bash "$(dirname "$0")/depotdownloader.sh")"
item="${RUNNER_TEMP:?}/workshop-$id"
for attempt in 1 2 3; do
  if "$dd" -app 108600 -pubfile "$id" -dir "$item" > "$item.log" 2>&1; then
    break
  fi
  tail -n 20 "$item.log"
  echo "::warning::Workshop download attempt $attempt failed, retrying"
  sleep 15
done
mkdir -p "$out"
for path in "${paths[@]}"; do
  if [ ! -f "$item/$path" ]; then
    echo "::error::Workshop item $id has no $path; it has: $(cd "$item" && find . -name '*.jar' -not -path './.DepotDownloader/*' | sed 's#^\./##' | paste -sd' ' -)"
    exit 1
  fi
  mkdir -p "$out/$(dirname "$path")"
  cp "$item/$path" "$out/$path"
done
echo "Workshop item $id ($(grep -o 'Manifest [0-9]*' "$item.log" | head -1)): ${#paths[@]} jar(s)"
