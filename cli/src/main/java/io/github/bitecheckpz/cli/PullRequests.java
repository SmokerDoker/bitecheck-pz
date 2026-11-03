package io.github.bitecheckpz.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/** Opens pull requests against the registry, with the GitHub CLI. */
final class PullRequests {

    private PullRequests() {
    }

    /**
     * Pushes HEAD of the registry clone at {@code root} to a new branch (in the registry if you may
     * push there, otherwise in your fork) and opens a
     * pull request with {@code title}. Returns the pull request's URL.
     */
    static String open(Path root, String title) throws IOException, InterruptedException {
        String upstream = Exec.gh(root, "repo", "view", "--json", "nameWithOwner", "--jq", ".nameWithOwner");
        return open(root, upstream, title, "Created with `bcz`.");
    }

    static String open(Path root, String upstream, String title, String body) throws IOException, InterruptedException {
        String login = Exec.gh(root, "api", "user", "--jq", ".login");
        // With write access (e.g. the registry's owner, who cannot fork their own repository) the
        // branch goes to the registry itself; pull request workflows run read-only either way.
        boolean canPush = "true".equals(Exec.gh(root, "api", "repos/" + upstream, "--jq", ".permissions.push"));
        String owner = canPush ? upstream.substring(0, upstream.indexOf('/')) : login;
        if (!canPush) {
            // With a repository argument gh adds no remote (and rejects --remote).
            Exec.gh(root, "repo", "fork", upstream, "--clone=false");
        }
        String repo = upstream.substring(upstream.indexOf('/') + 1);
        String slug = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.]+", "-").replaceAll("^-+|-+$", "");
        String branch = "bcz/" + slug.substring(0, Math.min(50, slug.length())) + "-"
                + Long.toString(System.currentTimeMillis() / 1000, 36);
        // gh provides the credentials, without changing the user's git configuration.
        Exec.git(root, "-c", "credential.helper=", "-c", "credential.helper=!gh auth git-credential",
                "push", "--quiet", "https://github.com/" + owner + "/" + repo + ".git", "HEAD:refs/heads/" + branch);
        return Exec.gh(root, "pr", "create", "--repo", upstream, "--head", owner + ":" + branch,
                "--title", title, "--body", body);
    }
}
