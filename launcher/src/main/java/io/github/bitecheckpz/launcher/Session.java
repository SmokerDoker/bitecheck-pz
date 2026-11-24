package io.github.bitecheckpz.launcher;

import io.github.bitecheckpz.core.Index;
import io.github.bitecheckpz.core.Keys;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    /** When Steam last updated each Workshop item, for the update hints. */
    public final Map<String, String> steamUpdated;
    /** A newer BiteCheck release, if there is one (see {@link UpdateCheck}). */
    public Optional<String> newerVersion = Optional.empty();
    /** Whether BiteCheck's own jar is the one of its signed release (see {@link SelfCheck}). */
    public SelfCheck.Result selfCheck = SelfCheck.Result.unknown();

    /** A release to install: a newer one, or the same one again to repair a changed installation. */
    public record UpdateTarget(String version, boolean repair) {
    }

    /** What to install now, if anything: a newer release wins, as it also replaces a changed jar. */
    public Optional<UpdateTarget> updateTarget() {
        if (newerVersion.isPresent()) {
            return Optional.of(new UpdateTarget(newerVersion.get(), false));
        }
        return selfCheck.outcome() == SelfCheck.Outcome.CHANGED
                ? Optional.of(new UpdateTarget(selfCheck.version(), true)) : Optional.empty();
    }

    private Session(Platform platform, Config config, LauncherState state, ZombieBuddyApprovals approvals,
            List<JavaMods.JavaMod> mods, IndexClient.Outcome index, List<Planner.Row> rows, Instant now,
            Map<String, String> steamUpdated) {
        this.platform = platform;
        this.config = config;
        this.state = state;
        this.approvals = approvals;
        this.mods = mods;
        this.index = index;
        this.rows = rows;
        this.now = now;
        this.steamUpdated = steamUpdated;
    }

    public static Session run(Path biteCheckDir) throws IOException {
        return run(biteCheckDir, Map.of());
    }

    /** @param overrides config values given on the command line, e.g. server_dir */
    public static Session run(Path biteCheckDir, Map<String, String> overrides) throws IOException {
        Config config = Config.load(biteCheckDir.resolve("config.toml")).with(overrides);
        Platform platform = new Platform(config);
        LauncherState state = LauncherState.load(biteCheckDir.resolve("state.toml"));
        ZombieBuddyApprovals approvals = ZombieBuddyApprovals.load(platform.zombieBuddyDir());
        List<JavaMods.JavaMod> mods = JavaMods.discover(platform);
        Optional<Keys.PublicKeyInfo> key = IndexClient.registryKey(config);
        URI url = config.url("index_url").orElse(URI.create(IndexClient.DEFAULT_URL));
        Instant now = Instant.now();
        IndexClient.Outcome index = new IndexClient(url, key, IndexClient.timestampKey(config),
                biteCheckDir.resolve("cache"), state).fetch(now);
        Optional<Index> usable = index.index();
        Map<String, String> steamUpdated = platform.workshopUpdateTimes();
        List<Planner.Row> rows = Planner.plan(mods, usable, now, approvals, state, steamUpdated);
        Session session = new Session(platform, config, state, approvals, mods, index, rows, now, steamUpdated);
        if (state.tampered()) {
            session.notes.add("Your saved answers in " + biteCheckDir.resolve("state.toml") + " were changed outside "
                    + "BiteCheck. BiteCheck does not use them and asks you again about mods that are not verified.");
        }
        session.notes.addAll(index.notes());
        if (index.stale(now)) {
            session.notes.add("The registry index was last confirmed more than " + Index.MAX_AGE.toDays()
                    + " days ago. Verified mods are treated as unverified until it is updated.");
        }
        if (platform.workshopContentDirs().isEmpty() && platform.steamRoots().isEmpty()) {
            session.notes.add("Steam was not found. Set steam_dir in " + biteCheckDir.resolve("config.toml") + ".");
        }
        session.newerVersion = UpdateCheck.newerVersion(config);
        session.newerVersion.map(UpdateCheck::note).ifPresent(session.notes::add);
        session.selfCheck = SelfCheck.check(config, biteCheckDir.resolve("cache"));
        if (session.selfCheck.outcome() == SelfCheck.Outcome.CHANGED) {
            session.notes.add(0, SelfCheck.note(session.selfCheck));
        }
        return session;
    }

    /** Writes the planned decisions. Returns the number of changed jars. */
    public int apply() throws IOException {
        int changes = Planner.apply(rows, approvals, state, steamUpdated);
        java.util.Set<String> allowed = new java.util.HashSet<>();
        rows.stream().filter(Planner::trusted).forEach(r -> allowed.add(r.jar().sha256()));
        notes.addAll(ZombieBuddyConfig.secure(platform.zombieBuddyDir(), allowed));
        approvals.save();
        state.save();
        return changes;
    }

    /** ZombieBuddy jars the game must not start with yet (see {@link Planner.Row#stopsGame()}). */
    public List<Planner.Row> startStoppers() {
        return rows.stream().filter(Planner.Row::stopsGame).toList();
    }

    /** Re-plans after the player changed something (e.g. allowed a flagged mod). */
    public Session replan() {
        Session s = new Session(platform, config, state, approvals, mods, index,
                Planner.plan(mods, index.index(), now, approvals, state, steamUpdated), now, steamUpdated);
        s.notes.addAll(notes);
        s.newerVersion = newerVersion;
        s.selfCheck = selfCheck;
        return s;
    }
}
