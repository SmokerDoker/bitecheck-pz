package io.github.bitecheckpz.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Compares the contents of two jars, for "workshop" releases: the jar players get from the Steam
 * Workshop must contain exactly what CI built from the source. Every file must be byte-identical,
 * so the jars themselves may differ only in timestamps and order. The manifest (exactly
 * META-INF/MANIFEST.MF) is compared by its attributes: only the names of the build tool and JDK
 * may differ; anything that changes loading (Class-Path, agents, per-entry sections, ...) must be
 * the same. Other spellings of the manifest are compared like any file.
 *
 * <p>A jar signature (jarsigner) is left out: the signature files directly in META-INF
 * ({@code *.SF} with its block file {@code *.RSA}, {@code *.DSA} or {@code *.EC}) and the per-entry
 * digest attributes in the manifest. A rebuild can never reproduce the author's signature, and a
 * signature does not change what the code does; every signed file itself is still compared byte for
 * byte. The signature files are only left out if they really are a signature of this jar (Java
 * verifies every file against them, each .SF has its block file, a few signers at most, limited
 * size); otherwise they count like any other file, so nothing can hide under such a name.
 */
public final class JarCompare {

    private static final String MANIFEST = "META-INF/MANIFEST.MF";
    private static final Set<String> BUILD_INFO =
            Set.of("Manifest-Version", "Created-By", "Built-By", "Build-Jdk", "Build-Jdk-Spec");
    private static final java.util.regex.Pattern SIGNATURE_FILE =
            java.util.regex.Pattern.compile("META-INF/([^/]+)\\.(SF|RSA|DSA|EC)");
    private static final int MAX_SIGNERS = 4;
    private static final long MAX_SF_BYTES = 32L * 1024 * 1024;
    private static final long MAX_BLOCK_BYTES = 16L * 1024;

    private JarCompare() {
    }

    /** Differences between {@code built} and {@code other}; empty if they have the same files. */
    public static List<String> differences(Path built, Path other) throws IOException {
        Map<String, byte[]> a = files(built);
        Map<String, byte[]> b = files(other);
        List<String> differences = new ArrayList<>();
        TreeSet<String> names = new TreeSet<>(a.keySet());
        names.addAll(b.keySet());
        for (String name : names) {
            if (!a.containsKey(name)) {
                differences.add(name + ": not built from the source");
            } else if (!b.containsKey(name)) {
                differences.add(name + ": built from the source, missing in the other jar");
            } else if (name.equals(MANIFEST)) {
                if (!manifestWithoutBuildInfo(a.get(name)).equals(manifestWithoutBuildInfo(b.get(name)))) {
                    differences.add(name + ": attributes differ (beyond build tool and JDK)");
                }
            } else if (!Arrays.equals(a.get(name), b.get(name))) {
                differences.add(name + ": differs");
            }
        }
        return differences;
    }

    /** A manifest's main attributes and per-entry sections. */
    private record ManifestAttributes(Map<String, String> main, Map<String, Map<String, String>> sections) {
    }

    /** The manifest's attributes without the build tool and JDK names. */
    private static ManifestAttributes manifestWithoutBuildInfo(byte[] content) throws IOException {
        Manifest manifest = new Manifest(new ByteArrayInputStream(content));
        Map<String, String> main = new TreeMap<>();
        manifest.getMainAttributes().forEach((k, v) -> main.put(k.toString(), String.valueOf(v)));
        BUILD_INFO.forEach(main::remove);
        Map<String, Map<String, String>> sections = new TreeMap<>();
        for (Map.Entry<String, Attributes> e : manifest.getEntries().entrySet()) {
            Map<String, String> attributes = new TreeMap<>();
            e.getValue().forEach((k, v) -> attributes.put(k.toString(), String.valueOf(v)));
            // a jar signature's file hashes; any other attribute, or a value that is no hash, still counts
            attributes.entrySet().removeIf(a -> a.getKey().endsWith("-Digest") && isDigestValueQuiet(a.getKey(), a.getValue()));
            if (!attributes.isEmpty()) {
                sections.put(e.getKey(), attributes);
            }
        }
        return new ManifestAttributes(main, sections);
    }

    private static Map<String, byte[]> files(Path jar) throws IOException {
        boolean signed = validSignature(jar);
        Map<String, byte[]> files = new TreeMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                byte[] content;
                try (InputStream in = zip.getInputStream(entry)) {
                    content = in.readAllBytes();
                }
                if ((entry.isDirectory() && content.length == 0)
                        || (signed && SIGNATURE_FILE.matcher(entry.getName()).matches())) {
                    continue;
                }
                if (files.put(entry.getName(), content) != null) {
                    throw new IOException(jar.getFileName() + ": duplicate entry " + entry.getName());
                }
            }
        }
        return files;
    }

    /**
     * Whether the jar carries a jarsigner signature that Java verifies for every file: each .SF
     * has its block file and the other way round, at most {@link #MAX_SIGNERS} signers, and every
     * file outside the signature itself is signed and unchanged.
     */
    static boolean validSignature(Path jar) {
        Set<String> sf = new java.util.HashSet<>();
        Set<String> blocks = new java.util.HashSet<>();
        Set<String> names = new java.util.HashSet<>();
        List<byte[]> sfContents = new ArrayList<>();
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar.toFile(), true)) {
            for (Enumeration<java.util.jar.JarEntry> e = jf.entries(); e.hasMoreElements(); ) {
                java.util.jar.JarEntry entry = e.nextElement();
                java.util.regex.Matcher m = SIGNATURE_FILE.matcher(entry.getName());
                if (m.matches()) {
                    boolean isSf = m.group(2).equals("SF");
                    if (entry.getSize() < 0 || entry.getSize() > (isSf ? MAX_SF_BYTES : MAX_BLOCK_BYTES)
                            || !(isSf ? sf : blocks).add(m.group(1))) {
                        return false;
                    }
                    if (isSf) {
                        try (InputStream in = jf.getInputStream(entry)) {
                            sfContents.add(in.readAllBytes());
                        }
                    }
                    continue;
                }
                names.add(entry.getName());
                try (InputStream in = jf.getInputStream(entry)) {
                    in.readAllBytes(); // reading checks the file against the signature
                }
                if (entry.isDirectory() || entry.getName().equals(MANIFEST)) {
                    continue;
                }
                if (entry.getCodeSigners() == null) {
                    return false;
                }
            }
            for (byte[] content : sfContents) {
                if (!standardSignatureFile(content, names)) {
                    return false;
                }
            }
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            return false;
        }
        return !sf.isEmpty() && sf.size() <= MAX_SIGNERS && sf.equals(blocks);
    }

    /**
     * A .SF holding only what jarsigner writes, so nothing else can hide in it: Signature-Version,
     * Created-By and manifest digests in the main section, and one section per file of the jar with
     * only digest values (each the length of a real hash).
     */
    private static boolean standardSignatureFile(byte[] content, Set<String> names) throws IOException {
        Manifest sf = new Manifest(new ByteArrayInputStream(content));
        for (Map.Entry<Object, Object> a : sf.getMainAttributes().entrySet()) {
            String key = a.getKey().toString();
            boolean digest = key.endsWith("-Digest-Manifest") || key.endsWith("-Digest-Manifest-Main-Attributes");
            if (digest ? !isDigestValue(key, String.valueOf(a.getValue()))
                    : !key.equals("Signature-Version") && !key.equals("Created-By")) {
                return false;
            }
        }
        for (Map.Entry<String, Attributes> section : sf.getEntries().entrySet()) {
            if (!names.contains(section.getKey()) || section.getValue().isEmpty()) {
                return false;
            }
            for (Map.Entry<Object, Object> a : section.getValue().entrySet()) {
                String key = a.getKey().toString();
                if (!key.endsWith("-Digest") || !isDigestValue(key, String.valueOf(a.getValue()))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isDigestValueQuiet(String key, String value) {
        try {
            return isDigestValue(key, value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** A known digest name (SHA-1/SHA-256/384/512) whose value is a Base64 hash of the right length. */
    private static boolean isDigestValue(String key, String value) {
        String algorithm = key.substring(0, key.indexOf("-Digest"));
        int length = switch (algorithm) {
            case "SHA1", "SHA-1" -> 20;
            case "SHA-256" -> 32;
            case "SHA-384" -> 48;
            case "SHA-512" -> 64;
            default -> -1;
        };
        return length > 0 && java.util.Base64.getDecoder().decode(value.strip()).length == length;
    }
}
