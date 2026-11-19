package me.piitex.openzip.ui;

import me.piitex.engine.Engine;
import me.piitex.engine.gl.renderer.Snapshot;
import me.piitex.engine.Window;
import me.piitex.engine.WindowOptions;
import me.piitex.engine.scheduler.Scheduler;
import me.piitex.openzip.archive.ArchiveFormat;
import me.piitex.openzip.archive.ArchiveWriter;
import me.piitex.openzip.archive.Job;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Developer tool, not a test: opens a sample archive in the real window, saves what the window draws to a PNG, and
 * quits. Output goes to the folder in the {@code OPENZIP_OUT} environment variable.
 * <pre>./mvnw -o test-compile exec:exec -Dopenzip.main=me.piitex.openzip.ui.Screenshot -Dexec.classpathScope=test</pre>
 */
public class Screenshot {
    public static void main(String[] args) throws Exception {
        Path out = Path.of(System.getenv().getOrDefault("OPENZIP_OUT", "build/screenshots"));
        Files.createDirectories(out);

        Path sample = Files.createDirectories(out.resolve("sample/Holiday Photos"));
        Files.createDirectories(sample.resolve("2025/Rome"));
        Files.createDirectories(sample.resolve("2025/Paris"));
        Files.write(sample.resolve("2025/Rome/colosseum.jpg"), new byte[2_400_000]);
        Files.write(sample.resolve("2025/Rome/forum.jpg"), new byte[1_800_000]);
        Files.write(sample.resolve("2025/Paris/tower.jpg"), new byte[3_100_000]);
        Files.writeString(sample.resolve("notes.txt"), "Remember the tickets".repeat(50));
        Files.writeString(sample.resolve("README.md"), """
                # Holiday Photos

                Pictures from the **2025** trip, sorted by city. Open `notes.txt` for the ticket reminders.

                ## What is in here

                - Rome: the Colosseum and the Forum
                - Paris: the tower, at night
                - A list that is long enough that it has to wrap onto a second line so you can see the text lining up beside its bullet

                ## Copying them off

                1. Extract the archive
                2. Run the script below

                ```
                ./run.sh --resize 1600
                ```

                ### Notes

                The originals are not touched.
                """);
        Files.writeString(sample.resolve("run.sh"), """
                #!/bin/bash
                # Shrinks every photo for sharing.
                set -e

                WIDTH=${1:-1600}
                for photo in 2025/*/*.jpg; do
                  if [ -f "$photo" ]; then
                    echo "Resizing $photo to $WIDTH px"   # one line per photo
                  fi
                done
                exit 0
                """);
        Files.write(sample.resolve("setup.exe"), new byte[2048]);
        Path archive = out.resolve("Holiday Photos.zip");
        ArchiveWriter.create(archive, List.of(sample), new ArchiveWriter.CreateOptions(ArchiveFormat.ZIP), new Job());

        Path locked = out.resolve("Tax Records.7z");
        ArchiveWriter.create(locked, List.of(sample), new ArchiveWriter.CreateOptions(ArchiveFormat.SEVEN_Z, -1, true,
                "swordfish".toCharArray()), new Job());

        // A zip holding a jar, for opening an archive from inside another and coming back.
        Path bundle = Files.createDirectories(out.resolve("bundle/Release"));
        try (var jar = new java.util.jar.JarOutputStream(Files.newOutputStream(bundle.resolve("tool.jar")))) {
            jar.putNextEntry(new java.util.zip.ZipEntry("app/Main.class"));
            jar.write(new byte[4096]);
            jar.closeEntry();
        }
        Files.writeString(bundle.resolve("changelog.txt"), "1.0\n");
        Path nested = out.resolve("Release.zip");
        ArchiveWriter.create(nested, List.of(bundle), new ArchiveWriter.CreateOptions(ArchiveFormat.ZIP), new Job());

        Appearance.Mode mode = Appearance.Mode.valueOf(System.getenv().getOrDefault("OPENZIP_THEME", "DARK").toUpperCase());
        OpenZipApp.installTheme(mode);
        Window window = new Window(new WindowOptions("OpenZip").setDimensions(1000, 660));
        OpenZipApp app = new OpenZipApp(window, 1000, 660);
        app.menuBar(1000);

        Job busy = new Job();
        Scheduler.after(0.4f, () -> save(window, out.resolve("empty.png")));
        Scheduler.after(0.6f, () -> app.openPaths(List.of(archive)));
        Scheduler.after(1.0f, () -> save(window, out.resolve("root.png")));
        Scheduler.after(1.2f, () -> app.select("Holiday Photos/notes.txt"));
        Scheduler.after(1.5f, () -> save(window, out.resolve("browse.png")));
        Scheduler.after(1.52f, () -> app.selectMany("Holiday Photos/notes.txt", "Holiday Photos/2025"));
        Scheduler.after(1.57f, () -> save(window, out.resolve("multi.png")));
        Scheduler.after(1.58f, () -> app.sortBy("Size", false));
        Scheduler.after(1.585f, () -> save(window, out.resolve("sorted.png")));
        Scheduler.after(1.59f, () -> app.select("Holiday Photos/2025/Rome/forum.jpg"));
        Scheduler.after(1.595f, () -> save(window, out.resolve("deep.png")));
        Scheduler.after(1.6f, () -> invoke(app, "begin", new Class<?>[]{String.class, Job.class, boolean.class},
                "Extracting Holiday Photos.zip", busy, true));
        Scheduler.after(1.7f, () -> {
            busy.start(100);
            busy.advance(42);
            busy.setCurrent("Holiday Photos/2025/Rome/colosseum.jpg");
        });
        Scheduler.after(2.0f, () -> save(window, out.resolve("busy.png")));
        Scheduler.after(2.1f, () -> invoke(app, "finish", new Class<?>[0]));
        Scheduler.after(2.2f, () -> invoke(app, "testArchive", new Class<?>[0]));
        Scheduler.after(2.7f, () -> save(window, out.resolve("done.png")));
        Scheduler.after(2.8f, () -> window.setSize(760, 480)); // the layout has to follow a smaller window
        Scheduler.after(3.4f, () -> save(window, out.resolve("small.png")));
        Scheduler.after(3.5f, () -> window.setSize(1000, 660));
        Scheduler.after(3.9f, () -> app.showCreateDialog(List.of(sample)));
        Scheduler.after(4.5f, () -> save(window, out.resolve("create.png")));
        Scheduler.after(4.7f, app::showNewArchiveChooser);
        Scheduler.after(5.3f, () -> save(window, out.resolve("choose.png")));
        Scheduler.after(5.4f, () -> window.getModals().forEach(modal -> modal.close()));
        Scheduler.after(5.5f, () -> app.openPaths(List.of(locked)));
        Scheduler.after(6.2f, () -> save(window, out.resolve("locked.png")));
        Scheduler.after(6.3f, () -> invoke(app, "askPassword",
                new Class<?>[]{Path.class, me.piitex.openzip.archive.PasswordException.class, java.util.function.Consumer.class, Runnable.class},
                locked, null, (java.util.function.Consumer<char[]>) pw -> { }, (Runnable) () -> { }));
        Scheduler.after(6.9f, () -> save(window, out.resolve("password.png")));
        Scheduler.after(7.0f, () -> window.getModals().forEach(modal -> modal.close()));
        Scheduler.after(7.1f, () -> invoke(app, "askPassword",
                new Class<?>[]{Path.class, me.piitex.openzip.archive.PasswordException.class, java.util.function.Consumer.class, Runnable.class},
                locked, me.piitex.openzip.archive.PasswordException.wrongOrDamaged(null), (java.util.function.Consumer<char[]>) pw -> { }, (Runnable) () -> { }));
        Scheduler.after(7.7f, () -> save(window, out.resolve("wrong.png")));
        // Switching the look while an archive is open: it must stay open and everything must be redrawn.
        Scheduler.after(7.8f, () -> window.getModals().forEach(modal -> modal.close()));
        Scheduler.after(7.85f, () -> app.openPaths(List.of(archive)));
        Scheduler.after(8.4f, () -> app.setScheme(Palette.isDark() ? Palette.LIGHT : Palette.DARK));
        Scheduler.after(9.0f, () -> save(window, out.resolve("switched.png")));
        Scheduler.after(9.1f, () -> app.showCreateDialog(List.of(sample)));
        Scheduler.after(9.7f, () -> save(window, out.resolve("switched-create.png")));
        // A double-click on a program: it is extracted, then refused (nothing may be run from inside an archive).
        Scheduler.after(9.75f, () -> window.getModals().forEach(modal -> modal.close()));
        Scheduler.after(9.8f, () -> app.activate("Holiday Photos/setup.exe"));
        Scheduler.after(10.6f, () -> save(window, out.resolve("refused.png")));
        Scheduler.after(10.8f, () -> app.openPaths(List.of(nested)));
        Scheduler.after(11.3f, () -> app.activate("Release/tool.jar"));
        Scheduler.after(12.0f, () -> save(window, out.resolve("nested.png")));
        Scheduler.after(12.1f, () -> invoke(app, "closeArchive", new Class<?>[0]));
        Scheduler.after(12.5f, () -> save(window, out.resolve("back.png")));
        // The preview: Markdown as a document, the same file as source, and a script.
        Scheduler.after(12.7f, () -> app.openPaths(List.of(archive)));
        Scheduler.after(13.2f, () -> app.previewPath("Holiday Photos/README.md"));
        Scheduler.after(13.9f, () -> save(window, out.resolve("preview-md.png")));
        Scheduler.after(14.0f, () -> app.togglePreview());
        Scheduler.after(14.3f, () -> save(window, out.resolve("preview-md-source.png")));
        Scheduler.after(14.4f, () -> window.getModals().forEach(modal -> modal.close()));
        Scheduler.after(14.6f, () -> app.previewPath("Holiday Photos/run.sh"));
        Scheduler.after(15.3f, () -> save(window, out.resolve("preview-sh.png")));
        Scheduler.after(15.4f, () -> window.getModals().forEach(modal -> modal.close()));
        Scheduler.after(15.6f, () -> app.showFinderOffer());
        Scheduler.after(16.2f, () -> save(window, out.resolve("finder-offer.png")));
        Scheduler.after(16.3f, () -> window.getModals().forEach(modal -> modal.close()));
        Scheduler.after(16.5f, window::close);

        new Engine().start(window);
        System.out.println("saved to " + out.toAbsolutePath());
    }

    /** The app's job methods are private on purpose; this tool needs to see a job mid-flight. */
    private static void invoke(OpenZipApp app, String name, Class<?>[] types, Object... args) {
        try {
            var method = OpenZipApp.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(app, args);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void save(Window window, Path file) {
        try (Snapshot shot = window.takeSnapshot(1f)) {
            shot.savePng(file);
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }
}
