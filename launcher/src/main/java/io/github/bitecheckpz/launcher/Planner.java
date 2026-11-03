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
 * <table>
 *   <caption>What happens per jar</caption>
 *   <tr><th>Status</th><th>ZombieBuddy decision</th></tr>
 *   <tr><td>VERIFIED</td><td>allow - loads without a question</td></tr>
 *   <tr><td>UNVERIFIED, local scan clean or soft findings only</td><td>unchanged - ZombieBuddy warns
 *       and asks the player in game; an approval BiteCheck wrote earlier is taken back</td></tr>
 *   <tr><td>UNVERIFIED, local scan has hard findings ("suspicious")</td><td>deny, unless the player
 *       allowed it anyway (double confirmation)</td></tr>
 *   <tr><td>FLAGGED</td><td>deny, unless the player allowed it anyway (double confirmation)</td></tr>
 *   <tr><td>BLOCKED</td><td>deny - never loads</td></tr>
 *   <tr><td>UNVERIFIED, allowed ahead of time with {@code --allow}</td><td>allow (e.g. on a dedicated
 *       server, where ZombieBuddy runs with {@code policy=deny-new} and cannot ask)</td></tr>
 * </table>
 *
 * <p>The local scan is a provisional check for mods nobody has reviewed yet. It catches obvious
 * red flags (process start, native code, runtime code loading, obfuscation) but is no review.
 */
public final class Planner {

    public enum Action {
        NONE, ALLOW, DENY, ASK_IN_GAME
    }

    /**
     * @param scan local scan of an unverified jar, or null (verified/flagged/blocked jars are not
     *             scanned; also null if the jar could not be read, which counts as suspicious)
     */
    public record Row(JavaMods.JavaMod mod, JavaMods.Jar jar, Verifier.Status status, String info,
            Boolean currentDecision, Action action, boolean allowedAnyway, Scanner.Report scan,
            boolean suspicious) {

        /** Flagged or suspicious: blocked by BiteCheck unless the player allows it anyway. */
        public boolean needsPermission() {
            return status == Verifier.Status.FLAGGED || suspicious;
        }
    }

    /** Scan results by jar hash; jars do not change under the same hash. */
    private static final Map<String, Optional<Scanner.Report>> SCANS = new ConcurrentHashMap<>();

    private Planner() {
    }

    public static List<Row> plan(List<JavaMods.JavaMod> mods, Optional<Index> index, Instant now,
            ZombieBuddyApprovals approvals, LauncherState state) {
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
                boolean needsPermission = status == Verifier.Status.FLAGGED || suspicious;
                Action action;
                if (status == Verifier.Status.VERIFIED) {
                    action = Boolean.TRUE.equals(current) ? Action.NONE : Action.ALLOW;
                } else if (status == Verifier.Status.BLOCKED || status == Verifier.Status.INVALID) {
                    action = Boolean.FALSE.equals(current) ? Action.NONE : Action.DENY;
                } else if (needsPermission) {
                    action = allowedAnyway
                            ? (Boolean.TRUE.equals(current) ? Action.NONE : Action.ALLOW)
                            : (Boolean.FALSE.equals(current) ? Action.NONE : Action.DENY);
                } else if (allowedAnyway) {
                    // unverified, approved ahead of time by the player or server admin (--allow)
                    action = Boolean.TRUE.equals(current) ? Action.NONE : Action.ALLOW;
                } else {
                    action = state.autoApproved().containsKey(sha) ? Action.ASK_IN_GAME : Action.NONE;
                    if (!jar.build42() && action == Action.NONE) {
                        continue; // a Build 41 copy ZombieBuddy never loads and never asks about
                    }
                }
                rows.add(new Row(mod, jar, status, info, current, action, allowedAnyway, scan, suspicious));
            }
        }
        return rows;
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
        int changes = 0;
        for (Row row : rows) {
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
                case ASK_IN_GAME -> {
                    approvals.remove(sha);
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
}
