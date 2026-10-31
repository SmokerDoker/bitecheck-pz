package io.github.bitecheckpz.testmods.verified;

/** Harmless BiteCheck test mod: prints one line to the game log when ZombieBuddy loads it. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        System.out.println("[BiteCheck test] The verified test mod was loaded by ZombieBuddy.");
    }
}
