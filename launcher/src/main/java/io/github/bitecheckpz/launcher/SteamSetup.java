package io.github.bitecheckpz.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Steam launch options that run BiteCheck every time the game is started from Steam:
 *
 * <pre>
 * "…/BiteCheck.bat" --wrap %command% -agentlib:zbNative --
 * </pre>
 *
 * <p>Steam replaces {@code %command%} with the game's own command line. BiteCheck writes its
 * decisions for ZombieBuddy, then starts that command unchanged and waits until the game exits.
 * Everything after {@code %command%} still goes to the game, so ZombieBuddy's option keeps working.
 */
public final class SteamSetup {

    public static final String WRAP = "--wrap";

    private SteamSetup() {
    }

    /** The installed program or start script BiteCheck was started from, or the expected script next to the jar. */
    public static Path script(Platform.Os os) {
        // installed app (jpackage launcher: BiteCheck.exe, BiteCheck.app/Contents/MacOS/BiteCheck, /opt/bitecheck/bin/BiteCheck)
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null && !appPath.isBlank()) {
            return Path.of(appPath);
        }
        String fromScript = System.getProperty("bitecheck.script");
        if (fromScript != null && !fromScript.isBlank()) {
            return Path.of(fromScript);
        }
        Path dir = jarDir().orElse(Path.of("").toAbsolutePath());
        return dir.resolve(switch (os) {
            case WINDOWS -> "BiteCheck.bat";
            case MACOS -> "BiteCheck.command";
            case LINUX -> "bitecheck.sh";
        });
    }

    private static Optional<Path> jarDir() {
        try {
            Path jar = Path.of(SteamSetup.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Optional.ofNullable(Files.isRegularFile(jar) ? jar.getParent() : null);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** ZombieBuddy's launch option for this platform. */
    public static String zombieBuddyOption(Platform.Os os) {
        return os == Platform.Os.WINDOWS ? "-agentlib:zbNative --" : "-javaagent:ZombieBuddy.jar --";
    }

    /**
     * The launch options to use, keeping the player's current options.
     *
     * @param current the current launch options for Project Zomboid, if known
     */
    public static String launchOptions(Platform.Os os, Path script, Optional<String> current) {
        return launchOptions(os, script, current, false);
    }

    /**
     * @param jumpstartAgent Jumpstart.jar is in the game folder: it brings ZombieBuddy and sets its
     *                       own agent option (on Windows in ProjectZomboid64.json), so BiteCheck adds
     *                       no ZombieBuddy option; zbNative would not even be there to load
     */
    public static String launchOptions(Platform.Os os, Path script, Optional<String> current, boolean jumpstartAgent) {
        return launchOptions(os, script, current, jumpstartAgent, System.getenv());
    }

    static String launchOptions(Platform.Os os, Path script, Optional<String> current, boolean jumpstartAgent,
            Map<String, String> env) {
        String prefix = quote(script.toString()) + " " + WRAP + " ";
        String existing = current.map(String::strip).orElse("");
        if (hasOwnWrapper(existing)) {
            if (startsOldCopy(current, script, env)) {
                // the old copy in the player's folder: the same line, with the protected BiteCheck
                Matcher m = OWN_WRAPPER.matcher(existing);
                m.find();
                int end = existing.indexOf('"', m.start() + 1) + 1;
                return existing.substring(0, m.start()) + quote(script.toString()) + existing.substring(end);
            }
            return existing; // already set up
        }
        if (existing.isEmpty()) {
            return jumpstartAgent ? prefix + "%command%" : prefix + "%command% " + zombieBuddyOption(os);
        }
        if (existing.contains("%command%")) {
            // e.g. a wrapper the player already uses: put BiteCheck directly in front of the game
            return existing.replaceFirst(Pattern.quote("%command%"), Matcher.quoteReplacement(prefix + "%command%"));
        }
        return prefix + "%command% " + existing;
    }

    /**
     * BiteCheck's own wrapper: a quoted BiteCheck program or script followed by --wrap. Other tools
     * wrap the game the same way (Jumpstart: {@code "…/Jumpstart" --wrap %command%}); their --wrap
     * does not mean BiteCheck is set up, and BiteCheck then goes directly in front of %command%.
     */
    private static final Pattern OWN_WRAPPER = Pattern.compile(
            "\"(?:[^\"]*[/\\\\])?[^\"/\\\\]*[Bb]ite[Cc]heck[^\"/\\\\]*\"\\s+" + Pattern.quote(WRAP) + "(?:\\s|$)");

    static boolean hasOwnWrapper(String options) {
        return OWN_WRAPPER.matcher(options).find();
    }

    /** Jumpstart's wrapper ({@code "…/Jumpstart" --wrap} or {@code … -jar "…/JumpstartSetup.jar" --wrap}). */
    private static final Pattern JUMPSTART_WRAPPER = Pattern.compile(
            "[Jj]umpstart[^\"/\\\\]*\"\\s+" + Pattern.quote(WRAP) + "(?:\\s|$)");

    static boolean hasJumpstart(String options) {
        return JUMPSTART_WRAPPER.matcher(options).find();
    }

    /** Jumpstart's own agent option ({@code -javaagent:Jumpstart.jar...}), which BiteCheck keeps as it is. */
    static boolean hasJumpstartAgent(String options) {
        return options.contains("-javaagent:" + JavaMods.JUMPSTART_JAR);
    }

    /** True if Jumpstart (with ZombieBuddy built in) is the mod loader here. */
    public static boolean jumpstartAgent(Platform platform, Optional<String> current) {
        return JavaMods.hasJumpstart(platform) || current.map(SteamSetup::hasJumpstartAgent).orElse(false);
    }

    /** How to undo BiteCheck's launch options. */
    static String undo(Platform.Os os, boolean jumpstartAgent) {
        return jumpstartAgent
                ? "remove BiteCheck's part, everything up to and including " + WRAP + "; keep %command% and what follows"
                : "for ZombieBuddy: " + zombieBuddyOption(os);
    }

    /** True if the launch options already start BiteCheck. */
    public static boolean isSetUp(Optional<String> current) {
        return current.map(s -> hasOwnWrapper(s) && s.contains("%command%")).orElse(false);
    }

    /** True if the launch options start this BiteCheck ({@code script}), not the old copy it replaced. */
    public static boolean isSetUp(Optional<String> current, Path script) {
        return isSetUp(current) && !startsOldCopy(current, script, System.getenv());
    }

    /**
     * True if this is the protected BiteCheck in Program Files and the launch options still start the
     * old copy in the player's folder. That copy hands over to this one, but it stays installed (and
     * changeable by programs running as the player) until the launch options name this one.
     */
    static boolean startsOldCopy(Optional<String> current, Path script, Map<String, String> env) {
        Optional<Path> protectedDir = ProtectedInstall.protectedDir(env);
        return protectedDir.isPresent()
                && script.toAbsolutePath().normalize().startsWith(protectedDir.get().toAbsolutePath().normalize())
                && ProtectedInstall.steamUsesOldCopy(current, env);
    }

    /** Project Zomboid's launch options from Steam's localconfig.vdf files (one per Steam account). */
    public static List<String> currentLaunchOptions(Platform platform) {
        List<String> result = new ArrayList<>();
        for (Path root : platform.steamRoots()) {
            Path userdata = root.resolve("userdata");
            if (!Files.isDirectory(userdata)) {
                continue;
            }
            try (var users = Files.list(userdata)) {
                for (Path user : users.toList()) {
                    Path vdf = user.resolve("config/localconfig.vdf");
                    if (Files.isRegularFile(vdf)) {
                        launchOptionsFromVdf(Files.readString(vdf, StandardCharsets.UTF_8)).ifPresent(result::add);
                    }
                }
            } catch (IOException | RuntimeException e) {
                // unreadable Steam config: just no current options
            }
        }
        return result;
    }

    /** Extracts the LaunchOptions value of app 108600 from the text of a localconfig.vdf. */
    static Optional<String> launchOptionsFromVdf(String vdf) {
        Matcher app = Pattern.compile("\"" + Platform.PZ_APP_ID + "\"\\s*\\{").matcher(vdf);
        while (app.find()) {
            int depth = 1;
            int i = app.end();
            int start = i;
            boolean inString = false;
            for (; i < vdf.length() && depth > 0; i++) {
                char c = vdf.charAt(i);
                if (inString) {
                    if (c == '\\') {
                        i++;
                    } else if (c == '"') {
                        inString = false;
                    }
                } else if (c == '"') {
                    inString = true;
                } else if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                }
            }
            String block = vdf.substring(start, Math.max(start, i - 1));
            Matcher opt = Pattern.compile("\"LaunchOptions\"\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(block);
            if (opt.find()) {
                return Optional.of(opt.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
            }
        }
        return Optional.empty();
    }

    private static String quote(String s) {
        return "\"" + s + "\"";
    }

    /** Help text shown by --steam-options and the window. */
    public static String help(Platform platform) {
        Platform.Os os = platform.os();
        List<String> current = currentLaunchOptions(platform);
        Optional<String> first = current.stream().findFirst();
        boolean jumpstart = jumpstartAgent(platform, first);
        String loader = jumpstart ? "Jumpstart" : "ZombieBuddy";
        StringBuilder s = new StringBuilder();
        s.append("To check your Java mods every time you start Project Zomboid from Steam:\n\n");
        s.append("1. In Steam, right-click Project Zomboid > Properties > General > Launch Options.\n");
        s.append("2. Replace the text there with this line:\n\n");
        s.append("   ").append(launchOptions(os, script(os), first, jumpstart)).append("\n\n");
        s.append("BiteCheck then runs before the game, writes its decisions for " + loader + " and starts\n");
        s.append("the game as usual. If BiteCheck itself fails, the game still starts and " + loader + "\n");
        s.append("asks about new mods as before.\n");
        if (first.isPresent()) {
            s.append("\nYour current launch options: ").append(first.get()).append('\n');
            if (isSetUp(first, script(os))) {
                s.append("BiteCheck is already set up.\n");
            } else if (startsOldCopy(first, script(os), System.getenv())) {
                s.append("They start the old BiteCheck in your user folder. Replace them with the line above;\n"
                        + "BiteCheck then removes the old copy.\n");
            }
        }
        if (os == Platform.Os.WINDOWS) {
            s.append("\nWindows: a console window stays open while the game runs; that is BiteCheck waiting.\n");
        }
        s.append("\nTo undo: put back your old launch options (").append(undo(os, jumpstart)).append(").\n");
        return s.toString();
    }
}
