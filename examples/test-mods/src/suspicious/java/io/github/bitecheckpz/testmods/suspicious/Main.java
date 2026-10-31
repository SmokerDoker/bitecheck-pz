package io.github.bitecheckpz.testmods.suspicious;

import java.io.IOException;

/**
 * Harmless BiteCheck test mod for the local scan. {@link #neverCalled()} mentions a process start
 * so BiteCheck's scanner has something to find; nothing ever calls it.
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        System.out.println("[BiteCheck test] The suspicious test mod was loaded by ZombieBuddy.");
    }

    /** Never called. Only here so the local scan finds a "process" rule. */
    static Process neverCalled() throws IOException {
        return new ProcessBuilder("echo", "this never runs").start();
    }
}
