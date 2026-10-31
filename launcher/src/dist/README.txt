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

You can still start the game from Steam directly. Mods BiteCheck already
checked keep their decisions; new or changed mods are then handled by
ZombieBuddy's own question.

Command line (all platforms, add to the start script call):
  --status   show the status of all Java mods
  --sync     write the decisions without starting the game
  --launch   write the decisions and start the game

Files
  ~/.bitecheck/                    BiteCheck settings, cache and state
  ~/.zombie_buddy/mod_approvals.json  ZombieBuddy's decisions (BiteCheck keeps
                                   a backup as mod_approvals.json.bitecheck-backup)

Source code and license (GPL-3.0): https://github.com/SmokerDoker/bitecheck-pz
