package me.piitex.openzip.archive;

import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.FileHeader;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * Lists, extracts and tests archives. All of it is meant to run on a worker thread.
 */
public final class ArchiveReader {
    private static final int BUFFER = 64 * 1024;

    private ArchiveReader() {
    }

    /**
     * What to do when a file being extracted already exists.
     */
    public enum Overwrite {REPLACE, SKIP}

    /**
     * @param destination the folder to extract into; created if missing
     * @param only        entry paths to extract (a folder brings everything under it), or null for everything
     * @param skipJunk    leave out {@code __MACOSX}, {@code ._*} and {@code .DS_Store}
     * @param password    for an encrypted archive, or null. Not copied, and not cleared: the caller owns the array.
     */
    public record ExtractOptions(Path destination, Set<String> only, Overwrite overwrite, boolean skipJunk,
                                 char[] password) {
        public ExtractOptions(Path destination, Set<String> only, Overwrite overwrite, boolean skipJunk) {
            this(destination, only, overwrite, skipJunk, null);
        }

        public ExtractOptions(Path destination) {
            this(destination, null, Overwrite.REPLACE, true, null);
        }
    }

    /**
     * @param encrypted some entries need a password to read. Their names and sizes are listed without one.
     */
    public record Listing(List<EntryInfo> entries, List<String> warnings, boolean encrypted) {
        public Listing(List<EntryInfo> entries, List<String> warnings) {
            this(entries, warnings, false);
        }

        public long totalSize() {
            return entries.stream().filter(entry -> !entry.directory()).mapToLong(entry -> Math.max(0, entry.size())).sum();
        }
    }

    public record ExtractResult(int files, int folders, long bytes, int skipped, List<String> warnings) {
    }

    /**
     * Receives each entry together with a way to read its data. The data can be read once, and only during the call.
     */
    interface EntryVisitor {
        void visit(EntryInfo info, DataSource data) throws IOException;
    }

    interface DataSource {
        InputStream open() throws IOException;
    }

    interface Source extends Closeable {
        /**
         * Calls the visitor for each entry in the archive's own order. {@code skipped} receives names that were unusable.
         */
        void forEach(EntryVisitor visitor, List<String> skipped) throws IOException;

        /**
         * Whether any entry seen so far needs a password.
         */
        boolean sawEncrypted();
    }

    public static Listing list(Path archive) throws IOException {
        return list(archive, null);
    }

    /**
     * @param password only needed to open an archive whose names are encrypted too (some 7z); otherwise the listing of
     *                 an encrypted archive works without one and {@link Listing#encrypted} says to ask for it later
     */
    public static Listing list(Path archive, char[] password) throws IOException {
        List<EntryInfo> entries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean encrypted;
        try (Source source = open(archive, password)) {
            source.forEach((info, data) -> entries.add(info), warnings);
            encrypted = source.sawEncrypted();
        }
        return new Listing(entries, warnings, encrypted);
    }

    public static ExtractResult extract(Path archive, ExtractOptions options, Job job) throws IOException {
        Listing listing = list(archive, options.password());
        long total = 0;
        for (EntryInfo entry : listing.entries()) {
            if (!entry.directory() && wanted(entry, options)) total += Math.max(0, entry.size());
        }
        job.start(total);

        Path root = Files.createDirectories(options.destination()).toRealPath();
        List<String> warnings = new ArrayList<>(listing.warnings());
        int[] counts = new int[3]; // files, folders, skipped
        long[] bytes = new long[1];

        try (Source source = open(archive, options.password())) {
            source.forEach((info, data) -> {
                job.throwIfCancelled();
                if (!wanted(info, options)) return;
                job.setCurrent(info.path());
                // First, because Windows refuses to even build a Path from some of these names.
                String unusable = SafePaths.unusableHere(info.path());
                if (unusable != null) {
                    warnings.add("Skipped " + info.path() + ": " + unusable);
                    counts[2]++;
                    return;
                }
                Path target = SafePaths.resolve(root, info.path());
                if (target == null) {
                    warnings.add("Skipped " + info.path() + ": it points outside the destination");
                    counts[2]++;
                    return;
                }
                try {
                    if (info.directory()) {
                        ensureDirectory(root, target);
                        counts[1]++;
                    } else if (info.isSymlink()) {
                        if (writeSymlink(root, target, info, options, warnings)) counts[0]++;
                        else counts[2]++;
                    } else if (writeFile(root, target, info, data, options, job, bytes)) {
                        counts[0]++;
                    } else {
                        counts[2]++;
                    }
                } catch (SecurityException exception) {
                    warnings.add("Skipped " + info.path() + ": " + exception.getMessage());
                    counts[2]++;
                }
            }, new ArrayList<>()); // unusable names were already reported by list() above
        }
        return new ExtractResult(counts[0], counts[1], bytes[0], counts[2], warnings);
    }

    /**
     * Reads every byte of every entry and checks it. Returns the entries that failed, or an empty list.
     */
    public static List<String> test(Path archive, Job job) throws IOException {
        return test(archive, null, job);
    }

    /**
     * @throws PasswordException if the archive needs a password and {@code password} is missing or wrong
     */
    public static List<String> test(Path archive, char[] password, Job job) throws IOException {
        Listing listing = list(archive, password);
        job.start(listing.totalSize());
        List<String> failures = new ArrayList<>();
        byte[] buffer = new byte[BUFFER];
        try (Source source = open(archive, password)) {
            source.forEach((info, data) -> {
                job.throwIfCancelled();
                if (info.directory() || info.isSymlink()) return;
                job.setCurrent(info.path());
                try (InputStream in = data.open()) {
                    int count;
                    while ((count = in.read(buffer)) > 0) {
                        job.advance(count);
                        job.throwIfCancelled();
                    }
                } catch (PasswordException exception) {
                    throw exception; // every other entry would fail the same way
                } catch (IOException exception) {
                    failures.add(info.path() + ": " + exception.getMessage());
                }
            }, new ArrayList<>());
        }
        return failures;
    }

    private static boolean wanted(EntryInfo entry, ExtractOptions options) {
        if (options.skipJunk() && SafePaths.isJunkPath(entry.path())) return false;
        Set<String> only = options.only();
        if (only == null) return true;
        for (String chosen : only) {
            if (entry.path().equals(chosen) || entry.path().startsWith(chosen + "/")) return true;
        }
        // The folders above a wanted file are created on demand by writeFile, so they need no entry of their own.
        return false;
    }

    /**
     * Creates {@code dir} and its parents one segment at a time, refusing to pass through a symlink that leads out of
     * {@code root}. Each segment is checked before anything is created below it; checking afterwards would already
     * have made folders outside the destination.
     */
    private static void ensureDirectory(Path root, Path dir) throws IOException {
        if (dir.equals(root)) return;
        Path current = root;
        for (Path part : root.relativize(dir)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                try {
                    if (!current.toRealPath().startsWith(root)) throw new SecurityException(LEAVES_DESTINATION);
                } catch (IOException brokenLink) {
                    throw new SecurityException(LEAVES_DESTINATION);
                }
            } else if (!Files.exists(current)) {
                Files.createDirectory(current);
            } else if (!Files.isDirectory(current)) {
                throw new IOException("a file named " + part + " is in the way");
            }
        }
    }

    private static final String LEAVES_DESTINATION = "a folder in the path is a link that leaves the destination";

    private static boolean writeFile(Path root, Path target, EntryInfo info, DataSource data, ExtractOptions options,
                                     Job job, long[] bytes) throws IOException {
        ensureDirectory(root, target.getParent());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (options.overwrite() == Overwrite.SKIP) {
                job.advance(Math.max(0, info.size()));
                return false;
            }
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new SecurityException("a folder with this name already exists");
            }
            // Replace the link itself rather than writing through it to wherever it points.
            deleteExisting(target);
        }
        byte[] buffer = new byte[BUFFER];
        boolean created = false;
        try (InputStream in = data.open();
             OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            created = true;
            int count;
            while ((count = in.read(buffer)) > 0) {
                out.write(buffer, 0, count);
                bytes[0] += count;
                job.advance(count);
                job.throwIfCancelled();
            }
        } catch (IOException | RuntimeException exception) {
            // A wrong password, a corrupt entry or a cancel must not leave half a file that looks finished.
            if (created) Files.deleteIfExists(target);
            throw exception;
        }
        if (info.modified() >= 0) Files.setLastModifiedTime(target, FileTime.fromMillis(info.modified()));
        applyMode(target, info.mode());
        return true;
    }

    private static boolean writeSymlink(Path root, Path target, EntryInfo info, ExtractOptions options, List<String> warnings)
            throws IOException {
        if (!SafePaths.linkStaysInside(root, target, info.linkTarget())) {
            warnings.add("Skipped link " + info.path() + " -> " + info.linkTarget() + ": it points outside the destination");
            return false;
        }
        ensureDirectory(root, target.getParent());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (options.overwrite() == Overwrite.SKIP) return false;
            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS))
                throw new SecurityException("a folder with this name already exists");
            deleteExisting(target);
        }
        try {
            Files.createSymbolicLink(target, Path.of(info.linkTarget()));
            return true;
        } catch (UnsupportedOperationException | FileAlreadyExistsException exception) {
            warnings.add("Skipped link " + info.path() + ": " + exception);
        } catch (IOException exception) {
            // Windows needs a privilege to make links.
            warnings.add("Skipped link " + info.path() + ": " + exception.getMessage());
        }
        return false;
    }

    /**
     * Deletes a file that is about to be replaced. Windows refuses to delete a read-only one until that is cleared.
     */
    private static void deleteExisting(Path target) throws IOException {
        try {
            Files.delete(target);
        } catch (AccessDeniedException exception) {
            if (!target.toFile().setWritable(true)) throw exception;
            Files.delete(target);
        }
    }

    /**
     * Keeps the executable bit and the rest of the permissions, where the file system has them.
     */
    private static void applyMode(Path file, int mode) {
        if (mode < 0) return;
        Set<PosixFilePermission> perms = new HashSet<>();
        int bits = (mode & 0777) | 0400; // never leave a file the owner can't read
        PosixFilePermission[] all = {
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE};
        for (int i = 0; i < 9; i++) {
            if ((bits & (0400 >> i)) != 0) perms.add(all[i]);
        }
        try {
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Not a POSIX file system (Windows). Permissions don't apply there.
        }
    }

    private static Source open(Path archive, char[] password) throws IOException {
        ArchiveFormat format = ArchiveFormat.detect(archive)
                .orElseThrow(() -> new IOException("Not a supported archive: " + archive.getFileName()));
        return switch (format) {
            case ZIP -> new ZipSource(archive, password);
            case SEVEN_Z -> new SevenZSource(archive, password);
            case RAR -> new RarSource(archive, password);
            case GZ, XZ, BZ2 -> openCompressed(archive, format);
            default -> new TarSource(decompressed(archive, format));
        };
    }

    private static InputStream decompressed(Path path, ArchiveFormat format) throws IOException {
        InputStream raw = new BufferedInputStream(Files.newInputStream(path), BUFFER);
        try {
            return switch (format) {
                case TAR_GZ -> new GzipCompressorInputStream(raw);
                case GZ -> new GzipCompressorInputStream(raw, true); // gzip files can be several members joined
                case TAR_BZ2, BZ2 -> new BZip2CompressorInputStream(raw);
                case TAR_XZ, XZ -> new XZCompressorInputStream(raw);
                default -> raw;
            };
        } catch (IOException | RuntimeException exception) {
            raw.close();
            throw exception;
        }
    }

    private static final int TAR_BLOCK = 512;

    /**
     * A tar block names its format at offset 257: "ustar" for POSIX and GNU tar.
     */
    private static boolean looksLikeTar(byte[] head) {
        return head.length == TAR_BLOCK && head[257] == 'u' && head[258] == 's' && head[259] == 't' && head[260] == 'a' && head[261] == 'r';
    }

    /**
     * A {@code .gz}, {@code .xz} or {@code .bz2}: a tarball if that is what it holds, otherwise the one file inside.
     */
    private static Source openCompressed(Path path, ArchiveFormat format) throws IOException {
        BufferedInputStream in = new BufferedInputStream(decompressed(path, format), BUFFER);
        try {
            in.mark(TAR_BLOCK);
            byte[] head = in.readNBytes(TAR_BLOCK);
            in.reset();
            return looksLikeTar(head) ? new TarSource(in) : new SingleFileSource(path, format, in);
        } catch (IOException | RuntimeException exception) {
            in.close();
            throw exception;
        }
    }

    /**
     * Cleans up the name and reports it if it is unusable. Returns null for entries to ignore.
     */
    static String cleanName(String raw, List<String> skipped) {
        String path = SafePaths.normalize(raw);
        if (path == null) {
            // "./" and "/" alone are just the archive's root, which isn't worth a warning.
            if (raw != null && !raw.isBlank() && !raw.equals("./") && !raw.equals("/")) {
                skipped.add("Skipped " + raw + ": it points outside the destination");
            }
        }
        return path;
    }

    private static final class ZipSource implements Source {
        private final Path path;
        private final char[] password;
        private final ZipFile zip;
        private net.lingala.zip4j.ZipFile encryptedView; // opened the first time an encrypted entry is read
        private boolean encrypted;

        ZipSource(Path path, char[] password) throws IOException {
            this.path = path;
            this.password = password == null || password.length == 0 ? null : password;
            this.zip = ZipFile.builder().setPath(path).get();
        }

        @Override
        public void forEach(EntryVisitor visitor, List<String> skipped) throws IOException {
            Enumeration<ZipArchiveEntry> entries = zip.getEntriesInPhysicalOrder();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                String path = cleanName(entry.getName(), skipped);
                if (path == null) continue;
                boolean locked = entry.getGeneralPurposeBit().usesEncryption() && !entry.isDirectory();
                encrypted |= locked;
                int mode = entry.getPlatform() == ZipArchiveEntry.PLATFORM_UNIX ? entry.getUnixMode() & 07777 : -1;
                String link = null;
                if (entry.isUnixSymlink() && !entry.isDirectory()) {
                    // An encrypted link can't be read for a plain listing; its target is only needed to extract it.
                    link = locked && password == null ? "" : new String(open(entry, locked).readAllBytes(), StandardCharsets.UTF_8);
                }
                EntryInfo info = new EntryInfo(path, entry.isDirectory(), entry.isDirectory() ? 0 : entry.getSize(),
                        entry.getCompressedSize(), entry.getTime(), mode, link);
                visitor.visit(info, () -> open(entry, locked));
            }
        }

        private InputStream open(ZipArchiveEntry entry, boolean locked) throws IOException {
            if (locked) return openEncrypted(entry);
            if (!zip.canReadEntryData(entry)) {
                throw new IOException("uses a compression method OpenZip can't read");
            }
            return new CrcCheckedStream(zip.getInputStream(entry), entry.getCrc());
        }

        private InputStream openEncrypted(ZipArchiveEntry entry) throws IOException {
            if (password == null) throw PasswordException.required();
            if (encryptedView == null) encryptedView = new net.lingala.zip4j.ZipFile(path.toFile(), password);
            FileHeader header = encryptedView.getFileHeader(entry.getName());
            if (header == null) throw new IOException("can't find " + entry.getName() + " to decrypt it");
            try {
                return encryptedView.getInputStream(header);
            } catch (ZipException exception) {
                if (exception.getType() == ZipException.Type.WRONG_PASSWORD) throw PasswordException.wrong(exception);
                throw exception;
            }
        }

        @Override
        public boolean sawEncrypted() {
            return encrypted;
        }

        @Override
        public void close() throws IOException {
            zip.close();
        }
    }

    /**
     * Checks the CRC-32 at the end of the entry. The zip reader does not do this on its own.
     */
    private static final class CrcCheckedStream extends InputStream {
        private final InputStream in;
        private final long expected;
        private final CRC32 crc = new CRC32();
        private boolean checked;

        CrcCheckedStream(InputStream in, long expected) {
            this.in = in;
            this.expected = expected;
        }

        @Override
        public int read() throws IOException {
            int value = in.read();
            if (value >= 0) crc.update(value);
            else verify();
            return value;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int count = in.read(buf, off, len);
            if (count > 0) crc.update(buf, off, count);
            else if (count < 0) verify();
            return count;
        }

        private void verify() throws IOException {
            if (checked) return;
            checked = true;
            if (expected >= 0 && crc.getValue() != expected)
                throw new IOException("checksum mismatch, the data is corrupt");
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    private static final class TarSource implements Source {
        private final TarArchiveInputStream tar;

        TarSource(InputStream decompressed) throws IOException {
            try {
                this.tar = new TarArchiveInputStream(decompressed, "UTF-8"); // not the JVM default, which can be a legacy charset
            } catch (RuntimeException exception) {
                decompressed.close();
                throw exception;
            }
        }

        @Override
        public void forEach(EntryVisitor visitor, List<String> skipped) throws IOException {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String path = cleanName(entry.getName(), skipped);
                if (path == null) continue;
                boolean link = entry.isSymbolicLink();
                if (!entry.isDirectory() && !entry.isFile() && !link) {
                    skipped.add("Skipped " + path + ": a " + (entry.isLink() ? "hard link" : "special file") + " can't be extracted");
                    continue;
                }
                EntryInfo info = new EntryInfo(path, entry.isDirectory(), entry.isDirectory() ? 0 : entry.getSize(), -1,
                        entry.getModTime() == null ? -1 : entry.getModTime().getTime(), entry.getMode() & 07777,
                        link ? entry.getLinkName() : null);
                visitor.visit(info, () -> new NonClosingStream(tar));
            }
        }

        @Override
        public boolean sawEncrypted() {
            return false;
        }

        @Override
        public void close() throws IOException {
            tar.close();
        }
    }

    /**
     * One compressed file, shown as the single entry it unpacks to: {@code access.log.gz} holds {@code access.log}.
     */
    private static final class SingleFileSource implements Source {
        private final Path path;
        private final ArchiveFormat format;
        private final InputStream in;

        SingleFileSource(Path path, ArchiveFormat format, InputStream in) {
            this.path = path;
            this.format = format;
            this.in = in;
        }

        @Override
        public void forEach(EntryVisitor visitor, List<String> skipped) throws IOException {
            String name = cleanName(ArchiveFormat.baseName(path), skipped);
            if (name == null) name = "data";
            long packed = Files.size(path);
            long modified = Files.getLastModifiedTime(path).toMillis();
            visitor.visit(new EntryInfo(name, false, format == ArchiveFormat.GZ ? gzipSize(packed) : -1, packed, modified, -1, null),
                    () -> new NonClosingStream(in));
        }

        /**
         * gzip ends with the unpacked size modulo 2^32; xz and bzip2 don't record it, so theirs shows as unknown.
         */
        private long gzipSize(long packed) throws IOException {
            if (packed < 18) return -1;
            try (var file = new java.io.RandomAccessFile(path.toFile(), "r")) {
                file.seek(packed - 4);
                int b0 = file.read(), b1 = file.read(), b2 = file.read(), b3 = file.read();
                return (b0 | b1 << 8 | b2 << 16 | (long) b3 << 24) & 0xFFFFFFFFL;
            }
        }

        @Override
        public boolean sawEncrypted() {
            return false;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    private static final class SevenZSource implements Source {
        private final SevenZFile sevenZ;
        private final boolean hasPassword;
        private boolean encrypted;

        SevenZSource(Path path, char[] password) throws IOException {
            this.hasPassword = password != null && password.length > 0;
            try {
                SevenZFile.Builder builder = SevenZFile.builder().setPath(path);
                if (hasPassword) builder.setPassword(password);
                this.sevenZ = builder.get();
            } catch (org.apache.commons.compress.PasswordRequiredException exception) {
                throw PasswordException.required();
            } catch (IOException exception) {
                // With encrypted names a wrong password only shows up as a header that makes no sense.
                if (hasPassword && !(exception instanceof PasswordException))
                    throw PasswordException.wrongOrDamaged(exception);
                throw exception;
            }
        }

        private static boolean usesEncryption(SevenZArchiveEntry entry) {
            Iterable<? extends SevenZMethodConfiguration> methods = entry.getContentMethods();
            if (methods == null) return false;
            for (SevenZMethodConfiguration method : methods) {
                if (method.getMethod() == SevenZMethod.AES256SHA256) return true;
            }
            return false;
        }

        /**
         * Reading with a wrong key fails somewhere inside the decoder; say that instead of the decoder's complaint.
         */
        private IOException blame(IOException exception, boolean locked) {
            if (exception instanceof PasswordException) return exception;
            if (exception instanceof org.apache.commons.compress.PasswordRequiredException)
                return PasswordException.required();
            return locked && hasPassword ? PasswordException.wrongOrDamaged(exception) : exception;
        }

        @Override
        public void forEach(EntryVisitor visitor, List<String> skipped) throws IOException {
            while (true) {
                SevenZArchiveEntry next;
                try {
                    next = sevenZ.getNextEntry();
                } catch (IOException exception) {
                    throw blame(exception, encrypted);
                }
                if (next == null) return;
                String path = cleanName(next.getName(), skipped);
                if (path == null) continue;
                boolean locked = usesEncryption(next);
                encrypted |= locked;
                int attrs = next.getHasWindowsAttributes() ? next.getWindowsAttributes() : 0;
                int mode = (attrs & 0x8000) != 0 ? (attrs >>> 16) : -1; // high half holds Unix mode when this bit is set
                boolean symlink = mode >= 0 && (mode & 0170000) == 0120000 && !next.isDirectory();
                String link = null;
                if (symlink) {
                    if (locked && !hasPassword) {
                        link = ""; // only the extraction needs the target, and that asks for the password first
                    } else {
                        try {
                            link = new String(sevenZ.getInputStream(next).readAllBytes(), StandardCharsets.UTF_8);
                        } catch (IOException exception) {
                            throw blame(exception, locked);
                        }
                    }
                }
                EntryInfo info = new EntryInfo(path, next.isDirectory(), next.isDirectory() ? 0 : next.getSize(), -1,
                        next.getHasLastModifiedDate() ? next.getLastModifiedDate().getTime() : -1,
                        mode >= 0 ? mode & 07777 : -1, link);
                final SevenZArchiveEntry entry = next;
                visitor.visit(info, () -> {
                    InputStream in;
                    try {
                        in = sevenZ.getInputStream(entry);
                    } catch (IOException exception) {
                        throw blame(exception, locked);
                    }
                    return new NonClosingStream(in) {
                        @Override
                        public int read() throws IOException {
                            try {
                                return super.read();
                            } catch (IOException exception) {
                                throw blame(exception, locked);
                            }
                        }

                        @Override
                        public int read(byte[] buffer, int off, int len) throws IOException {
                            try {
                                return super.read(buffer, off, len);
                            } catch (IOException exception) {
                                throw blame(exception, locked);
                            }
                        }
                    };
                });
            }
        }

        @Override
        public boolean sawEncrypted() {
            return encrypted;
        }

        @Override
        public void close() throws IOException {
            sevenZ.close();
        }
    }

    /**
     * Lets the caller use try-with-resources on a stream that belongs to the whole archive.
     */
    private static class NonClosingStream extends InputStream {
        private final InputStream in;

        NonClosingStream(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            return in.read();
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            return in.read(buffer, off, len);
        }

        @Override
        public void close() {
            // the archive stream stays open for the next entry
        }
    }
}
