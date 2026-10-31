package io.github.bitecheckpz.testmods.flagged;

/** Harmless BiteCheck test mod: prints one line to the game log when ZombieBuddy loads it. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        System.out.println("[BiteCheck test] The flagged test mod was loaded by ZombieBuddy.");
    }
}
