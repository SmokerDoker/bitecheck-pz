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
5. For the AI pre-analysis (`ai_prereview = true` in `registry/policy.toml`), create a second environment `ai-review` (deployment branch `main` only). It holds the AI credential and never the registry key, because the model reads untrusted mod files.
   - **GitHub Copilot (default):** create a [fine-grained personal access token](https://github.com/settings/personal-access-tokens/new) with the account permission **Copilot Requests** and store it as secret `COPILOT_GITHUB_TOKEN` in `ai-review`. Each analysis uses requests from that account's Copilot plan (premium requests on the paid models). Optional repository variable `AI_PREREVIEW_MODEL` picks the model (e.g. a Claude model your plan offers); empty = Copilot's default.
   - **Claude API (alternative):** set the repository variable `AI_PREREVIEW_BACKEND = anthropic` and store `ANTHROPIC_API_KEY` in `ai-review` (model `claude-opus-5-5`, effort `high`; set a spend limit in the Claude Console).
   At most 10 analyses run per workflow run; failed ones are retried on the next run.

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

How many approvals a release needs is set in `registry/policy.toml`. The start-up policy is one approval from a `trusted` reviewer plus a signed AI pre-analysis of the exact build; raise `required_approvals` to 2 once there are more reviewers.

## Rotating a compromised key

1. Create a new key (step 1) and replace the secret (step 2).
2. Re-sign all build records: delete the `*.build.toml(.sig)` files in one commit; the `Registry` workflow rebuilds and signs them again.
3. Release a new launcher version, since launchers pin the key.
