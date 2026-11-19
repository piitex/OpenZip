package me.piitex.openzip.archive;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Everything that decides whether a name from an archive may touch the disk. An archive is untrusted input: a name
 * such as {@code ../../.ssh/authorized_keys} (zip slip) or a symlink pointing out of the folder must never get out.
 * <p>
 * {@code WINDOWS_DEVICES} holds the names Windows treats as the device whatever the extension, so {@code nul.txt}
 * writes to the bit bucket.
 */
final class SafePaths {
    private static final Set<String> JUNK_NAMES = Set.of(
            ".DS_Store", "__MACOSX", ".Spotlight-V100", ".Trashes", ".fseventsd", ".TemporaryItems", "Thumbs.db", "desktop.ini");

    private static final Set<String> WINDOWS_DEVICES = Set.of(
            "CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$",
            "COM0", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT0", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private SafePaths() {
    }

    /**
     * Whether this system's file names follow Windows' rules.
     */
    static boolean onWindows() {
        return File.separatorChar == '\\';
    }

    /**
     * Why Windows can't store a file with this path, or null if it can. Zips and tars come from systems that allow
     * names Windows doesn't: {@code a:b} would write into a hidden alternate data stream of {@code a}, {@code NUL} and
     * {@code CON} are devices, and a trailing dot or space is silently dropped, so two different names land on one file.
     * Those are skipped and reported rather than extracted as something else.
     */
    static String windowsProblem(String path) {
        for (String part : path.split("/")) {
            for (int i = 0; i < part.length(); i++) {
                char character = part.charAt(i);
                if (character < 32 || "<>:\"|?*".indexOf(character) >= 0) {
                    return "Windows doesn't allow " + (character < 32 ? "a control character" : "\"" + character + "\"") + " in a name";
                }
            }
            if (part.endsWith(".") || part.endsWith(" "))
                return "Windows doesn't allow a name that ends in a dot or a space";
            int dot = part.indexOf('.');
            String stem = (dot < 0 ? part : part.substring(0, dot)).stripTrailing().toUpperCase(Locale.ROOT);
            if (WINDOWS_DEVICES.contains(stem)) return "Windows reserves the name " + stem + " for a device";
        }
        return null;
    }

    /**
     * {@link #windowsProblem} where it applies, which is only on Windows.
     */
    static String unusableHere(String path) {
        return onWindows() ? windowsProblem(path) : null;
    }

    /**
     * Turns an archive's entry name into a clean relative {@code /}-separated path, or null if the name could
     * escape its folder (absolute, a drive letter, or any {@code ..}) or is empty.
     */
    static String normalize(String raw) {
        if (raw == null) return null;
        String normalized = raw.replace('\\', '/');
        if (normalized.indexOf('\0') >= 0) return null;
        if (normalized.startsWith("/") || (normalized.length() >= 2 && normalized.charAt(1) == ':' && Character.isLetter(normalized.charAt(0))))
            return null;
        StringBuilder out = new StringBuilder();
        for (String part : normalized.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) return null;
            if (!out.isEmpty()) out.append('/');
            out.append(part);
        }
        return out.isEmpty() ? null : out.toString();
    }

    /**
     * {@code root.resolve(path)}, or null if it lands outside {@code root}. {@code path} comes from {@link #normalize}.
     */
    static Path resolve(Path root, String path) {
        Path target;
        try {
            target = root.resolve(path).normalize();
        } catch (InvalidPathException exception) {
            return null; // a name this file system can't hold at all
        }
        return target.startsWith(root) && !target.equals(root) ? target : null;
    }

    /**
     * Whether a symlink at {@code linkPath} (relative to the extraction root) may point at {@code target}. Only
     * relative targets that stay inside the root are accepted.
     */
    static boolean linkStaysInside(Path root, Path linkLocation, String target) {
        if (target == null || target.isEmpty() || target.indexOf('\0') >= 0) return false;
        Path targetPath = Path.of(target.replace('\\', '/'));
        if (targetPath.isAbsolute() || target.startsWith("/")) return false;
        Path resolved = linkLocation.getParent().resolve(targetPath).normalize();
        return resolved.startsWith(root);
    }

    /**
     * Finder, Explorer and Spotlight leave these behind. They are clutter in an archive and on extraction.
     */
    static boolean isJunkName(String segment) {
        return JUNK_NAMES.contains(segment) || segment.startsWith("._");
    }

    /**
     * True if any segment of an entry path is junk, so everything inside {@code __MACOSX/} goes too.
     */
    static boolean isJunkPath(String path) {
        for (String part : path.split("/")) {
            if (isJunkName(part)) return true;
        }
        return false;
    }
}
