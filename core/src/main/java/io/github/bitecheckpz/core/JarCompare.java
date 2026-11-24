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
                if (!manifestWithoutBuildInfo(a.get(name), a).equals(manifestWithoutBuildInfo(b.get(name), b))) {
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

    /**
     * The manifest's attributes without the build tool and JDK names, and without the per-entry
     * hashes of a jar signature: a digest attribute is only left out if it is the real hash of that
     * file in this jar, so no other data can hide under a digest name.
     */
    private static ManifestAttributes manifestWithoutBuildInfo(byte[] content, Map<String, byte[]> files)
            throws IOException {
        Manifest manifest = new Manifest(new ByteArrayInputStream(content));
        Map<String, String> main = new TreeMap<>();
        manifest.getMainAttributes().forEach((k, v) -> main.put(k.toString(), String.valueOf(v)));
        BUILD_INFO.forEach(main::remove);
        Map<String, Map<String, String>> sections = new TreeMap<>();
        for (Map.Entry<String, Attributes> e : manifest.getEntries().entrySet()) {
            Map<String, String> attributes = new TreeMap<>();
            e.getValue().forEach((k, v) -> attributes.put(k.toString(), String.valueOf(v)));
            byte[] file = files.get(e.getKey());
            attributes.entrySet().removeIf(a -> file != null && isDigestOf(a.getKey(), "-Digest", a.getValue(), file));
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
        byte[] manifest = null;
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
                byte[] bytes;
                try (InputStream in = jf.getInputStream(entry)) {
                    bytes = in.readAllBytes(); // reading checks the file against the signature
                }
                if (entry.getName().equals(MANIFEST)) {
                    manifest = bytes;
                }
                if (entry.isDirectory() || entry.getName().equals(MANIFEST)) {
                    continue;
                }
                if (entry.getCodeSigners() == null) {
                    return false;
                }
            }
            for (byte[] content : sfContents) {
                if (manifest == null || !standardSignatureFile(content, manifest, names)) {
                    return false;
                }
            }
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            return false;
        }
        return !sf.isEmpty() && sf.size() <= MAX_SIGNERS && sf.equals(blocks);
    }

    /**
     * A .SF holding exactly what jarsigner writes, so nothing else can hide in it. Checked on the
     * text itself, not on a parsed map (which would keep only the last of repeated attributes):
     * the main section has Signature-Version, Created-By and the manifest digests; every other
     * section is one "Name" of a file of the jar plus digests. Every digest must be the real hash
     * of what it covers (the whole manifest, its main section or the file's manifest section).
     */
    private static boolean standardSignatureFile(byte[] content, byte[] manifest, Set<String> names) {
        List<RawSection> sf = rawSections(content);
        List<RawSection> mf = rawSections(manifest);
        if (sf.isEmpty() || mf.isEmpty()) {
            return false;
        }
        Map<String, byte[]> mfSections = new java.util.HashMap<>();
        for (RawSection section : mf.subList(1, mf.size())) {
            String first = section.lines().get(0);
            if (first.startsWith("Name: ")) {
                mfSections.put(first.substring(6), section.raw());
            }
        }
        Set<String> keys = new java.util.HashSet<>();
        for (String line : sf.get(0).lines()) {
            String[] kv = keyValue(line);
            if (kv == null || !keys.add(kv[0])) {
                return false;
            }
            boolean ok = kv[0].equals("Signature-Version") || kv[0].equals("Created-By")
                    || isDigestOf(kv[0], "-Digest-Manifest", kv[1], manifest)
                    || isDigestOf(kv[0], "-Digest-Manifest-Main-Attributes", kv[1], mf.get(0).raw());
            if (!ok) {
                return false;
            }
        }
        Set<String> seen = new java.util.HashSet<>();
        for (RawSection section : sf.subList(1, sf.size())) {
            List<String> lines = section.lines();
            String[] name = keyValue(lines.get(0));
            if (name == null || !name[0].equals("Name") || !names.contains(name[1]) || !seen.add(name[1])
                    || !mfSections.containsKey(name[1]) || lines.size() < 2) {
                return false;
            }
            Set<String> digests = new java.util.HashSet<>();
            for (String line : lines.subList(1, lines.size())) {
                String[] kv = keyValue(line);
                if (kv == null || !digests.add(kv[0])
                        || !isDigestOf(kv[0], "-Digest", kv[1], mfSections.get(name[1]))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** A manifest-style section: its logical lines (continuations joined) and its bytes with the blank line after it. */
    private record RawSection(List<String> lines, byte[] raw) {
    }

    private static List<RawSection> rawSections(byte[] content) {
        List<RawSection> sections = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        StringBuilder current = null;
        int start = 0;
        int i = 0;
        while (i < content.length) {
            int lineStart = i;
            while (i < content.length && content[i] != '\n' && content[i] != '\r') {
                i++;
            }
            String line = new String(content, lineStart, i - lineStart, java.nio.charset.StandardCharsets.UTF_8);
            if (i < content.length && content[i] == '\r') {
                i++;
            }
            if (i < content.length && content[i] == '\n') {
                i++;
            }
            if (line.isEmpty()) {
                if (current != null) {
                    lines.add(current.toString());
                    current = null;
                }
                if (!lines.isEmpty()) {
                    sections.add(new RawSection(lines, Arrays.copyOfRange(content, start, i)));
                    lines = new ArrayList<>();
                }
                start = i;
            } else if (line.startsWith(" ")) {
                if (current == null) {
                    throw new IllegalArgumentException("continuation without a line");
                }
                current.append(line, 1, line.length());
            } else {
                if (current != null) {
                    lines.add(current.toString());
                }
                current = new StringBuilder(line);
            }
        }
        if (current != null) {
            lines.add(current.toString());
        }
        if (!lines.isEmpty()) {
            sections.add(new RawSection(lines, Arrays.copyOfRange(content, start, content.length)));
        }
        return sections;
    }

    private static String[] keyValue(String line) {
        int colon = line.indexOf(": ");
        return colon <= 0 ? null : new String[] {line.substring(0, colon), line.substring(colon + 2)};
    }

    /** Whether {@code key} is {@code <algorithm><suffix>} and {@code value} is that hash of {@code data}, in Base64. */
    private static boolean isDigestOf(String key, String suffix, String value, byte[] data) {
        if (!key.endsWith(suffix)) {
            return false;
        }
        String algorithm = switch (key.substring(0, key.length() - suffix.length())) {
            case "SHA1", "SHA-1" -> "SHA-1";
            case "SHA-256" -> "SHA-256";
            case "SHA-384" -> "SHA-384";
            case "SHA-512" -> "SHA-512";
            default -> null;
        };
        if (algorithm == null) {
            return false;
        }
        try {
            byte[] expected = java.security.MessageDigest.getInstance(algorithm).digest(data);
            return java.util.Base64.getEncoder().encodeToString(expected).equals(value.strip());
        } catch (java.security.NoSuchAlgorithmException e) {
            return false;
        }
    }
}
