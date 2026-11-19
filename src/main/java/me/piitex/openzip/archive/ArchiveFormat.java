package me.piitex.openzip.archive;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The archive formats OpenZip understands. Everything can be read; {@link #canWrite} says what can be created.
 */
public enum ArchiveFormat {
    ZIP("ZIP", true, ".zip", ".jar", ".apk", ".epub"),
    TAR_GZ("TAR.GZ", true, ".tar.gz", ".tgz"),
    TAR_XZ("TAR.XZ", true, ".tar.xz", ".txz"),
    TAR_BZ2("TAR.BZ2", true, ".tar.bz2", ".tbz2", ".tbz"),
    TAR("TAR", true, ".tar"),
    SEVEN_Z("7Z", true, ".7z"),
    /**
     * Read only: RAR is RARLAB's format and only their software may write it.
     */
    RAR("RAR", false, ".rar", ".cbr"),
    /**
     * Read only: one compressed file ({@code access.log.gz}). Listed after the tar formats, so {@code .tar.gz} still
     * means a tarball. A file with one of these names that holds a tar is opened as a tar anyway.
     */
    GZ("GZ", false, ".gz"),
    XZ("XZ", false, ".xz"),
    BZ2("BZ2", false, ".bz2");

    private final String label;
    private final boolean canWrite;
    private final List<String> extensions;

    ArchiveFormat(String label, boolean canWrite, String... extensions) {
        this.label = label;
        this.canWrite = canWrite;
        this.extensions = List.of(extensions);
    }

    public String label() {
        return label;
    }

    public boolean canWrite() {
        return canWrite;
    }

    /**
     * The extension new archives of this format get, with the dot.
     */
    public String extension() {
        return extensions.getFirst();
    }

    /**
     * Whether archives of this format can be encrypted with a password. Tar has no encryption of its own.
     */
    public boolean supportsPassword() {
        return this == ZIP || this == SEVEN_Z;
    }

    public boolean isTar() {
        return this == TAR || this == TAR_GZ || this == TAR_XZ || this == TAR_BZ2;
    }

    /**
     * Extensions without the dot, for a file dialog filter.
     */
    public static String[] readableExtensions() {
        return Arrays.stream(values()).flatMap(format -> format.extensions.stream()).map(extension -> extension.substring(1)).toArray(String[]::new);
    }

    /**
     * The format of {@code file}: by name first, then by the first bytes, so a renamed archive still opens.
     */
    public static Optional<ArchiveFormat> detect(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        for (ArchiveFormat format : values()) {
            for (String ext : format.extensions) {
                if (name.endsWith(ext)) return Optional.of(format);
            }
        }
        return sniff(file);
    }

    /**
     * Strips this format's extension from a file name: {@code photos.tar.gz} becomes {@code photos}.
     */
    public static String baseName(Path file) {
        String name = file.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        for (ArchiveFormat format : values()) {
            for (String ext : format.extensions) {
                if (lower.endsWith(ext) && name.length() > ext.length())
                    return name.substring(0, name.length() - ext.length());
            }
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static Optional<ArchiveFormat> sniff(Path file) {
        byte[] head = new byte[8];
        int count;
        try (InputStream in = Files.newInputStream(file)) {
            count = in.readNBytes(head, 0, head.length);
        } catch (IOException exception) {
            return Optional.empty();
        }
        if (count >= 4 && head[0] == 'P' && head[1] == 'K' && (head[2] == 3 || head[2] == 5)) return Optional.of(ZIP);
        if (count >= 2 && (head[0] & 0xFF) == 0x1F && (head[1] & 0xFF) == 0x8B) return Optional.of(GZ);
        if (count >= 3 && head[0] == 'B' && head[1] == 'Z' && head[2] == 'h') return Optional.of(BZ2);
        if (count >= 6 && (head[0] & 0xFF) == 0xFD && head[1] == '7' && head[2] == 'z' && head[3] == 'X' && head[4] == 'Z')
            return Optional.of(XZ);
        if (count >= 6 && head[0] == '7' && head[1] == 'z' && (head[2] & 0xFF) == 0xBC && (head[3] & 0xFF) == 0xAF)
            return Optional.of(SEVEN_Z);
        // "Rar!", 1A 07, then 00 (RAR 4 and earlier) or 01 00 (RAR 5).
        if (count >= 7 && head[0] == 'R' && head[1] == 'a' && head[2] == 'r' && head[3] == '!' && head[4] == 0x1A && head[5] == 0x07
                && (head[6] == 0 || (head[6] == 1 && count >= 8 && head[7] == 0))) return Optional.of(RAR);
        return Optional.empty();
    }
}
