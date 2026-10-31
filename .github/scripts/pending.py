"""Lists releases that have no build record yet (excluding example mods) as a matrix."""
import json
import pathlib
import re
import tomllib

entries = []
for release in sorted(pathlib.Path("registry/mods").glob("*/releases/*.toml")):
    if release.name.endswith(".build.toml"):
        continue
    if release.with_name(release.name[: -len(".toml")] + ".build.toml").exists():
        continue
    mod_file = release.parent.parent / "mod.toml"
    try:
        mod = tomllib.loads(mod_file.read_text(encoding="utf-8"))
    except (OSError, tomllib.TOMLDecodeError):
        continue
    if mod.get("example") is True:
        continue
    key = re.sub(r"[^A-Za-z0-9.-]", "-", f"{release.parent.parent.name}-{release.name[:-5]}")
    entries.append({"release": release.as_posix(), "key": key})

print("releases=" + json.dumps(entries))
