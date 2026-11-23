package io.github.bitecheckpz.launcher;

import io.github.bitecheckpz.core.Hashing;
import io.github.bitecheckpz.core.Keys;
import io.github.bitecheckpz.core.Signatures;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Installs a newer BiteCheck release on its own, without asking the player.
 *
 * <p>The window updates right after its check and starts again; the Steam start ({@code --wrap})
 * downloads while the game runs and installs after it exits. Each version is tried on its own at
 * most once a day; {@code auto_update = "off"} leaves only the update button.
 *
 * <p>Only installed launchers (MSI, DMG, DEB/RPM) update themselves; the portable zip keeps the
 * note with the link. The installer is downloaded from the GitHub release together with
 * {@code SHA256SUMS.txt} and its signature. Nothing runs unless the signature was made with the
 * release key built into this launcher and the installer's SHA-256 is the one listed for its
 * exact file name (which contains the version, so an older signed release cannot be passed off
 * as the new one).
 *
 * <ul>
 *   <li>Windows: {@code msiexec /quiet} (per-user install, no admin prompt) once BiteCheck has exited.</li>
 *   <li>macOS: the app bundle is replaced from the DMG once BiteCheck has exited.</li>
 *   <li>Linux: packages need root, so BiteCheck only downloads and checks the package and shows the
 *       command to install it.</li>
 * </ul>
 */
public final class SelfUpdate {

    static final String DOWNLOAD_BASE = "https://github.com/SmokerDoker/bitecheck-pz/releases/download/";
    static final String SUMS = "SHA256SUMS.txt";
    private static final int TIMEOUT_MS = 15_000;
    private static final long MAX_BYTES = 400L * 1024 * 1024;

    private SelfUpdate() {
    }

    /** What the player can install: the version, the installer's file name and where it goes. */
    public record Offer(String version, String asset, Platform.Os os) {
    }

    /** The result of an update: either it was started (BiteCheck must exit now) or a message to show. */
    public record Result(boolean exitNow, String message) {
    }

    /** An installer for this computer, if BiteCheck was installed with one and a release key is built in. */
    public static Optional<Offer> offer(String version, Config config) {
        try {
            if (System.getProperty("jpackage.app-path") == null || IndexClient.releaseKey(config).isEmpty()) {
                return Optional.empty();
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        Platform.Os os = Platform.detectOs();
        return assetName(os, System.getProperty("os.arch", ""), version, linuxPackageType())
                .map(asset -> new Offer(version, asset, os));
    }

    /** The release file for this system, e.g. BiteCheck-0.1.5-windows-x64.msi. */
    static Optional<String> assetName(Platform.Os os, String arch, String version, String linuxType) {
        String a = arch.toLowerCase(Locale.ROOT);
        boolean arm = a.equals("aarch64") || a.equals("arm64");
        boolean x64 = a.equals("amd64") || a.equals("x86_64") || a.equals("x64");
        String prefix = "BiteCheck-" + version + "-";
        return switch (os) {
            case WINDOWS -> x64 ? Optional.of(prefix + "windows-x64.msi") : Optional.empty();
            case MACOS -> arm ? Optional.of(prefix + "macos-arm64.dmg")
                    : x64 ? Optional.of(prefix + "macos-x64.dmg") : Optional.empty();
            case LINUX -> x64 && linuxType != null ? Optional.of(prefix + "linux-x64." + linuxType) : Optional.empty();
        };
    }

    private static String linuxPackageType() {
        if (Files.exists(Path.of("/usr/bin/dpkg"))) {
            return "deb";
        }
        return Files.exists(Path.of("/usr/bin/rpm")) ? "rpm" : null;
    }

    /** Downloads the installer and the signed checksums into {@code dir} and checks them. */
    public static Path download(Offer offer, Path dir, Keys.PublicKeyInfo releaseKey) throws IOException {
        Files.createDirectories(dir);
        String base = DOWNLOAD_BASE + "v" + offer.version() + "/";
        Path sums = dir.resolve(SUMS);
        fetch(URI.create(base + SUMS), sums);
        fetch(URI.create(base + SUMS + ".sig"), Signatures.signaturePath(sums));
        Path installer = dir.resolve(offer.asset());
        fetch(URI.create(base + offer.asset()), installer);
        try {
            check(sums, installer, releaseKey);
        } catch (IOException e) {
            Files.deleteIfExists(installer);
            throw e;
        }
        return installer;
    }

    /** Throws unless the checksums are signed with the release key and list the installer's hash. */
    static void check(Path sums, Path installer, Keys.PublicKeyInfo releaseKey) throws IOException {
        Signatures.Check sig = Signatures.verify(sums, releaseKey);
        if (sig != Signatures.Check.VALID) {
            throw new IOException("The release checksums are not signed by the BiteCheck release key ("
                    + sig.description() + "). Nothing was installed.");
        }
        String name = installer.getFileName().toString();
        String expected = expectedHash(Files.readAllLines(sums, StandardCharsets.UTF_8), name)
                .orElseThrow(() -> new IOException(name + " is not listed in the signed checksums. Nothing was installed."));
        String actual = Hashing.sha256Hex(installer);
        if (!expected.equals(actual)) {
            throw new IOException("The download of " + name + " does not match the signed checksum. Nothing was installed.");
        }
    }

    /** The hash listed for exactly this file name ("{hash}  {name}" lines as written by sha256sum). */
    static Optional<String> expectedHash(List<String> lines, String name) {
        for (String line : lines) {
            String[] parts = line.strip().split("\\s+", 2);
            String hash = parts[0].toLowerCase(Locale.ROOT);
            if (parts.length == 2 && Hashing.isSha256Hex(hash)) {
                String file = parts[1].startsWith("*") ? parts[1].substring(1) : parts[1];
                if (file.equals(name)) {
                    return Optional.of(hash);
                }
            }
        }
        return Optional.empty();
    }

    /** Starts the installation, or on Linux returns the command for the player. */
    public static Result install(Offer offer, Path installer, boolean restart) throws IOException {
        String app = System.getProperty("jpackage.app-path");
        String pid = Long.toString(ProcessHandle.current().pid());
        Path dir = installer.getParent();
        switch (offer.os()) {
            case WINDOWS -> {
                // The values are written into the script: "cmd /c" mangles several quoted arguments.
                Path script = dir.resolve("install-update.cmd");
                Files.writeString(script, windowsScript(pid, installer.toString(), app, restart), StandardCharsets.UTF_8);
                new ProcessBuilder("cmd", "/c", "start", "BiteCheck update", "/min", script.toString()).start();
                return new Result(true, "");
            }
            case MACOS -> {
                Path bundle = macBundle(app).orElseThrow(() -> new IOException("BiteCheck.app was not found."));
                Path script = dir.resolve("install-update.sh");
                Files.writeString(script, String.join("\n",
                        "#!/bin/sh",
                        "# Waits for BiteCheck to exit, replaces the app from the DMG and opens it again if asked ($4).",
                        "while kill -0 \"$1\" 2>/dev/null; do sleep 0.3; done",
                        "mnt=$(mktemp -d) || exit 1",
                        "hdiutil attach -nobrowse -readonly -mountpoint \"$mnt\" \"$2\" >/dev/null || exit 1",
                        "if [ -d \"$mnt/BiteCheck.app\" ]; then",
                        "  rm -rf \"$3.old\"",
                        "  if mv \"$3\" \"$3.old\"; then",
                        "    if ditto \"$mnt/BiteCheck.app\" \"$3\"; then rm -rf \"$3.old\"; else rm -rf \"$3\"; mv \"$3.old\" \"$3\"; fi",
                        "  fi",
                        "fi",
                        "hdiutil detach \"$mnt\" -quiet",
                        "[ \"$4\" = 1 ] && open \"$3\"",
                        "exit 0",
                        ""), StandardCharsets.UTF_8);
                new ProcessBuilder("/bin/sh", script.toString(), pid, installer.toString(), bundle.toString(),
                        restart ? "1" : "0").start();
                return new Result(true, "");
            }
            default -> {
                String tool = installer.toString().endsWith(".rpm") ? "sudo dnf install" : "sudo apt install";
                return new Result(false, "BiteCheck " + offer.version() + " was downloaded and its signature checked:\n"
                        + installer + "\n\nInstall it in a terminal with:\n" + tool + " '" + installer + "'");
            }
        }
    }

    /** Waits for BiteCheck (pid) to exit, installs the MSI silently and, if asked, starts BiteCheck again. */
    static String windowsScript(String pid, String msi, String app, boolean restart) {
        return String.join("\r\n",
                "@echo off",
                "rem Waits for BiteCheck to exit, installs the update and starts BiteCheck again if asked.",
                ":wait",
                "tasklist /FI \"PID eq " + pid + "\" 2>NUL | find \"" + pid + "\" >NUL && (ping -n 2 127.0.0.1 >NUL & goto wait)",
                "msiexec /i \"" + cmdEscape(msi) + "\" /quiet /norestart",
                restart ? "start \"\" \"" + cmdEscape(app) + "\"" : "rem no restart",
                "");
    }

    /**
     * Whether BiteCheck may install this version on its own now: not turned off with
     * {@code auto_update = "off"}, and not already tried in the last day (a failing install must
     * not restart BiteCheck over and over).
     */
    static boolean autoAllowed(Config config, Path updatesDir, String version, Instant now) {
        if ("off".equals(config.string("auto_update").orElse(""))) {
            return false;
        }
        Path marker = updatesDir.resolve("attempt-" + version);
        try {
            return !Files.exists(marker)
                    || Files.getLastModifiedTime(marker).toInstant().isBefore(now.minus(Duration.ofDays(1)));
        } catch (IOException e) {
            return false;
        }
    }

    /** Downloads, checks and installs without asking; remembers the attempt (see {@link #autoAllowed}). */
    public static Result auto(Offer offer, Path updatesDir, Config config, boolean restart) throws IOException {
        return install(offer, prepare(offer, updatesDir, config), restart);
    }

    /** Downloads and checks the installer; remembers the attempt (see {@link #autoAllowed}). */
    public static Path prepare(Offer offer, Path updatesDir, Config config) throws IOException {
        Keys.PublicKeyInfo key = IndexClient.releaseKey(config).orElseThrow(() -> new IOException("No release key is built in."));
        Files.createDirectories(updatesDir);
        Files.writeString(updatesDir.resolve("attempt-" + offer.version()), Instant.now().toString());
        return download(offer, updatesDir, key);
    }

    /** The offer, if BiteCheck should install it on its own now. */
    public static Optional<Offer> autoOffer(String version, Config config, Path updatesDir) {
        return offer(version, config).filter(o -> autoAllowed(config, updatesDir, version, Instant.now()));
    }

    /** Windows paths cannot contain quotes; % is the only character a .cmd file would expand. */
    private static String cmdEscape(String path) {
        return path.replace("%", "%%");
    }

    /** ".../BiteCheck.app/Contents/MacOS/BiteCheck" gives ".../BiteCheck.app". */
    static Optional<Path> macBundle(String appPath) {
        if (appPath == null) {
            return Optional.empty();
        }
        Path p = Path.of(appPath).toAbsolutePath();
        for (int i = 0; i < 3 && p != null; i++) {
            p = p.getParent();
        }
        return p != null && p.getFileName() != null && p.getFileName().toString().endsWith(".app")
                ? Optional.of(p) : Optional.empty();
    }

    private static void fetch(URI uri, Path target) throws IOException {
        HttpURLConnection http = (HttpURLConnection) uri.toURL().openConnection();
        try {
            http.setConnectTimeout(TIMEOUT_MS);
            http.setReadTimeout(TIMEOUT_MS);
            http.setRequestProperty("User-Agent", "BiteCheck-Launcher");
            int code = http.getResponseCode();
            if (code != 200) {
                throw new IOException("Download failed (HTTP " + code + "): " + uri);
            }
            Path part = target.resolveSibling(target.getFileName() + ".part");
            try (InputStream in = http.getInputStream(); OutputStream out = Files.newOutputStream(part)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                for (int n; (n = in.read(buffer)) > 0; ) {
                    total += n;
                    if (total > MAX_BYTES) {
                        throw new IOException("Download is larger than expected: " + uri);
                    }
                    out.write(buffer, 0, n);
                }
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            http.disconnect();
        }
    }
}
