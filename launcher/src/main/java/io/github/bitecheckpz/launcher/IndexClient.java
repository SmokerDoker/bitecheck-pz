package io.github.bitecheckpz.launcher;

import io.github.bitecheckpz.core.Hashing;
import io.github.bitecheckpz.core.Index;
import io.github.bitecheckpz.core.Keys;
import io.github.bitecheckpz.core.Signatures;
import io.github.bitecheckpz.core.Timestamp;
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
 *
 * <p>Next to the index, {@code timestamp.toml} (signed with the pinned timestamp key) may confirm
 * that the index with exactly this hash is still current. It is optional: a missing or invalid
 * timestamp only means the index counts from its own generated_at.
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
    private final Optional<Keys.PublicKeyInfo> timestampKey;
    private final Path cacheDir;
    private final LauncherState state;

    public IndexClient(URI url, Optional<Keys.PublicKeyInfo> registryKey, Optional<Keys.PublicKeyInfo> timestampKey,
            Path cacheDir, LauncherState state) {
        this.url = url;
        this.registryKey = registryKey;
        this.timestampKey = timestampKey;
        this.cacheDir = cacheDir;
        this.state = state;
    }

    /** Built-in registry key, or the one configured for testing. */
    public static Optional<Keys.PublicKeyInfo> registryKey(Config config) throws IOException {
        return pinnedKey(config, "registry_key", "registry.pub");
    }

    /** Built-in timestamp key, or the one configured for testing. */
    public static Optional<Keys.PublicKeyInfo> timestampKey(Config config) throws IOException {
        return pinnedKey(config, "timestamp_key", "timestamp.pub");
    }

    private static Optional<Keys.PublicKeyInfo> pinnedKey(Config config, String setting, String resource)
            throws IOException {
        try {
            Optional<Path> configured = config.path(setting);
            if (configured.isPresent()) {
                return Optional.of(Keys.readPublic(configured.get()));
            }
            try (InputStream in = IndexClient.class.getResourceAsStream(resource)) {
                if (in == null) {
                    return Optional.empty();
                }
                Path tmp = Files.createTempFile("bitecheck-key", ".pub");
                try {
                    Files.write(tmp, in.readAllBytes());
                    return Optional.of(Keys.readPublic(tmp));
                } finally {
                    Files.deleteIfExists(tmp);
                }
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("invalid " + resource + ": " + e.getMessage(), e);
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
            fetchTimestamp();
            return new Outcome(Optional.of(confirm(fresh, cached, now, notes)), false, notes);
        } catch (IOException | Toml.TomlException e) {
            notes.add("Could not update the registry index: " + e.getMessage());
        }
        if (Files.isRegularFile(cached)) {
            try {
                Index index = confirm(check(cached, now), cached, now, notes);
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

    /** Downloads the timestamp next to the cached index. Failures are fine: the saved one stays. */
    private void fetchTimestamp() {
        if (timestampKey.isEmpty()) {
            return;
        }
        Path download = cacheDir.resolve("timestamp.toml.download");
        try {
            fetchTo(url.resolve("timestamp.toml"), download);
            fetchTo(url.resolve("timestamp.toml.sig"), Signatures.signaturePath(download));
            Path target = cacheDir.resolve("timestamp.toml");
            Files.move(download, target, StandardCopyOption.REPLACE_EXISTING);
            Files.move(Signatures.signaturePath(download), Signatures.signaturePath(target),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // none published yet, or offline
        }
    }

    /** The index, confirmed by the cached timestamp if that one is valid and names this index. */
    private Index confirm(Index index, Path indexFile, Instant now, List<String> notes) {
        Path file = cacheDir.resolve("timestamp.toml");
        if (timestampKey.isEmpty() || !Files.isRegularFile(file)) {
            return index;
        }
        try {
            Signatures.Check sig = Signatures.verify(file, timestampKey.get());
            if (sig != Signatures.Check.VALID) {
                throw new IOException("signature: " + sig.description());
            }
            Timestamp ts = Timestamp.parse(file);
            if (!ts.indexSha256().equals(Hashing.sha256Hex(indexFile))) {
                return index; // made for another index, e.g. one published since: not an error
            }
            if (ts.issuedAt().isAfter(now.plus(Index.CLOCK_SKEW))) {
                throw new IOException("dated in the future (" + ts.issuedAt() + "); check your clock");
            }
            return index.confirmedAt(ts.issuedAt());
        } catch (IOException | Toml.TomlException e) {
            notes.add("Ignoring the index timestamp: " + e.getMessage());
            return index;
        }
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
