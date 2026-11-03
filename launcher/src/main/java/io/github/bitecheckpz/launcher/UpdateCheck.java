package io.github.bitecheckpz.launcher;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tells the player when a newer launcher is released. It only reads the version from where
 * GitHub's "latest release" page redirects to; nothing is downloaded or installed, and the link
 * shown is always the fixed releases page. Turned off with {@code update_check = "off"}.
 */
public final class UpdateCheck {

    public static final String RELEASES_URL = "https://github.com/SmokerDoker/bitecheck-pz/releases/latest";
    private static final int TIMEOUT_MS = 5_000;
    private static final Pattern TAG = Pattern.compile("/releases/tag/v(\\d{1,6}(?:\\.\\d{1,6}){0,3})$");
    private static final Pattern VERSION = Pattern.compile("^(\\d{1,6}(?:\\.\\d{1,6}){0,3})");

    private UpdateCheck() {
    }

    /** A note for the player if a newer release exists; empty if not, if offline or for a dev build. */
    public static Optional<String> note(Config config) {
        String current = UpdateCheck.class.getPackage().getImplementationVersion();
        if (current == null || current.endsWith("-SNAPSHOT") || "off".equals(config.string("update_check").orElse(""))) {
            return Optional.empty();
        }
        try {
            return latest().filter(v -> isNewer(v, current))
                    .map(v -> "BiteCheck " + v + " is available (you have " + current + "): " + RELEASES_URL);
        } catch (IOException | RuntimeException e) {
            return Optional.empty(); // no update hint is no reason to bother the player
        }
    }

    private static Optional<String> latest() throws IOException {
        HttpURLConnection http = (HttpURLConnection) URI.create(RELEASES_URL).toURL().openConnection();
        try {
            http.setInstanceFollowRedirects(false);
            http.setConnectTimeout(TIMEOUT_MS);
            http.setReadTimeout(TIMEOUT_MS);
            http.setRequestProperty("User-Agent", "BiteCheck-Launcher");
            http.getResponseCode();
            return versionFromLocation(http.getHeaderField("Location"));
        } finally {
            http.disconnect();
        }
    }

    /** "…/releases/tag/v0.1.2" gives "0.1.2"; anything else gives nothing. */
    static Optional<String> versionFromLocation(String location) {
        if (location == null) {
            return Optional.empty();
        }
        Matcher m = TAG.matcher(location);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /** Compares the numeric parts; "0.2.0-rc1" counts as 0.2.0. */
    static boolean isNewer(String candidate, String current) {
        int[] a = parts(candidate);
        int[] b = parts(current);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return x > y;
            }
        }
        return false;
    }

    private static int[] parts(String version) {
        Matcher m = VERSION.matcher(version);
        if (!m.find()) {
            return new int[0];
        }
        String[] s = m.group(1).split("\\.");
        int[] parts = new int[s.length];
        for (int i = 0; i < s.length; i++) {
            parts[i] = Integer.parseInt(s[i]);
        }
        return parts;
    }
}
