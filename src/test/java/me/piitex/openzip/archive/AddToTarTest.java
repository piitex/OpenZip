package me.piitex.openzip.archive;

import me.piitex.openzip.archive.ArchiveReader.ExtractOptions;
import me.piitex.openzip.archive.ArchiveWriter.CreateOptions;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

/** Adding to a tar means writing it again from its old entries, in whatever compression it had. */
class AddToTarTest {
    @TempDir
    Path tmp;

    private Path make(ArchiveFormat format) throws IOException {
        Path source = Files.createDirectories(tmp.resolve("project"));
        Files.writeString(source.resolve("readme.txt"), "old readme");
        Files.createDirectories(source.resolve("src"));
        Files.writeString(source.resolve("src/Main.java"), "class Main {}");
        Files.writeString(source.resolve("日本語.txt"), "unicode name");
        Files.createDirectories(source.resolve("empty"));
        Files.write(source.resolve("big.bin"), new byte[200_000]);
        Path archive = tmp.resolve("out" + format.extension());
        ArchiveWriter.create(archive, List.of(source), new CreateOptions(format), new Job());
        return archive;
    }

    private Path file(String name, String text) throws IOException {
        Path file = Files.createTempDirectory(tmp, "in").resolve(name);
        Files.writeString(file, text);
        return file;
    }

    private static List<String> names(Path archive) throws IOException {
        return ArchiveReader.list(archive).entries().stream().map(EntryInfo::path).toList();
    }

    private String read(Path archive, String entry) throws IOException {
        Path dest = Files.createTempDirectory(tmp, "x");
        ArchiveReader.extract(archive, new ExtractOptions(dest), new Job());
        return Files.readString(dest.resolve(entry));
    }

    private void assertNoLeftovers() throws IOException {
        try (var children = Files.list(tmp)) {
            assertEquals(List.of(), children.filter(path -> path.getFileName().toString().startsWith(".openzip-")).toList());
        }
    }

    @ParameterizedTest
    @EnumSource(value = ArchiveFormat.class, names = {"TAR", "TAR_GZ", "TAR_XZ", "TAR_BZ2"})
    void addsFilesAndFoldersAndKeepsEverythingElse(ArchiveFormat format) throws IOException {
        Path archive = make(format);
        long before = Files.size(archive);
        Path folder = Files.createDirectories(tmp.resolve("drop/Scans/page1"));
        Files.writeString(folder.resolve("one.txt"), "scan one");

        var result = ArchiveWriter.addTo(archive, "", List.of(file("report.txt", "the report"), folder.getParent()), true, new Job());

        assertEquals(2, result.files());
        assertEquals(2, result.folders());
        List<String> names = names(archive);
        assertTrue(names.containsAll(List.of("project/readme.txt", "project/src/Main.java", "project/日本語.txt", "project/empty",
                "report.txt", "Scans", "Scans/page1", "Scans/page1/one.txt")), names.toString());
        assertEquals("the report", read(archive, "report.txt"));
        assertEquals("unicode name", read(archive, "project/日本語.txt"));
        assertTrue(Files.size(archive) >= before - 1000, "still the same kind of archive, not an emptied one");
        assertEquals(List.of(), ArchiveReader.test(archive, new Job()));
        assertEquals(format, ArchiveFormat.detect(archive).orElseThrow());
        assertNoLeftovers();
    }

    @ParameterizedTest
    @EnumSource(value = ArchiveFormat.class, names = {"TAR", "TAR_GZ", "TAR_XZ", "TAR_BZ2"})
    void keepsItsCompression(ArchiveFormat format) throws IOException {
        Path archive = make(format);
        ArchiveWriter.addTo(archive, "", List.of(file("n.txt", "n")), true, new Job());
        byte[] head = new byte[6];
        try (var in = Files.newInputStream(archive)) {
            in.readNBytes(head, 0, 6);
        }
        switch (format) {
            case TAR_GZ -> assertTrue((head[0] & 0xFF) == 0x1F && (head[1] & 0xFF) == 0x8B);
            case TAR_BZ2 -> assertEquals("BZh", new String(head, 0, 3, StandardCharsets.US_ASCII));
            case TAR_XZ -> assertEquals("7zXZ", new String(head, 1, 4, StandardCharsets.US_ASCII));
            default -> assertNotEquals(0x1F, head[0] & 0xFF);
        }
    }

    @Test
    void addsIntoAFolderAndReplacesSameNames() throws IOException {
        Path archive = make(ArchiveFormat.TAR_GZ);
        ArchiveWriter.addTo(archive, "project", List.of(file("readme.txt", "NEW readme"), file("extra.txt", "extra")), true, new Job());

        List<String> names = names(archive);
        assertEquals(1, names.stream().filter("project/readme.txt"::equals).count(), "replaced, not duplicated");
        assertTrue(names.contains("project/extra.txt"));
        assertEquals("NEW readme", read(archive, "project/readme.txt"));
        assertEquals("class Main {}", read(archive, "project/src/Main.java"));
    }

    @Test
    void aFolderIsMergedAndAFileTakesTheNameOfAFolder() throws IOException {
        Path archive = make(ArchiveFormat.TAR);
        Path src = Files.createDirectories(tmp.resolve("drop/src"));
        Files.writeString(src.resolve("Extra.java"), "class Extra {}");
        ArchiveWriter.addTo(archive, "project", List.of(src), true, new Job());
        assertTrue(names(archive).containsAll(List.of("project/src/Main.java", "project/src/Extra.java")));

        ArchiveWriter.addTo(archive, "project", List.of(file("src", "now a file")), true, new Job());
        List<String> names = names(archive);
        assertEquals("now a file", read(archive, "project/src"));
        assertFalse(names.contains("project/src/Main.java"), "everything in the folder it replaced goes too");
        assertFalse(names.contains("project/src/Extra.java"));
    }

    @Test
    void entriesWrittenWithADotSlashPrefixAreRecognisedAsTheSameName() throws IOException {
        Path archive = tmp.resolve("system.tar");
        try (OutputStream out = Files.newOutputStream(archive); TarArchiveOutputStream tar = new TarArchiveOutputStream(out)) {
            for (String name : new String[]{"./", "./keep.txt", "./replace.txt"}) {
                TarArchiveEntry entry = new TarArchiveEntry(name);
                byte[] data = name.endsWith("/") ? new byte[0] : name.getBytes(StandardCharsets.UTF_8);
                entry.setSize(data.length);
                entry.setMode(name.endsWith("/") ? 0755 : 0640);
                tar.putArchiveEntry(entry);
                tar.write(data);
                tar.closeArchiveEntry();
            }
        }
        ArchiveWriter.addTo(archive, "", List.of(file("replace.txt", "new")), true, new Job());
        assertEquals(List.of("keep.txt", "replace.txt"), names(archive).stream().sorted().toList());
        assertEquals("new", read(archive, "replace.txt"));
        assertEquals("./keep.txt", read(archive, "keep.txt"));
        assertEquals(0640, ArchiveReader.list(archive).entries().stream().filter(entry -> entry.path().equals("keep.txt")).findFirst().orElseThrow().mode(),
                "an entry that was copied keeps its mode");
    }

    @Test
    void aCancelledAddLeavesTheArchiveExactlyAsItWas() throws IOException {
        Path archive = make(ArchiveFormat.TAR_GZ);
        byte[] before = Files.readAllBytes(archive);
        Job job = new Job();
        job.cancel();
        assertThrows(CancellationException.class, () -> ArchiveWriter.addTo(archive, "", List.of(file("n.txt", "n")), true, job));
        assertArrayEquals(before, Files.readAllBytes(archive));
        assertNoLeftovers();
    }

    @Test
    void aTruncatedTarIsRefusedAndNotReplaced() throws IOException {
        Path archive = make(ArchiveFormat.TAR_GZ);
        byte[] bytes = Files.readAllBytes(archive);
        Files.write(archive, java.util.Arrays.copyOf(bytes, bytes.length / 2));
        byte[] before = Files.readAllBytes(archive);
        assertThrows(IOException.class, () -> ArchiveWriter.addTo(archive, "", List.of(file("n.txt", "n")), true, new Job()));
        assertArrayEquals(before, Files.readAllBytes(archive), "a damaged archive is not made worse");
        assertNoLeftovers();
    }

    @Test
    void aSingleCompressedFileCannotBeAddedTo() throws IOException {
        Path gz = tmp.resolve("notes.txt.gz");
        try (OutputStream out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(gz))) {
            out.write("hello".getBytes(StandardCharsets.UTF_8));
        }
        assertFalse(ArchiveWriter.canAddTo(ArchiveFormat.GZ));
        assertThrows(UnsupportedOperationException.class, () -> ArchiveWriter.addTo(gz, "", List.of(file("n.txt", "n")), true, new Job()));
    }
}
