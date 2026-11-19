package me.piitex.openzip.archive;

import me.piitex.openzip.archive.ArchiveReader.ExtractOptions;
import me.piitex.openzip.archive.ArchiveReader.ExtractResult;
import me.piitex.openzip.archive.ArchiveReader.Listing;
import me.piitex.openzip.archive.ArchiveReader.Overwrite;
import me.piitex.openzip.archive.ArchiveWriter.CreateOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ArchiveRoundTripTest {
    @TempDir
    Path tmp;
    Path source;

    @BeforeEach
    void makeSource() throws IOException {
        source = Files.createDirectories(tmp.resolve("project"));
        Files.writeString(source.resolve("readme.txt"), "hello");
        Files.createDirectories(source.resolve("src/deep"));
        Files.writeString(source.resolve("src/deep/Main.java"), "class Main {}");
        Files.writeString(source.resolve("ünï cödé 日本語.txt"), "unicode name");
        Files.createDirectories(source.resolve("empty"));
        Files.write(source.resolve("big.bin"), new byte[300_000]);
        Files.setLastModifiedTime(source.resolve("readme.txt"), FileTime.fromMillis(1_700_000_000_000L));
    }

    static Path create(Path out, Path input, ArchiveFormat format) throws IOException {
        ArchiveWriter.create(out, List.of(input), new CreateOptions(format), new Job());
        return out;
    }

    @Test
    void aJarOpensAsAZipEvenThoughTheSystemCallsItAProgram() throws IOException {
        Path jar = tmp.resolve("tool.jar");
        var manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
        try (var out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar), manifest)) {
            out.putNextEntry(new java.util.zip.ZipEntry("app/Main.class"));
            out.write(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
            out.closeEntry();
        }
        assertEquals(ArchiveFormat.ZIP, ArchiveFormat.detect(jar).orElseThrow());
        List<String> paths = ArchiveReader.list(jar).entries().stream().map(EntryInfo::path).toList();
        assertTrue(paths.contains("app/Main.class"), paths.toString());
        assertTrue(paths.contains("META-INF/MANIFEST.MF"), paths.toString());
    }

    @ParameterizedTest
    @EnumSource(value = ArchiveFormat.class, names = {"ZIP", "TAR", "TAR_GZ", "TAR_XZ", "TAR_BZ2", "SEVEN_Z"})
    void roundTripKeepsContentNamesAndEmptyFolders(ArchiveFormat format) throws IOException {
        Path archive = create(tmp.resolve("out" + format.extension()), source, format);
        assertEquals(format, ArchiveFormat.detect(archive).orElseThrow());

        Listing listing = ArchiveReader.list(archive);
        List<String> paths = listing.entries().stream().map(EntryInfo::path).toList();
        assertTrue(paths.contains("project/src/deep/Main.java"), paths.toString());
        assertTrue(paths.contains("project/ünï cödé 日本語.txt"), paths.toString());
        assertTrue(paths.contains("project/empty"), "empty folders are kept");
        assertTrue(listing.entries().stream().filter(entry -> entry.path().equals("project/empty")).findFirst().orElseThrow().directory());

        Path dest = tmp.resolve("extracted");
        ExtractResult result = ArchiveReader.extract(archive, new ExtractOptions(dest), new Job());
        assertEquals(0, result.skipped());
        assertEquals("hello", Files.readString(dest.resolve("project/readme.txt")));
        assertEquals("class Main {}", Files.readString(dest.resolve("project/src/deep/Main.java")));
        assertEquals("unicode name", Files.readString(dest.resolve("project/ünï cödé 日本語.txt")));
        assertTrue(Files.isDirectory(dest.resolve("project/empty")));
        assertEquals(300_000, Files.size(dest.resolve("project/big.bin")));
        assertEquals(1_700_000_000_000L, Files.getLastModifiedTime(dest.resolve("project/readme.txt")).toMillis(), 2000);
    }

    @ParameterizedTest
    @EnumSource(value = ArchiveFormat.class, names = {"ZIP", "TAR_GZ", "SEVEN_Z"})
    void keepsExecutableBitAndSymlinks(ArchiveFormat format) throws IOException {
        assumeTrue(Files.getFileStore(tmp).supportsFileAttributeView("posix"));
        Path script = source.resolve("run.sh");
        Files.writeString(script, "#!/bin/sh\n");
        Files.setPosixFilePermissions(script, PosixFilePermissionsOf.of("rwxr-xr-x"));
        Files.createSymbolicLink(source.resolve("latest"), Path.of("readme.txt"));

        Path archive = create(tmp.resolve("exec" + format.extension()), source, format);
        Path dest = tmp.resolve("x");
        ArchiveReader.extract(archive, new ExtractOptions(dest), new Job());

        assertTrue(Files.isExecutable(dest.resolve("project/run.sh")), "the executable bit survives");
        assertTrue(Files.isSymbolicLink(dest.resolve("project/latest")), "the symlink stays a symlink");
        assertEquals(Path.of("readme.txt"), Files.readSymbolicLink(dest.resolve("project/latest")));
        assertTrue(Files.getPosixFilePermissions(dest.resolve("project/run.sh")).contains(PosixFilePermission.OWNER_EXECUTE));
    }

    @Test
    void junkIsLeftOutWhenCreatingAndExtracting() throws IOException {
        Files.writeString(source.resolve(".DS_Store"), "junk");
        Files.writeString(source.resolve("._readme.txt"), "appledouble");
        Files.createDirectories(source.resolve("__MACOSX"));
        Files.writeString(source.resolve("__MACOSX/x"), "junk");

        Path archive = create(tmp.resolve("clean.zip"), source, ArchiveFormat.ZIP);
        List<String> paths = ArchiveReader.list(archive).entries().stream().map(EntryInfo::path).toList();
        assertTrue(paths.stream().noneMatch(path -> path.contains(".DS_Store") || path.contains("._") || path.contains("__MACOSX")), paths.toString());

        // An archive made by someone else, with the clutter inside it.
        Path dirty = tmp.resolve("dirty.zip");
        ArchiveWriter.create(dirty, List.of(source), new CreateOptions(ArchiveFormat.ZIP, -1, false), new Job());
        assertTrue(ArchiveReader.list(dirty).entries().stream().anyMatch(entry -> entry.path().endsWith(".DS_Store")));
        Path dest = tmp.resolve("x");
        ArchiveReader.extract(dirty, new ExtractOptions(dest), new Job());
        assertFalse(Files.exists(dest.resolve("project/.DS_Store")));
        assertFalse(Files.exists(dest.resolve("project/__MACOSX")));
        assertTrue(Files.exists(dest.resolve("project/readme.txt")));
    }

    @Test
    void extractsOnlyTheSelectedEntriesAndFolders() throws IOException {
        Path archive = create(tmp.resolve("sel.zip"), source, ArchiveFormat.ZIP);
        Path dest = tmp.resolve("x");
        ArchiveReader.extract(archive, new ExtractOptions(dest, Set.of("project/src"), Overwrite.REPLACE, true), new Job());
        assertTrue(Files.exists(dest.resolve("project/src/deep/Main.java")));
        assertFalse(Files.exists(dest.resolve("project/readme.txt")));
    }

    /** Opening a file from the browser extracts just it, and finds it at its own path under the scratch folder. */
    @Test
    void extractingOneFileKeepsItsPathAndNothingElse() throws IOException {
        Path archive = create(tmp.resolve("one.zip"), source, ArchiveFormat.ZIP);
        Path dest = tmp.resolve("scratch");
        ArchiveReader.extract(archive, new ExtractOptions(dest, Set.of("project/src/deep/Main.java"), Overwrite.REPLACE, true), new Job());
        assertTrue(Files.isRegularFile(dest.resolve("project/src/deep/Main.java")));
        try (var walk = Files.walk(dest)) {
            assertEquals(1, walk.filter(Files::isRegularFile).count());
        }
    }

    @Test
    void overwritePolicy() throws IOException {
        Path archive = create(tmp.resolve("o.zip"), source, ArchiveFormat.ZIP);
        Path dest = Files.createDirectories(tmp.resolve("x/project"));
        Files.writeString(dest.resolve("readme.txt"), "mine");

        ExtractResult skipped = ArchiveReader.extract(archive,
                new ExtractOptions(tmp.resolve("x"), null, Overwrite.SKIP, true), new Job());
        assertEquals("mine", Files.readString(dest.resolve("readme.txt")));
        assertEquals(1, skipped.skipped());

        ArchiveReader.extract(archive, new ExtractOptions(tmp.resolve("x")), new Job());
        assertEquals("hello", Files.readString(dest.resolve("readme.txt")));
    }

    @Test
    void zipSlipEntriesAreRefused() throws IOException {
        Path evil = tmp.resolve("evil.zip");
        try (var zip = new org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream(evil)) {
            for (String name : new String[]{"../escaped.txt", "a/../../escaped2.txt", "/abs/escaped3.txt", "C:/win/escaped4.txt", "ok.txt"}) {
                zip.putArchiveEntry(new org.apache.commons.compress.archivers.zip.ZipArchiveEntry(name));
                zip.write("x".getBytes(StandardCharsets.UTF_8));
                zip.closeArchiveEntry();
            }
        }
        Path dest = Files.createDirectories(tmp.resolve("inner/dest"));
        ExtractResult result = ArchiveReader.extract(evil, new ExtractOptions(dest), new Job());

        assertEquals(1, result.files());
        assertTrue(Files.exists(dest.resolve("ok.txt")));
        assertFalse(Files.exists(tmp.resolve("inner/escaped.txt")));
        assertFalse(Files.exists(tmp.resolve("escaped2.txt")));
        assertFalse(Files.exists(Path.of("/abs/escaped3.txt")));
        assertEquals(4, result.warnings().size(), result.warnings().toString());
    }

    @Test
    void symlinkThatLeavesTheDestinationIsRefusedAndCannotBeWrittenThrough() throws IOException {
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Path evil = tmp.resolve("evil.tar");
        try (var tar = new org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(Files.newOutputStream(evil))) {
            var link = new org.apache.commons.compress.archivers.tar.TarArchiveEntry("link", org.apache.commons.compress.archivers.tar.TarConstants.LF_SYMLINK);
            link.setLinkName("../outside");
            tar.putArchiveEntry(link);
            tar.closeArchiveEntry();
            var abs = new org.apache.commons.compress.archivers.tar.TarArchiveEntry("abs", org.apache.commons.compress.archivers.tar.TarConstants.LF_SYMLINK);
            abs.setLinkName(outside.toString());
            tar.putArchiveEntry(abs);
            tar.closeArchiveEntry();
            var file = new org.apache.commons.compress.archivers.tar.TarArchiveEntry("link/pwned.txt");
            file.setSize(1);
            tar.putArchiveEntry(file);
            tar.write('x');
            tar.closeArchiveEntry();
        }
        Path dest = Files.createDirectories(tmp.resolve("dest"));
        ExtractResult result = ArchiveReader.extract(evil, new ExtractOptions(dest), new Job());

        assertFalse(Files.exists(outside.resolve("pwned.txt")), "nothing was written outside the destination");
        assertFalse(Files.isSymbolicLink(dest.resolve("link")), "the escaping link was not created");
        assertTrue(Files.isRegularFile(dest.resolve("link/pwned.txt")), "the file went into a plain folder instead");
        assertFalse(Files.exists(dest.resolve("abs"), LinkOption.NOFOLLOW_LINKS));
        assertEquals(2, result.warnings().size(), result.warnings().toString());
    }

    @Test
    void cannotWriteThroughASymlinkAlreadyInTheDestination() throws IOException {
        assumeTrue(Files.getFileStore(tmp).supportsFileAttributeView("posix"));
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Path dest = Files.createDirectories(tmp.resolve("dest"));
        Files.createSymbolicLink(dest.resolve("project"), outside); // someone's folder that links elsewhere

        Path archive = create(tmp.resolve("through.zip"), source, ArchiveFormat.ZIP);
        ExtractResult result = ArchiveReader.extract(archive, new ExtractOptions(dest), new Job());

        try (var stream = Files.list(outside)) {
            assertEquals(0, stream.count(), "nothing was written through the link");
        }
        assertTrue(result.skipped() > 0);
        assertFalse(result.warnings().isEmpty());
    }

    @Test
    void corruptedZipIsReportedByTest() throws IOException {
        // Level 0 stores the bytes as they are, so the file's text can be found in the archive and damaged.
        Path archive = tmp.resolve("c.zip");
        ArchiveWriter.create(archive, List.of(source), new CreateOptions(ArchiveFormat.ZIP, 0, true), new Job());
        assertTrue(ArchiveReader.test(archive, new Job()).isEmpty(), "a good archive tests clean");

        byte[] bytes = Files.readAllBytes(archive);
        byte[] marker = "class Main {}".getBytes(StandardCharsets.UTF_8);
        int at = indexOf(bytes, marker);
        assertTrue(at > 0, "stored data is findable");
        bytes[at] ^= 0x01; // one flipped bit
        Path bad = tmp.resolve("bad.zip");
        Files.write(bad, bytes);

        List<String> failures = ArchiveReader.test(bad, new Job());
        assertEquals(1, failures.size(), failures.toString());
        assertTrue(failures.getFirst().startsWith("project/src/deep/Main.java"), failures.getFirst());
    }

    @Test
    void cancelStopsCreateAndLeavesNoPartialFile() throws IOException {
        Job job = new Job();
        job.cancel();
        Path out = tmp.resolve("cancelled.zip");
        assertThrows(CancellationException.class,
                () -> ArchiveWriter.create(out, List.of(source), new CreateOptions(ArchiveFormat.ZIP), job));
        assertFalse(Files.exists(out));
        try (var stream = Files.list(tmp)) {
            assertTrue(stream.noneMatch(path -> path.getFileName().toString().startsWith(".openzip-")), "temp file is cleaned up");
        }
    }

    @Test
    void createNeverDestroysAnExistingArchiveOnFailure() throws IOException {
        Path out = tmp.resolve("keep.zip");
        Files.writeString(out, "precious");
        assertThrows(IOException.class,
                () -> ArchiveWriter.create(out, List.of(tmp.resolve("does-not-exist")), new CreateOptions(ArchiveFormat.ZIP), new Job()));
        assertEquals("precious", Files.readString(out));
    }

    @Test
    void archiveInsideTheFolderBeingPackedIsNotPackedIntoItself() throws IOException {
        Path out = source.resolve("self.zip");
        ArchiveWriter.create(out, List.of(source), new CreateOptions(ArchiveFormat.ZIP), new Job());
        assertTrue(ArchiveReader.list(out).entries().stream().noneMatch(entry -> entry.path().endsWith("self.zip")));
    }

    @Test
    void progressReachesTheTotal() throws IOException {
        Job job = new Job();
        Path archive = tmp.resolve("p.zip");
        ArchiveWriter.create(archive, List.of(source), new CreateOptions(ArchiveFormat.ZIP), job);
        assertEquals(job.total(), job.done());
        assertEquals(1.0, job.fraction());

        Job extract = new Job();
        ArchiveReader.extract(archive, new ExtractOptions(tmp.resolve("x")), extract);
        assertEquals(extract.total(), extract.done());
        assertTrue(extract.total() > 300_000);
    }

    @Test
    void formatIsFoundByContentWhenTheNameIsWrong() throws IOException {
        Path archive = create(tmp.resolve("real.zip"), source, ArchiveFormat.ZIP);
        Path renamed = Files.copy(archive, tmp.resolve("mystery.dat"));
        assertEquals(ArchiveFormat.ZIP, ArchiveFormat.detect(renamed).orElseThrow());
        assertEquals("photos", ArchiveFormat.baseName(Path.of("photos.tar.gz")));
        assertEquals("photos", ArchiveFormat.baseName(Path.of("photos.zip")));
        assertTrue(ArchiveFormat.detect(source.resolve("readme.txt")).isEmpty());
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /** Tiny helper so the test reads like chmod. */
    private static final class PosixFilePermissionsOf {
        static Set<PosixFilePermission> of(String text) {
            return java.nio.file.attribute.PosixFilePermissions.fromString(text);
        }
    }
}
