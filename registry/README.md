# Registry

Every object in the registry is one file, and every change is a pull request. This keeps submissions, reviews, flags and decisions public and versioned.

```
registry/
  mods/<mod-id>/mod.toml                          the mod itself
  mods/<mod-id>/releases/<version>.toml           one release (commit, build recipe, expected hash)
  mods/<mod-id>/reviews/<version>/<reviewer>.toml one review per reviewer
  mods/<mod-id>/flags/<flag-id>.toml              flags raised against a release
  notes/<sha256>.toml                             review notes for closed-source mods (no file, no source)
keys/
  registry.pub
  authors/<name>.pub
  reviewers/<name>.pub
```

## When is a release verified?

A release counts as verified only when **all** of these hold:

1. Two independent CI builds of the given commit produced the same SHA-256.
2. The scanner reported no hard findings.
3. At least two valid reviewer approvals exist, at least one from a trusted reviewer.
4. There is no open critical flag.

This status is derived from the files above — there is no field anyone can simply set to "verified".

`mods/example-mod/` shows the format. It is an example only and is ignored by the launcher.
