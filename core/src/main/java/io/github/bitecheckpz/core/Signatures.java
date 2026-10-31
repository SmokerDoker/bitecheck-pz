package io.github.bitecheckpz.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Detached Ed25519 signatures over the exact bytes of a file.
 *
 * <p>The signature for {@code foo.toml} lives in {@code foo.toml.sig}:
 *
 * <pre>
 * bitecheck-signature-v1
 * key-id: &lt;id of the signing key&gt;
 * file-sha256: &lt;hash of the signed file, informational&gt;
 * signature: &lt;base64&gt;
 * </pre>
 */
public final class Signatures {

    private static final String HEADER = "bitecheck-signature-v1";

    private Signatures() {
    }

    public enum Check {
        VALID("valid signature"),
        MISSING("signature file is missing"),
        MALFORMED("signature file is malformed"),
        WRONG_KEY("signed with a different key"),
        INVALID("signature does not match the file");

        private final String description;

        Check(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    public static Path signaturePath(Path file) {
        return file.resolveSibling(file.getFileName() + ".sig");
    }

    /** Signs a file and writes {@code <file>.sig}. Returns the signature path. */
    public static Path sign(Path file, Keys.PrivateKeyInfo key) throws IOException, GeneralSecurityException {
        byte[] data = Files.readAllBytes(file);
        Signature signer = Signature.getInstance(Keys.ALGORITHM);
        signer.initSign(key.key());
        signer.update(data);
        String content = HEADER + "\n"
                + "key-id: " + key.id() + "\n"
                + "file-sha256: " + Hashing.sha256Hex(data) + "\n"
                + "signature: " + Base64.getEncoder().encodeToString(signer.sign()) + "\n";
        Path sigPath = signaturePath(file);
        Files.writeString(sigPath, content, StandardCharsets.UTF_8);
        return sigPath;
    }

    /** Checks {@code <file>.sig} against the given public key. Never throws for bad input. */
    public static Check verify(Path file, Keys.PublicKeyInfo key) {
        Path sigPath = signaturePath(file);
        if (!Files.isRegularFile(sigPath)) {
            return Check.MISSING;
        }
        Map<String, String> fields = new HashMap<>();
        try {
            var lines = Files.readAllLines(sigPath, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.get(0).strip().equals(HEADER)) {
                return Check.MALFORMED;
            }
            for (String line : lines.subList(1, lines.size())) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    fields.put(line.substring(0, colon).strip(), line.substring(colon + 1).strip());
                }
            }
            String keyId = fields.get("key-id");
            String sig = fields.get("signature");
            if (keyId == null || sig == null) {
                return Check.MALFORMED;
            }
            if (!keyId.equals(key.id())) {
                return Check.WRONG_KEY;
            }
            Signature verifier = Signature.getInstance(Keys.ALGORITHM);
            verifier.initVerify(key.key());
            verifier.update(Files.readAllBytes(file));
            return verifier.verify(Base64.getDecoder().decode(sig)) ? Check.VALID : Check.INVALID;
        } catch (IllegalArgumentException e) {
            return Check.MALFORMED; // bad base64
        } catch (IOException | GeneralSecurityException e) {
            return Check.INVALID;
        }
    }
}
