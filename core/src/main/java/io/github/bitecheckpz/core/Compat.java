package io.github.bitecheckpz.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which versions of Project Zomboid and ZombieBuddy a release works with, from its release file:
 *
 * <pre>
 * [compat]
 * project_zomboid = "&gt;=42.13, &lt;43"
 * zombiebuddy = "&gt;=2.3"
 * </pre>
 *
 * A range is a comma-separated list of conditions ({@code >=}, {@code >}, {@code <=}, {@code <},
 * {@code =}) on dotted versions; all must hold. Versions compare part by part as numbers, and text
 * after the numbers ({@code 3.0.0-beta3}) is ignored. This is about whether a mod works, not about
 * safety: a mismatch is a warning, never a block.
 */
public final class Compat {
    /** The fields of {@code [compat]} and what they name. */
    public static final Map<String, String> FIELDS = Map.of("game", "Project Zomboid", "zombiebuddy", "ZombieBuddy");

    /**
     * The field names a release file may use in {@code [compat]}: {@code project_zomboid} (the
     * release format's name) or {@code game} (the same), and {@code zombiebuddy}.
     */
    public static final java.util.Set<String> RELEASE_FIELDS = java.util.Set.of("project_zomboid", "game", "zombiebuddy");

    private static final Pattern CONDITION = Pattern.compile("\\s*(>=|<=|>|<|=)?\\s*(\\d+(?:\\.\\d+){0,3})\\s*");

    private Compat() {
    }

    /** True if {@code range} is a valid range. */
    public static boolean isValid(String range) {
        if (range == null || range.isBlank()) {
            return false;
        }
        for (String part : range.split(",")) {
            if (!CONDITION.matcher(part).matches()) {
                return false;
            }
        }
        return true;
    }

    /** True if {@code version} (e.g. "42.22.2", "3.0.0-beta3") lies in {@code range}; true for an invalid range. */
    public static boolean satisfies(String version, String range) {
        if (!isValid(range)) {
            return true;
        }
        List<Integer> v = numbers(version);
        if (v.isEmpty()) {
            return true; // unknown installed version: nothing to say
        }
        for (String part : range.split(",")) {
            Matcher m = CONDITION.matcher(part);
            if (!m.matches()) {
                return true;
            }
            int c = compare(v, numbers(m.group(2)));
            String op = m.group(1) == null ? "=" : m.group(1);
            boolean ok = switch (op) {
                case ">=" -> c >= 0;
                case ">" -> c > 0;
                case "<=" -> c <= 0;
                case "<" -> c < 0;
                default -> c == 0;
            };
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** The leading numbers of a version: "42.22.2 3d39148846" -> [42, 22, 2]; "3.0.0-beta3" -> [3, 0, 0]. */
    static List<Integer> numbers(String version) {
        List<Integer> out = new ArrayList<>();
        if (version == null) {
            return out;
        }
        Matcher m = Pattern.compile("^\\s*(\\d+(?:\\.\\d+)*)").matcher(version);
        if (m.find()) {
            for (String n : m.group(1).split("\\.")) {
                try {
                    out.add(Integer.parseInt(n));
                } catch (NumberFormatException e) {
                    out.add(Integer.MAX_VALUE);
                }
            }
        }
        return out;
    }

    /** Compares part by part; a missing part is 0 (42.13 = 42.13.0). */
    static int compare(List<Integer> a, List<Integer> b) {
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            int x = i < a.size() ? a.get(i) : 0;
            int y = i < b.size() ? b.get(i) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    /** "game >=42.13; zombiebuddy >=2.3" from a release's {@code [compat]} table, or null if it has none. */
    public static String describe(Map<String, Object> release) {
        Map<String, Object> compat = Toml.table(release, "compat");
        List<String> parts = new ArrayList<>();
        for (String field : List.of("game", "zombiebuddy")) {
            String range = Toml.string(compat, field);
            if (range == null && field.equals("game")) {
                range = Toml.string(compat, "project_zomboid"); // the field's name in release files
            }
            if (isValid(range)) {
                parts.add(field + " " + range.replace(" ", ""));
            }
        }
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    /**
     * What does not match: for {@code "game >=42.13; zombiebuddy >=2.3"} and the installed versions
     * (null if unknown), e.g. {@code ["Project Zomboid >=42.13 (installed 42.12.1)"]}.
     */
    public static List<String> mismatches(String described, String gameVersion, String zombieBuddyVersion) {
        List<String> out = new ArrayList<>();
        if (described == null) {
            return out;
        }
        for (String part : described.split(";")) {
            String[] fr = part.strip().split(" ", 2);
            if (fr.length != 2 || !FIELDS.containsKey(fr[0])) {
                continue;
            }
            String installed = fr[0].equals("game") ? gameVersion : zombieBuddyVersion;
            if (installed != null && !satisfies(installed, fr[1])) {
                out.add(FIELDS.get(fr[0]) + " " + fr[1] + " (installed " + installed.strip().split("\\s+")[0] + ")");
            }
        }
        return out;
    }
}
