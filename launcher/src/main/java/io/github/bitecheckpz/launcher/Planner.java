package io.github.bitecheckpz.launcher;

import io.github.bitecheckpz.core.Index;
import io.github.bitecheckpz.core.Verifier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Turns the installed Java mods and the registry index into decisions for ZombieBuddy.
 *
 * <table>
 *   <caption>What happens per jar</caption>
 *   <tr><th>Status</th><th>ZombieBuddy decision</th></tr>
 *   <tr><td>VERIFIED</td><td>allow - loads without a question</td></tr>
 *   <tr><td>UNVERIFIED</td><td>unchanged - ZombieBuddy warns and asks the player in game; an approval
 *       BiteCheck wrote earlier is taken back</td></tr>
 *   <tr><td>FLAGGED</td><td>deny, unless the player allowed it anyway in BiteCheck (double
 *       confirmation)</td></tr>
 *   <tr><td>BLOCKED</td><td>deny - never loads</td></tr>
 * </table>
 */
public final class Planner {

    public enum Action {
        NONE, ALLOW, DENY, ASK_IN_GAME
    }

    public record Row(JavaMods.JavaMod mod, JavaMods.Jar jar, Verifier.Status status, String info,
            Boolean currentDecision, Action action, boolean allowedAnyway) {
    }

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
                Boolean current = approvals.decision(sha);
                boolean allowedAnyway = state.allowedFlagged().containsKey(sha);
                Action action = switch (status) {
                    case VERIFIED -> Boolean.TRUE.equals(current) ? Action.NONE : Action.ALLOW;
                    case BLOCKED -> Boolean.FALSE.equals(current) ? Action.NONE : Action.DENY;
                    case FLAGGED -> allowedAnyway
                            ? (Boolean.TRUE.equals(current) ? Action.NONE : Action.ALLOW)
                            : (Boolean.FALSE.equals(current) ? Action.NONE : Action.DENY);
                    default -> state.autoApproved().containsKey(sha) ? Action.ASK_IN_GAME : Action.NONE;
                };
                rows.add(new Row(mod, jar, status, info, current, action, allowedAnyway));
            }
        }
        return rows;
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
