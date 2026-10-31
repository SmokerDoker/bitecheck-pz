package io.github.bitecheckpz.launcher;

import io.github.bitecheckpz.core.Index;
import io.github.bitecheckpz.core.Keys;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** One check run: find mods, load the index, plan the decisions. Shared by the window and the CLI. */
public final class Session {

    public final Platform platform;
    public final Config config;
    public final LauncherState state;
    public final ZombieBuddyApprovals approvals;
    public final List<JavaMods.JavaMod> mods;
    public final IndexClient.Outcome index;
    public final List<Planner.Row> rows;
    public final List<String> notes = new ArrayList<>();
    public final Instant now;

    private Session(Platform platform, Config config, LauncherState state, ZombieBuddyApprovals approvals,
            List<JavaMods.JavaMod> mods, IndexClient.Outcome index, List<Planner.Row> rows, Instant now) {
        this.platform = platform;
        this.config = config;
        this.state = state;
        this.approvals = approvals;
        this.mods = mods;
        this.index = index;
        this.rows = rows;
        this.now = now;
    }

    public static Session run(Path biteCheckDir) throws IOException {
        Config config = Config.load(biteCheckDir.resolve("config.toml"));
        Platform platform = new Platform(config);
        LauncherState state = LauncherState.load(biteCheckDir.resolve("state.toml"));
        ZombieBuddyApprovals approvals = ZombieBuddyApprovals.load(platform.zombieBuddyDir());
        List<JavaMods.JavaMod> mods = JavaMods.discover(platform);
        Optional<Keys.PublicKeyInfo> key = IndexClient.registryKey(config);
        URI url = config.url("index_url").orElse(URI.create(IndexClient.DEFAULT_URL));
        Instant now = Instant.now();
        IndexClient.Outcome index = new IndexClient(url, key, biteCheckDir.resolve("cache"), state).fetch(now);
        Optional<Index> usable = index.index();
        List<Planner.Row> rows = Planner.plan(mods, usable, now, approvals, state);
        Session session = new Session(platform, config, state, approvals, mods, index, rows, now);
        session.notes.addAll(index.notes());
        if (index.stale(now)) {
            session.notes.add("The registry index is older than " + Index.MAX_AGE.toDays()
                    + " days. Verified mods are treated as unverified until it is updated.");
        }
        if (platform.workshopContentDirs().isEmpty() && platform.steamRoots().isEmpty()) {
            session.notes.add("Steam was not found. Set steam_dir in " + biteCheckDir.resolve("config.toml") + ".");
        }
        return session;
    }

    /** Writes the planned decisions. Returns the number of changed jars. */
    public int apply() throws IOException {
        int changes = Planner.apply(rows, approvals, state);
        approvals.save();
        state.save();
        return changes;
    }

    /** Re-plans after the player changed something (e.g. allowed a flagged mod). */
    public Session replan() {
        Session s = new Session(platform, config, state, approvals, mods, index,
                Planner.plan(mods, index.index(), now, approvals, state), now);
        s.notes.addAll(notes);
        return s;
    }
}
