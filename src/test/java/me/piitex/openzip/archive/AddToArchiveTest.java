package me.piitex.openzip.archive;

import me.piitex.openzip.archive.ArchiveReader.ExtractOptions;
import me.piitex.openzip.archive.ArchiveWriter.CreateOptions;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class AddToArchiveTest {
    @TempDir
    Path tmp;
    Path archive;

    @BeforeEach
    void makeArchive() throws IOException {
        Path source = Files.createDirectories(tmp.resolve("project"));
        Files.writeString(source.resolve("readme.txt"), "old readme");
        Files.createDirectories(source.resolve("src"));
        Files.writeString(source.resolve("src/Main.java"), "class Main {}");
        Files.writeString(source.resolve("run.sh"), "#!/bin/sh");
        Files.writeString(source.resolve("日本語.txt"), "unicode name");
        Files.createDirectories(source.resolve("empty"));
        Files.write(source.resolve("big.bin"), new byte[200_000]);
        archive = tmp.resolve("out.zip");
        ArchiveWriter.create(archive, List.of(source), new CreateOptions(ArchiveFormat.ZIP), new Job());
    }

    private List<String> names() throws IOException {
        return ArchiveReader.list(archive).entries().stream().map(EntryInfo::path).toList();
    }

    private String read(String entry) throws IOException {
        Path dest = Files.createTempDirectory(tmp, "x");
        ArchiveReader.extract(archive, new ExtractOptions(dest), new Job());
        return Files.readString(dest.resolve(entry));
    }

    private Path file(String name, String text) throws IOException {
        Path dir = Files.createTempDirectory(tmp, "in");
        Path file = dir.resolve(name);
        Files.writeString(file, text);
        return file;
    }

    private void assertIntact() throws IOException {
        assertEquals(List.of(), ArchiveReader.test(archive, new Job()), "every file must still read back with a good checksum");
        try (var children = Files.list(tmp)) {
            assertEquals(List.of(), children.filter(path -> path.getFileName().toString().startsWith(".openzip-")).toList(),
                    "no temporary file is left behind");
        }
    }

    @Test
    void addsToTheTopAndKeepsEverythingElse() throws IOException {
        var result = ArchiveWriter.addTo(archive, "", List.of(file("new.txt", "fresh")), true, new Job());
        assertEquals(1, result.files());
        List<String> names = names();
        assertTrue(names.contains("new.txt"), names.toString());
        assertTrue(names.containsAll(List.of("project/readme.txt", "project/src/Main.java", "project/日本語.txt", "project/empty")), names.toString());
        assertEquals("fresh", read("new.txt"));
        assertEquals("unicode name", read("project/日本語.txt"));
        Path dest = Files.createTempDirectory(tmp, "x");
        ArchiveReader.extract(archive, new ExtractOptions(dest), new Job());
        assertEquals(200_000, Files.size(dest.resolve("project/big.bin")));
        assertIntact();
    }

    @Test
    void addsIntoAFolderAndAFolderWithItsContents() throws IOException {
        Path folder = Files.createDirectories(tmp.resolve("assets/icons"));
        Files.writeString(folder.resolve("a.svg"), "<svg/>");
        ArchiveWriter.addTo(archive, "project/src", List.of(file("Extra.java", "class Extra {}"), tmp.resolve("assets")), true, new Job());
        List<String> names = names();
        assertTrue(names.contains("project/src/Extra.java"), names.toString());
        assertTrue(names.contains("project/src/assets/icons/a.svg"), names.toString());
        assertEquals("class Extra {}", read("project/src/Extra.java"));
        assertIntact();
    }

    @Test
    void aSameNamedFileIsReplacedNotDuplicated() throws IOException {
        ArchiveWriter.addTo(archive, "project", List.of(file("readme.txt", "new readme")), true, new Job());
        assertEquals(1, names().stream().filter("project/readme.txt"::equals).count());
        assertEquals("new readme", read("project/readme.txt"));
        assertEquals("class Main {}", read("project/src/Main.java"));
        assertIntact();
    }

    @Test
    void aSameNamedFolderIsMergedInto() throws IOException {
        Path dropped = Files.createDirectories(tmp.resolve("in/src"));
        Files.writeString(dropped.resolve("Main.java"), "class Main2 {}");
        Files.writeString(dropped.resolve("Other.java"), "class Other {}");
        ArchiveWriter.addTo(archive, "project", List.of(dropped), true, new Job());
        List<String> names = names();
        assertEquals(1, names.stream().filter("project/src/Main.java"::equals).count(), names.toString());
        assertTrue(names.contains("project/src/Other.java"), names.toString());
        assertEquals("class Main2 {}", read("project/src/Main.java"));
        assertTrue(names.contains("project/readme.txt"), "what wasn't dropped stays");
        assertIntact();
    }

    @Test
    void aFileTakesThePlaceOfAFolderOfTheSameNameWithAllItHolds() throws IOException {
        ArchiveWriter.addTo(archive, "project", List.of(file("src", "now a file")), true, new Job());
        List<String> names = names();
        assertTrue(names.contains("project/src"), names.toString());
        assertFalse(names.contains("project/src/Main.java"), names.toString());
        assertIntact();
    }

    @Test
    void entriesThatWereThereKeepTheirModeAndTheArchiveKeepsItsComment() throws IOException {
        Path commented = tmp.resolve("commented.zip");
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(commented)) {
            var entry = new org.apache.commons.compress.archivers.zip.ZipArchiveEntry("tool.sh");
            entry.setUnixMode(0100755);
            out.putArchiveEntry(entry);
            out.write("#!/bin/sh".getBytes());
            out.closeArchiveEntry();
            out.setComment("made by hand");
        }
        ArchiveWriter.addTo(commented, "", List.of(file("n.txt", "n")), true, new Job());
        EntryInfo tool = ArchiveReader.list(commented).entries().stream().filter(entry -> entry.path().equals("tool.sh")).findFirst().orElseThrow();
        assertEquals(0755, tool.mode());
        try (var zip = new net.lingala.zip4j.ZipFile(commented.toFile())) {
            assertEquals("made by hand", zip.getComment());
        }
    }

    @Test
    void aZipFromTheJdkWhoseEntriesUseDataDescriptorsStillReadsBack() throws IOException {
        Path jdk = tmp.resolve("jdk.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jdk))) {
            for (int i = 0; i < 20; i++) {
                out.putNextEntry(new ZipEntry("dir/file" + i + ".txt")); // deflated and streamed: sizes come after the data
                out.write(("content " + i + " ").repeat(500).getBytes());
                out.closeEntry();
            }
        }
        archive = jdk;
        ArchiveWriter.addTo(jdk, "dir", List.of(file("added.txt", "added")), true, new Job());
        assertEquals(21, ArchiveReader.list(jdk).entries().stream().filter(entry -> !entry.directory()).count());
        assertEquals(List.of(), ArchiveReader.test(jdk, new Job()));
        assertEquals("added", read("dir/added.txt"));
        assertEquals(("content 7 ").repeat(500), read("dir/file7.txt"));
    }

    @Test
    void aCancelledAddLeavesTheArchiveExactlyAsItWas() throws IOException {
        byte[] before = Files.readAllBytes(archive);
        Job job = new Job();
        job.cancel();
        assertThrows(CancellationException.class, () -> ArchiveWriter.addTo(archive, "", List.of(file("n.txt", "n")), true, job));
        assertArrayEquals(before, Files.readAllBytes(archive));
        assertIntact();
    }

    @Test
    void aMissingInputFailsWithoutTouchingTheArchive() throws IOException {
        byte[] before = Files.readAllBytes(archive);
        assertThrows(IOException.class, () -> ArchiveWriter.addTo(archive, "", List.of(tmp.resolve("nope.txt")), true, new Job()));
        assertArrayEquals(before, Files.readAllBytes(archive));
        assertIntact();
    }

    @Test
    void twoItemsWithTheSameNameAreRefused() throws IOException {
        byte[] before = Files.readAllBytes(archive);
        var ex = assertThrows(IOException.class,
                () -> ArchiveWriter.addTo(archive, "", List.of(file("same.txt", "1"), file("same.txt", "2")), true, new Job()));
        assertTrue(ex.getMessage().contains("same.txt"), ex.getMessage());
        assertArrayEquals(before, Files.readAllBytes(archive));
    }

    @Test
    void junkIsLeftOutAndNothingLeftToAddIsAnError() throws IOException {
        assertThrows(IOException.class, () -> ArchiveWriter.addTo(archive, "", List.of(file(".DS_Store", "junk")), true, new Job()));
        ArchiveWriter.addTo(archive, "", List.of(file("keep.txt", "k"), file(".DS_Store", "junk")), true, new Job());
        assertTrue(names().contains("keep.txt"));
        assertFalse(names().contains(".DS_Store"));
    }

    @Test
    void theArchiveItselfIsNeverPackedIntoItself() throws IOException {
        ArchiveWriter.addTo(archive, "", List.of(tmp, file("n.txt", "n")), true, new Job());
        assertTrue(names().stream().noneMatch(name -> name.endsWith("out.zip")), names().toString());
        assertTrue(names().stream().noneMatch(name -> name.contains(".openzip-")), names().toString());
        assertIntact();
    }

    @Test
    void onlyZipCanBeAddedTo() throws IOException {
        Path sevenZ = create7z();
        assertFalse(ArchiveWriter.canAddTo(ArchiveFormat.SEVEN_Z));
        assertThrows(UnsupportedOperationException.class, () -> ArchiveWriter.addTo(sevenZ, "", List.of(file("n.txt", "n")), true, new Job()));
    }

    private Path create7z() throws IOException {
        Path out = tmp.resolve("x.7z");
        ArchiveWriter.create(out, List.of(file("a.txt", "a")), new CreateOptions(ArchiveFormat.SEVEN_Z), new Job());
        return out;
    }
}
