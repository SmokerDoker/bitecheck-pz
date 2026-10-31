package io.github.bitecheckpz.launcher;

import io.github.bitecheckpz.core.Hashing;
import io.github.bitecheckpz.core.Verifier;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * BiteCheck launcher entry point.
 *
 * <pre>
 * BiteCheck                 open the window (default)
 * BiteCheck --status        print the status of all installed Java mods
 * BiteCheck --sync          apply the decisions without starting the game
 * BiteCheck --launch        apply the decisions and start Project Zomboid through Steam
 * BiteCheck --allow &lt;sha256&gt; --i-understand-the-risk   (a flagged or suspicious mod)
 * BiteCheck --dir &lt;folder&gt;  use another BiteCheck folder instead of ~/.bitecheck
 * </pre>
 */
public final class LauncherMain {

    private LauncherMain() {
    }

    public static void main(String[] args) {
        int code = run(args, System.out, System.err);
        if (code >= 0) {
            System.exit(code);
        }
        // negative: the window is open and keeps the JVM alive
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        List<String> rest = new ArrayList<>(List.of(args));
        Path dir = Path.of(System.getProperty("user.home"), ".bitecheck");
        int dirIdx = rest.indexOf("--dir");
        if (dirIdx >= 0) {
            if (dirIdx + 1 >= rest.size()) {
                err.println("--dir needs a folder");
                return 2;
            }
            dir = Path.of(rest.get(dirIdx + 1));
            rest.remove(dirIdx + 1);
            rest.remove(dirIdx);
        }
        String command = rest.isEmpty() ? "--window" : rest.get(0);
        try {
            switch (command) {
                case "--help", "-h" -> {
                    out.println("Usage: BiteCheck [--status | --sync | --launch | --allow <sha256> "
                            + "--i-understand-the-risk] [--dir <folder>]");
                    return 0;
                }
                case "--version" -> {
                    String v = LauncherMain.class.getPackage().getImplementationVersion();
                    out.println("BiteCheck launcher " + (v == null ? "dev" : v));
                    return 0;
                }
                case "--window" -> {
                    if (GraphicsEnvironment.isHeadless()) {
                        err.println("No display available; showing the status instead.");
                        return printStatus(Session.run(dir), out);
                    }
                    LauncherWindow.open(dir);
                    return -1;
                }
                case "--status" -> {
                    return printStatus(Session.run(dir), out);
                }
                case "--sync", "--launch" -> {
                    Session session = Session.run(dir);
                    printStatus(session, out);
                    int changes = session.apply();
                    out.println(changes + " change(s) written to " + session.approvals.file());
                    if (command.equals("--launch")) {
                        GameStarter.start(session.platform.os());
                        out.println("Starting Project Zomboid through Steam…");
                    }
                    return 0;
                }
                case "--allow", "--allow-flagged" -> {
                    if (rest.size() < 3 || !rest.get(2).equals("--i-understand-the-risk")) {
                        err.println("Usage: BiteCheck --allow <sha256> --i-understand-the-risk");
                        return 2;
                    }
                    String sha = rest.get(1);
                    if (!Hashing.isSha256Hex(sha)) {
                        err.println("not a SHA-256 hash: " + sha);
                        return 2;
                    }
                    Session session = Session.run(dir);
                    boolean blockedByBiteCheck = session.rows.stream()
                            .anyMatch(r -> r.jar().sha256().equals(sha) && r.needsPermission());
                    if (!blockedByBiteCheck) {
                        err.println("No installed flagged or suspicious mod has this hash.");
                        return 1;
                    }
                    session.state.allowedAnyway().put(sha, "allowed by the player");
                    session.replan().apply();
                    out.println("Allowed. ZombieBuddy will load this mod.");
                    return 0;
                }
                default -> {
                    err.println("Unknown option " + command + ". Try --help.");
                    return 2;
                }
            }
        } catch (IOException e) {
            err.println("BiteCheck: " + e.getMessage());
            return 1;
        }
    }

    static int printStatus(Session session, PrintStream out) {
        out.println("Game:        " + session.platform.gameDir().map(Path::toString).orElse("not found"));
        out.println("Mods:        " + session.mods.size() + " Java mod(s) found");
        out.println("ZombieBuddy: " + session.approvals.file());
        session.index.index().ifPresentOrElse(
                i -> out.println("Index:       " + i.generatedAt() + (session.index.fromCache() ? " (saved copy)" : "")
                        + ", " + i.entries().size() + " entries"),
                () -> out.println("Index:       not available"));
        session.notes.forEach(n -> out.println("note: " + n));
        for (Planner.Row r : session.rows) {
            out.printf("%-10s %-24s %-28s %s -> %s%n", r.suspicious() ? "SUSPICIOUS" : r.status(), r.mod().id(),
                    r.jar().path().getFileName(), r.info(), describe(r.action()));
        }
        return 0;
    }

    static String describe(Planner.Action action) {
        return switch (action) {
            case NONE -> "no change";
            case ALLOW -> "allow in ZombieBuddy";
            case DENY -> "block in ZombieBuddy";
            case ASK_IN_GAME -> "ZombieBuddy will ask again";
        };
    }
}
