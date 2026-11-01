package io.github.bitecheckpz.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Generates the public registry website: one static HTML page listing every mod, its releases,
 * jar hashes with their verification status, reviews and flags. Everything on the page is
 * derived from the registry files, the same way the launcher index is.
 */
public final class Site {

    /** One jar of a release with its status. */
    public record JarRow(String sha256, Verifier.Status status, String detail) {
    }

    /** @param analysis the AI pre-analysis record, or null */
    public record ReleaseRow(String version, String commit, String type, List<String> artifacts,
            boolean built, List<JarRow> jars, List<Map<String, Object>> reviews, Map<String, Object> analysis) {
    }

    public record ModRow(String id, String name, String author, String publisher, String source, String license,
            String workshopId, String status, List<ReleaseRow> releases, List<Map<String, Object>> flags,
            boolean hasNotes) {

        /** Overall label for the list: the best status of the newest release. */
        public String badge() {
            if ("pending".equals(status)) {
                return "pending";
            }
            if ("suspended".equals(status) || "withdrawn".equals(status)) {
                return status;
            }
            if (releases.isEmpty()) {
                return "no release";
            }
            ReleaseRow newest = releases.get(releases.size() - 1);
            if (!newest.built()) {
                return "building";
            }
            for (Verifier.Status s : List.of(Verifier.Status.BLOCKED, Verifier.Status.FLAGGED,
                    Verifier.Status.INVALID, Verifier.Status.UNVERIFIED)) {
                if (newest.jars().stream().anyMatch(j -> j.status() == s)) {
                    return s == Verifier.Status.UNVERIFIED ? "in review" : s.name().toLowerCase();
                }
            }
            return newest.jars().isEmpty() ? "no jars" : "verified";
        }
    }

    private Site() {
    }

    /** Collects the data for the page. Without a registry key, statuses are not checked. */
    public static List<ModRow> collect(Registry registry, Optional<Keys.PublicKeyInfo> registryKey)
            throws IOException, Toml.TomlException {
        Optional<Verifier> verifier = registryKey.map(k -> new Verifier(registry, k));
        Path notesDir = registry.root().resolve("docs/reviews");
        List<ModRow> mods = new ArrayList<>();
        for (Path modDir : list(registry.modsDir())) {
            Path modFile = modDir.resolve("mod.toml");
            if (!Files.isDirectory(modDir) || !Files.isRegularFile(modFile)) {
                continue;
            }
            Map<String, Object> m = registry.read(modFile);
            if (Toml.bool(m, "example")) {
                continue;
            }
            String id = modDir.getFileName().toString();
            List<ReleaseRow> releases = new ArrayList<>();
            for (Path f : list(modDir.resolve("releases"))) {
                String name = f.getFileName().toString();
                if (!name.endsWith(".toml") || name.endsWith(".build.toml")) {
                    continue;
                }
                Map<String, Object> rel = registry.read(f);
                String version = str(rel, "version");
                Path build = f.resolveSibling(name.substring(0, name.length() - 5) + ".build.toml");
                List<JarRow> jars = new ArrayList<>();
                boolean built = Files.isRegularFile(build);
                if (built) {
                    for (String sha : Registry.jarHashes(registry.read(build))) {
                        if (verifier.isPresent()) {
                            Verifier.Result r = verifier.get().verifyHash(sha);
                            String detail = r.messages().isEmpty() ? "" : r.messages().get(r.messages().size() - 1);
                            jars.add(new JarRow(sha, r.status(), detail));
                        } else {
                            jars.add(new JarRow(sha, Verifier.Status.UNVERIFIED, "not checked: no registry key"));
                        }
                    }
                }
                List<Map<String, Object>> reviews = new ArrayList<>();
                for (Path r : list(modDir.resolve("reviews").resolve(version))) {
                    if (r.getFileName().toString().endsWith(".toml")) {
                        reviews.add(registry.read(r));
                    }
                }
                String type = Optional.ofNullable(Toml.string(Toml.table(rel, "build"), "type")).orElse("rebuild");
                Path analysisFile = f.resolveSibling(name.substring(0, name.length() - 5) + ".analysis.toml");
                Map<String, Object> analysis = Files.isRegularFile(analysisFile) ? registry.read(analysisFile) : null;
                releases.add(new ReleaseRow(version, str(rel, "commit"), type, Releases.artifacts(rel), built, jars,
                        reviews, analysis));
            }
            List<Map<String, Object>> flags = new ArrayList<>();
            for (Path fl : list(modDir.resolve("flags"))) {
                if (fl.getFileName().toString().endsWith(".toml")) {
                    flags.add(registry.read(fl));
                }
            }
            mods.add(new ModRow(id, orElse(str(m, "name"), id), str(m, "author"), str(m, "publisher"), str(m, "source"),
                    str(m, "license"), str(m, "workshop_id"), orElse(str(m, "status"), "active"), releases, flags,
                    Files.isRegularFile(notesDir.resolve(id + ".md"))));
        }
        return mods;
    }

    /** Renders the page. {@code repoUrl} is the registry's GitHub URL, used for links to files. */
    public static String render(List<ModRow> mods, Instant now, String repoUrl, boolean keyChecked) {
        StringBuilder h = new StringBuilder();
        h.append("""
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>BiteCheck Registry</title>
                <style>
                :root{--bg:#fbfaf7;--fg:#1d1d1b;--muted:#6b6a64;--line:#e3e1da;--card:#fff;--ok:#1e7b34;--warn:#9a6700;
                --bad:#c62828;--info:#3a5a9b}
                @media (prefers-color-scheme:dark){:root{--bg:#161614;--fg:#ecebe6;--muted:#a3a29b;--line:#34332f;
                --card:#1f1f1c;--ok:#5cc274;--warn:#e0b04a;--bad:#ff7a6e;--info:#8fb0f0}}
                *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);
                font:15px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif}
                main{max-width:980px;margin:0 auto;padding:24px 16px 64px}
                h1{font-size:28px;margin:0 0 4px}h2{font-size:19px;margin:0}h3{font-size:15px;margin:16px 0 6px}
                p.lead{color:var(--muted);margin:0 0 20px}a{color:var(--info)}
                .bar{display:flex;gap:8px;flex-wrap:wrap;margin:0 0 20px}
                input{flex:1;min-width:220px;padding:9px 12px;border:1px solid var(--line);border-radius:8px;
                background:var(--card);color:var(--fg);font:inherit}
                .mod{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:16px;margin:0 0 14px}
                .head{display:flex;gap:10px;align-items:baseline;flex-wrap:wrap}
                .meta{color:var(--muted);font-size:13px;margin:4px 0 0}
                .badge{font-size:12px;font-weight:600;padding:2px 8px;border-radius:99px;border:1px solid currentColor}
                .verified{color:var(--ok)}.flagged,.pending,.building,.in-review{color:var(--warn)}
                .blocked,.invalid,.suspended,.withdrawn{color:var(--bad)}
                table{border-collapse:collapse;width:100%;font-size:13px}td,th{text-align:left;padding:5px 6px;
                border-top:1px solid var(--line);vertical-align:top}th{color:var(--muted);font-weight:500}
                code{font:12px ui-monospace,SFMono-Regular,Menlo,monospace;word-break:break-all}
                .scroll{overflow-x:auto}.empty{color:var(--muted)}footer{color:var(--muted);font-size:13px;margin-top:32px}
                </style>
                </head>
                <body><main>
                <h1>BiteCheck Registry</h1>
                <p class="lead">Java mods for Project Zomboid with public source code, reproducible or source-checked
                builds and signed reviews. The BiteCheck launcher loads <b>verified</b> jars without a question and warns
                about everything else.</p>
                <div class="bar"><input id="q" type="search" placeholder="Search by name, author or SHA-256 of a jar"
                aria-label="Search"></div>
                """);
        if (!keyChecked) {
            h.append("<p class=\"empty\">The registry key is not set up yet, so no jar is verified.</p>\n");
        }
        if (mods.isEmpty()) {
            h.append("<p class=\"empty\">No mods in the registry yet.</p>\n");
        }
        for (ModRow m : mods) {
            String badge = m.badge();
            StringBuilder search = new StringBuilder(m.id() + " " + m.name() + " " + nz(m.author()));
            m.releases().forEach(r -> r.jars().forEach(j -> search.append(' ').append(j.sha256())));
            h.append("<section class=\"mod\" id=\"").append(esc(m.id())).append("\" data-search=\"")
                    .append(esc(search.toString().toLowerCase())).append("\">\n");
            h.append("<div class=\"head\"><h2>").append(esc(m.name())).append("</h2><span class=\"badge ")
                    .append(badge.replace(' ', '-')).append("\">").append(esc(badge)).append("</span></div>\n");
            h.append("<p class=\"meta\">").append(esc(m.id())).append(" · by ").append(esc(nz(m.author())));
            if (m.publisher() != null) {
                h.append(" · published to the registry by ").append(esc(m.publisher()));
            }
            h.append(" · ").append(esc(nz(m.license())));
            if (m.source() != null) {
                h.append(" · <a href=\"").append(esc(m.source())).append("\">source</a>");
            }
            if (m.workshopId() != null && !m.workshopId().isBlank()) {
                h.append(" · <a href=\"https://steamcommunity.com/sharedfiles/filedetails/?id=")
                        .append(esc(m.workshopId())).append("\">Workshop</a>");
            }
            if (m.hasNotes()) {
                h.append(" · <a href=\"").append(esc(repoUrl)).append("/blob/main/docs/reviews/").append(esc(m.id()))
                        .append(".md\">review notes</a>");
            }
            h.append("</p>\n");
            if ("pending".equals(m.status())) {
                h.append("<p class=\"meta\">Prepared, not yet signed by the publisher. Nothing of this mod is verified yet.</p>\n");
            }
            String modId = m.id();
            for (ReleaseRow r : m.releases()) {
                h.append("<h3>").append(esc(r.version())).append(" <span class=\"meta\">· ")
                        .append("published".equals(r.type()) ? "published jars, reviewed against source"
                                : "rebuilt by CI")
                        .append(" · commit <code>").append(esc(shortCommit(r.commit()))).append("</code></span></h3>\n");
                h.append("<div class=\"scroll\"><table><tr><th>Jar</th><th>SHA-256</th><th>Status</th></tr>\n");
                if (r.jars().isEmpty()) {
                    for (String a : r.artifacts()) {
                        h.append("<tr><td><code>").append(esc(a)).append("</code></td><td class=\"empty\">")
                                .append(r.built() ? "-" : "not built yet").append("</td><td class=\"empty\">-</td></tr>\n");
                    }
                } else {
                    for (int i = 0; i < r.jars().size(); i++) {
                        JarRow j = r.jars().get(i);
                        String name = i < r.artifacts().size() ? r.artifacts().get(i) : "";
                        String cls = j.status().name().toLowerCase();
                        h.append("<tr><td><code>").append(esc(name)).append("</code></td><td><code>")
                                .append(esc(j.sha256())).append("</code></td><td><span class=\"").append(cls)
                                .append("\">").append(esc(j.status().name())).append("</span>");
                        if (j.status() != Verifier.Status.VERIFIED && !j.detail().isBlank()) {
                            h.append("<br><span class=\"meta\">").append(esc(j.detail())).append("</span>");
                        }
                        h.append("</td></tr>\n");
                    }
                }
                h.append("</table></div>\n");
                if (r.analysis() != null) {
                    Map<String, Object> an = r.analysis();
                    String verdict = nz(str(an, "verdict"));
                    h.append("<p class=\"meta\">AI pre-analysis (").append(esc(nz(str(an, "model")))).append(", ")
                            .append(esc(date(str(an, "analyzed_at")))).append("): <span class=\"")
                            .append(verdict.equals("no-concerns") ? "verified" : "flagged").append("\">")
                            .append(esc(verdict)).append("</span> - ").append(esc(nz(str(an, "summary"))))
                            .append(" · <a href=\"").append(esc(repoUrl)).append("/blob/main/registry/mods/")
                            .append(esc(modId)).append("/releases/").append(esc(r.version()))
                            .append(".analysis.md\">report</a> · an aid for reviewers, not an approval</p>\n");
                }
                if (!r.reviews().isEmpty()) {
                    h.append("<div class=\"scroll\"><table><tr><th>Reviewer</th><th>Verdict</th><th>Date</th><th>Notes</th></tr>\n");
                    for (Map<String, Object> rv : r.reviews()) {
                        h.append("<tr><td>").append(esc(nz(str(rv, "reviewer")))).append("</td><td>")
                                .append(esc(nz(str(rv, "verdict")))).append(" (").append(esc(nz(str(rv, "scope"))))
                                .append(")</td><td>").append(esc(date(str(rv, "reviewed_at")))).append("</td><td>")
                                .append(esc(nz(str(rv, "notes")))).append("</td></tr>\n");
                    }
                    h.append("</table></div>\n");
                }
            }
            if (!m.flags().isEmpty()) {
                h.append("<h3>Flags</h3><div class=\"scroll\"><table><tr><th>Version</th><th>Severity</th><th>Status</th><th>Description</th></tr>\n");
                for (Map<String, Object> f : m.flags()) {
                    h.append("<tr><td>").append(esc(nz(str(f, "version")))).append("</td><td>")
                            .append(esc(nz(str(f, "severity")))).append("</td><td>").append(esc(nz(str(f, "status"))))
                            .append("</td><td>").append(esc(nz(str(f, "description")))).append("</td></tr>\n");
                }
                h.append("</table></div>\n");
            }
            h.append("</section>\n");
        }
        h.append("<footer>Generated ").append(esc(now.truncatedTo(ChronoUnit.SECONDS).toString()))
                .append(" from the registry files · <a href=\"").append(esc(repoUrl))
                .append("\">BiteCheck on GitHub</a> (GPL-3.0) · submit a mod: see CONTRIBUTING.md</footer>\n");
        h.append("""
                </main>
                <script>
                const q=document.getElementById('q');
                q.addEventListener('input',()=>{const t=q.value.trim().toLowerCase();
                for(const s of document.querySelectorAll('section.mod')){s.hidden=t!==''&&!s.dataset.search.includes(t);}});
                </script>
                </body></html>
                """);
        return h.toString();
    }

    static String esc(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static String shortCommit(String commit) {
        return commit == null ? "?" : commit.substring(0, Math.min(12, commit.length()));
    }

    private static String date(String iso) {
        return iso == null ? "" : iso.length() >= 10 ? iso.substring(0, 10) : iso;
    }

    private static String str(Map<String, Object> doc, String key) {
        return Toml.string(doc, key);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String orElse(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }

    private static List<Path> list(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.sorted().toList();
        }
    }
}
