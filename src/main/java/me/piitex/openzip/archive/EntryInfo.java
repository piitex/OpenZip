package me.piitex.openzip.archive;

/**
 * One entry of an archive.
 *
 * @param path           always {@code /}-separated, relative, with no trailing slash. Already checked to stay inside its root.
 * @param size           uncompressed bytes, or -1 if the archive doesn't say
 * @param compressedSize packed bytes, or -1 if unknown (tar and solid 7z don't record it per file)
 * @param modified       epoch milliseconds, or -1
 * @param mode           Unix permission bits (such as {@code 0755}), or -1 if the archive has none
 * @param linkTarget     where a symbolic link points, or null if this is not a link
 */
public record EntryInfo(String path, boolean directory, long size, long compressedSize, long modified, int mode,
                        String linkTarget) {

    public boolean isSymlink() {
        return linkTarget != null;
    }

    /**
     * The last segment of the path.
     */
    public String name() {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /**
     * The path of the folder holding this entry, or the empty string for a top-level entry.
     */
    public String parent() {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    public boolean isExecutable() {
        return mode >= 0 && (mode & 0100) != 0 && !directory;
    }
}
