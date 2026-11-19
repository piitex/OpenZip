package me.piitex.openzip.archive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DestinationsTest {
    @TempDir
    Path tmp;

    private static EntryInfo file(String path) {
        return new EntryInfo(path, false, 1, 1, -1, -1, null);
    }

    private static ArchiveReader.Listing listing(String... paths) {
        return new ArchiveReader.Listing(java.util.Arrays.stream(paths).map(DestinationsTest::file).toList(), List.of());
    }

    @Test
    void singleTopLevelFolderExtractsIntoTheChosenFolder() {
        Path dest = Destinations.choose(tmp, Path.of("/x/project.zip"), listing("project/a.txt", "project/b/c.txt"));
        assertEquals(tmp, dest);
    }

    @Test
    void macosMetadataBesideTheRealFolderDoesNotCountAsASecondRoot() {
        Path dest = Destinations.choose(tmp, Path.of("/x/project.zip"), listing("project/a.txt", "__MACOSX/project/._a.txt"));
        assertEquals(tmp, dest);
    }

    @Test
    void severalTopLevelEntriesGoIntoANewFolderNamedAfterTheArchive() {
        Path dest = Destinations.choose(tmp, Path.of("/x/photos.tar.gz"), listing("a.jpg", "b.jpg"));
        assertEquals(tmp.resolve("photos"), dest);
    }

    @Test
    void existingFoldersAreNeverMergedInto() throws IOException {
        Files.createDirectories(tmp.resolve("photos"));
        Files.createDirectories(tmp.resolve("photos 2"));
        assertEquals(tmp.resolve("photos 3"), Destinations.choose(tmp, Path.of("/x/photos.zip"), listing("a.jpg", "b.jpg")));

        // The single root already exists in the folder, so it gets its own folder rather than merging.
        Files.createDirectories(tmp.resolve("project"));
        assertEquals(tmp.resolve("project 2"), Destinations.choose(tmp, Path.of("/x/project.zip"), listing("project/a.txt")));
    }

    @Test
    void newArchiveNames() {
        assertEquals("notes.txt.zip", Destinations.archiveName(List.of(Path.of("/a/notes.txt")), ArchiveFormat.ZIP));
        assertEquals("Archive.tar.gz", Destinations.archiveName(List.of(Path.of("/a/x"), Path.of("/a/y")), ArchiveFormat.TAR_GZ));
    }

    @Test
    void quickArchiveNeverReplacesAFile(@TempDir Path tmp) throws IOException {
        assertEquals(tmp.resolve("my.project.zip"), Destinations.uniqueFile(tmp, "my.project", ".zip"));
        Files.createFile(tmp.resolve("my.project.zip"));
        assertEquals(tmp.resolve("my.project 2.zip"), Destinations.uniqueFile(tmp, "my.project", ".zip"));
        Files.createFile(tmp.resolve("my.project 2.zip"));
        assertEquals(tmp.resolve("my.project 3.zip"), Destinations.uniqueFile(tmp, "my.project", ".zip"));
        // a double extension stays together
        Files.createFile(tmp.resolve("src.tar.gz"));
        assertEquals(tmp.resolve("src 2.tar.gz"), Destinations.uniqueFile(tmp, "src", ".tar.gz"));
    }
}
