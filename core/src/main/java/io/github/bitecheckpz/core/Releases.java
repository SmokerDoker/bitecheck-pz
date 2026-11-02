package io.github.bitecheckpz.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Helpers for release files. */
public final class Releases {

    private Releases() {
    }

    /** Artifact paths of a release: [build] artifacts = [...] or the single [build] artifact. */
    public static List<String> artifacts(Map<String, Object> release) {
        Map<String, Object> build = Toml.table(release, "build");
        List<String> result = new ArrayList<>();
        if (build.get("artifacts") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s) {
                    result.add(s);
                }
            }
        }
        String single = Toml.string(build, "artifact");
        if (single != null && !result.contains(single)) {
            result.add(single);
        }
        return result;
    }

    /**
     * For a "workshop" release: where each artifact is inside the Steam Workshop item
     * ([build] workshop_artifacts = [...], in the order of {@link #artifacts}).
     */
    public static List<String> workshopArtifacts(Map<String, Object> release) {
        List<String> result = new ArrayList<>();
        if (Toml.table(release, "build").get("workshop_artifacts") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s) {
                    result.add(s);
                }
            }
        }
        return result;
    }

    /** True if a path is a safe relative path inside a repository (no spaces, no '..'). */
    public static boolean isSafeArtifactPath(String path) {
        return path != null && path.matches("[A-Za-z0-9._/+-]+") && !path.startsWith("/")
                && !path.contains("..") && path.endsWith(".jar");
    }
}
