package io.github.bitecheckpz.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 helpers. All hashes are lower-case hex. */
public final class Hashing {

    private Hashing() {
    }

    public static String sha256Hex(Path file) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String sha256Hex(byte[] data) {
        return HexFormat.of().formatHex(newDigest().digest(data));
    }

    /** True if the value looks like a SHA-256 hex digest. */
    public static boolean isSha256Hex(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java runtime", e);
        }
    }
}
