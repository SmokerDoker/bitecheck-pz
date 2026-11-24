package io.github.bitecheckpz.launcher;

import java.awt.Color;
import java.awt.Font;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.swing.UIManager;

/**
 * Colors and type for BiteCheck's own windows, light or dark after the system setting.
 *
 * <p>Warm, slightly earthy neutrals (paper and asphalt rather than pure white and black), one quiet
 * olive accent for focus and selection, and three state colors. Plain JDK, no look-and-feel library.
 */
final class Theme {

    final boolean dark;
    final Color window;
    final Color surface;
    final Color raised;
    final Color line;
    final Color text;
    final Color muted;
    final Color accent;
    final Color selection;
    final Color primary;
    final Color primaryHover;
    final Color primaryText;
    final Color ok;
    final Color okTint;
    final Color warn;
    final Color warnTint;
    final Color bad;
    final Color badTint;
    final Font base;

    private static Theme current;

    private Theme(boolean dark) {
        this.dark = dark;
        if (dark) {
            window = new Color(0x1A1917);
            surface = new Color(0x211F1C);
            raised = new Color(0x2A2824);
            line = new Color(0x37342E);
            text = new Color(0xECE9E2);
            muted = new Color(0xA29E94);
            accent = new Color(0xA9B27A);
            selection = new Color(0x2F2D27);
            primary = new Color(0xE6E1D3);
            primaryHover = new Color(0xF4F0E6);
            primaryText = new Color(0x1A1917);
            ok = new Color(0x86C493);
            okTint = new Color(0x1F2A20);
            warn = new Color(0xE0B45E);
            warnTint = new Color(0x2E2717);
            bad = new Color(0xEF8B7F);
            badTint = new Color(0x34201D);
        } else {
            window = new Color(0xF3F1EC);
            surface = new Color(0xFBFAF7);
            raised = new Color(0xEAE7DF);
            line = new Color(0xDEDAD0);
            text = new Color(0x1F1E1B);
            muted = new Color(0x67645B);
            accent = new Color(0x5F6838);
            selection = new Color(0xECEADD);
            primary = new Color(0x2A2924);
            primaryHover = new Color(0x3B3933);
            primaryText = new Color(0xF6F4EF);
            ok = new Color(0x2F6B3A);
            okTint = new Color(0xE5EEE1);
            warn = new Color(0x84590A);
            warnTint = new Color(0xF4EBD7);
            bad = new Color(0xA5342B);
            badTint = new Color(0xF5E2DE);
        }
        Font f = UIManager.getFont("Label.font");
        base = f != null ? f.deriveFont(Math.max(13f, f.getSize2D())) : new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    }

    /** The theme for this session, chosen once from the system setting. */
    static synchronized Theme get() {
        if (current == null) {
            current = new Theme(systemIsDark());
        }
        return current;
    }

    Font size(float factor) {
        return base.deriveFont(base.getSize2D() * factor);
    }

    Font bold(float factor) {
        return base.deriveFont(Font.BOLD, base.getSize2D() * factor);
    }

    /** Asks the operating system for its light/dark setting; light if that does not work. */
    static boolean systemIsDark() {
        String forced = System.getProperty("bitecheck.theme");
        if (forced != null) {
            return forced.equalsIgnoreCase("dark");
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                String out = run("reg", "query",
                        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                        "/v", "AppsUseLightTheme");
                return out.matches("(?s).*AppsUseLightTheme\\s+REG_DWORD\\s+0x0\\b.*");
            }
            if (os.contains("mac")) {
                return run("defaults", "read", "-g", "AppleInterfaceStyle").strip().equalsIgnoreCase("dark");
            }
            String scheme = run("gsettings", "get", "org.gnome.desktop.interface", "color-scheme");
            if (scheme.contains("dark")) {
                return true;
            }
            return run("gsettings", "get", "org.gnome.desktop.interface", "gtk-theme").toLowerCase(Locale.ROOT)
                    .contains("dark");
        } catch (IOException | RuntimeException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String run(String... command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!p.waitFor(2, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return "";
        }
        return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
}
