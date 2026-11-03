package io.github.bitecheckpz.cli;

import io.github.bitecheckpz.cli.Main.Args;
import io.github.bitecheckpz.cli.Main.UsageException;
import io.github.bitecheckpz.core.Hashing;
import io.github.bitecheckpz.core.Keys;
import io.github.bitecheckpz.core.Lint;
import io.github.bitecheckpz.core.Registry;
import io.github.bitecheckpz.core.Releases;
import io.github.bitecheckpz.core.Signatures;
import io.github.bitecheckpz.core.Toml;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Commands around people and their GitHub accounts: {@code login}, {@code submit} and
 * {@code fetch} (mod authors), {@code submitter-check} (CI), {@code trust} and {@code reviewers}
 * (maintainers).
 */
final class AccountCommands {

    static final String REGISTRY_REPO = "SmokerDoker/bitecheck-pz";
    private static final Pattern GITHUB_REPO =
            Pattern.compile("github\\.com[:/]([A-Za-z0-9-]+)/([A-Za-z0-9._-]+?)(?:\\.git)?/?$");
    private static final List<String> BUILD_KEYS =
            List.of("type", "jdk", "gradle", "command", "artifact", "artifacts", "game_branch", "workshop_id",
                    "workshop_artifacts");

    private AccountCommands() {
    }

    // ---- login ----

    static int login(Args a, PrintStream out) throws IOException, GeneralSecurityException, InterruptedException {
        String github;
        try {
            github = Exec.gh(null, "api", "user", "--jq", ".login");
        } catch (IOException e) {
            throw new IOException("log in to GitHub first with 'gh auth login'\n" + e.getMessage(), e);
        }
        String name = a.option("as").orElse(github.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-")
                .replaceAll("^-+", ""));
        Registry.requireName(name, "name");
        Path keys = ModCommands.home().resolve("keys");
        Path privateFile = keys.resolve(name + ".key");
        Path publicFile = keys.resolve(name + ".pub");
        boolean created = !Files.isRegularFile(privateFile);
        PublicKey publicKey;
        if (created) {
            KeyPair pair = Keys.generate();
            Keys.writePrivate(privateFile, pair.getPrivate(), Keys.keyId(pair.getPublic()));
            publicKey = pair.getPublic();
        } else {
            publicKey = Keys.publicKey(Keys.readPrivate(privateFile).key());
        }
        Files.writeString(publicFile, Keys.encodePublic(publicKey, name), StandardCharsets.UTF_8);
        Files.writeString(ModCommands.home().resolve("config.toml"),
                "name = " + Toml.quote(name) + "\ngithub = " + Toml.quote(github) + "\n", StandardCharsets.UTF_8);
        out.println("Logged in as '" + name + "' (GitHub " + github + ", key " + Keys.keyId(publicKey) + ")"
                + (created ? ". New key " + privateFile + ": back it up." : "."));
        return Main.OK;
    }

    // ---- submit ----

    /**
     * Submits a release of the mod in the current directory: reads bitecheck.toml, writes and signs
     * the release file in a fresh clone of the registry, and opens a pull request from your fork.
     */
    static int submit(Args a, PrintStream out) throws IOException, GeneralSecurityException, Toml.TomlException,
            UsageException, InterruptedException {
        Path project = Path.of(a.option("dir").orElse(".")).toAbsolutePath().normalize();
        Path metaFile = project.resolve("bitecheck.toml");
        if (!Files.isRegularFile(metaFile)) {
            throw new IOException("no bitecheck.toml in " + project + " (see the mod template)");
        }
        Map<String, Object> meta = Toml.parse(metaFile);
        String id = Toml.string(meta, "id");
        Registry.requireName(id, "mod id");
        Matcher remote = GITHUB_REPO.matcher(Exec.git(project, "remote", "get-url", "origin"));
        if (!remote.find()) {
            throw new IOException("the mod's 'origin' remote must be a GitHub repository");
        }
        String sourceRepo = remote.group(1) + "/" + remote.group(2);
        String commit = a.option("commit").orElse(Exec.git(project, "rev-parse", "HEAD"));
        if (!commit.matches("[0-9a-f]{40}")) {
            throw new IOException("commit must be a full 40-character hash");
        }
        String version = version(a, project, commit);
        if (!commit.equals(Exec.tryGit(project, "rev-parse", commit + "^{commit}").output())) {
            throw new IOException("commit " + commit.substring(0, 7) + " is not in this repository");
        }
        var published = Exec.run(project, "gh", "api", "repos/" + sourceRepo + "/commits/" + commit, "--jq", ".sha");
        if (published.code() != 0) {
            String why = published.output();
            throw new IOException(why.contains("HTTP 404") || why.contains("HTTP 422")
                    ? "commit " + commit.substring(0, 7) + " is not public on " + sourceRepo + " yet: push it first"
                    : "could not check commit " + commit.substring(0, 7) + " on GitHub:\n" + why);
        }

        Path keyFile = a.option("key").map(Path::of).orElse(null);
        String github = Exec.gh(null, "api", "user", "--jq", ".login");
        String upstream = upstream(a);
        Path work = ModCommands.home().resolve("submit").resolve(id + "-" + version);
        deleteTree(work);
        Files.createDirectories(work.getParent());
        Exec.git(null, "clone", "--quiet", "https://github.com/" + upstream + ".git", work.toString());
        Registry registry = new Registry(work);

        // Who signs: --as, the saved login, the registered author with this key, or the GitHub name.
        if (keyFile == null) {
            keyFile = ModCommands.home().resolve("keys").resolve(ModCommands.me(a) + ".key");
        }
        Keys.PrivateKeyInfo key = Keys.readPrivate(keyFile);
        PublicKey publicKey = Keys.publicKey(key.key());
        String me = a.option("as").orElse(null);
        if (me == null && a.option("key").isEmpty()) {
            me = ModCommands.me(a);
        }
        if (me == null) {
            me = authorWithKey(registry, Keys.keyId(publicKey));
        }
        if (me == null) {
            me = github.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-").replaceAll("^-+", "");
        }
        Registry.requireName(me, "name");

        Path modDir = registry.modDir(id);
        Path modFile = modDir.resolve("mod.toml");
        String source = "https://github.com/" + sourceRepo;
        if (Files.isRegularFile(modFile)) {
            Map<String, Object> modInfo = registry.read(modFile);
            if (!me.equals(Registry.signer(modInfo))) {
                throw new IOException(id + " is published by '" + Registry.signer(modInfo) + "', not by '" + me + "'");
            }
            if (!source.equalsIgnoreCase(String.valueOf(Toml.string(modInfo, "source")))) {
                throw new IOException(id + " is registered with source " + Toml.string(modInfo, "source"));
            }
        } else {
            Files.createDirectories(modDir);
            Files.writeString(modFile, "id = " + Toml.quote(id) + "\nname = " + Toml.quote(required(meta, "name"))
                    + "\nauthor = " + Toml.quote(me) + "\nsource = " + Toml.quote(source)
                    + "\nlicense = " + Toml.quote(required(meta, "license"))
                    + "\nworkshop_id = " + Toml.quote(String.valueOf(Toml.string(meta, "workshop_id") == null ? ""
                            : Toml.string(meta, "workshop_id")))
                    + "\nstatus = \"active\"\n", StandardCharsets.UTF_8);
        }
        Path releaseFile = registry.releaseFile(id, version);
        if (Files.exists(releaseFile)) {
            throw new IOException(id + " " + version + " was already submitted");
        }
        Files.createDirectories(releaseFile.getParent());
        Files.writeString(releaseFile, releaseText(meta, id, version, commit), StandardCharsets.UTF_8);
        Signatures.sign(releaseFile, key);

        // First submission: register the key and link it to this GitHub account.
        List<String> paths = new ArrayList<>(List.of("registry/mods/" + id));
        Path authorKey = work.resolve("keys/authors/" + me + ".pub");
        if (Files.isRegularFile(authorKey)) {
            if (!Keys.readPublic(authorKey).id().equals(Keys.keyId(publicKey))) {
                throw new IOException("'" + me + "' is registered with a different key; ask a maintainer to replace it");
            }
        } else {
            Files.createDirectories(authorKey.getParent());
            Files.writeString(authorKey, Keys.encodePublic(publicKey, me), StandardCharsets.UTF_8);
            paths.add("keys/authors/" + me + ".pub");
        }
        String linked = registry.githubAccount(me).orElse(null);
        if (linked == null) {
            Path accounts = work.resolve("keys/accounts.toml");
            String before = Files.isRegularFile(accounts) ? Files.readString(accounts, StandardCharsets.UTF_8) : "";
            Files.writeString(accounts, before + (before.isEmpty() || before.endsWith("\n\n") ? "" : "\n")
                    + "[accounts." + me + "]\ngithub = " + Toml.quote(github) + "\n", StandardCharsets.UTF_8);
            paths.add("keys/accounts.toml");
        } else if (!linked.equalsIgnoreCase(github)) {
            throw new IOException("'" + me + "' belongs to GitHub user " + linked + "; pass --as <your name>");
        }

        List<String> problems = Lint.check(registry, registry.registryKey()).stream()
                .filter(p -> p.startsWith("registry/mods/" + id + "/") || p.startsWith("keys/")).toList();
        if (!problems.isEmpty()) {
            throw new IOException("the submission is not valid:\n  " + String.join("\n  ", problems));
        }
        List<String> add = new ArrayList<>(List.of("add", "--"));
        add.addAll(paths);
        Exec.git(work, add.toArray(String[]::new));
        Exec.git(work, "-c", "user.name=" + github, "-c", "user.email=" + github + "@users.noreply.github.com",
                "commit", "--quiet", "-m", "Submit " + id + " " + version);
        String url = PullRequests.open(work, upstream, "Submit " + id + " " + version,
                "Release " + version + " of " + id + " from " + source + " at commit " + commit
                        + ", submitted with `bcz submit` by @" + github + ".");
        out.println("Submitted " + id + " " + version + ": " + url);
        return Main.OK;
    }

    /** --version, else the tag on the commit without a leading "v". */
    private static String version(Args a, Path project, String commit) throws IOException, UsageException,
            InterruptedException {
        String version = a.option("version").orElse(null);
        if (version == null) {
            Exec.Result tag = Exec.tryGit(project, "describe", "--tags", "--exact-match", commit);
            if (tag.code() != 0) {
                throw new UsageException("tag the release first (git tag v1.0.0) or pass --version");
            }
            version = tag.output().replaceFirst("^v", "");
        }
        Registry.requireVersion(version);
        return version;
    }

    /** The registry's GitHub repository: --registry-repo, $BITECHECK_REGISTRY or the official one. */
    private static String upstream(Args a) throws UsageException {
        String upstream = a.option("registry-repo").or(() -> Optional.ofNullable(System.getenv("BITECHECK_REGISTRY"))
                .filter(s -> !s.isBlank())).orElse(REGISTRY_REPO);
        if (!upstream.matches("[A-Za-z0-9-]+/[A-Za-z0-9._-]+")) {
            throw new UsageException("the registry must be given as owner/repository");
        }
        return upstream;
    }

    // ---- fetch ----

    /**
     * Compares the jars of the mod in the current directory with the registry's signed build record.
     * Only if they differ, and only after you confirm, replaces them with the jars CI built.
     */
    static int fetch(Args a, PrintStream out, InputStream in) throws IOException, Toml.TomlException,
            UsageException, InterruptedException {
        Path project = Path.of(a.option("dir").orElse(".")).toAbsolutePath().normalize();
        Path metaFile = project.resolve("bitecheck.toml");
        if (!Files.isRegularFile(metaFile)) {
            throw new IOException("no bitecheck.toml in " + project + " (see the mod template)");
        }
        String id = Toml.string(Toml.parse(metaFile), "id");
        Registry.requireName(id, "mod id");
        String version = version(a, project, a.option("commit").orElse("HEAD"));
        String upstream = upstream(a);
        Registry registry;
        if (a.option("registry").isPresent()) {
            registry = new Registry(Path.of(a.option("registry").get()));
        } else {
            Path cache = ModCommands.home().resolve("registries").resolve(upstream.replace('/', '-'));
            if (Files.isDirectory(cache.resolve(".git"))) {
                Exec.git(cache, "pull", "--quiet", "--ff-only");
            } else {
                Files.createDirectories(cache.getParent());
                Exec.git(null, "clone", "--quiet", "--depth", "1", "https://github.com/" + upstream + ".git",
                        cache.toString());
            }
            registry = new Registry(cache);
        }

        Path buildFile = registry.buildFile(id, version);
        if (!Files.isRegularFile(buildFile)) {
            out.println(id + " " + version + ": no build record yet (CI writes it after the release is merged)");
            return Main.ERROR;
        }
        Keys.PublicKeyInfo registryKey = registry.registryKey()
                .orElseThrow(() -> new IOException("the registry has no keys/registry.pub"));
        Signatures.Check check = Signatures.verify(buildFile, registryKey);
        if (check != Signatures.Check.VALID) {
            throw new IOException(registry.root().relativize(buildFile) + ": " + check.description());
        }
        Map<String, Object> build = registry.read(buildFile);
        if ("workshop".equals(Toml.string(build, "method"))) {
            out.println(id + " " + version + ": players get the verified jar from the Steam Workshop, nothing to fetch");
            return Main.OK;
        }
        List<String> paths = Releases.artifacts(registry.read(registry.releaseFile(id, version)));
        List<String> hashes = new ArrayList<>();
        if ("published".equals(Toml.string(build, "method")) && build.get("artifact_sha256") instanceof List<?> list) {
            list.forEach(o -> hashes.add(String.valueOf(o)));
        } else if (Toml.string(build, "jar_sha256") != null) {
            hashes.add(Toml.string(build, "jar_sha256"));
        }
        if (paths.size() != hashes.size() || !hashes.stream().allMatch(Hashing::isSha256Hex)) {
            throw new IOException(registry.root().relativize(buildFile) + " does not match the release's artifacts");
        }

        List<Integer> differ = new ArrayList<>();
        for (int i = 0; i < paths.size(); i++) {
            Path local = project.resolve(paths.get(i));
            String sha = Files.isRegularFile(local) ? Hashing.sha256Hex(local) : null;
            if (!hashes.get(i).equals(sha)) {
                differ.add(i);
                out.println(paths.get(i) + ": local " + (sha == null ? "missing" : sha.substring(0, 12))
                        + ", verified " + hashes.get(i).substring(0, 12));
            }
        }
        if (differ.isEmpty()) {
            out.println(id + " " + version + ": local jar" + (paths.size() == 1 ? " matches" : "s match")
                    + " the verified build");
            return Main.OK;
        }
        out.print("Replace with the jar" + (differ.size() == 1 ? "" : "s") + " CI built? [y/N] ");
        out.flush();
        String answer = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).readLine();
        if (answer == null || !answer.strip().toLowerCase(Locale.ROOT).startsWith("y")) {
            out.println("Nothing changed.");
            return Main.ERROR;
        }

        // CI's jars: the artifacts of the build jobs (kept 30 days), checked against the build record.
        String key = (id + "-" + version).replaceAll("[^A-Za-z0-9.-]", "-");
        Path tmp = Files.createTempDirectory("bcz-fetch");
        try {
            for (String builder : List.of("a", "b")) {
                String urls = Exec.gh(null, "api", "repos/" + upstream + "/actions/artifacts?per_page=20&name=build-"
                        + builder + "-" + key, "--jq", ".artifacts[] | select(.expired | not) | .archive_download_url");
                for (String url : urls.lines().filter(s -> !s.isBlank()).toList()) {
                    Path zip = tmp.resolve("artifact.zip");
                    if (!Exec.ghToFile(zip, "api", url)) {
                        continue;
                    }
                    Map<Integer, Path> jars = unzipJars(zip, tmp, differ, hashes);
                    if (jars.size() == differ.size()) {
                        for (int i : differ) {
                            Path local = project.resolve(paths.get(i));
                            Files.createDirectories(local.getParent());
                            Files.copy(jars.get(i), local, StandardCopyOption.REPLACE_EXISTING);
                        }
                        out.println("Replaced with the verified build.");
                        return Main.OK;
                    }
                }
            }
        } finally {
            deleteTree(tmp);
        }
        throw new IOException("CI's jars for " + id + " " + version + " are no longer available (kept 30 days);"
                + " build with the current game version and run 'bcz fetch' again");
    }

    /** The jars <i>.jar of a build artifact whose SHA-256 is the verified one, by artifact index. */
    private static Map<Integer, Path> unzipJars(Path zip, Path dir, List<Integer> wanted, List<String> hashes)
            throws IOException {
        Map<Integer, Path> found = new TreeMap<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                Matcher m = Pattern.compile("(\\d+)\\.jar").matcher(entry.getName());
                if (m.matches() && wanted.contains(Integer.parseInt(m.group(1)))) {
                    int i = Integer.parseInt(m.group(1));
                    Path jar = dir.resolve(i + ".jar");
                    Files.copy(in, jar, StandardCopyOption.REPLACE_EXISTING);
                    if (hashes.get(i).equals(Hashing.sha256Hex(jar))) {
                        found.put(i, jar);
                    }
                }
            }
        }
        return found;
    }

    private static String releaseText(Map<String, Object> meta, String id, String version, String commit) {
        StringBuilder text = new StringBuilder();
        text.append("mod = ").append(Toml.quote(id)).append("\nversion = ").append(Toml.quote(version))
                .append("\ncommit = ").append(Toml.quote(commit)).append("\n\n[build]\n");
        Map<String, Object> build = Toml.table(meta, "build");
        for (String k : BUILD_KEYS) {
            if (build.containsKey(k)) {
                text.append(k).append(" = ").append(value(build.get(k))).append('\n');
            }
        }
        Map<String, Object> compat = Toml.table(meta, "compat");
        if (!compat.isEmpty()) {
            text.append("\n[compat]\n");
            compat.forEach((k, v) -> text.append(k).append(" = ").append(value(v)).append('\n'));
        }
        return text.toString();
    }

    private static String value(Object v) {
        if (v instanceof List<?> list) {
            return "[" + String.join(", ", list.stream().map(o -> Toml.quote(String.valueOf(o))).toList()) + "]";
        }
        return v instanceof String s ? Toml.quote(s) : String.valueOf(v);
    }

    private static String required(Map<String, Object> meta, String key) throws IOException {
        String value = Toml.string(meta, key);
        if (value == null || value.isBlank()) {
            throw new IOException("bitecheck.toml needs " + key);
        }
        return value;
    }

    private static String authorWithKey(Registry registry, String keyId) throws IOException {
        Path dir = registry.root().resolve("keys/authors");
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".pub")).sorted().toList()) {
                try {
                    if (Keys.readPublic(f).id().equals(keyId)) {
                        return f.getFileName().toString().replaceFirst("\\.pub$", "");
                    }
                } catch (GeneralSecurityException e) {
                    // not a key: lint reports it
                }
            }
        }
        return null;
    }

    // ---- submitter-check (CI) ----

    /**
     * Checks that a pull request only changes files that belong to its author's GitHub account:
     * their own mods and releases, their own reviews, and their own new key. Anyone may add a flag.
     * Everything else - including changing or removing a flag - is listed for a maintainer.
     */
    static int submitterCheck(Args a, PrintStream out) throws IOException, Toml.TomlException, UsageException,
            InterruptedException {
        Path root = Path.of(a.option("registry").orElse(".")).toAbsolutePath();
        String login = a.requiredOption("login");
        String base = a.requiredOption("base");
        Map<String, Object> baseAccounts = baseToml(root, base, "keys/accounts.toml");
        Map<String, Object> headAccounts = Files.isRegularFile(root.resolve("keys/accounts.toml"))
                ? Toml.parse(root.resolve("keys/accounts.toml")) : Map.of();
        List<String> errors = new ArrayList<>();
        List<String> maintainer = new ArrayList<>();
        Pattern modPath = Pattern.compile("registry/mods/([a-z0-9-]+)/(.+)");
        Pattern review = Pattern.compile("reviews/[^/]+/([a-z0-9-]+)\\.toml(\\.sig)?");
        Pattern release = Pattern.compile("releases/[^/]+\\.toml(\\.sig)?");
        Pattern authorKey = Pattern.compile("keys/authors/([a-z0-9-]+)\\.pub");
        // No rename detection: a moved file counts as deleted at its old path and added at the new one.
        for (String line : Exec.git(root, "diff", "--no-renames", "--name-status", base + "...HEAD").lines().toList()) {
            String change = line.substring(0, 1);
            String path = line.substring(line.indexOf('\t') + 1);
            Matcher m = modPath.matcher(path);
            Matcher k = authorKey.matcher(path);
            if (m.matches()) {
                String rest = m.group(2);
                Matcher r = review.matcher(rest);
                if (rest.startsWith("flags/")) {
                    // Anyone may raise a flag; changing or removing one is a maintainer's decision.
                    if (!change.equals("A")) {
                        maintainer.add(path + ": changes or removes a flag");
                    }
                } else if (r.matches()) {
                    owner(path, r.group(1), login, baseAccounts, headAccounts, errors);
                } else if (rest.matches("releases/[^/]+\\.build\\.toml(\\.sig)?")) {
                    maintainer.add(path + ": build records are written by CI");
                } else if (rest.equals("mod.toml") || release.matcher(rest).matches()) {
                    Map<String, Object> modInfo = baseToml(root, base, "registry/mods/" + m.group(1) + "/mod.toml");
                    if (modInfo.isEmpty() && Files.isRegularFile(root.resolve("registry/mods/" + m.group(1) + "/mod.toml"))) {
                        modInfo = Toml.parse(root.resolve("registry/mods/" + m.group(1) + "/mod.toml"));
                    }
                    owner(path, Registry.signer(modInfo), login, baseAccounts, headAccounts, errors);
                } else {
                    maintainer.add(path);
                }
            } else if (k.matches()) {
                if (Exec.tryGit(root, "cat-file", "-e", base + ":" + path).code() == 0) {
                    maintainer.add(path + ": replaces a registered key");
                } else {
                    owner(path, k.group(1), login, baseAccounts, headAccounts, errors);
                }
            } else if (path.equals("keys/accounts.toml")) {
                Map<String, Object> before = Toml.table(baseAccounts, "accounts");
                for (Map.Entry<String, Object> e : Toml.table(headAccounts, "accounts").entrySet()) {
                    if (!e.getValue().equals(before.get(e.getKey()))) {
                        boolean knownName = Exec.tryGit(root, "cat-file", "-e",
                                base + ":keys/authors/" + e.getKey() + ".pub").code() == 0
                                || Exec.tryGit(root, "cat-file", "-e",
                                        base + ":keys/reviewers/" + e.getKey() + ".pub").code() == 0;
                        if (before.containsKey(e.getKey()) || knownName) {
                            // Only a brand-new name (its key added in this PR) may link itself to an account.
                            maintainer.add(path + ": links or changes the account of existing name '" + e.getKey() + "'");
                        } else {
                            owner(path, e.getKey(), login, Map.of(), headAccounts, errors);
                        }
                    }
                }
                before.keySet().stream().filter(n -> !Toml.table(headAccounts, "accounts").containsKey(n))
                        .forEach(n -> maintainer.add(path + ": removes the account of '" + n + "'"));
            } else {
                maintainer.add(path);
            }
        }
        errors.forEach(e -> out.println("error: " + e));
        maintainer.forEach(p -> out.println("maintainer: " + p));
        // Anything not owned by the author fails the check, so a maintainer has to look before merging.
        out.println(!errors.isEmpty() ? errors.size() + " problem(s)"
                : !maintainer.isEmpty() ? "needs a maintainer: the files above do not belong to " + login
                : "OK: every change belongs to " + login);
        return errors.isEmpty() && maintainer.isEmpty() ? Main.OK : Main.ERROR;
    }

    /** The name's GitHub account must be the pull request's author; the base registry wins over the PR. */
    private static void owner(String path, String name, String login, Map<String, Object> baseAccounts,
            Map<String, Object> headAccounts, List<String> errors) {
        if (name == null || !Registry.NAME.matcher(name).matches()) {
            errors.add(path + ": no valid publisher or author name");
            return;
        }
        Map<String, Object> entry = Toml.table(baseAccounts, "accounts." + name);
        if (entry.isEmpty()) {
            entry = Toml.table(headAccounts, "accounts." + name);
        }
        String github = Toml.string(entry, "github");
        if (github == null) {
            errors.add(path + ": '" + name + "' has no GitHub account in keys/accounts.toml");
        } else if (!github.equalsIgnoreCase(login)) {
            errors.add(path + ": belongs to '" + name + "' (GitHub " + github + "), not to " + login);
        }
    }

    private static Map<String, Object> baseToml(Path root, String base, String path)
            throws IOException, Toml.TomlException, InterruptedException {
        Exec.Result r = Exec.tryGit(root, "show", base + ":" + path);
        return r.code() == 0 ? Toml.parse(r.output() + "\n", base + ":" + path) : Map.of();
    }

    // ---- trust and reviewers (maintainers) ----

    /** {@code bcz trust <name>} shows a reviewer's level; {@code bcz trust <name> <level>} sets it. */
    static int trust(Args a, PrintStream out) throws IOException, Toml.TomlException, UsageException,
            InterruptedException {
        Registry registry = ModCommands.registry(a);
        String name = a.positional(0, "reviewer");
        Registry.requireName(name, "reviewer");
        if (a.positionals.size() < 2) {
            out.println(name + ": " + registry.reviewerLevel(name));
            return Main.OK;
        }
        String level = a.positionals.get(1);
        if (!List.of("new", "reviewer", "trusted").contains(level)) {
            throw new UsageException("level must be new, reviewer or trusted");
        }
        ModCommands.pull(registry);
        if (registry.reviewerKey(name).isEmpty()) {
            throw new IOException("no valid key keys/reviewers/" + name + ".pub");
        }
        Path file = registry.root().resolve("keys/trust.toml");
        Map<String, String> levels = new TreeMap<>();
        if (Files.isRegularFile(file)) {
            Toml.table(Toml.parse(file), "reviewers").forEach((n, v) ->
                    levels.put(n, String.valueOf(v instanceof Map<?, ?> m ? m.get("level") : "new")));
        }
        levels.put(name, level);
        StringBuilder text = new StringBuilder();
        levels.forEach((n, l) -> text.append(text.isEmpty() ? "" : "\n").append("[reviewers.").append(n)
                .append("]\nlevel = ").append(Toml.quote(l)).append('\n'));
        Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
        ModCommands.commitAndPush(out, registry, "Trust: " + name + " is " + level, "keys/trust.toml");
        out.println(name + " is now " + level + ".");
        return Main.OK;
    }

    /** Every reviewer with level, GitHub account and how many reviews they signed. */
    static int reviewers(Args a, PrintStream out) throws IOException, Toml.TomlException, UsageException,
            InterruptedException {
        Registry registry = ModCommands.registry(a);
        ModCommands.tryPull(registry);
        Path dir = registry.root().resolve("keys/reviewers");
        List<String> names;
        try (Stream<Path> files = Files.isDirectory(dir) ? Files.list(dir) : Stream.empty()) {
            names = files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".pub"))
                    .map(n -> n.substring(0, n.length() - 4)).sorted(Comparator.naturalOrder()).toList();
        }
        Map<String, int[]> counts = new TreeMap<>();
        Map<String, String> last = new TreeMap<>();
        if (Files.isDirectory(registry.modsDir())) {
            try (Stream<Path> files = Files.walk(registry.modsDir())) {
                for (Path f : files.filter(p -> p.toString().endsWith(".toml")
                        && p.getParent().getParent().getFileName().toString().equals("reviews")).toList()) {
                    Map<String, Object> review = registry.read(f);
                    String who = Toml.string(review, "reviewer");
                    int[] c = counts.computeIfAbsent(who, n -> new int[2]);
                    c[0]++;
                    c[1] += "approve".equals(Toml.string(review, "verdict")) ? 1 : 0;
                    String at = String.valueOf(Toml.string(review, "reviewed_at"));
                    last.merge(who, at, (x, y) -> x.compareTo(y) > 0 ? x : y);
                }
            }
        }
        for (String name : names) {
            int[] c = counts.getOrDefault(name, new int[2]);
            String state = registry.reviewerKey(name).isEmpty() ? "  (key revoked)" : "";
            out.printf("%-20s %-9s %3d reviews, %3d approved  last %-10s  GitHub %s%s%n", name,
                    registry.reviewerLevel(name), c[0], c[1],
                    last.containsKey(name) ? last.get(name).substring(0, Math.min(10, last.get(name).length())) : "-",
                    registry.githubAccount(name).orElse("-"), state);
        }
        return Main.OK;
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                p.toFile().setWritable(true); // git marks pack files read-only on Windows
                Files.delete(p);
            }
        }
    }
}
