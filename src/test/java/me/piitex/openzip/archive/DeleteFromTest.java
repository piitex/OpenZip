package me.piitex.openzip.archive;

import me.piitex.openzip.archive.ArchiveReader.ExtractOptions;
import me.piitex.openzip.archive.ArchiveWriter.CreateOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

/** Deleting means writing the archive again without the chosen entries, and only once that has worked. */
class DeleteFromTest {
    @TempDir
    Path tmp;

    private Path make(ArchiveFormat format) throws IOException {
        Path source = Files.createDirectories(tmp.resolve("project"));
        Files.writeString(source.resolve("readme.txt"), "readme");
        Files.createDirectories(source.resolve("src/deep"));
        Files.writeString(source.resolve("src/Main.java"), "class Main {}");
        Files.writeString(source.resolve("src/deep/Other.java"), "class Other {}");
        Files.writeString(source.resolve("日本語.txt"), "unicode name");
        Files.createDirectories(source.resolve("empty"));
        Path archive = tmp.resolve("out" + format.extension());
        ArchiveWriter.create(archive, List.of(source), new CreateOptions(format), new Job());
        return archive;
    }

    private static List<String> names(Path archive) throws IOException {
        return ArchiveReader.list(archive).entries().stream().map(EntryInfo::path).sorted().toList();
    }

    private void assertNoLeftovers() throws IOException {
        try (var children = Files.list(tmp)) {
            assertEquals(List.of(), children.filter(path -> path.getFileName().toString().startsWith(".openzip-")).toList());
        }
    }

    @ParameterizedTest
    @EnumSource(value = ArchiveFormat.class, names = {"ZIP", "TAR", "TAR_GZ", "TAR_XZ", "TAR_BZ2"})
    void deletesAFileAndKeepsTheRest(ArchiveFormat format) throws IOException {
        Path archive = make(format);
        List<String> before = names(archive);

        var result = ArchiveWriter.deleteFrom(archive, List.of("project/readme.txt", "project/日本語.txt"), new Job());

        assertEquals(2, result.files());
        assertEquals(0, result.folders());
        assertEquals(before.stream().filter(name -> !name.equals("project/readme.txt") && !name.equals("project/日本語.txt")).toList(), names(archive));
        assertEquals(List.of(), ArchiveReader.test(archive, new Job()));
        assertEquals(format, ArchiveFormat.detect(archive).orElseThrow());
        Path out = Files.createDirectories(tmp.resolve("x-" + format.name()));
        ArchiveReader.extract(archive, new ExtractOptions(out), new Job());
        assertEquals("class Other {}", Files.readString(out.resolve("project/src/deep/Other.java")));
        assertNoLeftovers();
    }

    @ParameterizedTest
    @EnumSource(value = ArchiveFormat.class, names = {"ZIP", "TAR_GZ"})
    void deletingAFolderTakesEverythingInIt(ArchiveFormat format) throws IOException {
        Path archive = make(format);

        var result = ArchiveWriter.deleteFrom(archive, List.of("project/src"), new Job());

        assertEquals(2, result.files());
        assertEquals(2, result.folders(), "src and src/deep");
        assertEquals(List.of("project", "project/empty", "project/readme.txt", "project/日本語.txt").stream().sorted().toList(), names(archive));
    }

    @Test
    void aFileWithTheSameStartAsAFolderIsNotTakenWithIt() throws IOException {
        Path source = Files.createDirectories(tmp.resolve("p"));
        Files.createDirectories(source.resolve("src"));
        Files.writeString(source.resolve("src/a.txt"), "a");
        Files.writeString(source.resolve("src.bak"), "backup");
        Path zip = tmp.resolve("p.zip");
        ArchiveWriter.create(zip, List.of(source), new CreateOptions(ArchiveFormat.ZIP), new Job());

        ArchiveWriter.deleteFrom(zip, List.of("p/src"), new Job());

        assertEquals(List.of("p", "p/src.bak"), names(zip));
    }

    @Test
    void everythingCanGoAndTheArchiveIsStillOne() throws IOException {
        for (ArchiveFormat format : List.of(ArchiveFormat.ZIP, ArchiveFormat.TAR_GZ)) {
            Path archive = make(format);
            ArchiveWriter.deleteFrom(archive, List.of("project"), new Job());
            assertEquals(List.of(), names(archive), format.name());
            assertEquals(List.of(), ArchiveReader.test(archive, new Job()));
        }
    }

    @Test
    void theZipCommentAndTheOtherEntriesPackedBytesSurvive() throws IOException {
        Path archive = make(ArchiveFormat.ZIP);
        long before = ArchiveReader.list(archive).entries().stream().filter(entry -> entry.path().equals("project/src/deep/Other.java"))
                .findFirst().orElseThrow().compressedSize();
        ArchiveWriter.deleteFrom(archive, List.of("project/readme.txt"), new Job());
        long after = ArchiveReader.list(archive).entries().stream().filter(entry -> entry.path().equals("project/src/deep/Other.java"))
                .findFirst().orElseThrow().compressedSize();
        assertEquals(before, after, "copied as it was, not packed again");
    }

    @Test
    void anEntryNamedWithADotSlashIsStillFound() throws IOException {
        Path archive = tmp.resolve("system.tar");
        try (var out = Files.newOutputStream(archive);
             var tar = new org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(out)) {
            for (String name : new String[]{"./", "./keep.txt", "./gone.txt"}) {
                var entry = new org.apache.commons.compress.archivers.tar.TarArchiveEntry(name);
                byte[] data = name.endsWith("/") ? new byte[0] : name.getBytes();
                entry.setSize(data.length);
                tar.putArchiveEntry(entry);
                tar.write(data);
                tar.closeArchiveEntry();
            }
        }
        ArchiveWriter.deleteFrom(archive, List.of("gone.txt"), new Job());
        assertEquals(List.of("keep.txt"), names(archive));
    }

    @Test
    void aCancelledDeleteLeavesTheArchiveExactlyAsItWas() throws IOException {
        Path archive = make(ArchiveFormat.TAR_GZ);
        byte[] before = Files.readAllBytes(archive);
        Job job = new Job();
        job.cancel();
        assertThrows(CancellationException.class, () -> ArchiveWriter.deleteFrom(archive, List.of("project/readme.txt"), job));
        assertArrayEquals(before, Files.readAllBytes(archive));
        assertNoLeftovers();
    }

    @Test
    void namesThatAreNotThereAreRefusedAndTheArchiveIsNotRewritten() throws IOException {
        Path archive = make(ArchiveFormat.ZIP);
        byte[] before = Files.readAllBytes(archive);
        assertThrows(IOException.class, () -> ArchiveWriter.deleteFrom(archive, List.of("project/nope.txt"), new Job()));
        assertThrows(IOException.class, () -> ArchiveWriter.deleteFrom(archive, List.of("../project"), new Job()));
        assertArrayEquals(before, Files.readAllBytes(archive));
        assertNoLeftovers();
    }

    @Test
    void sevenZAndSingleFilesCannotBeDeletedFrom() throws IOException {
        assertFalse(ArchiveWriter.canDeleteFrom(ArchiveFormat.SEVEN_Z));
        assertFalse(ArchiveWriter.canDeleteFrom(ArchiveFormat.RAR));
        assertFalse(ArchiveWriter.canDeleteFrom(ArchiveFormat.GZ));
        Path archive = make(ArchiveFormat.SEVEN_Z);
        assertThrows(UnsupportedOperationException.class, () -> ArchiveWriter.deleteFrom(archive, List.of("project"), new Job()));
    }

    @Test
    void aPasswordProtectedZipIsRefusedAndLeftAlone() throws IOException {
        Path source = Files.createDirectories(tmp.resolve("secret"));
        Files.writeString(source.resolve("keep.txt"), "keep me");
        Files.writeString(source.resolve("drop.txt"), "drop me");
        Path zip = tmp.resolve("secret.zip");
        char[] password = "hunter2".toCharArray();
        ArchiveWriter.create(zip, List.of(source), new CreateOptions(ArchiveFormat.ZIP, -1, true, password), new Job());
        byte[] before = Files.readAllBytes(zip);

        IOException exception = assertThrows(IOException.class, () -> ArchiveWriter.deleteFrom(zip, List.of("secret/drop.txt"), new Job()));

        assertTrue(exception.getMessage().contains("password protected"), exception.getMessage());
        assertArrayEquals(before, Files.readAllBytes(zip));
        assertNoLeftovers();
    }
}
