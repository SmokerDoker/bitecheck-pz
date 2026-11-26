package io.github.bitecheckpz.launcher;

import io.github.bitecheckpz.core.Index;
import io.github.bitecheckpz.core.Scanner;
import io.github.bitecheckpz.core.Verifier;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns the installed Java mods and the registry index into decisions for ZombieBuddy.
 *
 * <p>The player decides in BiteCheck, not in ZombieBuddy: any Java mod can write ZombieBuddy's
 * approvals file, so BiteCheck keeps the player's answers in its own state and writes an explicit
 * allow or deny for every jar. ZombieBuddy then has nothing left to ask.
 *
 * <table>
 *   <caption>What happens per jar</caption>
 *   <tr><th>Status</th><th>ZombieBuddy decision</th></tr>
 *   <tr><td>VERIFIED</td><td>allow - loads without a question</td></tr>
 *   <tr><td>UNVERIFIED, local scan clean or soft findings only</td><td>deny until the player allowed
 *       it; BiteCheck asks before the start (one question)</td></tr>
 *   <tr><td>UNVERIFIED with hard findings ("suspicious"), or FLAGGED</td><td>deny until the player
 *       allowed it anyway (two confirmations)</td></tr>
 *   <tr><td>BLOCKED, INVALID</td><td>deny - never loads</td></tr>
 * </table>
 *
 * <p>A "no" is remembered too, so BiteCheck does not ask again at every start. ZombieBuddy's own
 * jar (in the game folder) follows the same rules, but cannot be denied through its own approvals:
 * a ZombieBuddy jar that would be denied keeps the game from starting ({@link Row#stopsGame()}).
 *
 * <p>The local scan is a provisional check for mods nobody has reviewed yet. It catches obvious
 * red flags (process start, native code, runtime code loading, obfuscation) but is no review.
 */
public final class Planner {

    public enum Action {
        NONE, ALLOW, DENY
    }

    /**
     * @param scan local scan of an unverified jar, or null (verified/flagged/blocked jars are not
     *             scanned; also null if the jar could not be read, which counts as suspicious)
     */
    public record Row(JavaMods.JavaMod mod, JavaMods.Jar jar, Verifier.Status status, String info,
            Boolean currentDecision, Action action, boolean allowedAnyway, Scanner.Report scan,
            boolean suspicious, Change change, boolean refused) {

        /** Flagged or suspicious: allowing it takes two confirmations instead of one. */
        public boolean needsPermission() {
            return status == Verifier.Status.FLAGGED || suspicious;
        }

        /** Not verified and the player has not answered yet: BiteCheck asks before the start. */
        public boolean undecided() {
            return (status == Verifier.Status.UNVERIFIED || status == Verifier.Status.FLAGGED)
                    && !allowedAnyway && !refused;
        }

        /**
         * ZombieBuddy's own jar under the same rules as every mod, where it cannot decide about
         * itself: "does not load" means the game does not start with this jar.
         */
        public boolean stopsGame() {
            return mod.isLoader() && (status == Verifier.Status.BLOCKED || status == Verifier.Status.INVALID
                    || (status != Verifier.Status.VERIFIED && !allowedAnyway));
        }
    }

    /**
     * How an unverified jar relates to the version of the same jar the player trusted before.
     * Only a hint for the player's decision: a jar can claim any version, and anything on the
     * computer can write Steam's files.
     */
    public enum ChangeKind {
        /** Steam updated the Workshop item, or the jar has a new version number. */
        UPDATE,
        /** The jar changed, but Steam did not update the item / the version number is the same. */
        WITHOUT_UPDATE,
        /** The jar changed; BiteCheck cannot tell whether it was an update. */
        UNKNOWN
    }

    public record Change(ChangeKind kind, String text) {
    }

    /** Scan results by jar hash; jars do not change under the same hash. */
    private static final Map<String, Optional<Scanner.Report>> SCANS = new ConcurrentHashMap<>();

    private Planner() {
    }

    public static List<Row> plan(List<JavaMods.JavaMod> mods, Optional<Index> index, Instant now,
            ZombieBuddyApprovals approvals, LauncherState state) {
        return plan(mods, index, now, approvals, state, Map.of());
    }

    /** @param steamUpdated when Steam last updated each Workshop item, see {@link Platform#workshopUpdateTimes()} */
    public static List<Row> plan(List<JavaMods.JavaMod> mods, Optional<Index> index, Instant now,
            ZombieBuddyApprovals approvals, LauncherState state, Map<String, String> steamUpdated) {
        return plan(mods, index, now, approvals, state, steamUpdated, java.util.Set.of());
    }

    /**
     * @param trustedReviewers the reviewers the player trusts ({@code trusted_reviewers} in
     *                         config.toml); empty: every reviewer the registry accepts. A verified jar
     *                         none of them approved counts as unverified for this player.
     */
    public static List<Row> plan(List<JavaMods.JavaMod> mods, Optional<Index> index, Instant now,
            ZombieBuddyApprovals approvals, LauncherState state, Map<String, String> steamUpdated,
            java.util.Set<String> trustedReviewers) {
        boolean stale = index.isPresent() && index.get().isStale(now);
        List<Row> rows = new ArrayList<>();
        for (JavaMods.JavaMod mod : mods) {
            for (JavaMods.Jar jar : mod.jars()) {
                String sha = jar.sha256();
                Index.Entry entry = index.map(i -> i.lookup(sha))
                        .orElse(new Index.Entry(Verifier.Status.UNVERIFIED, "registry index not available"));
                Verifier.Status status = entry.status();
                String info = entry.label();
                if (status == Verifier.Status.VERIFIED && stale) {
                    status = Verifier.Status.UNVERIFIED;
                    info = "index is out of date, cannot confirm verification";
                }
                if (status == Verifier.Status.VERIFIED && !trustedReviewers.isEmpty()) {
                    List<String> by = index.get().reviewers(sha);
                    if (by.stream().noneMatch(trustedReviewers::contains)) {
                        status = Verifier.Status.UNVERIFIED;
                        info = "verified" + (by.isEmpty() ? "" : " by " + String.join(", ", by))
                                + ", but by none of the reviewers you trust";
                    }
                }
                String note = index.map(i -> i.note(sha)).orElse(null);
                if (note != null) {
                    info = info + "; " + note; // open info or suspicion flags, review notes
                }
                Scanner.Report scan = null;
                boolean suspicious = false;
                if (status == Verifier.Status.UNVERIFIED) {
                    Optional<Scanner.Report> report = scan(jar);
                    scan = report.orElse(null);
                    suspicious = report.isEmpty() || report.get().hasHardFindings();
                    info = info + "; " + describeScan(report);
                }
                Boolean current = approvals.decision(sha);
                boolean allowedAnyway = state.allowedAnyway().containsKey(sha);
                boolean refused = !allowedAnyway && state.refused().containsKey(sha);
                boolean hardBlocked = status == Verifier.Status.BLOCKED || status == Verifier.Status.INVALID;
                boolean allow = status == Verifier.Status.VERIFIED || (allowedAnyway && !hardBlocked);
                if (!allow && !hardBlocked && !jar.build42()) {
                    continue; // a Build 41 copy ZombieBuddy never loads: nothing to ask about
                }
                Action action;
                if (mod.isLoader()) {
                    action = Action.NONE; // decided at the start, not in ZombieBuddy's approvals
                } else if (allow) {
                    action = Boolean.TRUE.equals(current) ? Action.NONE : Action.ALLOW;
                } else {
                    action = Boolean.FALSE.equals(current) ? Action.NONE : Action.DENY;
                }
                Change change = status == Verifier.Status.VERIFIED ? null
                        : change(state.trusted().get(jarKey(mod, jar)), jar, mod, steamUpdated);
                rows.add(new Row(mod, jar, status, info, current, action, allowedAnyway, scan, suspicious, change,
                        refused));
            }
        }
        return rows;
    }

    /** Where the jar sits, the same for every version: mod id and path inside the mod folder. */
    static String jarKey(JavaMods.JavaMod mod, JavaMods.Jar jar) {
        String rel = mod.folder().relativize(jar.path()).toString().replace('\\', '/');
        if (mod.isLoader() && rel.endsWith(JavaMods.PENDING_SUFFIX)) {
            rel = rel.substring(0, rel.length() - JavaMods.PENDING_SUFFIX.length()); // the update of the same jar
        }
        return mod.id() + "/" + rel;
    }

    private static Change change(LauncherState.Trusted before, JavaMods.Jar jar, JavaMods.JavaMod mod,
            Map<String, String> steamUpdated) {
        if (before == null || before.sha256().equals(jar.sha256())) {
            return null;
        }
        if (mod.workshopId() != null) {
            String now = steamUpdated.get(mod.workshopId());
            if (now == null || before.steamUpdated() == null) {
                return new Change(ChangeKind.UNKNOWN, "Changed since you last trusted it; BiteCheck cannot tell "
                        + "whether Steam updated it.");
            }
            if (!now.equals(before.steamUpdated())) {
                return new Change(ChangeKind.UPDATE, "Update of a version you trusted (Steam updated the mod on "
                        + date(now) + "). If you trust the author, allowing it is usually safe.");
            }
            return new Change(ChangeKind.WITHOUT_UPDATE, "Changed without an update: this jar differs from the "
                    + "version you trusted, but Steam did not update the mod. Only allow it if you changed it yourself.");
        }
        String version = JavaMods.version(jar.path());
        if (version == null || before.version() == null) {
            return new Change(ChangeKind.UNKNOWN, "Changed since you last trusted it; BiteCheck cannot tell "
                    + "whether this is an update.");
        }
        if (!version.equals(before.version())) {
            return new Change(ChangeKind.UPDATE, "Update of a version you trusted (" + before.version() + " to "
                    + version + "). If you trust the author, allowing it is usually safe.");
        }
        return new Change(ChangeKind.WITHOUT_UPDATE, "Changed without an update: this jar differs from the "
                + "version you trusted, but has the same version number (" + version
                + "). Only allow it if you changed it yourself.");
    }

    private static String date(String unixSeconds) {
        try {
            return java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH)
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(Instant.ofEpochSecond(Long.parseLong(unixSeconds)));
        } catch (RuntimeException e) {
            return unixSeconds;
        }
    }

    static Optional<Scanner.Report> scan(JavaMods.Jar jar) {
        return SCANS.computeIfAbsent(jar.sha256(), sha -> {
            try {
                return Optional.of(Scanner.scan(jar.path()));
            } catch (IOException | RuntimeException e) {
                return Optional.empty();
            }
        });
    }

    /** Short text such as "local scan: no findings" or "local scan found: process, native". */
    static String describeScan(Optional<Scanner.Report> report) {
        if (report.isEmpty()) {
            return "local scan: jar could not be read";
        }
        Scanner.Report r = report.get();
        if (r.hasHardFindings()) {
            return "local scan found: " + rules(r, Scanner.Severity.HARD);
        }
        if (r.findings().isEmpty()) {
            return "local scan: no findings";
        }
        return "local scan, check: " + rules(r, Scanner.Severity.SOFT);
    }

    private static String rules(Scanner.Report r, Scanner.Severity severity) {
        Set<String> rules = new LinkedHashSet<>();
        for (Scanner.Finding f : r.findings()) {
            if (f.severity() == severity) {
                rules.add(f.rule());
            }
        }
        return String.join(", ", rules);
    }

    /** Applies the planned actions to ZombieBuddy's approvals and BiteCheck's own state. */
    public static int apply(List<Row> rows, ZombieBuddyApprovals approvals, LauncherState state) {
        return apply(rows, approvals, state, Map.of());
    }

    public static int apply(List<Row> rows, ZombieBuddyApprovals approvals, LauncherState state,
            Map<String, String> steamUpdated) {
        int changes = 0;
        for (Row row : rows) {
            if (trusted(row)) {
                String key = jarKey(row.mod(), row.jar());
                state.trusted().put(key, new LauncherState.Trusted(key, row.jar().sha256(),
                        JavaMods.version(row.jar().path()),
                        row.mod().workshopId() == null ? null : steamUpdated.get(row.mod().workshopId())));
            }
            if (row.mod().isLoader()) {
                continue; // never written into ZombieBuddy's approvals
            }
            String sha = row.jar().sha256();
            String label = row.mod().id() + " " + row.jar().path().getFileName();
            switch (row.action()) {
                case ALLOW -> {
                    approvals.set(row.mod().id(), row.mod().workshopId(), sha, true);
                    if (row.status() == Verifier.Status.VERIFIED) {
                        state.autoApproved().put(sha, label);
                    }
                    changes++;
                }
                case DENY -> {
                    approvals.set(row.mod().id(), row.mod().workshopId(), sha, false);
                    state.autoApproved().remove(sha);
                    changes++;
                }
                case NONE -> {
                    if (row.status() == Verifier.Status.VERIFIED) {
                        state.autoApproved().put(sha, label);
                    }
                }
            }
        }
        return changes;
    }

    /** Loads with the player's trust: verified, or allowed by the player in BiteCheck. */
    static boolean trusted(Row r) {
        return switch (r.status()) {
            case VERIFIED -> true;
            case BLOCKED, INVALID -> false;
            case FLAGGED -> r.allowedAnyway();
            case UNVERIFIED -> r.allowedAnyway();
        };
    }
}
