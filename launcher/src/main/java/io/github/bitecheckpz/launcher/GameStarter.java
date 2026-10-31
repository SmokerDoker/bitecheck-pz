package io.github.bitecheckpz.launcher;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.util.List;

/** Starts Project Zomboid through Steam, so Steam's own launch options (e.g. ZombieBuddy) apply. */
public final class GameStarter {

    public static final String STEAM_URI = "steam://rungameid/" + Platform.PZ_APP_ID;

    private GameStarter() {
    }

    public static void start(Platform.Os os) throws IOException {
        List<String> command = switch (os) {
            case WINDOWS -> List.of("rundll32", "url.dll,FileProtocolHandler", STEAM_URI);
            case MACOS -> List.of("open", STEAM_URI);
            case LINUX -> List.of("xdg-open", STEAM_URI);
        };
        try {
            new ProcessBuilder(command).inheritIO().start();
        } catch (IOException e) {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(STEAM_URI));
            } else {
                throw new IOException("Could not start Steam. Start Project Zomboid from Steam yourself.", e);
            }
        }
    }
}
