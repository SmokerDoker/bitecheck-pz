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
import java.util.Optional;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * The player's decision about mods that are not verified, the same for every mod including the
 * loader (ZombieBuddy or Jumpstart). Before the start one window lists every open mod with a
 * checkbox ({@link StartDialog}); a ticked flagged or suspicious mod also needs ALLOW typed. A "yes"
 * is kept in BiteCheck's state, so the jar is not asked about again. An unticked mod stays off for
 * this start and is asked about next time. Without the loader allowed (or with it blocked) the game
 * does not start, because the loader cannot switch itself off; a window says so.
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
                err.println("BiteCheck: " + loaderName(session) + " is not allowed, so the game was not started.");
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
            err.println("BiteCheck: " + loaderName(session) + " is not allowed, so the game was not started.");
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
                String loader = LauncherWindow.escape(r.mod().name());
                message(parent, "<html><b>" + loader + " is " + word(r) + "</b> and cannot be "
                        + "allowed:<br>" + LauncherWindow.escape(r.info()) + "<br><br>The game does not start with it. "
                        + "Install " + loader + " again from its official source.</html>",
                        r.mod().name() + " blocked", JOptionPane.ERROR_MESSAGE);
                return false;
            }
        }
        Path stateFile = biteCheckDir.resolve("state.toml");
        if (session.state.tampered()) {
            message(parent, "<html><b>Your saved answers were changed outside BiteCheck.</b><br><br>"
                    + "Something on this computer edited " + LauncherWindow.escape(stateFile.toString()) + ".<br>"
                    + "BiteCheck ignores those answers and asks you again. If you did not edit the file yourself,<br>"
                    + "check your computer with an antivirus program.</html>",
                    "Answers changed", JOptionPane.WARNING_MESSAGE);
        }
        List<Question> questions = questions(session);
        if (questions.isEmpty()) {
            return true;
        }
        // one window with every open mod; unticked ones stay off for this start and are asked again
        Optional<List<Question>> ticked = StartDialog.show(parent, questions);
        Optional<Question> loader = questions.stream().filter(q -> q.mod().isLoader()).findFirst();
        if (ticked.isEmpty()) {
            if (loader.isPresent()) {
                notStarted(parent, loader.get().mod().name());
                return false;
            }
            return true;
        }
        for (Question q : ticked.get()) {
            boolean yes = !q.risky() || confirmRisky(parent, q, stateFile);
            if (yes) {
                for (Planner.Row r : q.rows()) {
                    session.state.refused().remove(r.jar().sha256());
                    session.state.allowedAnyway().put(r.jar().sha256(), q.mod().id() + " - allowed by the player");
                }
            } else if (q.mod().isLoader()) {
                notStarted(parent, q.mod().name());
                return false;
            }
        }
        return true;
    }

    /** Visible, so the player knows why the game does not start (asked again at the next start). */
    private static void notStarted(Component parent, String loader) {
        message(parent, "<html><b>The game does not start.</b><br><br>" + LauncherWindow.escape(loader)
                + " loads every Java mod and is not allowed yet.<br>Start the game again to decide.</html>",
                "BiteCheck", JOptionPane.INFORMATION_MESSAGE);
    }

    /** Keeps a BiteCheck window above Steam and the desktop: started from Steam it must not hide. */
    static void onTop(java.awt.Window w) {
        w.setAlwaysOnTop(true);
        w.setAutoRequestFocus(true);
    }

    static void message(Component parent, Object text, String title, int type) {
        JOptionPane pane = new JOptionPane(text, type);
        JDialog d = pane.createDialog(parent, title);
        onTop(d);
        d.setVisible(true);
        d.dispose();
    }

    static int confirm(Component parent, Object text, String title, int type) {
        JOptionPane pane = new JOptionPane(text, type, JOptionPane.YES_NO_OPTION);
        JDialog d = pane.createDialog(parent, title);
        onTop(d);
        d.setVisible(true);
        d.dispose();
        return pane.getValue() instanceof Integer i ? i : JOptionPane.CLOSED_OPTION;
    }

    static String input(Component parent, Object text, String title) {
        JOptionPane pane = new JOptionPane(text, JOptionPane.WARNING_MESSAGE, JOptionPane.OK_CANCEL_OPTION);
        pane.setWantsInput(true);
        JDialog d = pane.createDialog(parent, title);
        onTop(d);
        pane.selectInitialValue();
        d.setVisible(true);
        d.dispose();
        boolean ok = pane.getValue() instanceof Integer i && i == JOptionPane.OK_OPTION;
        Object v = pane.getInputValue();
        return ok && v != JOptionPane.UNINITIALIZED_VALUE ? String.valueOf(v) : null;
    }

    /** True for "ALLOW" in any case, with spaces around it. */
    static boolean typedAllow(String typed) {
        return typed != null && typed.strip().equalsIgnoreCase("ALLOW");
    }

    /** The second confirmation for a ticked flagged or risky mod: type ALLOW. */
    private static boolean confirmRisky(Component parent, Question q, Path stateFile) {
        Planner.Row row = q.rows().stream().filter(Planner.Row::needsPermission).findFirst().orElse(q.first());
        String name = LauncherWindow.escape(q.mod().name());
        String why = row.status() == Verifier.Status.FLAGGED
                ? "has been <b>flagged</b> as possibly harmful by the BiteCheck registry"
                : "is not reviewed and BiteCheck's <b>local scan</b> found risky code";
        return typedAllow(input(parent, "<html><b>" + name + "</b> " + why + ":<br>" + LauncherWindow.escape(row.info())
                + "<br><br>Only allow it if you trust the author and know why it needs this.<br>"
                + "To confirm, type <b>ALLOW</b>. You can undo this by deleting<br>"
                + LauncherWindow.escape(stateFile.toString()) + "</html>", "Risky mod: " + q.mod().name()));
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
                ? name + " loads every Java mod. If you say no, the game does not start."
                : "Java mods can access your whole computer. If you say no, the game starts without this mod.";
        if (!q.risky()) {
            int answer = confirm(parent, "<html><b>" + name + "</b> is not reviewed yet:<br>"
                    + LauncherWindow.escape(row.info()) + hint + "<br><br>" + what + "<br><br>"
                    + "Allow " + name + "?</html>",
                    "Not reviewed", JOptionPane.QUESTION_MESSAGE);
            return answer == JOptionPane.YES_OPTION;
        }
        String why = row.status() == Verifier.Status.FLAGGED
                ? "has been <b>flagged</b> as possibly harmful by the BiteCheck registry:"
                : "is not reviewed and BiteCheck's <b>local scan</b> found risky code:";
        int first = confirm(parent,
                "<html><b>" + name + "</b> " + why + "<br><br>" + LauncherWindow.escape(row.info()) + hint
                        + "<br><br>" + what + "<br>"
                        + "Only allow it if you trust the author and know why it needs this.<br>"
                        + "Do you really want to allow it?</html>",
                "Risky mod", JOptionPane.WARNING_MESSAGE);
        if (first != JOptionPane.YES_OPTION) {
            return false;
        }
        return typedAllow(input(parent,
                "<html>To confirm, type <b>ALLOW</b>. You can undo this by deleting<br>"
                        + LauncherWindow.escape(stateFile.toString()) + "</html>",
                "Confirm"));
    }

    /** The mod loader's name for messages: ZombieBuddy, or Jumpstart with ZombieBuddy built in. */
    static String loaderName(Session session) {
        return session.rows.stream().filter(r -> r.mod().isLoader()).map(r -> r.mod().name()).findFirst()
                .orElse("ZombieBuddy");
    }

    static boolean hardBlocked(Planner.Row r) {
        return r.status() == Verifier.Status.BLOCKED || r.status() == Verifier.Status.INVALID;
    }

    static String word(Planner.Row r) {
        return switch (r.status()) {
            case BLOCKED -> "blocked";
            case INVALID -> "invalid (its registry entry does not match its signature)";
            case FLAGGED -> "flagged";
            case VERIFIED -> "verified";
            case UNVERIFIED -> r.suspicious() ? "not reviewed, and the local scan found risky code" : "not reviewed";
        };
    }
}
