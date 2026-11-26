package io.github.bitecheckpz.core.fixtures;

/** Scanner fixture: opens a URL in another program. */
public class DesktopMod {
    public void open() throws Exception {
        java.awt.Desktop.getDesktop().browse(new java.net.URI("https://example.invalid"));
    }
}
