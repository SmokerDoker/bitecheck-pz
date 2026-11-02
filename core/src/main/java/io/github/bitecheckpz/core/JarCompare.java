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
 * the same. Other spellings of the manifest and signature files are compared like any file.
 */
public final class JarCompare {

    private static final String MANIFEST = "META-INF/MANIFEST.MF";
    private static final Set<String> BUILD_INFO =
            Set.of("Manifest-Version", "Created-By", "Built-By", "Build-Jdk", "Build-Jdk-Spec");

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
            sections.put(e.getKey(), attributes);
        }
        return new ManifestAttributes(main, sections);
    }

    private static Map<String, byte[]> files(Path jar) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                byte[] content;
                try (InputStream in = zip.getInputStream(entry)) {
                    content = in.readAllBytes();
                }
                if (entry.isDirectory() && content.length == 0) {
                    continue;
                }
                if (files.put(entry.getName(), content) != null) {
                    throw new IOException(jar.getFileName() + ": duplicate entry " + entry.getName());
                }
            }
        }
        return files;
    }
}
