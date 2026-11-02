package io.github.smokerdoker.bitecheck;

import java.util.Map;

/** Pinned ZombieBuddy releases: the jar is downloaded from GitHub and checked against these hashes. */
final class ZombieBuddy {

    static final String DEFAULT_VERSION = "2.3.4";

    /** SHA-256 of ZombieBuddy.jar per release (https://github.com/zed-0xff/ZombieBuddy/releases). */
    static final Map<String, String> KNOWN = Map.of(
            "2.3.4", "eb79b9876332010733a8e0d7ce4fe0377846059a9e28859029e2a0fb449f6cf2",
            "2.3.3", "c000c1ab79873314db73f53b971ae004272aa145c03a0a42f1a61279c54837fe");

    private ZombieBuddy() {
    }

    static String url(String version) {
        return "https://github.com/zed-0xff/ZombieBuddy/releases/download/v" + version + "/ZombieBuddy.jar";
    }
}
