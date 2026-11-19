package me.piitex.openzip.ui;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Which files are text, how to read one for the preview, and how to hand one to the system's text editor.
 * <p>
 * A shell script or a batch file is a program to the engine's {@code Browser.open}, which refuses it. Opening it in a
 * text editor runs nothing, so text files go through here and not through that check.
 * <p>
 * {@code NAMES} lists names with no extension that are text all the same. {@code PREVIEW_LIMIT} is the most of a file
 * the preview reads; a bigger one is cut there and says so.
 */
final class TextFiles {
    private TextFiles() {
    }

    private static final Set<String> MARKDOWN = Set.of("md", "markdown", "mdown", "mkd");

    private static final Set<String> EXTENSIONS = Set.of(
            "txt", "text", "log", "md", "markdown", "mdown", "mkd", "rst", "adoc", "tex", "csv", "tsv", "srt", "diff", "patch",
            "json", "jsonc", "json5", "xml", "html", "htm", "xhtml", "svg", "css", "scss", "less",
            "yml", "yaml", "toml", "ini", "cfg", "conf", "config", "properties", "env", "plist", "gradle",
            "sh", "bash", "zsh", "fish", "command", "bat", "cmd", "ps1",
            "java", "kt", "kts", "scala", "groovy", "js", "mjs", "cjs", "ts", "tsx", "jsx", "py", "rb", "php", "pl", "lua",
            "c", "h", "cc", "cpp", "cxx", "hpp", "cs", "go", "rs", "swift", "m", "mm", "sql", "r", "dart", "vue",
            "gitignore", "gitattributes", "editorconfig", "dockerfile", "makefile");

    private static final Set<String> NAMES = Set.of(
            "readme", "license", "licence", "copying", "notice", "authors", "changelog", "changes", "contributing",
            "todo", "makefile", "dockerfile", "gemfile", "rakefile", "procfile", "vagrantfile");

    static final long PREVIEW_LIMIT = 1_000_000;

    /**
     * True if {@code fileName} is something a text editor is the right program for.
     */
    static boolean isText(String fileName) {
        String name = baseName(fileName).toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0) return NAMES.contains(name);
        // ".gitignore" has its whole name after the dot, "notes.txt" has an extension.
        return EXTENSIONS.contains(name.substring(dot + 1)) || (dot == 0 && NAMES.contains(name.substring(1)));
    }

    static boolean isMarkdown(String fileName) {
        String name = baseName(fileName).toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && MARKDOWN.contains(name.substring(dot + 1));
    }

    private static String baseName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /**
     * What the preview shows: the text, and whether the file was longer than the limit.
     */
    record Content(String text, boolean truncated) {
    }

    /**
     * Reads up to {@code limit} bytes as UTF-8. Returns null if it isn't text after all: a NUL byte near the start
     * means a binary file with a text-like name, which would only show as noise.
     */
    static Content read(Path file, long limit) throws IOException {
        byte[] bytes;
        boolean truncated;
        try (InputStream in = java.nio.file.Files.newInputStream(file)) {
            bytes = in.readNBytes((int) limit + 1);
            truncated = bytes.length > limit;
            if (truncated) bytes = java.util.Arrays.copyOf(bytes, (int) limit);
        }
        for (int i = 0; i < Math.min(bytes.length, 8192); i++) {
            if (bytes[i] == 0) return null;
        }
        int start = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        String text = new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
        if (truncated) {
            // A cut can land in the middle of a character; the replacement mark at the end is not worth showing.
            while (text.endsWith("�")) text = text.substring(0, text.length() - 1);
        }
        return new Content(text.replace("\r\n", "\n").replace('\r', '\n'), truncated);
    }

    /**
     * Opens {@code file} in the system's text editor, as a text file and never as a program.
     *
     * @return false if this system has no way to do that, so the caller can show its own preview instead
     */
    static boolean openInEditor(Path file) {
        List<String> command = editorCommand(System.getProperty("os.name", ""), file.toAbsolutePath(), TextFiles::linuxEditor);
        if (command == null) return false;
        try {
            new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(Redirect.DISCARD).start();
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    /**
     * The name of the system's text editor on this OS, for messages.
     */
    static String editorName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") ? "TextEdit" : os.contains("win") ? "Notepad" : "your text editor";
    }

    /**
     * The command that opens {@code file} in a text editor, or null if there isn't one.
     * {@code linuxEditor} is asked for the desktop entry of the default text editor, which only Linux needs.
     */
    static List<String> editorCommand(String osName, Path file, java.util.function.Supplier<String> linuxEditor) {
        String os = osName.toLowerCase(Locale.ROOT);
        String target = file.toString();
        if (os.contains("mac")) {
            // -t is "the default text editor", whatever the file's own type is associated with.
            return List.of("open", "-t", target);
        }
        if (os.contains("win")) {
            return List.of("notepad.exe", target);
        }
        if (os.contains("linux") || os.contains("nux") || os.contains("bsd")) {
            // xdg-open would give a .sh to whatever the desktop does with scripts, which can be to run it. Asking for
            // the editor by name can't.
            String desktopEntry = linuxEditor.get();
            return desktopEntry == null ? null : List.of("gtk-launch", desktopEntry, target);
        }
        return null;
    }

    /**
     * The default handler of {@code text/plain} as a desktop entry name, or null.
     */
    private static String linuxEditor() {
        try {
            Process process = new ProcessBuilder("xdg-mime", "query", "default", "text/plain")
                    .redirectError(Redirect.DISCARD).start();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            String entry = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return entry.isEmpty() ? null : entry;
        } catch (IOException exception) {
            return null;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
