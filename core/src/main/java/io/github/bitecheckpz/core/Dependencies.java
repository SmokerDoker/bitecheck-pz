package io.github.bitecheckpz.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Libraries a mod jar bundles (shaded in), as Maven builds record them in
 * {@code META-INF/maven/<group>/<artifact>/pom.properties}. They can be checked against known
 * vulnerabilities (OSV, {@code bcz osv}); a library shaded without that file is not seen.
 */
public final class Dependencies {

    /** One bundled library. */
    public record Library(String group, String artifact, String version) {
        /** "group:artifact", the package name OSV uses for Maven. */
        public String name() {
            return group + ":" + artifact;
        }
    }

    private Dependencies() {
    }

    public static List<Library> bundled(Path jar) throws IOException {
        List<Library> out = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String name = e.getName();
                if (!name.startsWith("META-INF/maven/") || !name.endsWith("/pom.properties") || e.getSize() > 64_000) {
                    continue;
                }
                Properties p = new Properties();
                try (InputStream in = zip.getInputStream(e)) {
                    p.load(in);
                }
                String group = p.getProperty("groupId");
                String artifact = p.getProperty("artifactId");
                String version = p.getProperty("version");
                if (group != null && artifact != null && version != null) {
                    out.add(new Library(group.strip(), artifact.strip(), version.strip()));
                }
            }
        }
        return out;
    }

    /** The OSV batch query for the libraries (https://api.osv.dev/v1/querybatch). */
    public static String osvQuery(List<Library> libs) {
        StringBuilder q = new StringBuilder("{\"queries\":[");
        for (int i = 0; i < libs.size(); i++) {
            Library l = libs.get(i);
            q.append(i == 0 ? "" : ",").append("{\"package\":{\"ecosystem\":\"Maven\",\"name\":")
                    .append(Json.write(l.name())).append("},\"version\":").append(Json.write(l.version())).append('}');
        }
        return q.append("]}").toString();
    }
}
