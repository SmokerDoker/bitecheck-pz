package io.github.bitecheckpz.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;

/**
 * Ed25519 keys in BiteCheck's file formats.
 *
 * <pre>
 * public key  (one line):  bitecheck-ed25519 &lt;base64 X.509&gt; &lt;comment&gt;
 * private key (three lines): bitecheck-ed25519-private / key-id: &lt;id&gt; / &lt;base64 PKCS#8&gt;
 * </pre>
 *
 * <p>The key id is the first 16 hex characters of the SHA-256 of the encoded public key.
 */
public final class Keys {

    public static final String ALGORITHM = "Ed25519";
    private static final String PUBLIC_PREFIX = "bitecheck-ed25519";
    private static final String PRIVATE_HEADER = "bitecheck-ed25519-private";

    private Keys() {
    }

    public record PublicKeyInfo(PublicKey key, String id, String comment) {
    }

    public record PrivateKeyInfo(PrivateKey key, String id) {
    }

    public static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 is not available in this Java runtime", e);
        }
    }

    /**
     * The public half of an Ed25519 private key. The JDK has no direct call for this, so the key
     * pair is generated again from the private key's 32-byte seed.
     */
    public static PublicKey publicKey(PrivateKey key) throws GeneralSecurityException {
        if (!(key instanceof java.security.interfaces.EdECPrivateKey ed) || ed.getBytes().isEmpty()) {
            throw new GeneralSecurityException("not an Ed25519 private key");
        }
        byte[] seed = ed.getBytes().get();
        KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITHM);
        generator.initialize(java.security.spec.NamedParameterSpec.ED25519, new java.security.SecureRandom() {
            private static final long serialVersionUID = 1L;

            @Override
            public void nextBytes(byte[] bytes) {
                System.arraycopy(seed, 0, bytes, 0, Math.min(seed.length, bytes.length));
            }
        });
        PublicKey derived = generator.generateKeyPair().getPublic();
        // Make sure the derived key really belongs to the private key.
        java.security.Signature s = java.security.Signature.getInstance(ALGORITHM);
        s.initSign(key);
        s.update(seed);
        byte[] signature = s.sign();
        s.initVerify(derived);
        s.update(seed);
        if (!s.verify(signature)) {
            throw new GeneralSecurityException("cannot derive the public key from this private key");
        }
        return derived;
    }

    public static String keyId(PublicKey key) {
        return Hashing.sha256Hex(key.getEncoded()).substring(0, 16);
    }

    public static String encodePublic(PublicKey key, String comment) {
        String safeComment = comment == null ? "" : comment.replaceAll("\\s+", "-");
        return PUBLIC_PREFIX + " " + Base64.getEncoder().encodeToString(key.getEncoded())
                + (safeComment.isEmpty() ? "" : " " + safeComment) + "\n";
    }

    public static PublicKeyInfo readPublic(Path file) throws IOException, GeneralSecurityException {
        String line = Files.readString(file, StandardCharsets.UTF_8).strip();
        String[] parts = line.split("\\s+");
        if (parts.length < 2 || !parts[0].equals(PUBLIC_PREFIX)) {
            throw new GeneralSecurityException(file + ": not a BiteCheck public key");
        }
        PublicKey key = KeyFactory.getInstance(ALGORITHM)
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(parts[1])));
        return new PublicKeyInfo(key, keyId(key), parts.length > 2 ? parts[2] : "");
    }

    /** Writes a private key file readable only by the owner. Refuses to overwrite. */
    public static void writePrivate(Path file, PrivateKey key, String keyId) throws IOException {
        if (Files.exists(file)) {
            throw new FileAlreadyExistsException(file.toString(), null, "refusing to overwrite a private key");
        }
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        String content = PRIVATE_HEADER + "\nkey-id: " + keyId + "\n"
                + Base64.getEncoder().encodeToString(key.getEncoded()) + "\n";
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            Files.createFile(file); // Windows: rely on the user profile's ACLs
        }
        Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public static PrivateKeyInfo readPrivate(Path file) throws IOException, GeneralSecurityException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.size() < 3 || !lines.get(0).strip().equals(PRIVATE_HEADER)
                || !lines.get(1).startsWith("key-id: ")) {
            throw new GeneralSecurityException(file + ": not a BiteCheck private key");
        }
        String id = lines.get(1).substring("key-id: ".length()).strip();
        PrivateKey key = KeyFactory.getInstance(ALGORITHM)
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(lines.get(2).strip())));
        return new PrivateKeyInfo(key, id);
    }
}
