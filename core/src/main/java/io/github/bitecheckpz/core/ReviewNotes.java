package io.github.bitecheckpz.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Review notes on jars whose source is not public (closed-source mods), which the registry can
 * never verify: a reviewer looked at the jar and writes down the findings only, never code or the
 * file itself (§ 69e UrhG). Players see the note in the launcher's warning about that jar.
 *
 * <pre>
 * registry/notes/&lt;sha256&gt;.toml + .sig   (signed by the reviewer)
 * sha256 = "…"
 * name = "Some Mod 1.2 (SomeMod.jar)"
 * reviewer = "bob"
 * findings = "Reads and writes only Zomboid/Lua; opens no network connection; no native code."
 * </pre>
 */
public final class ReviewNotes {

    public record Note(String sha256, String name, String reviewer, String findings) {
    }

    private ReviewNotes() {
    }

    public static Path dir(Registry registry) {
        return registry.root().resolve("registry").resolve("notes");
    }

    /** Writes {@code registry/notes/<sha256>.toml}; sign it afterwards with the reviewer's key. */
    public static Path write(Registry registry, String sha256, String name, String reviewer, String findings)
            throws IOException {
        if (!Hashing.isSha256Hex(sha256)) {
            throw new IllegalArgumentException("not a SHA-256: " + sha256);
        }
        Registry.requireName(reviewer, "reviewer");
        if (findings == null || findings.isBlank()) {
            throw new IllegalArgumentException("write down the findings with --findings \"...\"");
        }
        Path file = dir(registry).resolve(sha256 + ".toml");
        if (Files.exists(file)) {
            throw new FileAlreadyExistsException(file.toString(), null, "there is a note for this jar already");
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, "sha256 = " + Toml.quote(sha256) + "\nname = " + Toml.quote(name == null ? "" : name.strip())
                + "\nreviewer = " + Toml.quote(reviewer) + "\nfindings = " + Toml.quote(findings.strip()) + "\n",
                StandardCharsets.UTF_8);
        return file;
    }

    /** The problem with one note file, or null if it is valid. */
    public static String problem(Registry registry, Path file) {
        String fileName = file.getFileName().toString();
        String sha = fileName.endsWith(".toml") ? fileName.substring(0, fileName.length() - ".toml".length()) : "";
        if (!Hashing.isSha256Hex(sha)) {
            return "a note is named <sha256>.toml";
        }
        try {
            Map<String, Object> doc = Toml.parse(file);
            String reviewer = Toml.string(doc, "reviewer");
            if (!sha.equals(Toml.string(doc, "sha256"))) {
                return "sha256 must match the file name";
            }
            String findings = Toml.string(doc, "findings");
            if (findings == null || findings.isBlank()) {
                return "needs findings";
            }
            if (reviewer == null || !Registry.NAME.matcher(reviewer).matches()) {
                return "needs a valid reviewer";
            }
            String level = registry.reviewerLevel(reviewer);
            if (!level.equals("reviewer") && !level.equals("trusted")) {
                return "reviewer level is '" + level + "'";
            }
            var key = registry.reviewerKey(reviewer);
            if (key.isEmpty()) {
                return "no valid key for reviewer '" + reviewer + "'";
            }
            Signatures.Check sig = Signatures.verify(file, key.get());
            return sig == Signatures.Check.VALID ? null : "reviewer signature: " + sig.description();
        } catch (IOException | Toml.TomlException e) {
            return "cannot read: " + e.getMessage();
        }
    }

    /** Every valid note; invalid ones are left out (Lint reports them). */
    public static List<Note> valid(Registry registry) throws IOException {
        List<Note> out = new ArrayList<>();
        Path dir = dir(registry);
        if (!Files.isDirectory(dir)) {
            return out;
        }
        List<Path> files;
        try (Stream<Path> list = Files.list(dir)) {
            files = list.filter(p -> p.getFileName().toString().endsWith(".toml")).sorted().toList();
        }
        for (Path f : files) {
            if (problem(registry, f) != null) {
                continue;
            }
            try {
                Map<String, Object> doc = Toml.parse(f);
                out.add(new Note(Toml.string(doc, "sha256"), Toml.string(doc, "name"), Toml.string(doc, "reviewer"),
                        Toml.string(doc, "findings")));
            } catch (Toml.TomlException e) {
                // checked above
            }
        }
        return out;
    }
}
