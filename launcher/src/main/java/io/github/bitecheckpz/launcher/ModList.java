package io.github.bitecheckpz.launcher;

import io.github.bitecheckpz.core.Hashing;
import io.github.bitecheckpz.core.Toml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A list of installed Java mod jars with their hashes, for comparing two installations, e.g. a
 * dedicated server and a player. The server admin exports the list ({@code --export}), players
 * compare their own mods against it ({@code --compare}).
 *
 * <pre>
 * format = 1
 * generated_at = "2026-11-01T10:00:00Z"
 *
 * [jar.&lt;sha256&gt;]
 * mod = "ZBBetterFPS"
 * workshop_id = "1234567890"
 * file = "42.13/media/java/client/ZBBetterFPS.jar"
 * status = "VERIFIED"
 * </pre>
 */
public final class ModList {

    public record Item(String sha256, String mod, String workshopId, String file, String status) {

        String key() {
            return mod + "/" + file;
        }

        String label() {
            return mod + " " + file;
        }
    }

    /** Result of comparing the local mods with a reference list. */
    public record Diff(List<Item> missing, List<Item[]> different, List<Item> extra) {

        /** True if every jar of the reference list is installed with the same hash. */
        public boolean matches() {
            return missing.isEmpty() && different.isEmpty();
        }
    }

    private ModList() {
    }

    public static List<Item> of(List<Planner.Row> rows) {
        List<Item> items = new ArrayList<>();
        for (Planner.Row r : rows) {
            Path file = r.jar().path();
            Path folder = r.mod().folder();
            String rel = (file.startsWith(folder) ? folder.relativize(file) : file.getFileName()).toString()
                    .replace('\\', '/');
            String status = r.suspicious() ? "SUSPICIOUS" : r.status().name();
            items.add(new Item(r.jar().sha256(), r.mod().id(), r.mod().workshopId() == null ? "" : r.mod().workshopId(),
                    rel, status));
        }
        return items;
    }

    public static void write(Path out, List<Item> items, Instant now) throws IOException {
        StringBuilder s = new StringBuilder();
        s.append("# BiteCheck mod list. Compare your mods with: BiteCheck --compare <this file>\n");
        s.append("format = 1\n");
        s.append("generated_at = ").append(Toml.quote(now.toString())).append('\n');
        for (Item i : items) {
            s.append('\n').append("[jar.").append(i.sha256()).append("]\n");
            s.append("mod = ").append(Toml.quote(i.mod())).append('\n');
            s.append("workshop_id = ").append(Toml.quote(i.workshopId())).append('\n');
            s.append("file = ").append(Toml.quote(i.file())).append('\n');
            s.append("status = ").append(Toml.quote(i.status())).append('\n');
        }
        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(out, s.toString(), StandardCharsets.UTF_8);
    }

    public static List<Item> read(Path file) throws IOException {
        Map<String, Object> doc;
        try {
            doc = Toml.parse(file);
        } catch (Toml.TomlException e) {
            throw new IOException("not a BiteCheck mod list: " + e.getMessage(), e);
        }
        if (!Long.valueOf(1).equals(doc.get("format"))) {
            throw new IOException("not a BiteCheck mod list (format = 1 missing): " + file);
        }
        List<Item> items = new ArrayList<>();
        for (Map.Entry<String, Object> e : Toml.table(doc, "jar").entrySet()) {
            if (!Hashing.isSha256Hex(e.getKey()) || !(e.getValue() instanceof Map<?, ?>)) {
                throw new IOException("invalid entry [jar." + e.getKey() + "] in " + file);
            }
            Map<String, Object> t = Toml.table(doc, "jar." + e.getKey());
            items.add(new Item(e.getKey(), orEmpty(Toml.string(t, "mod")), orEmpty(Toml.string(t, "workshop_id")),
                    orEmpty(Toml.string(t, "file")), orEmpty(Toml.string(t, "status"))));
        }
        return items;
    }

    public static Diff compare(List<Item> local, List<Item> reference) {
        Map<String, Item> localByKey = new LinkedHashMap<>();
        Map<String, Item> localBySha = new LinkedHashMap<>();
        for (Item i : local) {
            localByKey.put(i.key(), i);
            localBySha.put(i.sha256(), i);
        }
        Map<String, Item> refBySha = new LinkedHashMap<>();
        Map<String, Item> refByKey = new LinkedHashMap<>();
        for (Item i : reference) {
            refBySha.put(i.sha256(), i);
            refByKey.put(i.key(), i);
        }
        List<Item> missing = new ArrayList<>();
        List<Item[]> different = new ArrayList<>();
        for (Item ref : reference) {
            if (localBySha.containsKey(ref.sha256())) {
                continue;
            }
            Item mine = localByKey.get(ref.key());
            if (mine != null) {
                different.add(new Item[] {ref, mine});
            } else {
                missing.add(ref);
            }
        }
        List<Item> extra = new ArrayList<>();
        for (Item mine : local) {
            if (!refBySha.containsKey(mine.sha256()) && !refByKey.containsKey(mine.key())) {
                extra.add(mine);
            }
        }
        return new Diff(missing, different, extra);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
