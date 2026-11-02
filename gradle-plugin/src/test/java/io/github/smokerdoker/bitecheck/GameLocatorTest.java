package io.github.smokerdoker.bitecheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GameLocatorTest {

    @TempDir
    Path tmp;

    private Path touch(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.write(file, new byte[] {1});
    }

    @Test
    void findsTheJarOfTheGameClient() throws IOException {
        Path jar = touch(tmp.resolve("projectzomboid.jar"));
        assertEquals(Optional.of(jar), GameLocator.gameJar(tmp));
    }

    @Test
    void findsTheJarOfTheDedicatedServer() throws IOException {
        Path jar = touch(tmp.resolve("java/projectzomboid.jar"));
        assertEquals(Optional.of(jar), GameLocator.gameJar(tmp));
    }

    @Test
    void findsTheJarOnMacOsIgnoringCase() throws IOException {
        Path jar = touch(tmp.resolve("Project Zomboid.app/Contents/Java/ProjectZomboid.jar"));
        assertEquals(Optional.of(jar.toRealPath()), GameLocator.gameJar(tmp).map(p -> {
            try {
                return p.toRealPath();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }));
    }

    @Test
    void emptyDirectoryHasNoJar() {
        assertTrue(GameLocator.gameJar(tmp).isEmpty());
    }

    @Test
    void parsesSteamLibraryFolders() {
        String vdf = """
                "libraryfolders"
                {
                	"0"
                	{
                		"path"		"C:\\\\Program Files (x86)\\\\Steam"
                		"label"		""
                	}
                	"1"
                	{
                		"path"		"/mnt/games/SteamLibrary"
                	}
                }
                """;
        assertEquals(List.of(Path.of("C:\\Program Files (x86)\\Steam"), Path.of("/mnt/games/SteamLibrary")),
                GameLocator.parseLibraryFolders(vdf));
    }

    @Test
    void autodetectsTheDedicatedServerInASteamLibrary() throws IOException {
        Path home = tmp.resolve("home");
        Path library = tmp.resolve("library");
        Path steam = home.resolve(".local/share/Steam");
        Files.createDirectories(steam.resolve("steamapps"));
        Files.writeString(steam.resolve("steamapps/libraryfolders.vdf"),
                "\"libraryfolders\" { \"0\" { \"path\" \"" + library.toString().replace("\\", "\\\\") + "\" } }");
        Path server = library.resolve("steamapps/common/Project Zomboid Dedicated Server");
        touch(server.resolve("java/projectzomboid.jar"));
        assertEquals(Optional.of(server), GameLocator.autodetect("Linux", home, Map.of()));
    }

    @Test
    void validatesTheJarFolder() {
        assertEquals("42/media/java/client", BiteCheckPlugin.jarFolder("42", "client"));
        assertEquals("42.13/media/java/shared", BiteCheckPlugin.jarFolder("42.13", "shared"));
        org.junit.jupiter.api.Assertions.assertThrows(org.gradle.api.GradleException.class,
                () -> BiteCheckPlugin.jarFolder("42/../..", "client"));
        org.junit.jupiter.api.Assertions.assertThrows(org.gradle.api.GradleException.class,
                () -> BiteCheckPlugin.jarFolder("42", "everywhere"));
    }
}
