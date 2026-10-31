package io.github.bitecheckpz.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Checks that every class in a jar has a matching type declaration in the source code.
 *
 * <p>For "published" releases (the jar the author uploaded, not a CI rebuild) this is the first
 * thing a reviewer runs: a class without source is code nobody can review and blocks the release.
 * Matching is by package and type name (including nested types; anonymous and lambda classes are
 * accepted as part of their outer type). It does not prove that the bytecode equals the source;
 * reviewers still read the source and compare suspicious classes with a decompiler.
 */
public final class SourceCheck {

    private static final Pattern PACKAGE = Pattern.compile("\\bpackage\\s+([\\w.]+)\\s*;");
    private static final Pattern TYPE = Pattern.compile("\\b(?:class|interface|enum|record)\\s+([A-Za-z_$][\\w$]*)"
            + "|@interface\\s+([A-Za-z_$][\\w$]*)");

    private SourceCheck() {
    }

    public record Result(int classCount, int sourceFiles, List<String> classesWithoutSource,
            List<String> otherEntries) {
        public boolean ok() {
            return classesWithoutSource.isEmpty();
        }
    }

    public static Result check(Path jar, Path sourceRoot) throws IOException {
        Set<String> declared = new TreeSet<>();
        int sourceFiles = 0;
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                sourceFiles++;
                String code = stripCommentsAndStrings(Files.readString(f, StandardCharsets.UTF_8));
                Matcher pm = PACKAGE.matcher(code);
                String pkg = pm.find() ? pm.group(1) + "." : "";
                Matcher tm = TYPE.matcher(code);
                while (tm.find()) {
                    declared.add(pkg + (tm.group(1) != null ? tm.group(1) : tm.group(2)));
                }
            }
        }
        List<String> missing = new ArrayList<>();
        List<String> other = new ArrayList<>();
        int classes = 0;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (e.isDirectory()) {
                    continue;
                }
                if (!name.endsWith(".class")) {
                    if (!name.startsWith("META-INF/")) {
                        other.add(name);
                    }
                    continue;
                }
                String binary = name.substring(0, name.length() - ".class".length());
                if (binary.endsWith("module-info") || binary.endsWith("package-info")) {
                    continue;
                }
                classes++;
                if (!hasSource(binary, declared)) {
                    missing.add(binary.replace('/', '.'));
                }
            }
        }
        return new Result(classes, sourceFiles, missing, other);
    }

    /** a/b/Outer$Inner$1 needs a.b.Outer and a.b.Inner; numeric parts are anonymous classes. */
    static boolean hasSource(String binary, Set<String> declared) {
        int slash = binary.lastIndexOf('/');
        String pkg = slash < 0 ? "" : binary.substring(0, slash).replace('/', '.') + ".";
        String[] parts = binary.substring(slash + 1).split("\\$");
        for (String part : parts) {
            if (part.isEmpty() || part.chars().allMatch(Character::isDigit)) {
                continue; // anonymous class or synthetic
            }
            String simple = part.replaceFirst("^[0-9]+", ""); // local classes: Outer$1Local
            if (simple.startsWith("Lambda") || simple.startsWith("SwitchMap")) {
                continue;
            }
            if (!declared.contains(pkg + simple)) {
                return false;
            }
        }
        return true;
    }

    /** Removes comments, string, text-block and char literals so they cannot fake declarations. */
    static String stripCommentsAndStrings(String code) {
        StringBuilder out = new StringBuilder(code.length());
        int i = 0;
        int n = code.length();
        while (i < n) {
            char c = code.charAt(i);
            if (c == '/' && i + 1 < n && code.charAt(i + 1) == '/') {
                while (i < n && code.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && code.charAt(i + 1) == '*') {
                int end = code.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                out.append(' ');
            } else if (code.startsWith("\"\"\"", i)) {
                int end = code.indexOf("\"\"\"", i + 3);
                i = end < 0 ? n : end + 3;
                out.append("\"\"");
            } else if (c == '"' || c == '\'') {
                i++;
                while (i < n && code.charAt(i) != c && code.charAt(i) != '\n') {
                    if (code.charAt(i) == '\\') {
                        i++;
                    }
                    i++;
                }
                i++;
                out.append(c).append(c);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
