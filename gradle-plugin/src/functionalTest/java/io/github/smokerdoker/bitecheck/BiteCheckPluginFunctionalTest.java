package io.github.smokerdoker.bitecheck;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Builds a small mod against a fake game jar and a fake ZombieBuddy.jar with Gradle TestKit. */
class BiteCheckPluginFunctionalTest {

    @TempDir
    Path tmp;

    private Path project;
    private Path gameDir;
    private Path zombieBuddyJar;

    @BeforeEach
    void setUp() throws IOException {
        gameDir = tmp.resolve("Project Zomboid Dedicated Server");
        zombieBuddyJar = tmp.resolve("zb/ZombieBuddy.jar");
        // dedicated server layout: <dir>/java/projectzomboid.jar
        fakeJar(gameDir.resolve("java/projectzomboid.jar"), "zombie/ui/TextManager.java", """
                package zombie.ui;
                public class TextManager {
                    public static final TextManager instance = new TextManager();
                    public void DrawString(double x, double y, String text) { }
                }
                """);
        fakeJar(zombieBuddyJar, "me/zed_0xff/zombie_buddy/Patch.java", """
                package me.zed_0xff.zombie_buddy;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)
                public @interface Patch {
                    String className();
                    String methodName();
                    @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD) @interface OnExit { }
                }
                """);

        project = tmp.resolve("MyMod");
        write(project.resolve("settings.gradle.kts"), "rootProject.name = \"MyMod\"\n");
        write(project.resolve("build.gradle.kts"), """
                plugins { id("io.github.smokerdoker.bitecheck") }
                bitecheck { javaVersion.set(%d) }
                """.formatted(Runtime.version().feature()));
        write(project.resolve("src/main/java/com/example/mymod/Patch_Hello.java"), """
                package com.example.mymod;
                import me.zed_0xff.zombie_buddy.Patch;
                import zombie.ui.TextManager;
                @Patch(className = "zombie.gameStates.MainScreenState", methodName = "renderBackground")
                public class Patch_Hello {
                    @Patch.OnExit
                    public static void exit() { TextManager.instance.DrawString(0, 0, "Hello"); }
                }
                """);
        write(project.resolve("src/mod/42/mod.info"), "name=My Mod\nid=MyMod\n");
    }

    @Test
    void buildsTheModFolderReproducibly() throws IOException {
        BuildResult first = run("bitecheckMod", "--configuration-cache");
        assertTrue(first.getOutput().contains("BUILD SUCCESSFUL"), first.getOutput());
        Path jar = project.resolve("build/bitecheck/42/media/java/client/MyMod.jar");
        assertTrue(Files.isRegularFile(jar), "jar in the mod folder");
        assertTrue(Files.isRegularFile(project.resolve("build/bitecheck/42/mod.info")), "mod files copied");
        assertTrue(Files.isDirectory(project.resolve("build/bitecheck/common")), "common folder");

        List<String> entries = new ArrayList<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            jf.stream().forEach(e -> {
                entries.add(e.getName());
                assertEquals(FIXED_TIME, e.getTime(), "timestamp of " + e.getName());
            });
        }
        assertTrue(entries.contains("com/example/mymod/Patch_Hello.class"), entries.toString());
        assertTrue(entries.stream().noneMatch(n -> n.startsWith("zombie/") || n.startsWith("me/")),
                "game and ZombieBuddy classes are never bundled: " + entries);

        byte[] firstBytes = Files.readAllBytes(jar);
        run("clean", "bitecheckMod");
        assertArrayEquals(firstBytes, Files.readAllBytes(jar), "second build is byte-identical");
    }

    @Test
    void failsClearlyWithoutTheGame() throws IOException {
        BuildResult result = runner("compileJava", "-PpzGameDir=" + tmp.resolve("nothing-here")).buildAndFail();
        assertTrue(result.getOutput().contains("no projectzomboid.jar in"), result.getOutput());
    }

    private BuildResult run(String... tasks) {
        return runner(tasks).build();
    }

    private GradleRunner runner(String... tasks) {
        List<String> args = new ArrayList<>(List.of(tasks));
        if (Stream.of(tasks).noneMatch(t -> t.startsWith("-PpzGameDir="))) {
            args.add("-PpzGameDir=" + gameDir);
        }
        args.add("-PzombieBuddyJar=" + zombieBuddyJar);
        args.add("--stacktrace");
        return GradleRunner.create()
                .withProjectDir(project.toFile())
                .withPluginClasspath()
                .withArguments(args)
                .forwardOutput();
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    /** Compiles one source file and packs the classes into a jar. */
    private void fakeJar(Path jar, String sourcePath, String source) throws IOException {
        Path src = Files.createTempDirectory(tmp, "src");
        Path classes = Files.createTempDirectory(tmp, "classes");
        Path file = src.resolve(sourcePath);
        write(file, source);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        int rc = javac.run(null, null, null, "--release", "17", "-d", classes.toString(), file.toString());
        assertEquals(0, rc, "javac");
        Files.createDirectories(jar.getParent());
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(out);
                Stream<Path> walk = Files.walk(classes)) {
            for (Path p : walk.filter(Files::isRegularFile).sorted().toList()) {
                jos.putNextEntry(new JarEntry(classes.relativize(p).toString().replace('\\', '/')));
                jos.write(Files.readAllBytes(p));
                jos.closeEntry();
            }
        }
    }

    /** Gradle writes this constant time (1980-02-01 00:00 local time) into reproducible archives. */
    private static final long FIXED_TIME =
            new GregorianCalendar(1980, Calendar.FEBRUARY, 1, 0, 0, 0).getTimeInMillis();
}
