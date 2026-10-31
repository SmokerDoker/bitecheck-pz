# Contributing to BiteCheck

Thanks for helping! There are three ways to contribute.

## 1. Submit a mod (modders)

> The tooling is not ready yet. Until it is, open an issue titled `Mod submission: <mod name>` with a link to your public source repository and its license.

The planned flow:

1. Your mod's source code is public under a license that allows review and rebuilding (e.g. MIT, Apache 2.0, GPL).
2. Your build is reproducible. The BiteCheck mod template / Gradle plugin sets this up for you.
3. `bcz submit` opens a pull request in this repository with your signed release file. CI builds your mod twice, compares the hashes, runs the scanner and posts the results to the PR.
4. Two independent reviewers approve it, then it is merged.

Requirements for a release:

- No obfuscation.
- No native libraries, no runtime code loading, no process execution.
- Network access and file access outside `~/Zomboid` must be explained in the mod description and will be checked carefully in review.
- Game classes from The Indie Stone are referenced at compile time only (`compileOnly`) and never bundled.

## 2. Review mods (reviewers)

Anyone can review. Reviews from new accounts are visible but do not count toward approval until the reviewer has built up reputation (see the specification).

A review must state what was checked (full review or diff to the previous version) and explain every soft scanner finding. If you find something malicious, **flag it immediately** — a critical flag removes the approval for all players until it is resolved.

## 3. Work on BiteCheck itself (developers)

- Open an issue before starting larger changes, so we can agree on the approach.
- Keep pull requests small and focused.
- By contributing, you agree that your contribution is licensed under GPL-3.0.
