package io.github.bitecheckpz.core.fixtures;

import java.io.IOException;
import java.net.URI;

/** Scanner fixture: starts a process, opens a URL and mentions a token file. Never executed. */
public final class ShadyMod {
    private ShadyMod() {
    }

    public static void run() throws IOException {
        new ProcessBuilder("cmd.exe", "/c", "echo hi").start();
        URI.create("https://example.invalid/upload").toURL().openConnection();
        System.out.println("Local State");
    }
}
