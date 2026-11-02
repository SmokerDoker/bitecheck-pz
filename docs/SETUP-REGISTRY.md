# Setting up the registry key (maintainers)

The registry key signs build records and the launcher index. Its private half must only exist in two places: an offline backup and the GitHub secret. Do this once.

## 1. Create the key on your own computer

```sh
./gradlew :cli:installDist
cd keys
../cli/build/install/bcz/bin/bcz keygen registry
```

This writes `keys/registry.pub` (commit it) and `~/.bitecheck/keys/registry.key` (never commit it).

**Back up `registry.key` offline** (password manager, encrypted USB stick). If it is lost, every launcher needs an update with a new key; if it leaks, rotate it immediately.

## 2. Store the private key as a GitHub secret

1. GitHub → repository → **Settings → Environments → New environment**, name it `registry-signing`.
2. Under **Deployment branches**, allow only `main`.
3. Optional but recommended: add yourself as a **required reviewer**, so every signing run waits for your approval.
4. In the environment, **Add secret** `BITECHECK_REGISTRY_KEY` and paste the full content of `registry.key` (all three lines).

## 3. Commit the public key

```sh
git add keys/registry.pub
git commit -m "Add registry key"
git push
```

From now on:

- every launcher build pins this key (it is copied into `BiteCheck.jar`),
- the `Registry` workflow signs build records and publishes the signed index to the `index` branch daily and after every merge,
- `validate-registry.yml` checks build record signatures on pull requests.

## 4. Trust levels

Reviewers are added with a public key in `keys/reviewers/<name>.pub` and an entry in `keys/trust.toml`:

```toml
[reviewers.alice]
level = "trusted"   # new | reviewer | trusted
```

How many approvals a release needs is set in `registry/policy.toml`. The start-up policy is one approval from a `trusted` reviewer, and `publisher_may_review = true` lets the publisher of an upstream mod be that reviewer (an author can never approve their own mod). Later: two approvals (a newcomer plus a reviewer with a higher level) and `publisher_may_review = false`.

A publisher signs release files with a key in `keys/authors/<name>.pub`; a reviewer signs reviews with a key in `keys/reviewers/<name>.pub`. One person can use the same key pair for both: copy the `.pub` file into both folders.

Set and check levels with `bcz trust <name> [new|reviewer|trusted]`; `bcz reviewers` lists everyone with their level, GitHub account and how many reviews they signed — the basis for promoting someone.

## Revoking an author's or reviewer's key

If a personal key leaks or is lost, add its key id to `keys/revoked.toml` (see [registry/README.md](../registry/README.md)). From that commit on, nothing it signed counts: its releases become `INVALID` and its reviews stop counting. The owner creates a new key (`bcz login` after moving the old `~/.bitecheck/keys/<name>.key` away), a maintainer replaces their `.pub` file, and they re-sign their releases (`bcz publish`) or reviews (`bcz approve`).

## Rotating a compromised registry key

1. Create a new key (step 1) and replace the secret (step 2).
2. Re-sign all build records: delete the `*.build.toml(.sig)` files in one commit; the `Registry` workflow rebuilds and signs them again.
3. Release a new launcher version, since launchers pin the key.
