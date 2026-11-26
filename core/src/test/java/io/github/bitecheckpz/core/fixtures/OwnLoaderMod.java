package io.github.bitecheckpz.core.fixtures;

/** Scanner fixture: defines classes itself, so it can run code the scan never saw. */
public class OwnLoaderMod extends ClassLoader {
    public Class<?> define(byte[] bytes) {
        return defineClass(null, bytes, 0, bytes.length);
    }
}
