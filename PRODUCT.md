# Product

## Register

product

## Users

Project Zomboid players (Build 42) who use Java mods through ZombieBuddy, on Windows, macOS, Linux and
Steam Deck, plus server admins. They see the BiteCheck window for a few seconds before the game
starts, often in the evening, sometimes on a TV or handheld. Most are not security people; they want
to know "is it safe to start?" and then play.

## Product Purpose

BiteCheck checks every installed Java mod against a signed registry of reviewed, reproducibly built
jars before the game loads it. Verified mods load without a question, unverified ones are scanned
locally and BiteCheck asks the player once, flagged ones need a second confirmation, blocked ones do
not load. Success: the player understands
the state of their mods at a glance and starts the game with confidence, without reading paragraphs.

## Brand Personality

Calm, trustworthy, a little Zomboid. A security tool that knows it lives next to a survival game:
sober and precise like a good password manager, with a quiet hint of the game's worn, Kentucky-1993
atmosphere (muted, earthy, slightly rough), never kitsch. Three words: steady, honest, survivor.

## Anti-references

- Neon, terminal-green, hacker or "cyber" aesthetics.
- Alarmism: red only when something really is blocked; no fear-mongering copy or warning walls.
- Gaming-launcher bombast (giant glowing play buttons, RGB gradients).
- Blood, gore or zombie clip-art as decoration.

## Design Principles

1. **Answer first.** The window answers "can I start?" before anything else; details are there for
   whoever wants them.
2. **Practice what we preach.** A trust tool must look precise and finished; sloppy alignment reads as
   sloppy checking.
3. **Proportionate warnings.** Visual weight follows the real risk: verified is quiet, unverified is a
   note, blocked is clear.
4. **One mod, one line.** Speak in mods the player recognizes, not in jars, hashes or paths.
5. **Status never by color alone.** Every state has a symbol and a word.
6. **Nothing extra to trust.** No third-party libraries, not even for looks: the launcher UI is plain
   Swing from the JDK, styled by hand.

## Accessibility & Inclusion

Follows the system light/dark setting. Status always carries symbol plus text, not only color.
Readable at high DPI and on Steam Deck; keyboard usable (Enter starts the game).
