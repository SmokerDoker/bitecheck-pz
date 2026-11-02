package io.github.bitecheckpz.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Compares the contents of two jars, for "workshop" releases: the jar players get from the Steam
 * Workshop must contain exactly what CI built from the source. Every file must be byte-identical;
 * only META-INF/MANIFEST.MF (build tool and JDK names) and directory entries are ignored, so the
 * jars themselves may differ in timestamps and order.
 */
public final class JarCompare {

    private static final String MANIFEST = "META-INF/MANIFEST.MF";

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
            } else if (!Arrays.equals(a.get(name), b.get(name))) {
                differences.add(name + ": differs");
            }
        }
        return differences;
    }

    private static Map<String, byte[]> files(Path jar) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (entry.isDirectory() || entry.getName().equalsIgnoreCase(MANIFEST)) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    if (files.put(entry.getName(), in.readAllBytes()) != null) {
                        throw new IOException(jar.getFileName() + ": duplicate entry " + entry.getName());
                    }
                }
            }
        }
        return files;
    }
}
