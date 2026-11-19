package me.piitex.openzip.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;

/**
 * "Compress with OpenZip" in Finder's right-click menu (under Quick Actions) on macOS.
 * <p>
 * It is a Quick Action: a small Automator workflow in {@code ~/Library/Services} that opens whatever is selected with
 * OpenZip, which offers to compress it. A Finder menu entry can't come from the app bundle itself without native code
 * (a service or an extension has to be a program the system can call into), and a workflow needs none. Adding it is the
 * user's choice, made from the File menu, and removing it is the same click.
 * <p>
 * {@code NAME} is the name in the menu and of the workflow. {@code BUNDLE_ID} is the packaged app's bundle identifier,
 * which {@code identifier} in {@code rengl-package.properties} sets; the workflow opens the app by it.
 */
final class FinderAction {
    private FinderAction() {
    }

    static final String NAME = "Compress with OpenZip";
    static final String BUNDLE_ID = "me.piitex.openzip";

    static boolean supported() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    /**
     * True when this is the installed app (the process is {@code Something.app/Contents/MacOS/...}) and not a run from
     * the IDE or Maven, whose Finder action would have no app to open.
     */
    static boolean runningFromApp() {
        return runningFromApp(ProcessHandle.current().info().command().orElse(""));
    }

    static boolean runningFromApp(String command) {
        return command.contains(".app/Contents/MacOS/");
    }

    /**
     * Where this user's Quick Actions live.
     */
    static Path servicesFolder() {
        return Path.of(System.getProperty("user.home"), "Library", "Services");
    }

    static Path workflow(Path services) {
        return services.resolve(NAME + ".workflow");
    }

    static boolean isInstalled(Path services) {
        return Files.isRegularFile(workflow(services).resolve("Contents/document.wflow"));
    }

    /**
     * What the workflow runs: the selected items are its arguments.
     */
    static String command() {
        return "/usr/bin/open -b " + BUNDLE_ID + " \"$@\"";
    }

    /**
     * Writes the workflow into {@code services}, replacing an older copy.
     */
    static void install(Path services) throws IOException {
        Path target = workflow(services);
        Path staging = services.resolve(NAME + ".workflow.tmp");
        delete(staging);
        Path contents = Files.createDirectories(staging.resolve("Contents"));
        Files.writeString(contents.resolve("Info.plist"), fill(resource("Info.plist"), NAME));
        Files.writeString(contents.resolve("document.wflow"), fill(resource("document.wflow"), escape(command())));
        delete(target);
        Files.move(staging, target); // the workflow appears whole or not at all
    }

    static void remove(Path services) throws IOException {
        delete(workflow(services));
    }

    private static String fill(String template, String value) {
        return template.replace("@NAME@", NAME).replace("@COMMAND@", value);
    }

    private static String escape(String xml) {
        return xml.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = FinderAction.class.getResourceAsStream("/finder/" + name)) {
            if (in == null) throw new IOException("Missing resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void delete(Path folder) throws IOException {
        if (!Files.exists(folder)) return;
        try (var walk = Files.walk(folder)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }
}
