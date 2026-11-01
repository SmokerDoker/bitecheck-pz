# Registry

Every object in the registry is one file, and every change is a pull request. This keeps submissions, reviews, flags and decisions public and versioned. Signatures are detached: the signature for `foo.toml` is `foo.toml.sig`, an Ed25519 signature over the exact bytes of the file.

```
registry/
  mods/<mod>/mod.toml                                the mod: name, author, source, license
  mods/<mod>/releases/<version>.toml        + .sig   release: commit and build recipe   (signed by the author)
  mods/<mod>/releases/<version>.build.toml  + .sig   build record: jar SHA-256          (signed by the registry)
  mods/<mod>/releases/<version>.analysis.toml + .sig  AI pre-analysis record             (signed by the registry)
  mods/<mod>/releases/<version>.analysis.md          the analysis report (hash in the record)
  mods/<mod>/reviews/<version>/<reviewer>.toml + .sig   one review per reviewer          (signed by the reviewer)
  mods/<mod>/flags/<flag>.toml                       flags raised against a release
  blocklist.toml                                     jar hashes that must never load
keys/
  registry.pub, authors/<name>.pub, reviewers/<name>.pub
  trust.toml                                         reviewer levels
```

## When is a release verified?

`bcz verify <jar>` (and later the launcher) reports `VERIFIED` only when **all** of these hold:

1. A build record with the jar's SHA-256 exists and is signed by the registry key.
2. The release file it was built from is unchanged and signed by the mod's author.
3. Enough different reviewers (not the publisher) approved exactly this jar hash or build record with valid signatures, as set in `registry/policy.toml` (`required_approvals`, `required_trusted`; default two approvals, one of them `trusted`).
4. If the policy sets `ai_prereview = true`: an AI pre-analysis of exactly this build record exists, signed by the registry (`<version>.analysis.toml` + `.sig`, report in `<version>.analysis.md`). It is an aid for the reviewer and never counts as an approval.
5. There is no open critical flag for this hash.

Other results: `UNVERIFIED` (not in the registry or not enough approvals — the launcher asks the user), `FLAGGED` (open critical flag — strong warning), `BLOCKED` (on the block list or a confirmed flag — never loaded), `INVALID` (files do not match their signatures — treated as tampering).

This status is derived from the files — there is no field anyone can simply set to "verified".

## File formats

Registry files use a strict subset of TOML: tables, bare keys, `"strings"`, integers, booleans and one-line arrays. Dates are written as strings.

**Build record** (`<version>.build.toml`, written by `bcz build-record`):

```toml
mod = "example-mod"
version = "1.0.0"
release_sha256 = "…"     # hash of <version>.toml
jar_sha256 = "…"         # hash of the jar both builders produced
built_at = "2026-11-01T10:00:00Z"
builders = ["runner-a", "runner-b"]
```

**Review** (`reviews/<version>/<reviewer>.toml`, written by `bcz review`):

```toml
mod = "example-mod"
version = "1.0.0"
jar_sha256 = "…"
reviewer = "bob"          # must match the file name
verdict = "approve"       # approve | reject | questions
scope = "full"            # full | diff
reviewed_at = "2026-11-01T10:00:00Z"
notes = "…"
```

**Flag** (`flags/<id>.toml`):

```toml
mod = "example-mod"
version = "1.0.0"
jar_sha256 = "…"
severity = "critical"     # info | suspicion | critical
status = "open"           # open | resolved | confirmed
description = "…"
```

**Block list** (`blocklist.toml`):

```toml
[blocked]
<jar sha256> = "reason"
```

`mods/example-mod/` shows the format. It is marked `example = true` and is never verified.
