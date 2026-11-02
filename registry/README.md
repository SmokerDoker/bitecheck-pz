# Registry

Every object in the registry is one file, and every change is a pull request. This keeps submissions, reviews, flags and decisions public and versioned. Signatures are detached: the signature for `foo.toml` is `foo.toml.sig`, an Ed25519 signature over the exact bytes of the file.

```
registry/
  mods/<mod>/mod.toml                                the mod: name, author, source, license
  mods/<mod>/releases/<version>.toml        + .sig   release: commit and build recipe   (signed by the author)
  mods/<mod>/releases/<version>.build.toml  + .sig   build record: jar SHA-256          (signed by the registry)
  mods/<mod>/reviews/<version>/<reviewer>.toml + .sig   one review per reviewer          (signed by the reviewer)
  mods/<mod>/flags/<flag>.toml                       flags raised against a release
  blocklist.toml                                     jar hashes that must never load
keys/
  registry.pub, authors/<name>.pub, reviewers/<name>.pub
  trust.toml                                         reviewer levels
  accounts.toml                                      GitHub account of each name
  revoked.toml                                       revoked keys, by key id
```

## When is a release verified?

`bcz verify <jar>` (and later the launcher) reports `VERIFIED` only when **all** of these hold:

1. A build record with the jar's SHA-256 exists and is signed by the registry key.
2. The release file it was built from is unchanged and signed by the mod's author.
3. Enough different reviewers approved exactly this jar hash (or the build record listing it) with valid signatures, as set in `policy.toml`: `required_approvals` (default 2), of which `required_trusted` have level `trusted` (default 1). The author never counts; the publisher of an upstream mod counts only while `publisher_may_review = true` (start-up policy: one trusted approval, publisher may review). A review with `scope = "diff"` checked only the changes since the previous release; it counts only if it names the release directly before this one and that release is itself `VERIFIED`.
4. There is no open critical flag for this hash or its version.
5. None of the keys involved is listed in `keys/revoked.toml`.

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
base_version = "0.9.0"    # diff only: the previous release ...
base_commit = "…"         # ... and its commit
reviewed_at = "2026-11-01T10:00:00Z"
notes = "…"
```

A review names either one jar (`jar_sha256`) or the whole build record (`build_sha256`, what `bcz approve` writes).

**Flag** (`flags/<id>.toml`, written by `bcz flag`):

```toml
mod = "example-mod"
version = "1.0.0"
jar_sha256 = "…"          # optional: without it the flag covers every jar of the version
severity = "critical"     # info | suspicion | critical
status = "open"           # open | resolved | confirmed
description = "…"
```

**Accounts** (`keys/accounts.toml`): links a key name to a GitHub account. `bcz submit` adds the entry with an author's first submission; CI (`bcz submitter-check`) only accepts pull requests that change a mod, release or review from the account linked to its publisher or reviewer.

```toml
[accounts.alice]
github = "Alice-GH"
```

**Revoked keys** (`keys/revoked.toml`): nothing signed by a listed key counts any more — its releases are `INVALID`, its reviews are not counted. Re-sign with a new key after revoking.

```toml
[revoked.0e851eed1114101d]   # key id, as shown by bcz login / bcz keygen
name = "alice"
revoked_at = "2026-11-02"
reason = "key leaked"
```

**Block list** (`blocklist.toml`):

```toml
[blocked]
<jar sha256> = "reason"
```

`mods/example-mod/` shows the format. It is marked `example = true` and is never verified.
