BiteCheck - verified Java mods for Project Zomboid
===================================================

BiteCheck checks your Java mods against the BiteCheck registry before you play:

  verified    loads without a question
  unverified  ZombieBuddy warns you in game and asks before loading it
  flagged     not loaded (you can allow it anyway, with a double confirmation)
  blocked     never loaded

Lua mods are loaded by the game itself and are not affected.

Requirements
  - Project Zomboid (Build 42) from Steam
  - ZombieBuddy installed (https://github.com/zed-0xff/ZombieBuddy)

Start
  Windows:  double-click BiteCheck.bat
  macOS:    double-click BiteCheck.command
            (first time: right-click > Open, because the file was downloaded)
  Linux:    run ./bitecheck.sh

Then click "Start Project Zomboid". BiteCheck starts the game through Steam,
so your normal launch options (ZombieBuddy) are used.

Start from Steam automatically (recommended)
  Click "Steam setup..." in the BiteCheck window (or run the start script with
  --steam-options). It shows one line for Steam > Project Zomboid >
  Properties > Launch Options, for example on Windows:

    "C:\Games\BiteCheck\BiteCheck.bat" --wrap %command% -agentlib:zbNative --

  From then on BiteCheck checks your mods every time you press Play in Steam,
  then starts the game normally. If BiteCheck fails, the game still starts and
  ZombieBuddy asks about new mods as before. On Windows a console window stays
  open while you play; that is BiteCheck waiting for the game.

Without that, you can still start the game from Steam directly. Mods BiteCheck
already checked keep their decisions; new or changed mods are then handled by
ZombieBuddy's own question.

Command line (all platforms, add to the start script call):
  --status            show the status and SHA-256 of all Java mods
  --sync              write the decisions without starting the game
  --launch            write the decisions and start the game
  --wrap <command>    write the decisions, then run <command> (used by Steam)
  --steam-options     show the Steam launch options for --wrap
  --allow <sha256>    approve an unverified mod ahead of time
  --revoke <sha256>   take back an --allow
  --export <file>     save the list of your Java mods with their hashes
  --compare <file>    compare your Java mods with such a list (e.g. from a server)
  --server-dir <dir>  dedicated server install (see docs/SERVER.md on GitHub)

Files
  ~/.bitecheck/                    BiteCheck settings, cache and state
  ~/.zombie_buddy/mod_approvals.json  ZombieBuddy's decisions (BiteCheck keeps
                                   a backup as mod_approvals.json.bitecheck-backup)

Source code and license (GPL-3.0): https://github.com/SmokerDoker/bitecheck-pz
