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

How many approvals a release needs is set in `registry/policy.toml`. The start-up policy is one approval from a `trusted` reviewer; later two (a newcomer plus a reviewer with a higher level).

## Rotating a compromised key

1. Create a new key (step 1) and replace the secret (step 2).
2. Re-sign all build records: delete the `*.build.toml(.sig)` files in one commit; the `Registry` workflow rebuilds and signs them again.
3. Release a new launcher version, since launchers pin the key.
