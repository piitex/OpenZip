package me.piitex.openzip.archive;

import me.piitex.openzip.archive.ArchiveReader.ExtractOptions;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** A {@code .gz}, {@code .xz} or {@code .bz2} that is not a tarball is one compressed file. */
class SingleFileTest {
    @TempDir
    Path tmp;

    private Path compress(String name, byte[] data, Function<OutputStream, OutputStream> wrap) throws IOException {
        Path file = tmp.resolve(name);
        try (OutputStream out = wrap.apply(Files.newOutputStream(file))) {
            out.write(data);
        }
        return file;
    }

    private static OutputStream gzip(OutputStream output) {
        try {
            return new GzipCompressorOutputStream(output);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static OutputStream xz(OutputStream output) {
        try {
            return new XZCompressorOutputStream(output);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static OutputStream bzip2(OutputStream output) {
        try {
            return new BZip2CompressorOutputStream(output);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] text() {
        return "line one\nline two\n".repeat(500).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void namesAreRecognised() {
        assertEquals(ArchiveFormat.GZ, ArchiveFormat.detect(Path.of("access.log.gz")).orElseThrow());
        assertEquals(ArchiveFormat.XZ, ArchiveFormat.detect(Path.of("notes.txt.xz")).orElseThrow());
        assertEquals(ArchiveFormat.BZ2, ArchiveFormat.detect(Path.of("notes.txt.BZ2")).orElseThrow());
        assertEquals(ArchiveFormat.TAR_GZ, ArchiveFormat.detect(Path.of("photos.tar.gz")).orElseThrow(), "a tarball stays a tarball");
        assertEquals("access.log", ArchiveFormat.baseName(Path.of("access.log.gz")));
        assertFalse(ArchiveFormat.GZ.canWrite());
    }

    @Test
    void gzipShowsOneFileWithItsSize() throws IOException {
        Path gz = compress("access.log.gz", text(), SingleFileTest::gzip);
        var entries = ArchiveReader.list(gz).entries();
        assertEquals(1, entries.size());
        assertEquals("access.log", entries.getFirst().path());
        assertEquals(text().length, entries.getFirst().size());
        assertEquals(Files.size(gz), entries.getFirst().compressedSize());
    }

    @Test
    void xzAndBzip2ExtractTheirFile() throws IOException {
        for (var file : List.of(compress("a.txt.xz", text(), SingleFileTest::xz), compress("b.txt.bz2", text(), SingleFileTest::bzip2))) {
            Path dest = Files.createTempDirectory(tmp, "out");
            var result = ArchiveReader.extract(file, new ExtractOptions(dest), new Job());
            assertEquals(1, result.files());
            String name = ArchiveFormat.baseName(file);
            assertArrayEquals(text(), Files.readAllBytes(dest.resolve(name)));
            assertEquals(-1, ArchiveReader.list(file).entries().getFirst().size(), "xz and bzip2 don't record the size");
        }
    }

    @Test
    void renamedGzipIsStillRead() throws IOException {
        Path gz = compress("mystery.dat", text(), SingleFileTest::gzip);
        assertEquals(ArchiveFormat.GZ, ArchiveFormat.detect(gz).orElseThrow());
        assertEquals("mystery", ArchiveReader.list(gz).entries().getFirst().path());
    }

    @Test
    void tarballUnderAGzName() throws IOException {
        Path source = Files.createDirectories(tmp.resolve("pack"));
        Files.writeString(source.resolve("one.txt"), "1");
        Files.writeString(source.resolve("two.txt"), "2");
        Path tgz = tmp.resolve("pack.tar.gz");
        ArchiveWriter.create(tgz, List.of(source), new ArchiveWriter.CreateOptions(ArchiveFormat.TAR_GZ), new Job());
        Path plain = Files.copy(tgz, tmp.resolve("pack.gz"));

        List<String> names = ArchiveReader.list(plain).entries().stream().map(EntryInfo::path).toList();
        assertEquals(ArchiveReader.list(tgz).entries().stream().map(EntryInfo::path).toList(), names, "opened as the tar it is");
        assertTrue(names.contains("pack/one.txt"));
    }

    @Test
    void joinedGzipMembersAreAllRead() throws IOException {
        byte[] first = "first\n".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second\n".getBytes(StandardCharsets.UTF_8);
        Path firstFile = compress("a.gz", first, SingleFileTest::gzip);
        Path secondFile = compress("b.gz", second, SingleFileTest::gzip);
        Path joined = tmp.resolve("joined.gz");
        Files.write(joined, concat(Files.readAllBytes(firstFile), Files.readAllBytes(secondFile)));
        Path dest = Files.createDirectories(tmp.resolve("joined-out"));
        ArchiveReader.extract(joined, new ExtractOptions(dest), new Job());
        assertEquals("first\nsecond\n", Files.readString(dest.resolve("joined")));
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = new byte[first.length + second.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    @Test
    void corruptGzipFailsTheTestAndLeavesNoFile() throws IOException {
        byte[] data = new byte[300_000];
        new java.util.Random(1).nextBytes(data);
        Path gz = compress("noise.bin.gz", data, SingleFileTest::gzip);
        byte[] bytes = Files.readAllBytes(gz);
        bytes[bytes.length - 8] ^= 0x55; // the checksum
        Files.write(gz, bytes);

        assertFalse(ArchiveReader.test(gz, new Job()).isEmpty(), "the checksum catches it");
        Path dest = Files.createDirectories(tmp.resolve("bad-out"));
        assertThrows(IOException.class, () -> ArchiveReader.extract(gz, new ExtractOptions(dest), new Job()));
        try (var left = Files.list(dest)) {
            assertEquals(List.of(), left.toList(), "no half-written file");
        }
    }
}
