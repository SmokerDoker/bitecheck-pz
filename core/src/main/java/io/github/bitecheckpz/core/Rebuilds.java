package io.github.bitecheckpz.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Independent rebuilds by volunteers ({@code bcz rebuild}): someone built a release from source on
 * their own computer and got exactly the jar CI built. {@code rebuilds/<version>/<name>.toml}, signed
 * with that person's registered key (author or reviewer). They do not approve anything; they show
 * that the build does not depend on GitHub's runners.
 */
public final class Rebuilds {

    private Rebuilds() {
    }

    /** The problem with one rebuild record, or null if it is valid for this build record. */
    public static String problem(Registry registry, Path file, String mod, String version, String buildSha,
            List<String> jars) {
        String fileName = file.getFileName().toString();
        String name = fileName.substring(0, fileName.length() - ".toml".length());
        if (!Registry.NAME.matcher(name).matches()) {
            return "invalid name";
        }
        try {
            Map<String, Object> doc = Toml.parse(file);
            if (!name.equals(Toml.string(doc, "rebuilder")) || !mod.equals(Toml.string(doc, "mod"))
                    || !version.equals(Toml.string(doc, "version"))) {
                return "rebuilder, mod or version do not match the file";
            }
            if (buildSha != null && !buildSha.equals(Toml.string(doc, "build_sha256"))) {
                return "not for this build record";
            }
            if (!jars.contains(String.valueOf(Toml.string(doc, "jar_sha256")))) {
                return "not one of the build record's jars";
            }
            var key = registry.reviewerKey(name).or(() -> registry.authorKey(name));
            if (key.isEmpty()) {
                return "no registered key for '" + name + "'";
            }
            Signatures.Check sig = Signatures.verify(file, key.get());
            return sig == Signatures.Check.VALID ? null : "signature: " + sig.description();
        } catch (IOException | Toml.TomlException e) {
            return "cannot read: " + e.getMessage();
        }
    }

    /** The names of everybody who rebuilt this release identically, from valid records. */
    public static List<String> of(Registry registry, String mod, String version) throws IOException {
        List<String> out = new ArrayList<>();
        Path dir = registry.modDir(mod).resolve("rebuilds").resolve(version);
        Path buildFile = registry.buildFile(mod, version);
        if (!Files.isDirectory(dir) || !Files.isRegularFile(buildFile)) {
            return out;
        }
        List<String> jars;
        try {
            jars = Registry.jarHashes(registry.read(buildFile));
        } catch (Toml.TomlException e) {
            return out;
        }
        String buildSha = Hashing.sha256Hex(buildFile);
        List<Path> files;
        try (Stream<Path> list = Files.list(dir)) {
            files = list.filter(p -> p.getFileName().toString().endsWith(".toml")).sorted().toList();
        }
        for (Path f : files) {
            if (problem(registry, f, mod, version, buildSha, jars) == null) {
                String n = f.getFileName().toString();
                out.add(n.substring(0, n.length() - ".toml".length()));
            }
        }
        return out;
    }
}
