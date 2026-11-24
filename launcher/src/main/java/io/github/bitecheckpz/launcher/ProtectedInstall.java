package io.github.bitecheckpz.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The move on Windows from the installation in the player's folder ({@code %LOCALAPPDATA%\BiteCheck},
 * which every program running as the player can change) to the protected one in
 * {@code %ProgramFiles%\BiteCheck}, which only administrators can change.
 *
 * <ul>
 *   <li>An old copy hands every start over to the protected one, so Steam launch options that
 *       still name the old copy keep working ({@link #forwardTarget}).</li>
 *   <li>The protected copy removes the old one once the Steam launch options no longer name it
 *       ({@link #removeOldCopy}); until then it tells the player to update them.</li>
 * </ul>
 */
final class ProtectedInstall {

    static final String DIR_NAME = "BiteCheck";
    private static final String PRODUCTS = "HKCU\\Software\\Microsoft\\Installer\\Products";

    private ProtectedInstall() {
    }

    /** {@code %ProgramFiles%\BiteCheck}, or empty if not on Windows. */
    static Optional<Path> protectedDir(Map<String, String> env) {
        String pf = env.get("ProgramFiles");
        return pf == null || pf.isBlank() ? Optional.empty() : Optional.of(Path.of(pf, DIR_NAME));
    }

    /** {@code %LOCALAPPDATA%\BiteCheck}, where the earlier installer put BiteCheck. */
    static Optional<Path> oldDir(Map<String, String> env) {
        String local = env.get("LOCALAPPDATA");
        return local == null || local.isBlank() ? Optional.empty() : Optional.of(Path.of(local, DIR_NAME));
    }

    /**
     * The protected launcher to hand this start over to: when this launcher ({@code appPath}) runs
     * from outside the protected folder and the same launcher exists there.
     */
    static Optional<Path> forwardTarget(String appPath, Map<String, String> env) {
        if (appPath == null || appPath.isBlank()) {
            return Optional.empty();
        }
        Optional<Path> dir = protectedDir(env);
        if (dir.isEmpty()) {
            return Optional.empty();
        }
        Path self = Path.of(appPath).toAbsolutePath().normalize();
        Path protectedDir = dir.get().toAbsolutePath().normalize();
        if (self.startsWith(protectedDir) || self.getFileName() == null) {
            return Optional.empty();
        }
        Path target = protectedDir.resolve(self.getFileName());
        return Files.isRegularFile(target) ? Optional.of(target) : Optional.empty();
    }

    /** Runs the protected launcher with the same arguments and returns its exit code. */
    static int forward(Path target, String[] args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(target.toString());
        command.addAll(List.of(args));
        return new ProcessBuilder(command).inheritIO().start().waitFor();
    }

    /** Whether the Steam launch options start the old copy in the player's folder. */
    static boolean steamUsesOldCopy(Optional<String> launchOptions, Map<String, String> env) {
        Optional<Path> old = oldDir(env);
        return launchOptions.isPresent() && old.isPresent()
                && launchOptions.get().toLowerCase(Locale.ROOT).contains(old.get().toString().toLowerCase(Locale.ROOT));
    }

    /**
     * For the protected launcher: removes the old installation in the player's folder (no
     * administrator rights needed) once Steam no longer starts it. Returns a note for the player,
     * or empty if there is nothing to say.
     */
    static Optional<String> removeOldCopy(Platform platform, Map<String, String> env) {
        String app = System.getProperty("jpackage.app-path");
        Optional<Path> protectedDir = protectedDir(env);
        Optional<Path> old = oldDir(env);
        if (platform.os() != Platform.Os.WINDOWS || app == null || protectedDir.isEmpty() || old.isEmpty()
                || !Path.of(app).toAbsolutePath().normalize().startsWith(protectedDir.get().toAbsolutePath().normalize())
                || !Files.isDirectory(old.get())) {
            return Optional.empty();
        }
        Path oldDir = old.get().toAbsolutePath().normalize();
        boolean startedByOldCopy = ProcessHandle.current().parent().flatMap(h -> h.info().command())
                .map(c -> Path.of(c).toAbsolutePath().normalize().startsWith(oldDir)).orElse(false);
        if (startedByOldCopy) {
            return Optional.empty(); // its files are in use now; removed at a later start
        }
        Optional<String> launchOptions = SteamSetup.currentLaunchOptions(platform).stream().findFirst();
        if (steamUsesOldCopy(launchOptions, env)) {
            return Optional.of("Steam still starts the old BiteCheck in " + old.get() + ", which programs running as "
                    + "you can change. It hands over to the protected BiteCheck, but please update your launch "
                    + "options (Steam setup…); BiteCheck then removes the old copy.");
        }
        try {
            Optional<String> productCode = perUserProductCode();
            if (productCode.isEmpty()) {
                return Optional.empty();
            }
            Process p = new ProcessBuilder("msiexec", "/x", productCode.get(), "/qn", "/norestart").start();
            if (p.waitFor(2, TimeUnit.MINUTES) && p.exitValue() == 0) {
                return Optional.of("The old BiteCheck in " + old.get() + " was removed; BiteCheck now runs only from "
                        + protectedDir.get() + ".");
            }
        } catch (IOException e) {
            // try again next time
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Optional.empty();
    }

    /** The product code of a BiteCheck installed for the player only (per-user MSI), from the registry. */
    private static Optional<String> perUserProductCode() throws IOException, InterruptedException {
        Process p = new ProcessBuilder("reg", "query", PRODUCTS, "/s", "/f", "BiteCheck", "/d", "/e")
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(10, TimeUnit.SECONDS)) {
            p.destroy();
            return Optional.empty();
        }
        return productKeyFromQuery(out).map(ProtectedInstall::unpackGuid);
    }

    /** The packed product key of the entry whose ProductName is BiteCheck in "reg query" output. */
    static Optional<String> productKeyFromQuery(String regOutput) {
        String key = null;
        for (String line : regOutput.split("\\R")) {
            String t = line.strip();
            if (t.startsWith("HKEY_")) {
                String last = t.substring(t.lastIndexOf('\\') + 1);
                key = last.matches("[0-9A-Fa-f]{32}") ? last : null;
            } else if (key != null && t.matches("ProductName\\s+REG_SZ\\s+BiteCheck")) {
                return Optional.of(key);
            }
        }
        return Optional.empty();
    }

    /**
     * Windows Installer stores product codes "packed": each part of the GUID reversed, the last
     * two parts byte by byte. 1F0B34C41E4A0563FAE2D6333519447E gives {4C43B0F1-A4E1-3650-AF2E-6D33539144E7}.
     */
    static String unpackGuid(String packed) {
        String p = packed.toUpperCase(Locale.ROOT);
        StringBuilder g = new StringBuilder("{");
        g.append(new StringBuilder(p.substring(0, 8)).reverse()).append('-');
        g.append(new StringBuilder(p.substring(8, 12)).reverse()).append('-');
        g.append(new StringBuilder(p.substring(12, 16)).reverse()).append('-');
        for (int i = 16; i < 32; i += 2) {
            if (i == 20) {
                g.append('-');
            }
            g.append(p.charAt(i + 1)).append(p.charAt(i));
        }
        return g.append('}').toString();
    }
}
