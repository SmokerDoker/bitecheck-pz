#!/usr/bin/env bash
# Installs DepotDownloader (https://github.com/SteamRE/DepotDownloader, pinned by SHA-256) into
# $RUNNER_TEMP/depotdownloader once and prints the path of the binary. CI uses it with anonymous
# Steam logins: java/projectzomboid.jar of the dedicated server (app 380870) and Workshop items of
# Project Zomboid (app 108600). It checks every chunk against Valve's manifest.
set -euo pipefail
version="3.4.0"
sha256="a999dec66b4850fc961bd50366696d23c2d0fad7b18790e6a5647b2f19097a53"
dir="${RUNNER_TEMP:?}/depotdownloader"
if [ ! -x "$dir/DepotDownloader" ]; then
  mkdir -p "$dir"
  curl -fsSL -o "$dir/dd.zip" \
    "https://github.com/SteamRE/DepotDownloader/releases/download/DepotDownloader_$version/DepotDownloader-linux-x64.zip"
  echo "$sha256  $dir/dd.zip" | sha256sum -c --quiet >&2
  unzip -q -o "$dir/dd.zip" -d "$dir"
  chmod +x "$dir/DepotDownloader"
fi
echo "$dir/DepotDownloader"
