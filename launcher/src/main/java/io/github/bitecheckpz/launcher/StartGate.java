package io.github.bitecheckpz.launcher;

import io.github.bitecheckpz.core.Verifier;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * The player's decision about mods that are not verified, the same for every mod including
 * ZombieBuddy: one question for an unverified mod, two confirmations for a flagged or suspicious
 * one. Answers are kept in BiteCheck's state, so each jar is asked about once. A "no" for an
 * ordinary mod keeps that mod off; a "no" (or a block) for ZombieBuddy keeps the game from
 * starting, because ZombieBuddy cannot switch itself off.
 */
final class StartGate {

    private StartGate() {
    }

    /** One question per mod: its jars that are still undecided. */
    record Question(JavaMods.JavaMod mod, List<Planner.Row> rows) {

        boolean risky() {
            return rows.stream().anyMatch(Planner.Row::needsPermission);
        }

        Planner.Row first() {
            return rows.get(0);
        }
    }

    /** Mods the player still has to answer about, ZombieBuddy first. */
    static List<Question> questions(Session session) {
        Map<Path, List<Planner.Row>> byMod = new LinkedHashMap<>();
        for (Planner.Row r : session.rows) {
            if (r.undecided()) {
                byMod.computeIfAbsent(r.mod().folder(), k -> new ArrayList<>()).add(r);
            }
        }
        List<Question> list = new ArrayList<>();
        byMod.values().forEach(rows -> list.add(new Question(rows.get(0).mod(), List.copyOf(rows))));
        list.sort((a, b) -> Boolean.compare(b.mod().isLoader(), a.mod().isLoader()));
        return list;
    }

    /**
     * Before a Steam start ({@code --wrap}): asks the open questions and writes the decisions.
     * Returns true if the game may start. Without a screen (dedicated server) nothing is asked:
     * undecided mods stay off, and the output says how to allow them.
     */
    static boolean beforeStart(Session session, Path biteCheckDir, PrintStream err) throws IOException {
        List<Question> questions = questions(session);
        boolean loaderBlocked = session.rows.stream().anyMatch(r -> r.mod().isLoader() && hardBlocked(r));
        if (questions.isEmpty() && !loaderBlocked) {
            return true;
        }
        if (GraphicsEnvironment.isHeadless()) {
            for (Question q : questions) {
                for (Planner.Row r : q.rows()) {
                    err.println("BiteCheck: " + q.mod().name() + " is " + word(r) + "; it stays off. To allow it: "
                            + "BiteCheck --allow " + r.jar().sha256() + (r.needsPermission() ? " --i-understand-the-risk" : ""));
                }
            }
            if (!session.startStoppers().isEmpty()) {
                err.println("BiteCheck: ZombieBuddy is not allowed, so the game was not started.");
                return false;
            }
            return true;
        }
        boolean[] ok = {false};
        try {
            SwingUtilities.invokeAndWait(() -> {
                LauncherWindow.setUpLook();
                ok[0] = askAll(null, session, biteCheckDir);
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new IOException(e.getCause().getMessage(), e.getCause());
        }
        session.replan().apply();
        if (!ok[0]) {
            err.println("BiteCheck: ZombieBuddy is not allowed, so the game was not started.");
        }
        return ok[0];
    }

    /**
     * Asks about every undecided mod and records the answers in the session's state (the caller
     * re-plans and applies). Returns false if the game must not start: ZombieBuddy blocked or not allowed.
     */
    static boolean askAll(Component parent, Session session, Path biteCheckDir) {
        for (Planner.Row r : session.rows) {
            if (r.mod().isLoader() && hardBlocked(r)) {
                JOptionPane.showMessageDialog(parent, "<html><b>ZombieBuddy is " + word(r) + "</b> and cannot be "
                        + "allowed:<br>" + LauncherWindow.escape(r.info()) + "<br><br>The game does not start with it. "
                        + "Install ZombieBuddy again from its official source.</html>",
                        "ZombieBuddy blocked", JOptionPane.ERROR_MESSAGE);
                return false;
            }
        }
        Path stateFile = biteCheckDir.resolve("state.toml");
        for (Question q : questions(session)) {
            boolean yes = ask(parent, q, stateFile);
            for (Planner.Row r : q.rows()) {
                String sha = r.jar().sha256();
                if (yes) {
                    session.state.refused().remove(sha);
                    session.state.allowedAnyway().put(sha, q.mod().id() + " - allowed by the player");
                } else if (!q.mod().isLoader()) {
                    session.state.refused().put(sha, q.mod().id() + " - not allowed by the player");
                }
            }
            if (!yes && q.mod().isLoader()) {
                return false; // asked again at the next start
            }
        }
        return true;
    }

    /**
     * The question for one mod: a single one if it is only unverified, two (confirm, type ALLOW)
     * if a jar is flagged or suspicious. Shows the update hint if a jar replaced a version the
     * player trusted.
     */
    static boolean ask(Component parent, Question q, Path stateFile) {
        Planner.Row row = q.rows().stream().filter(Planner.Row::needsPermission).findFirst().orElse(q.first());
        String name = LauncherWindow.escape(q.mod().name());
        StringBuilder hint = new StringBuilder();
        for (Planner.Row r : q.rows()) {
            if (r.change() != null) {
                String text = LauncherWindow.escape(r.change().text());
                hint.append("<br><br>").append(r.change().kind() == Planner.ChangeKind.WITHOUT_UPDATE
                        ? "<b>⚠ " + text + "</b>" : text);
                break;
            }
        }
        String what = q.mod().isLoader()
                ? "ZombieBuddy loads every Java mod. If you say no, the game does not start."
                : "Java mods can access your whole computer. If you say no, the game starts without this mod.";
        if (!q.risky()) {
            int answer = JOptionPane.showConfirmDialog(parent, "<html><b>" + name + "</b> is not reviewed yet:<br>"
                    + LauncherWindow.escape(row.info()) + hint + "<br><br>" + what + "<br><br>"
                    + "Allow " + name + "?</html>",
                    "Not reviewed", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            return answer == JOptionPane.YES_OPTION;
        }
        String why = row.status() == Verifier.Status.FLAGGED
                ? "has been <b>flagged</b> as possibly harmful by the BiteCheck registry:"
                : "is not reviewed and BiteCheck's <b>local scan</b> found risky code:";
        int first = JOptionPane.showConfirmDialog(parent,
                "<html><b>" + name + "</b> " + why + "<br><br>" + LauncherWindow.escape(row.info()) + hint
                        + "<br><br>" + what + "<br>"
                        + "Only allow it if you trust the author and know why it needs this.<br>"
                        + "Do you really want to allow it?</html>",
                "Risky mod", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (first != JOptionPane.YES_OPTION) {
            return false;
        }
        String typed = JOptionPane.showInputDialog(parent,
                "<html>To confirm, type <b>ALLOW</b>. You can undo this by deleting<br>"
                        + LauncherWindow.escape(stateFile.toString()) + "</html>",
                "Confirm", JOptionPane.WARNING_MESSAGE);
        return "ALLOW".equals(typed == null ? null : typed.strip());
    }

    static boolean hardBlocked(Planner.Row r) {
        return r.status() == Verifier.Status.BLOCKED || r.status() == Verifier.Status.INVALID;
    }

    private static String word(Planner.Row r) {
        return switch (r.status()) {
            case BLOCKED -> "blocked";
            case INVALID -> "invalid (its registry entry does not match its signature)";
            case FLAGGED -> "flagged";
            case VERIFIED -> "verified";
            case UNVERIFIED -> r.suspicious() ? "not reviewed, and the local scan found risky code" : "not reviewed";
        };
    }
}
