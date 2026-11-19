package me.piitex.openzip.archive;

import net.lingala.zip4j.io.outputstream.ZipOutputStream;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.CompressionLevel;
import net.lingala.zip4j.model.enums.CompressionMethod;
import net.lingala.zip4j.model.enums.EncryptionMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipParameters;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.tukaani.xz.LZMA2Options;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Creates archives. Meant to run on a worker thread.
 */
public final class ArchiveWriter {
    private static final int BUFFER = 64 * 1024;
    private static final int S_IFDIR = 040000;
    private static final int S_IFREG = 0100000;
    private static final int S_IFLNK = 0120000;

    private ArchiveWriter() {
    }

    /**
     * @param level    0 (fastest, no compression) to 9 (smallest), or -1 for the format's default
     * @param skipJunk leave out {@code .DS_Store}, {@code ._*}, {@code __MACOSX} and similar
     * @param password encrypts the contents with AES-256 (zip and 7z only), or null for no encryption. Not copied,
     *                 and not cleared: the caller owns the array.
     */
    public record CreateOptions(ArchiveFormat format, int level, boolean skipJunk, char[] password) {
        public CreateOptions {
            if (password != null && password.length == 0) password = null;
            if (password != null && !format.supportsPassword()) {
                throw new IllegalArgumentException(format.label() + " archives can't be password protected");
            }
        }

        public CreateOptions(ArchiveFormat format, int level, boolean skipJunk) {
            this(format, level, skipJunk, null);
        }

        public CreateOptions(ArchiveFormat format) {
            this(format, -1, true, null);
        }

        public boolean encrypted() {
            return password != null;
        }
    }

    public record CreateResult(Path archive, int files, int folders, long uncompressedBytes, long archiveBytes) {
        public double ratio() {
            return uncompressedBytes <= 0 ? 1 : (double) archiveBytes / uncompressedBytes;
        }
    }

    private record Item(Path file, String name, boolean directory, boolean symlink, long size, long modified,
                        int mode) {
        Item named(String newName) {
            return new Item(file, newName, directory, symlink, size, modified, mode);
        }
    }

    /**
     * Whether a file or folder of this name is left out of archives when junk is skipped ({@code .DS_Store}, {@code __MACOSX}...).
     */
    public static boolean isJunk(String name) {
        return SafePaths.isJunkName(name);
    }

    /**
     * Whether {@link #addTo} works on this kind of archive: zip, which takes new entries without repacking the rest, and
     * tar, whose entries are copied across into a new tar (decompressed and compressed again for .gz, .xz and .bz2).
     * 7z would be the same job again and RAR can't be written.
     */
    public static boolean canAddTo(ArchiveFormat format) {
        return format == ArchiveFormat.ZIP || format.isTar();
    }

    /**
     * Adds {@code inputs} (files and folders, kept by name) to the zip or tar {@code archive}, inside {@code folder} ("" for
     * the top). In a zip the entries already there are copied as they are, without being unpacked and compressed again;
     * a tar is written anew from its old entries (same compression as before, so a .tar.gz is unpacked and packed again
     * and takes about as long as making it). An existing entry with the same name as a new one is replaced, and a folder that already exists is merged into.
     * Like {@link #create}, the result is built beside the archive and moved over it at the end, so a failure or a
     * cancel leaves the archive exactly as it was. Not for encrypted zips: the new entries would not be.
     *
     * @param folder where in the archive, without a leading or trailing slash
     */
    public static CreateResult addTo(Path archive, String folder, List<Path> inputs, boolean skipJunk, Job job) throws IOException {
        ArchiveFormat format = ArchiveFormat.detect(archive).filter(ArchiveWriter::canAddTo)
                .orElseThrow(() -> new UnsupportedOperationException("OpenZip can only add to zip and tar archives"));
        if (inputs.isEmpty()) throw new IOException("Nothing to add");

        Path target = archive.toRealPath(); // a link to the archive stays a link: the file it points at is what is replaced
        String prefix = folder.isEmpty() ? "" : folder + "/";
        List<Item> items = new ArrayList<>();
        for (Item item : collect(inputs, target, skipJunk, job)) items.add(item.named(prefix + item.name()));
        if (items.isEmpty()) throw new IOException("Nothing to add");

        Set<String> exact = new HashSet<>();
        Set<String> fileNames = new HashSet<>();
        for (Item item : items) {
            if (!exact.add(item.name() + (item.directory() ? "/" : ""))) {
                throw new IOException("Two of the items are both called " + item.name().substring(prefix.length()).split("/")[0]);
            }
            if (!item.directory()) fileNames.add(item.name());
        }
        // A new file takes the place of an old entry of that name, and of an old folder of that name with all it holds.
        Predicate<String> replaced = name -> {
            if (exact.contains(name) || exact.contains(name + "/")) return true;
            for (int slash = name.lastIndexOf('/'); slash > 0; slash = name.lastIndexOf('/', slash - 1)) {
                if (fileNames.contains(name.substring(0, slash))) return true;
            }
            return false;
        };

        long added = items.stream().filter(item -> !item.directory() && !item.symlink()).mapToLong(Item::size).sum();
        job.start(added + Files.size(target)); // the copying of what is already there counts towards the progress

        rewrite(target, format, replaced, items, () -> {
        }, job);
        int folders = (int) items.stream().filter(Item::directory).count();
        return new CreateResult(target, items.size() - folders, folders, added, Files.size(target));
    }

    /**
     * What {@link #deleteFrom} took out: the files (links included) and the folders that are gone.
     */
    public record DeleteResult(Path archive, int files, int folders) {
    }

    /**
     * Whether {@link #deleteFrom} works on this kind of archive: the same ones {@link #addTo} does.
     */
    public static boolean canDeleteFrom(ArchiveFormat format) {
        return canAddTo(format);
    }

    /**
     * Removes the entries at {@code paths} from the zip or tar {@code archive}, and everything inside a folder that is
     * named. Built beside the archive and moved over it at the end, like {@link #addTo}, so a failure or a cancel leaves
     * the archive exactly as it was. A zip keeps what stays as it is, packed bytes and comment; a tar is written again
     * from what stays. Not for password protected zips.
     *
     * @param paths entry paths as {@link EntryInfo#path} gives them, folders without a trailing slash
     * @throws IOException if none of them is in the archive
     */
    public static DeleteResult deleteFrom(Path archive, Collection<String> paths, Job job) throws IOException {
        ArchiveFormat format = ArchiveFormat.detect(archive).filter(ArchiveWriter::canDeleteFrom)
                .orElseThrow(() -> new UnsupportedOperationException("OpenZip can only delete from zip and tar archives"));
        Set<String> doomed = new HashSet<>();
        for (String path : paths) {
            String clean = SafePaths.normalize(path);
            if (clean != null) doomed.add(clean);
        }
        if (doomed.isEmpty()) throw new IOException("Nothing to delete");

        Path target = archive.toRealPath();
        int[] gone = new int[2]; // files, folders
        Predicate<String> dropped = name -> {
            String clean = SafePaths.normalize(name);
            if (clean == null) return false; // an unsafe name was never listed, so it was never chosen
            boolean hit = doomed.contains(clean);
            for (int slash = clean.lastIndexOf('/'); !hit && slash > 0; slash = clean.lastIndexOf('/', slash - 1)) {
                hit = doomed.contains(clean.substring(0, slash));
            }
            if (hit) gone[name.endsWith("/") ? 1 : 0]++;
            return hit;
        };
        job.start(Files.size(target));
        rewrite(target, format, dropped, List.of(), () -> {
            if (gone[0] + gone[1] == 0)
                throw new IOException("None of those are in " + archive.getFileName() + " any more");
        }, job);
        return new DeleteResult(target, gone[0], gone[1]);
    }

    /**
     * A last look at a rewritten archive before it takes the place of the original.
     */
    private interface Check {
        void run() throws IOException;
    }

    /**
     * Writes {@code target} again beside itself without the entries {@code dropped} names and with {@code items} added,
     * then moves it over the original unless {@code beforeMove} throws. A failure or cancel removes the new file and
     * leaves the original alone.
     */
    private static void rewrite(Path target, ArchiveFormat format, Predicate<String> dropped, List<Item> items,
                                Check beforeMove, Job job) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), ".openzip-", ".part");
        try {
            try {
                Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(target)); // not the temp file's 0600
            } catch (UnsupportedOperationException | IOException ignored) {
                // No Unix permissions here (Windows), or they can't be read; the new file keeps its defaults.
            }
            if (format == ArchiveFormat.ZIP) {
                try (ZipSink sink = new ZipSink(temp, -1)) {
                    sink.copyFrom(target, dropped, job);
                    addAll(sink, items, job);
                }
            } else {
                try (TarSink sink = new TarSink(temp, new CreateOptions(format))) {
                    sink.copyFrom(target, format, dropped, job);
                    addAll(sink, items, job);
                }
            }
            job.throwIfCancelled();
            beforeMove.run();
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(temp);
            throw exception;
        }
    }

    private static void addAll(Sink sink, List<Item> items, Job job) throws IOException {
        byte[] buffer = new byte[BUFFER];
        for (Item item : items) {
            job.throwIfCancelled();
            job.setCurrent(item.name());
            sink.add(item, job, buffer);
        }
    }

    /**
     * Packs {@code inputs} (files and folders, kept by name at the top level) into {@code output}. The archive is
     * built next to the output as a temporary file and moved into place at the end, so a failure or a cancel never
     * leaves a half-written archive and never destroys an existing one.
     */
    public static CreateResult create(Path output, List<Path> inputs, CreateOptions options, Job job) throws IOException {
        if (!options.format().canWrite()) {
            throw new UnsupportedOperationException("OpenZip can read " + options.format().label() + " but not create it yet");
        }
        if (inputs.isEmpty()) throw new IOException("Nothing to add");

        Path absoluteOutput = output.toAbsolutePath().normalize();
        List<Item> items = collect(inputs, absoluteOutput, options.skipJunk(), job);
        long total = items.stream().filter(item -> !item.directory() && !item.symlink()).mapToLong(Item::size).sum();
        job.start(total);

        Path temp = Files.createTempFile(absoluteOutput.getParent(), ".openzip-", ".part");
        try {
            try (Sink sink = openSink(temp, options)) {
                byte[] buffer = new byte[BUFFER];
                for (Item item : items) {
                    job.throwIfCancelled();
                    job.setCurrent(item.name());
                    sink.add(item, job, buffer);
                }
            }
            Files.move(temp, absoluteOutput, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(temp);
            throw exception;
        }
        int folders = (int) items.stream().filter(Item::directory).count();
        return new CreateResult(absoluteOutput, items.size() - folders, folders, total, Files.size(absoluteOutput));
    }

    private static List<Item> collect(List<Path> inputs, Path output, boolean skipJunk, Job job) throws IOException {
        List<Item> items = new ArrayList<>();
        for (Path input : inputs) {
            Path root = input.toAbsolutePath().normalize();
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not found: " + input);
            String base = root.getFileName() == null ? "root" : root.getFileName().toString();
            if (skipJunk && SafePaths.isJunkName(base)) continue;
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    job.throwIfCancelled();
                    if (skipJunk && SafePaths.isJunkName(dir.getFileName() == null ? "" : dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    items.add(item(dir, nameOf(root, base, dir), attrs));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    job.throwIfCancelled();
                    if (skipJunk && SafePaths.isJunkName(file.getFileName().toString()))
                        return FileVisitResult.CONTINUE;
                    if (file.equals(output) || file.getFileName().toString().startsWith(".openzip-")) {
                        return FileVisitResult.CONTINUE; // never pack the archive into itself
                    }
                    if (!attrs.isRegularFile() && !attrs.isSymbolicLink())
                        return FileVisitResult.CONTINUE; // sockets, devices
                    items.add(item(file, nameOf(root, base, file), attrs));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    throw new IOException("Can't read " + file + ": " + exc.getMessage(), exc);
                }
            });
        }
        return items;
    }

    private static String nameOf(Path root, String base, Path path) {
        if (path.equals(root)) return base;
        return base + "/" + root.relativize(path).toString().replace('\\', '/');
    }

    private static Item item(Path file, String name, BasicFileAttributes attrs) {
        int mode;
        try {
            mode = (Integer) Files.getAttribute(file, "unix:mode", LinkOption.NOFOLLOW_LINKS) & 07777;
        } catch (UnsupportedOperationException | IllegalArgumentException | IOException exception) {
            mode = defaultMode(file.getFileName().toString(), attrs.isDirectory()); // Windows has no mode bits
        }
        return new Item(file, name, attrs.isDirectory(), attrs.isSymbolicLink(),
                attrs.isRegularFile() ? attrs.size() : 0, attrs.lastModifiedTime().toMillis(), mode);
    }

    private static final java.util.Set<String> PROGRAM_EXTENSIONS = java.util.Set.of(
            "exe", "bat", "cmd", "com", "ps1", "sh", "msi", "command", "py", "pl", "rb");

    /**
     * The mode for a file on a system without Unix permissions. {@code Files.isExecutable} is no help there: Windows
     * answers yes for nearly every file, which would make every file in a zip executable once it is extracted on a Mac
     * or Linux machine. A program or script is marked executable by its extension, everything else is not.
     */
    static int defaultMode(String fileName, boolean directory) {
        if (directory) return 0755;
        int dot = fileName.lastIndexOf('.');
        String ext = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return PROGRAM_EXTENSIONS.contains(ext) ? 0755 : 0644;
    }

    private interface Sink extends Closeable {
        void add(Item item, Job job, byte[] buffer) throws IOException;
    }

    private static Sink openSink(Path temp, CreateOptions options) throws IOException {
        return switch (options.format()) {
            case ZIP -> options.encrypted() ? new EncryptedZipSink(temp, options) : new ZipSink(temp, options.level());
            case SEVEN_Z -> new SevenZSink(temp, options);
            default -> new TarSink(temp, options);
        };
    }

    private static void copy(Path file, OutputStream out, Job job, byte[] buffer) throws IOException {
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            int count;
            while ((count = in.read(buffer)) > 0) {
                out.write(buffer, 0, count);
                job.advance(count);
                job.throwIfCancelled();
            }
        }
    }

    private static final class ZipSink implements Sink {
        private final ZipArchiveOutputStream zip;

        ZipSink(Path temp, int level) throws IOException {
            this.zip = new ZipArchiveOutputStream(temp);
            if (level >= 0) zip.setLevel(level);
        }

        /**
         * Copies the entries of the zip at {@code source} that {@code replaced} doesn't name, packed bytes and all.
         */
        void copyFrom(Path source, Predicate<String> replaced, Job job) throws IOException {
            try (ZipFile existing = ZipFile.builder().setPath(source).get()) {
                String comment = commentOf(source);
                if (comment != null) zip.setComment(comment);
                Enumeration<ZipArchiveEntry> entries = existing.getEntriesInPhysicalOrder();
                while (entries.hasMoreElements()) {
                    job.throwIfCancelled();
                    ZipArchiveEntry entry = entries.nextElement();
                    if (entry.getGeneralPurposeBit().usesEncryption()) {
                        // Copied raw, an AES entry comes out in a form that can no longer be read, so it is left alone.
                        throw new IOException("This zip is password protected, and OpenZip can't change those yet");
                    }
                    if (replaced.test(entry.getName())) continue;
                    try (InputStream raw = new FilterInputStream(existing.getRawInputStream(entry)) {
                        @Override
                        public int read() throws IOException {
                            int value = super.read();
                            if (value >= 0) job.advance(1);
                            return value;
                        }

                        @Override
                        public int read(byte[] buffer, int off, int len) throws IOException {
                            int count = super.read(buffer, off, len);
                            if (count > 0) {
                                job.advance(count);
                                job.throwIfCancelled();
                            }
                            return count;
                        }
                    }) {
                        zip.addRawArchiveEntry(entry, raw);
                    }
                }
            }
        }

        /**
         * The comment in the zip's end-of-central-directory record, or null. The zip reader doesn't give it out.
         */
        private static String commentOf(Path zipFile) throws IOException {
            try (SeekableByteChannel channel = Files.newByteChannel(zipFile)) {
                long size = channel.size();
                int length = (int) Math.min(size, 22 + 0xFFFF);
                ByteBuffer tail = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
                channel.position(size - length);
                while (tail.hasRemaining() && channel.read(tail) > 0) {
                    // read until the tail is full
                }
                for (int i = length - 22; i >= 0; i--) {
                    if (tail.getInt(i) != 0x06054b50) continue;
                    int commentLength = tail.getShort(i + 20) & 0xFFFF;
                    if (i + 22 + commentLength != length)
                        continue; // a signature inside someone's comment, not the record
                    return commentLength == 0 ? null : new String(tail.array(), i + 22, commentLength, StandardCharsets.UTF_8);
                }
            }
            return null;
        }

        @Override
        public void add(Item item, Job job, byte[] buffer) throws IOException {
            String name = item.directory() ? item.name() + "/" : item.name();
            ZipArchiveEntry entry = new ZipArchiveEntry(name);
            entry.setTime(item.modified());
            if (item.directory()) {
                entry.setUnixMode(S_IFDIR | item.mode());
                zip.putArchiveEntry(entry);
            } else if (item.symlink()) {
                byte[] target = Files.readSymbolicLink(item.file()).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8);
                entry.setUnixMode(S_IFLNK | item.mode());
                entry.setSize(target.length);
                zip.putArchiveEntry(entry);
                zip.write(target);
            } else {
                entry.setUnixMode(S_IFREG | item.mode());
                entry.setSize(item.size());
                zip.putArchiveEntry(entry);
                copy(item.file(), zip, job, buffer);
            }
            zip.closeArchiveEntry();
        }

        @Override
        public void close() throws IOException {
            zip.close();
        }
    }

    /**
     * A zip whose entries are encrypted with AES-256. Written by zip4j; the plain zip path stays on Commons Compress.
     */
    private static final class EncryptedZipSink implements Sink {
        private final ZipOutputStream zip;
        private final int level;

        EncryptedZipSink(Path temp, CreateOptions options) throws IOException {
            this.level = options.level();
            this.zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(temp), BUFFER), options.password());
        }

        private ZipParameters parameters(Item item, String name, boolean encrypt) {
            ZipParameters parameters = new ZipParameters();
            parameters.setFileNameInZip(name);
            parameters.setLastModifiedFileTime(item.modified());
            parameters.setUnixMode(true);
            if (level == 0) {
                parameters.setCompressionMethod(CompressionMethod.STORE);
            } else {
                parameters.setCompressionMethod(CompressionMethod.DEFLATE);
                if (level > 0) parameters.setCompressionLevel(CompressionLevel.values()[Math.min(level, 9)]);
            }
            if (encrypt) {
                parameters.setEncryptFiles(true);
                parameters.setEncryptionMethod(EncryptionMethod.AES);
                parameters.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
            }
            return parameters;
        }

        @Override
        public void add(Item item, Job job, byte[] buffer) throws IOException {
            int type = item.directory() ? S_IFDIR : item.symlink() ? S_IFLNK : S_IFREG;
            if (item.directory()) {
                zip.putNextEntry(parameters(item, item.name() + "/", false)); // nothing to hide in a folder
            } else if (item.symlink()) {
                byte[] target = Files.readSymbolicLink(item.file()).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8);
                ZipParameters zipParameters = parameters(item, item.name(), true);
                zipParameters.setEntrySize(target.length);
                zip.putNextEntry(zipParameters);
                zip.write(target);
            } else {
                ZipParameters zipParameters = parameters(item, item.name(), true);
                zipParameters.setEntrySize(item.size());
                zip.putNextEntry(zipParameters);
                copy(item.file(), zip, job, buffer);
            }
            FileHeader header = zip.closeEntry();
            // zip4j writes DOS attributes only; the Unix mode goes in the high half like Info-ZIP and Commons do.
            int attributes = ((type | item.mode()) << 16) | (item.directory() ? 0x10 : 0);
            header.setExternalFileAttributes(new byte[]{
                    (byte) attributes, (byte) (attributes >>> 8), (byte) (attributes >>> 16), (byte) (attributes >>> 24)});
        }

        @Override
        public void close() throws IOException {
            zip.close();
        }
    }

    private static final class SevenZSink implements Sink {
        private final SevenZOutputFile sevenZ;

        SevenZSink(Path temp, CreateOptions options) throws IOException {
            this.sevenZ = options.encrypted()
                    ? new SevenZOutputFile(temp.toFile(), options.password())
                    : new SevenZOutputFile(temp.toFile());
            int level = options.level();
            if (level == 0) {
                sevenZ.setContentCompression(SevenZMethod.COPY);
            } else {
                sevenZ.setContentMethods(List.of(new SevenZMethodConfiguration(SevenZMethod.LZMA2,
                        new LZMA2Options(level < 0 ? 6 : level))));
            }
        }

        @Override
        public void add(Item item, Job job, byte[] buffer) throws IOException {
            SevenZArchiveEntry entry = new SevenZArchiveEntry();
            entry.setName(item.directory() ? item.name() + "/" : item.name());
            entry.setDirectory(item.directory());
            entry.setHasLastModifiedDate(true);
            entry.setLastModifiedDate(new Date(item.modified()));
            // The high half holds the Unix mode when bit 0x8000 is set: what p7zip and 7-Zip on Linux do.
            int type = item.directory() ? S_IFDIR : item.symlink() ? S_IFLNK : S_IFREG;
            entry.setHasWindowsAttributes(true);
            entry.setWindowsAttributes(((type | item.mode()) << 16) | 0x8000 | (item.directory() ? 0x10 : 0x20));
            sevenZ.putArchiveEntry(entry);
            if (item.symlink()) {
                sevenZ.write(Files.readSymbolicLink(item.file()).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
            } else if (!item.directory()) {
                try (InputStream in = Files.newInputStream(item.file(), LinkOption.NOFOLLOW_LINKS)) {
                    int count;
                    while ((count = in.read(buffer)) > 0) {
                        sevenZ.write(buffer, 0, count);
                        job.advance(count);
                        job.throwIfCancelled();
                    }
                }
            }
            sevenZ.closeArchiveEntry();
        }

        @Override
        public void close() throws IOException {
            sevenZ.close();
        }
    }

    private static final class TarSink implements Sink {
        private final TarArchiveOutputStream tar;

        TarSink(Path temp, CreateOptions options) throws IOException {
            OutputStream file = new BufferedOutputStream(Files.newOutputStream(temp), BUFFER);
            OutputStream compressed;
            try {
                int level = options.level();
                compressed = switch (options.format()) {
                    case TAR_GZ -> {
                        GzipParameters parameters = new GzipParameters();
                        if (level >= 0) parameters.setCompressionLevel(level);
                        yield new GzipCompressorOutputStream(file, parameters);
                    }
                    case TAR_XZ -> new XZCompressorOutputStream(file, level >= 0 ? level : 6);
                    case TAR_BZ2 -> new BZip2CompressorOutputStream(file, level >= 1 ? level : 9);
                    default -> file;
                };
            } catch (IOException | RuntimeException exception) {
                file.close();
                throw exception;
            }
            // UTF-8 names, whatever the JVM's default charset is: a legacy one (file.encoding=IBM437) turns characters it
            // can't hold into %U65E5 escapes, and PAX headers let bsdtar and GNU tar read the real name.
            this.tar = new TarArchiveOutputStream(compressed, "UTF-8");
            tar.setAddPaxHeadersForNonAsciiNames(true);
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
        }

        /**
         * Copies the entries of the tar at {@code source} that {@code replaced} doesn't name, header and data, owner and
         * mode as they were. The progress follows the packed file being read, which is what {@code Files.size} counts.
         */
        void copyFrom(Path source, ArchiveFormat format, Predicate<String> replaced, Job job) throws IOException {
            InputStream raw = new FilterInputStream(new java.io.BufferedInputStream(Files.newInputStream(source), BUFFER)) {
                @Override
                public int read() throws IOException {
                    int value = super.read();
                    if (value >= 0) job.advance(1);
                    return value;
                }

                @Override
                public int read(byte[] buffer, int off, int len) throws IOException {
                    int count = super.read(buffer, off, len);
                    if (count > 0) {
                        job.advance(count);
                        job.throwIfCancelled();
                    }
                    return count;
                }
            };
            try (InputStream unpacked = switch (format) {
                case TAR_GZ -> new GzipCompressorInputStream(raw, true);
                case TAR_XZ -> new XZCompressorInputStream(raw);
                case TAR_BZ2 -> new BZip2CompressorInputStream(raw);
                default -> raw;
            };
                 TarArchiveInputStream old = new TarArchiveInputStream(unpacked, "UTF-8")) {
                byte[] buffer = new byte[BUFFER];
                TarArchiveEntry entry;
                while ((entry = old.getNextEntry()) != null) {
                    job.throwIfCancelled();
                    String path = SafePaths.normalize(entry.getName()); // "./a/b" is the entry a new "a/b" replaces
                    if (path != null && replaced.test(entry.isDirectory() ? path + "/" : path)) continue;
                    tar.putArchiveEntry(entry);
                    int count;
                    while ((count = old.read(buffer)) > 0) tar.write(buffer, 0, count);
                    tar.closeArchiveEntry();
                }
            }
        }

        @Override
        public void add(Item item, Job job, byte[] buffer) throws IOException {
            TarArchiveEntry entry;
            if (item.symlink()) {
                entry = new TarArchiveEntry(item.name(), TarConstants.LF_SYMLINK);
                entry.setLinkName(Files.readSymbolicLink(item.file()).toString().replace('\\', '/'));
            } else {
                entry = new TarArchiveEntry(item.directory() ? item.name() + "/" : item.name());
                if (!item.directory()) entry.setSize(item.size());
            }
            entry.setMode(item.mode());
            entry.setModTime(item.modified());
            entry.setIds(0, 0); // don't leak the owner's user id or name into the archive
            entry.setNames("", "");
            tar.putArchiveEntry(entry);
            if (!item.directory() && !item.symlink()) copy(item.file(), tar, job, buffer);
            tar.closeArchiveEntry();
        }

        @Override
        public void close() throws IOException {
            tar.close();
        }
    }
}
