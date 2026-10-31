"""Turns a list of release file paths into a GitHub Actions matrix (releases=<json>)."""
import json
import re
import sys

PATTERN = re.compile(r"^registry/mods/([a-z0-9][a-z0-9-]*)/releases/([0-9A-Za-z][0-9A-Za-z.+_-]*)\.toml$")

entries = []
with open(sys.argv[1], encoding="utf-8") as f:
    for line in f:
        path = line.strip()
        m = PATTERN.match(path)
        if not m or path.endswith(".build.toml"):
            continue
        key = re.sub(r"[^A-Za-z0-9.-]", "-", f"{m.group(1)}-{m.group(2)}")
        entries.append({"release": path, "key": key})

print("releases=" + json.dumps(entries))
