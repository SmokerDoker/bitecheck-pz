# Security policy

## What BiteCheck protects against, and what not

BiteCheck stops a harmful Java mod **before it runs for the first time**. That is the moment it
can protect: a Java mod runs with your rights, so once harmful code has run, it can do anything
you can do on your computer, including changing BiteCheck itself.

**What BiteCheck does**

- Every Java mod jar, and ZombieBuddy's own jar, is checked before the game starts. A jar loads
  without a question only if its exact SHA-256 hash was rebuilt from public source by CI and
  approved by a reviewer, in a registry signed with keys that only CI holds.
- Every other jar loads only after you allowed it in BiteCheck: one question for an unverified
  jar, two confirmations if the local scan found risky code or the jar is flagged. Blocked jars
  never load. Your answers are kept by BiteCheck, not in ZombieBuddy's approvals file, which any
  Java mod could write.
- When a jar you trusted changes, BiteCheck tells you whether Steam updated the mod or whether
  the file changed without an update.
- BiteCheck updates only with installers whose checksums carry the release signature.

**What BiteCheck cannot do**

- **Protect against malware that is already on your computer.** Software running with your
  rights can change BiteCheck, ZombieBuddy, the game, your Steam launch options or Java itself,
  and no program can fully guard itself against that. BiteCheck makes some of it visible (see
  below), but it is not an antivirus. Use one.
- **Protect you when the game starts without it.** BiteCheck checks your mods when it starts
  the game (the BiteCheck window, or Steam's launch options with `--wrap`). If the game starts
  another way, only ZombieBuddy's own question protects you.
- **Judge Lua.** Lua mods run in the game's sandbox; BiteCheck decides only whether Java code loads.
- **Replace a review.** The local scan of an unverified jar is a quick check for the most
  dangerous APIs, not a review. A verified mod was reviewed, but reviews can miss things.

**What makes tampering harder or visible**

- **Signed everything.** The registry index, every release, build record and review is signed;
  BiteCheck rejects an index that is older than one it has already seen.
- **Tamper evidence for your answers.** BiteCheck's saved answers carry a check value. If
  something else changes them, BiteCheck notices, ignores the changed answers and asks you
  again. A program written to attack BiteCheck in particular can get around this; a generic one
  cannot.
- **Self-check.** BiteCheck compares its own program file with the signed checksums of its
  release and repairs itself through the signed update when they differ.
- **Multiplayer (planned).** Servers will compare the hashes of their players' Java mods, so a
  client whose BiteCheck or mods were changed stands out to the admin.

## Reporting a malicious mod

If a mod — verified or not — contains malicious code, please **open a flag** on its release in this repository (an issue titled `FLAG: <mod> <version>` until the flag tooling exists). Include the file hash and the code location if you can.

Critical flags remove a release's approval immediately until two trusted reviewers or a maintainer have decided.

## Reporting a vulnerability in BiteCheck itself

Please do **not** open a public issue. Use GitHub's private vulnerability reporting ("Report a vulnerability" in the Security tab of this repository).

We will acknowledge your report as soon as possible and keep you updated until it is fixed.
