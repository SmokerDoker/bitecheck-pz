"""Lists releases that have a build record but no AI pre-analysis yet, as a matrix.

Only used when registry/policy.toml sets ai_prereview = true. Example and pending entries are skipped.
"""
import json
import pathlib
import re
import tomllib

policy_file = pathlib.Path("registry/policy.toml")
policy = tomllib.loads(policy_file.read_text(encoding="utf-8")) if policy_file.exists() else {}
entries = []
if policy.get("ai_prereview") is True:
    for build in sorted(pathlib.Path("registry/mods").glob("*/releases/*.build.toml")):
        version = build.name[: -len(".build.toml")]
        release = build.with_name(version + ".toml")
        if not release.exists() or build.with_name(version + ".analysis.toml").exists():
            continue
        try:
            mod = tomllib.loads((build.parent.parent / "mod.toml").read_text(encoding="utf-8"))
        except (OSError, tomllib.TOMLDecodeError):
            continue
        if mod.get("example") is True or mod.get("status") == "pending":
            continue
        key = re.sub(r"[^A-Za-z0-9.-]", "-", f"{build.parent.parent.name}-{version}")
        entries.append({"release": release.as_posix(), "key": key})

print("releases=" + json.dumps(entries[:10]))  # at most 10 analyses per run (cost)
