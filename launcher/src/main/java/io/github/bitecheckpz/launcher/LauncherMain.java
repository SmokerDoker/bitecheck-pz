package io.github.bitecheckpz.launcher;

import io.github.bitecheckpz.core.Hashing;
import io.github.bitecheckpz.core.Verifier;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BiteCheck launcher entry point.
 *
 * <pre>
 * BiteCheck                          open the window (default)
 * BiteCheck --status                 print the status of all installed Java mods
 * BiteCheck --sync                   apply the decisions without starting the game
 * BiteCheck --launch                 apply the decisions and start Project Zomboid through Steam
 * BiteCheck --wrap &lt;command…&gt;       apply the decisions, then run the command and wait for it
 *                                    (Steam launch options: "…/BiteCheck.bat" --wrap %command% …)
 * BiteCheck --steam-options          show the Steam launch options that run BiteCheck automatically
 * BiteCheck --allow &lt;sha256&gt;        allow an unverified mod ahead of time (e.g. on a server)
 * BiteCheck --allow &lt;sha256&gt; --i-understand-the-risk   allow a flagged or suspicious mod
 * BiteCheck --revoke &lt;sha256&gt;       take back an --allow
 * BiteCheck --export &lt;file&gt;         write the installed Java mods with their hashes
 * BiteCheck --compare &lt;file&gt;        compare the installed Java mods with an exported list
 *
 * Options (before the command):
 *   --dir &lt;folder&gt;          BiteCheck folder instead of ~/.bitecheck
 *   --server-dir &lt;folder&gt;   dedicated server install (SteamCMD); its workshop folder is scanned
 *   --workshop-dir &lt;folder&gt; additional Steam Workshop content folder (…/content/108600)
 *   --zomboid-dir &lt;folder&gt;  instead of ~/Zomboid
 * </pre>
 */
public final class LauncherMain {

    /** Exit code of --compare when the mods differ. */
    public static final int MODS_DIFFER = 3;

    private static final Map<String, String> PATH_OPTIONS = Map.of(
            "--server-dir", "server_dir",
            "--workshop-dir", "workshop_dir",
            "--zomboid-dir", "zomboid_dir",
            "--zombiebuddy-dir", "zombiebuddy_dir");

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
        // Options are only read before --wrap; everything after it belongs to the game.
        List<String> all = List.of(args);
        int wrapIdx = all.indexOf(SteamSetup.WRAP);
        List<String> rest = new ArrayList<>(wrapIdx >= 0 ? all.subList(0, wrapIdx) : all);
        List<String> wrapped = wrapIdx >= 0 ? all.subList(wrapIdx + 1, all.size()) : List.of();

        Path dir = Path.of(System.getProperty("user.home"), ".bitecheck");
        Map<String, String> overrides = new LinkedHashMap<>();
        for (int i = 0; i < rest.size(); i++) {
            String opt = rest.get(i);
            if (opt.equals("--dir") || PATH_OPTIONS.containsKey(opt)) {
                if (i + 1 >= rest.size()) {
                    err.println(opt + " needs a folder");
                    return 2;
                }
                String value = rest.get(i + 1);
                if (opt.equals("--dir")) {
                    dir = Path.of(value);
                } else {
                    overrides.put(PATH_OPTIONS.get(opt), value);
                }
                rest.remove(i + 1);
                rest.remove(i);
                i--;
            }
        }
        if (wrapIdx >= 0) {
            if (!rest.isEmpty()) {
                err.println("Unknown option " + rest.get(0) + " before --wrap. Try --help.");
                return 2;
            }
            return wrap(dir, overrides, wrapped, err);
        }
        String command = rest.isEmpty() ? "--window" : rest.get(0);
        try {
            switch (command) {
                case "--help", "-h" -> {
                    out.println("Usage: BiteCheck [options] [--status | --sync | --launch | --steam-options");
                    out.println("                 | --allow <sha256> [--i-understand-the-risk] | --revoke <sha256>");
                    out.println("                 | --export <file> | --compare <file> | --wrap <game command...>]");
                    out.println("Options: --dir <folder>  --server-dir <folder>  --workshop-dir <folder>"
                            + "  --zomboid-dir <folder>");
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
                        return printStatus(Session.run(dir, overrides), out);
                    }
                    LauncherWindow.open(dir);
                    return -1;
                }
                case "--status" -> {
                    return printStatus(Session.run(dir, overrides), out);
                }
                case "--sync", "--launch" -> {
                    Session session = Session.run(dir, overrides);
                    printStatus(session, out);
                    int changes = session.apply();
                    out.println(changes + " change(s) written to " + session.approvals.file());
                    if (command.equals("--launch")) {
                        GameStarter.start(session.platform.os());
                        out.println("Starting Project Zomboid through Steam…");
                    }
                    return 0;
                }
                case "--steam-options" -> {
                    Platform platform = new Platform(Config.load(dir.resolve("config.toml")).with(overrides));
                    out.print(SteamSetup.help(platform));
                    return 0;
                }
                case "--allow", "--allow-flagged" -> {
                    return allow(dir, overrides, rest, out, err);
                }
                case "--revoke" -> {
                    return revoke(dir, overrides, rest, out, err);
                }
                case "--export" -> {
                    if (rest.size() < 2) {
                        err.println("Usage: BiteCheck --export <file>");
                        return 2;
                    }
                    Session session = Session.run(dir, overrides);
                    List<ModList.Item> items = ModList.of(session.rows);
                    Path file = Path.of(rest.get(1));
                    ModList.write(file, items, session.now);
                    out.println(items.size() + " jar(s) of " + session.mods.size() + " Java mod(s) written to " + file);
                    return 0;
                }
                case "--compare" -> {
                    if (rest.size() < 2) {
                        err.println("Usage: BiteCheck --compare <file>");
                        return 2;
                    }
                    List<ModList.Item> reference = ModList.read(Path.of(rest.get(1)));
                    Session session = Session.run(dir, overrides);
                    return printDiff(ModList.compare(ModList.of(session.rows), reference), out);
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

    /**
     * Applies the decisions, then runs the game command and returns its exit code. BiteCheck
     * problems never keep the game from starting: decisions written earlier stay in place and
     * ZombieBuddy asks about anything new, as without BiteCheck.
     */
    static int wrap(Path dir, Map<String, String> overrides, List<String> command, PrintStream err) {
        if (command.isEmpty()) {
            err.println("Usage: BiteCheck --wrap <game command...>   (Steam: --wrap %command%)");
            return 2;
        }
        try {
            Session session = Session.run(dir, overrides);
            int changes = session.apply();
            long blocked = session.rows.stream()
                    .filter(r -> Boolean.FALSE.equals(session.approvals.decision(r.jar().sha256()))).count();
            err.println("BiteCheck: " + session.rows.size() + " Java mod jar(s) checked, " + changes + " change(s), "
                    + blocked + " blocked.");
            session.notes.forEach(n -> err.println("BiteCheck note: " + n));
        } catch (IOException | RuntimeException e) {
            err.println("BiteCheck: check failed (" + e.getMessage() + "); starting the game anyway.");
        }
        try {
            Process game = new ProcessBuilder(command).inheritIO().start();
            return game.waitFor();
        } catch (IOException e) {
            err.println("BiteCheck: could not start " + command.get(0) + ": " + e.getMessage());
            return 1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 1;
        }
    }

    private static int allow(Path dir, Map<String, String> overrides, List<String> rest, PrintStream out,
            PrintStream err) throws IOException {
        if (rest.size() < 2 || !Hashing.isSha256Hex(rest.get(1))) {
            err.println("Usage: BiteCheck --allow <sha256> [--i-understand-the-risk]");
            return 2;
        }
        String sha = rest.get(1);
        boolean risk = rest.contains("--i-understand-the-risk");
        Session session = Session.run(dir, overrides);
        Planner.Row row = session.rows.stream().filter(r -> r.jar().sha256().equals(sha)).findFirst().orElse(null);
        if (row == null) {
            err.println("No installed Java mod has this hash. Run --status to see the hashes.");
            return 1;
        }
        switch (row.status()) {
            case VERIFIED -> {
                out.println("This mod is verified; nothing to allow.");
                return 0;
            }
            case BLOCKED, INVALID -> {
                err.println("This mod is " + row.status().name().toLowerCase() + " and cannot be allowed.");
                return 1;
            }
            default -> {
            }
        }
        if (row.needsPermission() && !risk) {
            err.println("This mod is " + (row.suspicious() ? "suspicious (" + row.info() + ")" : "flagged")
                    + ". To load it anyway: BiteCheck --allow " + sha + " --i-understand-the-risk");
            return 2;
        }
        session.state.allowedAnyway().put(sha, row.needsPermission() ? "allowed by the player" : "approved ahead of time");
        session.replan().apply();
        out.println("Allowed " + row.mod().id() + " " + row.jar().path().getFileName() + ". ZombieBuddy will load it.");
        return 0;
    }

    private static int revoke(Path dir, Map<String, String> overrides, List<String> rest, PrintStream out,
            PrintStream err) throws IOException {
        if (rest.size() < 2 || !Hashing.isSha256Hex(rest.get(1))) {
            err.println("Usage: BiteCheck --revoke <sha256>");
            return 2;
        }
        String sha = rest.get(1);
        Session session = Session.run(dir, overrides);
        if (session.state.allowedAnyway().remove(sha) == null) {
            err.println("This hash was not allowed with --allow.");
            return 1;
        }
        Session replanned = session.replan();
        boolean needsPermission = replanned.rows.stream()
                .anyMatch(r -> r.jar().sha256().equals(sha) && r.needsPermission());
        if (!needsPermission && replanned.approvals.decision(sha) != null
                && replanned.rows.stream().anyMatch(r -> r.jar().sha256().equals(sha)
                        && r.status() == Verifier.Status.UNVERIFIED)) {
            replanned.approvals.remove(sha); // ZombieBuddy asks again
        }
        replanned.apply();
        out.println("Taken back. " + (needsPermission ? "BiteCheck blocks this mod again."
                : "ZombieBuddy will ask about this mod again."));
        return 0;
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
            out.println("           sha256 " + r.jar().sha256() + (r.allowedAnyway() ? "  (allowed with --allow)" : ""));
        }
        return 0;
    }

    static int printDiff(ModList.Diff diff, PrintStream out) {
        for (ModList.Item i : diff.missing()) {
            out.println("MISSING    " + i.label() + (i.workshopId().isEmpty() ? "" : "  (workshop " + i.workshopId() + ")")
                    + "  " + i.sha256());
        }
        for (ModList.Item[] pair : diff.different()) {
            out.println("DIFFERENT  " + pair[0].label() + "  list " + pair[0].sha256() + "  yours " + pair[1].sha256());
        }
        for (ModList.Item i : diff.extra()) {
            out.println("EXTRA      " + i.label() + "  " + i.sha256() + "  (not in the list)");
        }
        if (diff.matches()) {
            out.println("OK: every Java mod jar in the list is installed with the same hash.");
            return 0;
        }
        out.println(diff.missing().size() + " missing, " + diff.different().size() + " different. "
                + "Update or subscribe to these mods so they match the list.");
        return MODS_DIFFER;
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
