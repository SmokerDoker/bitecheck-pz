package io.github.smokerdoker.bitecheck;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.jvm.toolchain.JavaLanguageVersion;

/**
 * Plugin {@code io.github.smokerdoker.bitecheck}: builds a Project Zomboid Java mod for ZombieBuddy
 * reproducibly, so that BiteCheck's CI can rebuild it byte for byte.
 *
 * <ul>
 * <li>applies {@code java}, sets the Java toolchain and {@code --release} to the game's Java version (25);</li>
 * <li>puts the game's {@code projectzomboid.jar} on {@code compileOnly} (never into the mod jar);</li>
 * <li>puts a pinned, SHA-256 checked ZombieBuddy.jar on {@code compileOnly};</li>
 * <li>makes every archive reproducible (no timestamps, fixed order and permissions);</li>
 * <li>task {@code bitecheckMod}: the complete mod folder in {@code build/bitecheck/}, with the jar at
 * {@code <gameVersion>/media/java/<side>/<modName>.jar}.</li>
 * </ul>
 */
public class BiteCheckPlugin implements Plugin<Project> {

    /** Gradle property with the game directory. */
    public static final String GAME_DIR_PROPERTY = "pzGameDir";
    /** Environment variable with the game directory (set by BiteCheck's CI). */
    public static final String GAME_DIR_ENV = "PZ_GAME_DIR";
    /** Gradle property with a local ZombieBuddy.jar. */
    public static final String ZOMBIE_BUDDY_JAR_PROPERTY = "zombieBuddyJar";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaPlugin.class);
        ProviderFactory providers = project.getProviders();
        BiteCheckExtension ext = project.getExtensions().create("bitecheck", BiteCheckExtension.class);

        ext.getJavaVersion().convention(25);
        ext.getGameDir().convention(project.getLayout().getProjectDirectory().dir(
                providers.gradleProperty(GAME_DIR_PROPERTY).orElse(providers.environmentVariable(GAME_DIR_ENV))));
        ext.getModName().convention(project.getName());
        ext.getGameVersion().convention("42");
        ext.getSide().convention("client");
        ext.getModDir().convention(project.getLayout().getProjectDirectory().dir("src/mod"));
        ext.getZombieBuddyVersion().convention(ZombieBuddy.DEFAULT_VERSION);
        ext.getZombieBuddyUrl().convention(ext.getZombieBuddyVersion().map(ZombieBuddy::url));
        ext.getZombieBuddySha256().convention(ext.getZombieBuddyVersion().map(v -> {
            String sha = ZombieBuddy.KNOWN.get(v);
            if (sha == null) {
                throw new GradleException("BiteCheck: no known SHA-256 for ZombieBuddy " + v
                        + ". Set bitecheck { zombieBuddySha256.set(\"<sha256 of ZombieBuddy.jar>\") }.");
            }
            return sha;
        }));
        ext.getZombieBuddyJar().convention(project.getLayout().getProjectDirectory().file(
                providers.gradleProperty(ZOMBIE_BUDDY_JAR_PROPERTY)));

        configureJava(project, ext);
        configureClasspath(project, ext, providers);
        configureArchives(project, ext);
    }

    private static void configureJava(Project project, BiteCheckExtension ext) {
        JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        java.getToolchain().getLanguageVersion().set(ext.getJavaVersion().map(JavaLanguageVersion::of));
        project.getTasks().withType(JavaCompile.class).configureEach(task -> {
            task.getOptions().setEncoding("UTF-8");
            task.getOptions().getRelease().set(ext.getJavaVersion());
        });
    }

    private static void configureClasspath(Project project, BiteCheckExtension ext, ProviderFactory providers) {
        Provider<String> osName = providers.systemProperty("os.name");
        Provider<String> home = providers.systemProperty("user.home");
        Provider<String> programFilesX86 = providers.environmentVariable("ProgramFiles(x86)");

        Callable<File> gameJar = () -> {
            if (ext.getGameDir().isPresent()) {
                Path dir = ext.getGameDir().get().getAsFile().toPath();
                return GameLocator.gameJar(dir).orElseThrow(() -> new GradleException(
                        "BiteCheck: no projectzomboid.jar in " + dir + " (from bitecheck.gameDir, -P" + GAME_DIR_PROPERTY
                                + " or " + GAME_DIR_ENV + "). Looked for: " + String.join(", ", GameLocator.JAR_LOCATIONS)
                                + ". This must be a Project Zomboid Build 42 game or dedicated server directory."))
                        .toFile();
            }
            Map<String, String> env = new HashMap<>();
            if (programFilesX86.isPresent()) {
                env.put("ProgramFiles(x86)", programFilesX86.get());
            }
            Path userHome = Path.of(home.get());
            return GameLocator.autodetect(osName.get(), userHome, env)
                    .flatMap(GameLocator::gameJar)
                    .orElseThrow(() -> new GradleException(notFound(osName.get(), userHome, env)))
                    .toFile();
        };
        project.getDependencies().add("compileOnly", project.files(gameJar));

        TaskProvider<DownloadVerifiedFile> download = project.getTasks().register("bitecheckZombieBuddy",
                DownloadVerifiedFile.class, task -> {
                    task.setGroup("bitecheck");
                    task.setDescription("Downloads the pinned ZombieBuddy.jar (API for compiling) and checks its SHA-256.");
                    task.getUrl().set(ext.getZombieBuddyUrl());
                    task.getSha256().set(ext.getZombieBuddySha256());
                    task.getCacheDir().set(new File(project.getGradle().getGradleUserHomeDir(), "caches/bitecheck"));
                    task.getOffline().set(project.getGradle().getStartParameter().isOffline());
                    task.getOutputFile().set(project.getLayout().getBuildDirectory().file(
                            ext.getZombieBuddyVersion().map(v -> "bitecheck-deps/ZombieBuddy-" + v + ".jar")));
                    task.onlyIf("no local ZombieBuddy.jar is configured", t -> !ext.getZombieBuddyJar().isPresent());
                });
        Callable<Object> zombieBuddy = () -> ext.getZombieBuddyJar().isPresent()
                ? ext.getZombieBuddyJar()
                : download.flatMap(DownloadVerifiedFile::getOutputFile);
        project.getDependencies().add("compileOnly", project.files(zombieBuddy).builtBy(download));
    }

    private static String notFound(String osName, Path home, Map<String, String> env) {
        List<Path> searched = GameLocator.candidates(osName, home, env);
        return "BiteCheck: Project Zomboid (Build 42) was not found. Install it with Steam, or tell the build where it is: "
                + "-P" + GAME_DIR_PROPERTY + "=<dir> (or " + GAME_DIR_PROPERTY + "=<dir> in ~/.gradle/gradle.properties), "
                + "the environment variable " + GAME_DIR_ENV + ", or bitecheck { gameDir.set(file(\"<dir>\")) }. "
                + "The directory may be the game or the dedicated server. Searched: "
                + (searched.isEmpty() ? "no Steam library found"
                        : searched.stream().map(Path::toString).collect(Collectors.joining(", ")));
    }

    private static void configureArchives(Project project, BiteCheckExtension ext) {
        project.getTasks().withType(AbstractArchiveTask.class).configureEach(task -> {
            task.setPreserveFileTimestamps(false);
            task.setReproducibleFileOrder(true);
            task.filePermissions(p -> p.unix("rw-r--r--"));
            task.dirPermissions(p -> p.unix("rwxr-xr-x"));
        });
        TaskProvider<Jar> jar = project.getTasks().named(JavaPlugin.JAR_TASK_NAME, Jar.class, task ->
                task.getArchiveFileName().set(ext.getModName().map(name -> requireName(name) + ".jar")));

        TaskProvider<Sync> mod = project.getTasks().register("bitecheckMod", Sync.class, task -> {
            task.setGroup("bitecheck");
            task.setDescription("Assembles the mod folder in build/bitecheck: the files from src/mod plus the "
                    + "jar in <gameVersion>/media/java/<side>/.");
            task.into(project.getLayout().getBuildDirectory().dir("bitecheck"));
            task.from(ext.getModDir());
            task.into(ext.getGameVersion().zip(ext.getSide(), BiteCheckPlugin::jarFolder), spec -> spec.from(jar));
            Provider<File> common = project.getLayout().getBuildDirectory().dir("bitecheck/common").map(d -> d.getAsFile());
            // Build 42 only lists a mod if it has a "common" folder, even an empty one.
            task.doLast("create common folder", t -> {
                try {
                    Files.createDirectories(common.get().toPath());
                } catch (java.io.IOException e) {
                    throw new GradleException("cannot create " + common.get(), e);
                }
            });
        });
        project.getTasks().named("assemble", task -> task.dependsOn(mod));
    }

    static String jarFolder(String gameVersion, String side) {
        if (!gameVersion.matches("[0-9]+(\\.[0-9]+)*")) {
            throw new GradleException("BiteCheck: gameVersion must look like 42 or 42.13, got '" + gameVersion + "'");
        }
        if (!List.of("client", "server", "shared").contains(side)) {
            throw new GradleException("BiteCheck: side must be client, server or shared, got '" + side + "'");
        }
        return gameVersion + "/media/java/" + side;
    }

    static String requireName(String modName) {
        if (!modName.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new GradleException("BiteCheck: modName may only contain letters, digits, '.', '_' and '-', got '"
                    + modName + "'");
        }
        return modName;
    }
}
