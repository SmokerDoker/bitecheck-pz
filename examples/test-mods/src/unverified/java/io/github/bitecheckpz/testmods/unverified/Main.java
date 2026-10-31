package io.github.bitecheckpz.testmods.unverified;

/** Harmless BiteCheck test mod: prints one line to the game log when ZombieBuddy loads it. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        System.out.println("[BiteCheck test] The unverified test mod was loaded by ZombieBuddy.");
    }
}
