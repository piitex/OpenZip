package me.piitex.openzip;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.openzip.archive.ArchiveFormat;
import me.piitex.openzip.ui.OpenZipApp;
import me.piitex.openzip.ui.QuickCompress;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * OpenZip: a free archive utility. Run with
 * {@code -XstartOnFirstThread --enable-native-access=ALL-UNNAMED} on macOS, or {@code ./mvnw compile exec:exec}.
 */
public class OpenZip {
    private static final int WIDTH = 1000;
    private static final int HEIGHT = 660;
    private static final String COMPRESS = "--compress=";

    public static void main(String[] args) {
        OpenZipApp.installTheme();

        // Explorer's "Compress to ZIP": a small progress window instead of the whole app, hidden until it is needed.
        Optional<ArchiveFormat> quick = quickFormat(args).filter(format -> namesExistingPath(args));
        if (quick.isPresent()) {
            Window window = new Window(new WindowOptions("OpenZip").setDimensions(QuickCompress.WIDTH, QuickCompress.HEIGHT)
                    .setIconPath(icon()).setResizable(false));
            QuickCompress job = new QuickCompress(window, quick.get());
            window.hide();
            Engine.onOpenFiles(job::start);
            Engine.openFilesFromArguments(args);
            new Engine().start(window);
            return;
        }

        Window window = new Window(new WindowOptions("OpenZip").setDimensions(WIDTH, HEIGHT).setIconPath(icon()).setSplashEnabled(false));
        window.setSizeLimits(720, 460, -1, -1);

        OpenZipApp app = new OpenZipApp(window, WIDTH, HEIGHT);
        app.menuBar(WIDTH);

        app.offerFinderAction(); // once, on the first launch of the installed app

        Engine.onOpenFiles(app::openPaths);   // double-clicked in Finder, "Open With", dropped on the Dock icon
        Engine.openFilesFromArguments(args);  // the same on Windows and Linux, which pass paths as arguments (options are ignored)

        new Engine().start(window); // blocks until the window closes
    }

    /** Whether any argument that is not an option names a file or folder that exists: what Engine hands on as paths. */
    static boolean namesExistingPath(String[] args) {
        for (String arg : args) {
            if (arg == null || arg.isBlank() || arg.startsWith("-")) continue;
            try {
                if (Files.exists(Path.of(arg))) return true;
            } catch (InvalidPathException ignored) {
                // not a path on this file system
            }
        }
        return false;
    }

    /**
     * {@code --compress=zip} (or {@code 7z}, {@code tar.gz}...) makes the folders and files named after it into an
     * archive of that kind next to them straight away, in a small window of its own: what Explorer's "Compress to ZIP"
     * runs. Only formats that can be written count; anything else is ignored and the paths are offered for compressing
     * as usual.
     */
    static Optional<ArchiveFormat> quickFormat(String[] args) {
        for (String arg : args) {
            if (!arg.startsWith(COMPRESS)) continue;
            String wanted = "." + arg.substring(COMPRESS.length()).toLowerCase(Locale.ROOT);
            for (ArchiveFormat format : ArchiveFormat.values()) {
                if (format.canWrite() && format.extension().equals(wanted)) return Optional.of(format);
            }
        }
        return Optional.empty();
    }

    /** macOS shows the window icon in the Dock, so it gets the one drawn with that platform's margin and shadow. */
    private static String icon() {
        boolean mac = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
        return mac ? "/icon-macos.png" : "/icon.png";
    }
}
