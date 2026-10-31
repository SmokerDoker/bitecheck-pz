# Keys

Public keys (Ed25519) used to verify registry files.

- `registry.pub` - the registry's key. The private key exists only in CI.
- `authors/<name>.pub` - mod authors, registered with their first submission.
- `reviewers/<name>.pub` - reviewers.

Revoked keys are listed in `revoked.toml` with the time of revocation. Everything signed with a revoked key after that time is treated as unverified.
