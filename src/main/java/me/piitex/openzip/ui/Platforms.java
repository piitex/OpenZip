package me.piitex.openzip.ui;

import java.util.Locale;

/**
 * What the current system calls things, for labels. Showing a file is {@code Browser.reveal}.
 */
final class Platforms {
    private Platforms() {
    }

    /**
     * What this system calls the place files are shown in, for button labels.
     */
    static String fileManager() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") ? "Finder" : os.contains("win") ? "Explorer" : "Files";
    }
}
