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
2. Run `bcz login` once to link your GitHub account and create your signing key.
3. Tag a release and run `bcz submit` — or let the included GitHub Action do it on every release tag.

Your mod is never blocked while it waits for review: players can load it as an unverified mod with a warning.

## Status

🚧 **Early concept.** Nothing here is usable yet. The current focus is the registry format and the MVP:

- [ ] Registry format and repository structure
- [ ] `bcz` CLI (sign, submit, verify)
- [ ] CI pipeline: double build, hash comparison, scanner, registry signature
- [ ] Launcher verification layer on top of [ZombieBuddy](https://github.com/zed-0xff/ZombieBuddy)
- [ ] Reviewer reputation, flags, update diffs

## Repository layout

```
registry/        Mod entries, releases, reviews and flags (see registry/README.md)
keys/            Public keys of the registry, authors and reviewers
.github/         CI workflows
```

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Security issues: see [SECURITY.md](SECURITY.md).

## License

BiteCheck is licensed under the [GNU General Public License v3.0](LICENSE). You may fork and redistribute it under the same license; see [TRADEMARK.md](TRADEMARK.md) for how the BiteCheck name may be used.
