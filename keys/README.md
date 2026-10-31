# Keys

Public Ed25519 keys used to verify registry files. Create a key pair with `bcz keygen <name>`; the private key stays in `~/.bitecheck/keys/` and must never be committed.

- `registry.pub` - the registry's key. The private key exists only in CI. Launchers pin this key in their own code instead of trusting the copy in a registry checkout.
- `authors/<name>.pub` - mod authors, registered with their first submission.
- `reviewers/<name>.pub` - reviewers.
- `trust.toml` - reviewer levels. Reviewers not listed are `new` and their reviews do not count yet.

```toml
[reviewers.bob]
level = "trusted"   # new | reviewer | trusted
```

Planned: `revoked.toml` listing revoked keys with the time of revocation.
