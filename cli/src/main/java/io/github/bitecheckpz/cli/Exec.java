package io.github.bitecheckpz.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Runs external tools (git, gh) and reports their own error messages. */
final class Exec {

    private Exec() {
    }

    record Result(int code, String output) {
    }

    /** Runs a command in {@code dir} (or the current directory) and returns exit code and output. */
    static Result run(Path dir, String... command) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(List.of(command)).redirectErrorStream(true);
        if (dir != null) {
            builder.directory(dir.toFile());
        }
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IOException(command[0] + " is needed for this command and was not found"
                    + (command[0].equals("gh") ? " (GitHub CLI: https://cli.github.com, then 'gh auth login')" : ""), e);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        return new Result(process.waitFor(), output);
    }

    /** Runs a command and returns its output; fails with the tool's own message if it does not succeed. */
    static String check(Path dir, String... command) throws IOException, InterruptedException {
        Result r = run(dir, command);
        if (r.code() != 0) {
            throw new IOException(command[0] + " " + command[1] + " failed"
                    + (r.output().isEmpty() ? "" : ":\n" + r.output()));
        }
        return r.output();
    }

    static String git(Path dir, String... args) throws IOException, InterruptedException {
        return check(dir, prepend("git", args));
    }

    static Result tryGit(Path dir, String... args) throws IOException, InterruptedException {
        return run(dir, prepend("git", args));
    }

    /** Runs git and writes its standard output (e.g. a binary blob of 'git show') to a file. */
    static boolean gitToFile(Path dir, Path file, String... args) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(prepend("git", args))
                .redirectOutput(file.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
        if (dir != null) {
            builder.directory(dir.toFile());
        }
        return builder.start().waitFor() == 0;
    }

    static String gh(Path dir, String... args) throws IOException, InterruptedException {
        return check(dir, prepend("gh", args));
    }

    /** Runs gh and writes its standard output (e.g. a downloaded zip) to a file. */
    static boolean ghToFile(Path file, String... args) throws IOException, InterruptedException {
        try {
            return new ProcessBuilder(prepend("gh", args)).redirectOutput(file.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start().waitFor() == 0;
        } catch (IOException e) {
            throw new IOException("gh is needed for this command and was not found"
                    + " (GitHub CLI: https://cli.github.com, then 'gh auth login')", e);
        }
    }

    private static String[] prepend(String first, String... rest) {
        List<String> all = new ArrayList<>(List.of(first));
        all.addAll(List.of(rest));
        return all.toArray(String[]::new);
    }
}
