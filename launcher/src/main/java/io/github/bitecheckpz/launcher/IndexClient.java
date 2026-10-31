package io.github.bitecheckpz.launcher;

import io.github.bitecheckpz.core.Index;
import io.github.bitecheckpz.core.Keys;
import io.github.bitecheckpz.core.Signatures;
import io.github.bitecheckpz.core.Toml;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Downloads the signed index, checks it and keeps a verified copy in the cache.
 *
 * <p>An index is only used if its signature is valid for the pinned registry key, it does not come
 * from the future and it is not older than the newest index accepted before (rollback protection).
 * If the download fails, the cached copy is used. A stale index (older than
 * {@link Index#MAX_AGE}) still blocks bad mods but no longer approves anything.
 */
public final class IndexClient {

    public static final String DEFAULT_URL =
            "https://raw.githubusercontent.com/SmokerDoker/bitecheck-pz/index/index.toml";
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final int TIMEOUT_MS = 15_000;

    public record Outcome(Optional<Index> index, boolean fromCache, List<String> notes) {
        public boolean stale(Instant now) {
            return index.isPresent() && index.get().isStale(now);
        }
    }

    private final URI url;
    private final Optional<Keys.PublicKeyInfo> registryKey;
    private final Path cacheDir;
    private final LauncherState state;

    public IndexClient(URI url, Optional<Keys.PublicKeyInfo> registryKey, Path cacheDir, LauncherState state) {
        this.url = url;
        this.registryKey = registryKey;
        this.cacheDir = cacheDir;
        this.state = state;
    }

    /** Built-in registry key, or the one configured for testing. */
    public static Optional<Keys.PublicKeyInfo> registryKey(Config config) throws IOException {
        try {
            Optional<Path> configured = config.path("registry_key");
            if (configured.isPresent()) {
                return Optional.of(Keys.readPublic(configured.get()));
            }
            try (InputStream in = IndexClient.class.getResourceAsStream("registry.pub")) {
                if (in == null) {
                    return Optional.empty();
                }
                Path tmp = Files.createTempFile("bitecheck-registry", ".pub");
                try {
                    Files.write(tmp, in.readAllBytes());
                    return Optional.of(Keys.readPublic(tmp));
                } finally {
                    Files.deleteIfExists(tmp);
                }
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("invalid registry key: " + e.getMessage(), e);
        }
    }

    public Outcome fetch(Instant now) {
        List<String> notes = new ArrayList<>();
        if (registryKey.isEmpty()) {
            notes.add("No registry key is configured, so no mod can be verified.");
            return new Outcome(Optional.empty(), false, notes);
        }
        Path cached = cacheDir.resolve("index.toml");
        try {
            Files.createDirectories(cacheDir);
            Path download = cacheDir.resolve("index.toml.download");
            Path downloadSig = Signatures.signaturePath(download);
            fetchTo(url, download);
            fetchTo(URI.create(url + ".sig"), downloadSig);
            Index fresh = check(download, now);
            Files.move(download, cached, StandardCopyOption.REPLACE_EXISTING);
            Files.move(downloadSig, Signatures.signaturePath(cached), StandardCopyOption.REPLACE_EXISTING);
            state.setNewestIndex(fresh.generatedAt());
            return new Outcome(Optional.of(fresh), false, notes);
        } catch (IOException | Toml.TomlException e) {
            notes.add("Could not update the registry index: " + e.getMessage());
        }
        if (Files.isRegularFile(cached)) {
            try {
                Index index = check(cached, now);
                notes.add("Using the saved index from " + index.generatedAt() + ".");
                return new Outcome(Optional.of(index), true, notes);
            } catch (IOException | Toml.TomlException e) {
                notes.add("The saved index is not usable: " + e.getMessage());
            }
        }
        return new Outcome(Optional.empty(), false, notes);
    }

    private Index check(Path file, Instant now) throws IOException, Toml.TomlException {
        Signatures.Check sig = Signatures.verify(file, registryKey.get());
        if (sig != Signatures.Check.VALID) {
            throw new IOException("index signature: " + sig.description());
        }
        Index index = Index.parse(file);
        if (index.generatedAt().isAfter(now.plus(Index.CLOCK_SKEW))) {
            throw new IOException("index is dated in the future (" + index.generatedAt() + "); check your clock");
        }
        Optional<Instant> newest = state.newestIndex();
        if (newest.isPresent() && index.generatedAt().isBefore(newest.get())) {
            throw new IOException("index is older than one already seen (" + newest.get() + ")");
        }
        return index;
    }

    private static void fetchTo(URI uri, Path target) throws IOException {
        URLConnection connection = uri.toURL().openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", "BiteCheck-Launcher");
        if (connection instanceof HttpURLConnection http) {
            int code = http.getResponseCode();
            if (code != 200) {
                throw new IOException(uri + " answered HTTP " + code);
            }
        }
        try (InputStream in = connection.getInputStream()) {
            byte[] data = in.readNBytes((int) MAX_BYTES + 1);
            if (data.length > MAX_BYTES) {
                throw new IOException(uri + " is too large");
            }
            Files.write(target, data);
        }
    }
}
