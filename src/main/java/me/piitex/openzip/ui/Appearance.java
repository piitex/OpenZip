package me.piitex.openzip.ui;

import me.piitex.engine.Engine;
import me.piitex.engine.config.Config;
import me.piitex.engine.input.SystemAppearance;
import me.piitex.engine.io.AppEnvironment;

import java.io.IOException;
import java.util.Locale;

/**
 * Light, dark, or whatever the system is set to, and the other small things the app remembers. They are saved in the
 * app's settings file; the system's own light or dark setting comes from the engine.
 */
final class Appearance {
    private Appearance() {
    }

    enum Mode {
        SYSTEM, LIGHT, DARK;

        Palette.Scheme scheme() {
            return switch (this) {
                case LIGHT -> Palette.LIGHT;
                case DARK -> Palette.DARK;
                case SYSTEM -> systemIsDark() ? Palette.DARK : Palette.LIGHT;
            };
        }
    }

    private static final String FILE = "settings.cfg";
    private static final String KEY = "appearance";

    /**
     * The saved choice, or {@link Mode#SYSTEM} the first time and whenever it can't be read.
     */
    static Mode load() {
        try {
            String saved = AppEnvironment.create("OpenZip").loadConfig(FILE).getString(KEY);
            if (saved != null) return Mode.valueOf(saved.trim().toUpperCase(Locale.ROOT));
        } catch (IOException | RuntimeException exception) {
            // Nothing saved yet, or the file is unusable: either way the default is fine.
        }
        return Mode.SYSTEM;
    }

    static void save(Mode mode) {
        try {
            AppEnvironment env = AppEnvironment.create("OpenZip");
            Config config = env.loadConfig(FILE);
            config.set(KEY, mode.name().toLowerCase(Locale.ROOT));
            env.saveConfig(FILE, config);
        } catch (IOException | RuntimeException exception) {
            // Not being able to remember the choice isn't worth interrupting anyone for.
        }
    }

    /**
     * A yes/no the app remembers between launches, such as a question it has already asked. False if never set.
     */
    static boolean flag(String key) {
        try {
            return "true".equals(AppEnvironment.create("OpenZip").loadConfig(FILE).getString(key));
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    static void setFlag(String key, boolean value) {
        try {
            AppEnvironment env = AppEnvironment.create("OpenZip");
            Config config = env.loadConfig(FILE);
            config.set(key, Boolean.toString(value));
            env.saveConfig(FILE, config);
        } catch (IOException | RuntimeException exception) {
            // Asked again next time at worst.
        }
    }

    /**
     * True if the system is in dark mode.
     */
    static boolean systemIsDark() {
        return Engine.getSystemAppearance() == SystemAppearance.Mode.DARK;
    }
}
