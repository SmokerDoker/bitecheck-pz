package io.github.bitecheckpz.launcher;

import io.github.bitecheckpz.core.Hashing;
import io.github.bitecheckpz.core.Toml;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Voluntary sharing of which unverified Java mod jars a player uses, so the BiteCheck registry can
 * review the mods people actually play first. Off until the player turns it on.
 *
 * <p>A report holds, per unverified jar: its SHA-256, the Steam Workshop item it came from (if
 * any), the mod id from mod.info and the file size. No paths, no names, no Steam ID. It also holds
 * a random installation id, so the server can count each installation once; the id changes every
 * month, and the server stores only a salted hash of it. At most one report per day.
 *
 * <p>The player's choice and the id live in {@code ~/.bitecheck/usage.toml}; the server is the
 * Jumpstart cloud Worker at {@link #DEFAULT_URL} unless config.toml sets {@code cloud_url}.
 */
public final class UsageSharing {
    /** The Jumpstart cloud Worker (see jumpstart-pz/cloud); empty until it is deployed. */
    public static final String DEFAULT_URL = "";
    static final String FORMAT = "jumpstart-usage 1";
    static final int MAX_JARS = 100;
    private static final Pattern WORKSHOP_ID = Pattern.compile("[/\\\\]content[/\\\\]108600[/\\\\]([0-9]{1,20})[/\\\\]");

    /** One unverified jar as it is reported. */
    public record Jar(String sha256, String workshopId, String modId, long size) {
        public Jar {
            if (!Hashing.isSha256Hex(sha256)) {
                throw new IllegalArgumentException("not a SHA-256: " + sha256);
            }
            workshopId = workshopId == null || workshopId.isBlank() ? null : workshopId;
            modId = modId == null ? "" : modId.replaceAll("[\\t\\r\\n]", " ").strip();
            if (modId.length() > 100) {
                modId = modId.substring(0, 100);
            }
        }

        /** The Workshop item of a jar below steamapps/workshop/content/108600/&lt;id&gt;/, else null. */
        public static String workshopIdOf(Path jar) {
            Matcher m = WORKSHOP_ID.matcher(jar.toAbsolutePath().toString());
            return m.find() ? m.group(1) : null;
        }
    }

    /** Sends a report body; returns the server's one-line answer. */
    @FunctionalInterface
    public interface Sender {
        String send(URI url, String body) throws IOException;
    }

    private final Path file;
    private boolean enabled;
    private String installId;
    private String installMonth;
    private String lastSent;

    private UsageSharing(Path file) {
        this.file = file;
    }

    /** The player's choice and state from {@code <biteCheckDir>/usage.toml}. */
    public static UsageSharing load(Path biteCheckDir) throws IOException {
        UsageSharing sharing = new UsageSharing(biteCheckDir.resolve("usage.toml"));
        if (Files.isRegularFile(sharing.file)) {
            Map<String, Object> doc;
            try {
                doc = Toml.parse(sharing.file);
            } catch (Toml.TomlException e) {
                return sharing; // a broken file means: off, like a fresh start
            }
            sharing.enabled = Toml.bool(doc, "enabled");
            sharing.installId = Toml.string(doc, "install_id");
            sharing.installMonth = Toml.string(doc, "install_month");
            sharing.lastSent = Toml.string(doc, "last_sent");
        }
        return sharing;
    }

    public boolean enabled() {
        return enabled;
    }

    /** The player's choice; saved at once. */
    public void setEnabled(boolean enabled) throws IOException {
        this.enabled = enabled;
        save();
    }

    /** The Jumpstart cloud: config.toml's cloud_url, else {@link #DEFAULT_URL}; empty if neither is set. */
    public static Optional<URI> url(Config config) {
        Optional<URI> configured = config.url("cloud_url");
        if (configured.isPresent()) {
            return configured;
        }
        return DEFAULT_URL.isEmpty() ? Optional.empty() : Optional.of(URI.create(DEFAULT_URL));
    }

    /** Exactly the text that would be sent, for the player to see before agreeing. */
    public static String preview(String client, List<Jar> jars) {
        return body("<random id, new every month>", client, jars);
    }

    /**
     * Sends today's report if sharing is on, there is a server, there are unverified jars and
     * nothing was sent today. Returns what happened, for the log or the screen.
     */
    public String sendIfDue(URI url, String client, List<Jar> jars, LocalDate today, Sender sender) throws IOException {
        if (!enabled) {
            return "sharing is off";
        }
        if (url == null) {
            return "no server set";
        }
        if (jars.isEmpty()) {
            return "nothing to report: no unverified jars";
        }
        String day = today.toString();
        if (day.equals(lastSent)) {
            return "already reported today";
        }
        String month = day.substring(0, 7);
        if (installId == null || !month.equals(installMonth)) {
            byte[] random = new byte[16];
            new SecureRandom().nextBytes(random);
            installId = HexFormat.of().formatHex(random);
            installMonth = month;
        }
        List<Jar> sent = jars.size() > MAX_JARS ? jars.subList(0, MAX_JARS) : jars;
        String answer = sender.send(url, body(installId, client, sent));
        lastSent = day;
        save();
        return answer;
    }

    static String body(String installId, String client, List<Jar> jars) {
        StringBuilder sb = new StringBuilder(FORMAT).append('\n');
        sb.append("install\t").append(installId).append('\n');
        sb.append("client\t").append(client.replaceAll("[\\t\\r\\n]", " ")).append('\n');
        for (Jar jar : jars) {
            sb.append("jar\t").append(jar.sha256()).append('\t')
                    .append(jar.workshopId() == null ? "" : jar.workshopId()).append('\t')
                    .append(jar.modId()).append('\t').append(jar.size()).append('\n');
        }
        return sb.toString();
    }

    /** A {@link Sender} over HTTPS, 15 seconds at most. */
    public static Sender httpSender() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return (url, body) -> {
            HttpRequest request = HttpRequest.newBuilder(url.resolve("/v1/usage"))
                    .timeout(Duration.ofSeconds(15))
                    .header("content-type", "text/plain; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                String answer = response.body().strip();
                if (response.statusCode() != 200) {
                    throw new IOException("server answered " + response.statusCode() + ": " + answer);
                }
                return answer;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        };
    }

    private void save() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# BiteCheck: voluntary sharing of unverified mods (see the README). Safe to delete.");
        lines.add("enabled = " + enabled);
        if (installId != null) {
            lines.add("install_id = " + Toml.quote(installId));
            lines.add("install_month = " + Toml.quote(installMonth));
        }
        if (lastSent != null) {
            lines.add("last_sent = " + Toml.quote(lastSent));
        }
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
