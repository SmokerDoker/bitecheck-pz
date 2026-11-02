package io.github.smokerdoker.bitecheck;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/**
 * Downloads a file once, checks its SHA-256 and keeps it in Gradle's user home, so a clean build
 * does not download it again. A file with another hash is never used.
 */
@DisableCachingByDefault(because = "the file is already cached by its SHA-256 in the Gradle user home")
public abstract class DownloadVerifiedFile extends DefaultTask {

    @Input
    public abstract Property<String> getUrl();

    @Input
    public abstract Property<String> getSha256();

    /** Shared cache, e.g. ~/.gradle/caches/bitecheck. */
    @Internal
    public abstract DirectoryProperty getCacheDir();

    /** True when Gradle runs with --offline: then only the cache is used. */
    @Internal
    public abstract Property<Boolean> getOffline();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void download() throws IOException, InterruptedException {
        String expected = getSha256().get().trim().toLowerCase(Locale.ROOT);
        if (!expected.matches("[0-9a-f]{64}")) {
            throw new GradleException("SHA-256 for " + getUrl().get() + " must be 64 hex digits, got '" + expected + "'");
        }
        Path cached = getCacheDir().get().getAsFile().toPath().resolve("sha256").resolve(expected);
        if (!Files.isRegularFile(cached) || !expected.equals(sha256(cached))) {
            Files.createDirectories(cached.getParent());
            Path tmp = Files.createTempFile(cached.getParent(), "download-", ".tmp");
            try {
                fetch(getUrl().get(), tmp);
                String actual = sha256(tmp);
                if (!expected.equals(actual)) {
                    throw new GradleException("SHA-256 mismatch for " + getUrl().get() + ": expected " + expected
                            + ", got " + actual + ". The file was not used.");
                }
                Files.move(tmp, cached, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        }
        Path out = getOutputFile().get().getAsFile().toPath();
        Files.createDirectories(out.getParent());
        Files.copy(cached, out, StandardCopyOption.REPLACE_EXISTING);
    }

    private void fetch(String url, Path target) throws IOException, InterruptedException {
        if (getOffline().getOrElse(false)) {
            throw new GradleException("Gradle runs offline and " + url + " is not in the cache yet");
        }
        getLogger().lifecycle("Downloading {}", url);
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).GET().build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            if (response.statusCode() != 200) {
                throw new GradleException("Download of " + url + " failed: HTTP " + response.statusCode());
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                for (int n; (n = in.read(buffer)) > 0;) {
                    digest.update(buffer, 0, n);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
