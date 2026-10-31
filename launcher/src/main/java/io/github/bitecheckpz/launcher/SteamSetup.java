package io.github.bitecheckpz.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

    /** The start script BiteCheck was started from (set by the scripts), or the expected one next to the jar. */
    public static Path script(Platform.Os os) {
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
        String prefix = quote(script.toString()) + " " + WRAP + " ";
        String existing = current.map(String::strip).orElse("");
        if (existing.contains(WRAP)) {
            return existing; // already set up
        }
        if (existing.isEmpty()) {
            return prefix + "%command% " + zombieBuddyOption(os);
        }
        if (existing.contains("%command%")) {
            // e.g. a wrapper the player already uses: put BiteCheck directly in front of the game
            return existing.replaceFirst(Pattern.quote("%command%"), Matcher.quoteReplacement(prefix + "%command%"));
        }
        return prefix + "%command% " + existing;
    }

    /** True if the launch options already start BiteCheck. */
    public static boolean isSetUp(Optional<String> current) {
        return current.map(s -> s.contains(WRAP) && s.contains("%command%")).orElse(false);
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
        StringBuilder s = new StringBuilder();
        s.append("To check your Java mods every time you start Project Zomboid from Steam:\n\n");
        s.append("1. In Steam, right-click Project Zomboid > Properties > General > Launch Options.\n");
        s.append("2. Replace the text there with this line:\n\n");
        s.append("   ").append(launchOptions(os, script(os), first)).append("\n\n");
        s.append("BiteCheck then runs before the game, writes its decisions for ZombieBuddy and starts\n");
        s.append("the game as usual. If BiteCheck itself fails, the game still starts and ZombieBuddy\n");
        s.append("asks about new mods as before.\n");
        if (first.isPresent()) {
            s.append("\nYour current launch options: ").append(first.get()).append('\n');
            if (isSetUp(first)) {
                s.append("BiteCheck is already set up.\n");
            }
        }
        if (os == Platform.Os.WINDOWS) {
            s.append("\nWindows: a console window stays open while the game runs; that is BiteCheck waiting.\n");
        }
        s.append("\nTo undo: put back your old launch options (for ZombieBuddy: ").append(zombieBuddyOption(os))
                .append(").\n");
        return s.toString();
    }
}
