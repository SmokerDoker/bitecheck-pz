# BiteCheck

**A verified Java mod launcher and curated mod registry for Project Zomboid.**

> BiteCheck is a community project. It is not affiliated with, endorsed by, or supported by The Indie Stone. "Project Zomboid" is a trademark of The Indie Stone.

## Why

Java mods can change far more of Project Zomboid than Lua mods can — but they also run with full access to your computer. Today you either trust a `.jar` blindly or you don't use Java mods at all.

BiteCheck adds a verification step before a Java mod is loaded:

- **Public source only.** The curated registry only lists mods whose full source code is public.
- **Reproducible builds.** The `.jar` you download is built by CI from that exact source, twice, on independent runners. Both builds must be byte-for-byte identical.
- **Signatures.** Every release is signed by its author, by the registry, and by at least two independent reviewers.
- **Automatic scanning + human review.** Suspicious APIs (process start, native code, dynamic class loading, network, file access outside `~/Zomboid`) are flagged before a human reviews the code.
- **Per-version approval.** Approval is tied to the SHA-256 hash of one exact `.jar`. A new version is unverified until it is reviewed.

## How it works for players

- **Verified mods** load without any prompt.
- **Unverified mods** still work, just like with ZombieBuddy — but BiteCheck shows a warning for each one and asks you to confirm before loading it. Your choice is remembered for that exact file only.
- **Mods with confirmed malware** are on a block list and never load.
- **Lua mods** are loaded by the game itself and are not affected by BiteCheck at all.

## How it works for modders

Submitting a mod should be no harder than publishing to the Workshop:

1. Start from the mod template (or add the Gradle plugin to your project). It configures reproducible builds for you.
2. Run `bcz keygen <name>` once to create your signing key (planned: `bcz login` links your GitHub account too).
3. Tag a release and run `bcz submit` — or let the included GitHub Action do it on every release tag.

Your mod is never blocked while it waits for review: players can load it as an unverified mod with a warning.

## Status

🚧 **Early development.** Not ready for players yet.

- [x] Registry format and repository structure
- [x] `bcz` CLI: keys, signatures, build records, reviews, verification
- [ ] `bcz login` / `bcz submit` (GitHub integration)
- [ ] CI pipeline: double build, hash comparison, scanner, registry signature
- [ ] Launcher verification layer on top of [ZombieBuddy](https://github.com/zed-0xff/ZombieBuddy)
- [ ] Mod template and Gradle plugin
- [ ] Key revocation, update diffs, reviewer reputation tooling

## The `bcz` command line tool

```sh
./gradlew :cli:installDist                 # needs Java 21+
alias bcz=cli/build/install/bcz/bin/bcz     # or bcz.bat on Windows

bcz keygen alice                            # Ed25519 key pair; private key in ~/.bitecheck/keys/
bcz sign registry/mods/my-mod/releases/1.0.0.toml --key ~/.bitecheck/keys/alice.key
bcz build-record <release.toml> <jar> --builder runner-a --builder runner-b   # CI
bcz review <release.toml> <jar> --reviewer bob --verdict approve              # reviewers
bcz verify my-mod.jar --registry . --registry-key keys/registry.pub
```

`bcz verify` prints `VERIFIED`, `UNVERIFIED`, `FLAGGED`, `BLOCKED` or `INVALID` with the reasons, and exits with 0, 10, 11, 12 or 13. See [registry/README.md](registry/README.md) for the exact rules.

BiteCheck has **no third-party dependencies**: everything you have to trust is in this repository or in the JDK itself. Its own jars are built reproducibly, and CI checks that.

## Repository layout

```
core/            Verification library (hashing, Ed25519 signatures, registry, verifier)
cli/             The bcz command line tool
registry/        Mod entries, releases, reviews and flags (see registry/README.md)
keys/            Public keys of the registry, authors and reviewers
.github/         CI workflows
```

Run the tests with `./gradlew check`.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Security issues: see [SECURITY.md](SECURITY.md).

## License

BiteCheck is licensed under the [GNU General Public License v3.0](LICENSE). You may fork and redistribute it under the same license; see [TRADEMARK.md](TRADEMARK.md) for how the BiteCheck name may be used.
