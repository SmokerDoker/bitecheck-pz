# BiteCheck on a dedicated server

A dedicated server loads Java mods the same way a game client does: ZombieBuddy decides per jar,
using `~/.zombie_buddy/mod_approvals.json` of the user that runs the server. BiteCheck writes
those decisions before the server starts. Nobody is there to answer ZombieBuddy's question on a
server, so the server runs ZombieBuddy with `policy=deny-new`: only jars that are already
approved load, everything else is skipped silently.

| Mod | On the server |
|---|---|
| verified | loads (BiteCheck approves it) |
| unverified | skipped, until the admin approves it once with `--allow <sha256>` |
| suspicious / flagged | skipped, unless the admin runs `--allow <sha256> --i-understand-the-risk` |
| blocked | never loads |

## Setup (Linux, SteamCMD)

1. Install ZombieBuddy on the server as its documentation describes and add `policy=deny-new` to
   its agent options, e.g. `-javaagent:ZombieBuddy.jar=policy=deny-new`.
2. Unpack the BiteCheck zip somewhere, e.g. `~/bitecheck/`.
3. Check what is installed (BiteCheck uses the server's own Java runtime from `--server-dir`;
   `BITECHECK_JAVA=/path/to/java` overrides that):

   ```sh
   ~/bitecheck/bitecheck.sh --server-dir ~/pzserver --status
   ```

   Workshop mods are found in `<server-dir>/steamapps/workshop/content/108600`, local mods in
   `~/Zomboid/mods`. Use `--workshop-dir` or `--zomboid-dir` for other locations, or put
   `server_dir = "…"` into `~/.bitecheck/config.toml` so you do not have to repeat it.

4. Approve the unverified mods you want, one jar hash at a time (the hashes are printed by
   `--status`):

   ```sh
   ~/bitecheck/bitecheck.sh --server-dir ~/pzserver --allow <sha256>
   ```

   The approval is for exactly this jar. When the mod updates, the new jar has a new hash and
   is skipped again until it is verified or approved.

5. Start the server through BiteCheck, so the decisions are refreshed before every start:

   ```sh
   ~/bitecheck/bitecheck.sh --server-dir ~/pzserver --wrap ~/pzserver/start-server.sh -servername myserver
   ```

   `--wrap` runs the command after the check and passes its exit code on. If BiteCheck itself
   fails, the server still starts with the decisions from the previous run. Alternatively run
   `--sync` in your own start script before the server starts.

Windows servers work the same with `BiteCheck.bat`; set `BITECHECK_JAVA` to the server's
`jre64\bin\java.exe`.

## The same mods on server and clients

The admin exports the list of Java mod jars with their hashes and shares the file:

```sh
~/bitecheck/bitecheck.sh --server-dir ~/pzserver --export server-mods.toml
```

Players compare their own installation with it:

```sh
BiteCheck --compare server-mods.toml
```

It lists jars that are missing, different (another version than the server's) or extra, and
exits with code 3 if anything from the list is missing or different. The list also shows the
BiteCheck status the server saw for each jar.
