# BiteCheck roadmap

Everything BiteCheck still wants to deliver, in one place. The [README](../README.md) describes what
BiteCheck does today; [KONZEPTION.md](KONZEPTION.md) is the full specification (German) with the
accepted deviations at its end. Items are checked off here when they are released.

Last audit of concept against code: 2026-11-26.

## Done so far

- Registry format, signatures, reviews, build records, flags, key revocation, reviewer levels
- `bcz` command line tool for authors, reviewers and maintainers; mod template, Gradle plugin, submit action
- CI: double build against the dedicated server's `projectzomboid.jar`, hash comparison, scanner, signed index, website
- Launcher for Windows, macOS and Linux: check chain (index signature, 7-day maximum age, no rollback,
  SHA-256 per jar, block list, double confirmation for flagged and suspicious mods), start window with one
  checkbox per unverified mod, Steam `--wrap` integration, dedicated server mode
- Installers with their own Java runtime, protected install (Program Files on Windows, `/opt` on Linux),
  signed self-update, state check value (`state.toml`)

## 1. Gaps to close first

- [x] Releases of a mod with status `suspended` or `withdrawn` must leave the index (today the website shows
  the status, but the index keeps them verified)
- [x] Hard scanner findings block a release in CI; a trusted reviewer may grant a signed exception for one
  finding of one release (needed for mod managers such as ZombieBuddy and Jumpstart, which use the
  instrumentation API), shown in the index and the launcher
- [x] `state.toml`: the rollback guard `newest_index` is covered by the check value; a deleted `state.key`
  no longer means the old state is accepted unchecked

## 2. Launcher check chain and everyday behaviour

- [x] Compatibility: releases name the Project Zomboid and ZombieBuddy versions they work with; the
  launcher warns when the installed versions do not match (check step 6)
- [x] Key revocation from a point in time: decided against it, see the deviations in KONZEPTION.md (signatures carry no
  trusted time, so a stolen key could sign "old" files; everything a revoked key signed stays untrusted)
- [x] Author key change: the new key is signed by the old one, no maintainer has to swap files by hand
- [x] Players choose reviewers they trust personally; the index names the reviewers of each release
- [x] Flags of severity "suspicion" and "info" show in the launcher and in `bcz inspect`, not only on the website
- [x] Review notes for closed-source mods (`notes/<sha256>.toml`, findings only, no code), shown in the warning
- [x] Download mirror: CI-built jars are kept as GitHub releases of this repository (hash checked, only for licenses
  that allow it); `bcz fetch` uses them. The launcher downloads no mods itself (Steam does); a mirror download in
  the launcher comes with the local jar cache below
- [ ] A new version that is not approved yet: stay on the last approved version (needs a local jar cache;
  built together with Jumpstart's hash cache)

## 3. Registry, reviews and flags

- [x] Two-review rule as specified: a newcomer plus a higher level; reviewers must be independent (not invited
  by the same person, not on the same mod team)
- [x] Reviewer levels: promotion by the documented criteria; a reviewer who approved malware loses the level
  and all their approvals are re-checked
- [x] A critical flag removes the approval at once: decided as "with the next index, published on merge" (see the
  deviations in KONZEPTION.md: an unmerged flag would let anyone switch off any mod)
- [x] Confirmed malware: the author key is revoked automatically and everybody who installed the version is told
- [x] Reputation: abusing flags lowers the reporter's standing
- [x] Diff rule: an update that changes more than 30 % of the code counts as a new first review
- [x] Reviewers co-sign the hash of the scanner report they saw
- [x] Scanner: `java.awt.Desktop` becomes a hard finding; custom `ClassLoader` subclasses are detected; bundled
  dependencies are checked against known vulnerabilities (OSV)
- [x] Reproducible build: volunteer rebuilders (`bcz rebuild`, signed rebuild records shown in the index);
  dependency verification in the template; results as a PR comment; a jar diff when the two builds differ.
  A container pinned by digest without network: decided against for now, see the deviations in KONZEPTION.md
- [x] A submission must come from the owner of the source repository, not only from the linked account

## 4. Multiplayer and servers (with Jumpstart's multiplayer work)

- [ ] The server publishes its Java mod list with hashes; players' launchers fetch exactly those versions and
  warn about unapproved ones (today: `--export` / `--compare` by hand)
- [ ] The server compares its players' hashes, so a manipulated client stands out to the admin

## 5. Security plan

- [x] Self-heal on Linux (pkexec asks for the password, a checked copy of the package is installed) and in the
  portable zip (every file of its folder against the signed release zip)
- [ ] Self-heal of the installed packages' Java runtime: needs a list of the installed files per package in each
  release; to be built and checked against a real release first, so it never repairs without a reason

## 6. Tests and people

- [ ] First test with real players (three players on Windows, Ubuntu and macOS on one server)
- [ ] A submission from a fork (an author without write access to the registry)
- [ ] Self-update through the Steam start, on macOS and on Linux
- [ ] A second reviewer; then Jumpstart and the ZombieBuddy fork can be reviewed
- [ ] Before going public: who operates the registry and pays for it, terms of use ("an approval is a careful
  review, not a guarantee"), tag protection once others get write access

## 7. Last, as a separate project

- [ ] Optional isolation at operating system level, for dedicated servers (container with its own user, only
  game ports, no outgoing network) and for player PCs (sandboxed start), with an indicator in the start window
