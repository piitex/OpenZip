package me.piitex.openzip.archive;

import me.piitex.openzip.archive.ArchiveReader.ExtractOptions;
import me.piitex.openzip.archive.ArchiveReader.Listing;
import me.piitex.openzip.archive.ArchiveReader.Overwrite;
import me.piitex.openzip.archive.RarFixtures.Entry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** RAR is read through 7-Zip's native library, so these are skipped on a system it has no build for. */
class RarTest {
    @TempDir
    Path tmp;

    private static final List<Entry> PROJECT = List.of(
            Entry.dir("project"),
            Entry.file("project/hello.txt", "hello from a rar"),
            Entry.dir("project/sub"),
            Entry.file("project/sub/data.csv", "a,b,c\n1,2,3\n"),
            Entry.file("project/empty.txt", ""),
            Entry.file("project/last.txt", "the last one"));

    @BeforeAll
    static void nativesLoad() throws IOException {
        // Fails the whole class with the reason, unless this really is a system without a build of the library.
        assumeTrue(RarSource.platformName(System.getProperty("os.name"), System.getProperty("os.arch")) != null,
                "no 7-Zip-JBinding for this system");
        RarSource.loadNatives();
    }

    /** All four platform jars are on the class path and the library alone would use the first one (Linux). */
    @Test
    void namesThePlatformItself() {
        assertEquals("Windows-amd64", RarSource.platformName("Windows 11", "amd64"));
        assertEquals("Windows-amd64", RarSource.platformName("Windows 10", "x86_64"));
        assertNull(RarSource.platformName("Windows 11", "aarch64"));
        assertEquals("Linux-amd64", RarSource.platformName("Linux", "amd64"));
        assertEquals("Linux-arm64", RarSource.platformName("Linux", "aarch64"));
        assertEquals("Mac", RarSource.platformName("Mac OS X", "aarch64"));
        assertEquals("Mac", RarSource.platformName("Mac OS X", "x86_64"));
        assertNull(RarSource.platformName("FreeBSD", "amd64"));
    }

    private Path write(String name, byte[] bytes) throws IOException {
        Path file = tmp.resolve(name);
        Files.write(file, bytes);
        return file;
    }

    private static List<String> names(Listing listing) {
        return listing.entries().stream().map(EntryInfo::path).toList();
    }

    @Test
    void readsRar4() throws Exception {
        Path rar = write("v4.rar", RarFixtures.rar4(PROJECT));
        assertEquals(Optional.of(ArchiveFormat.RAR), ArchiveFormat.detect(rar));

        Listing listing = ArchiveReader.list(rar);
        assertEquals(List.of("project", "project/hello.txt", "project/sub", "project/sub/data.csv", "project/empty.txt",
                "project/last.txt"), names(listing));
        assertFalse(listing.encrypted());
        EntryInfo hello = listing.entries().get(1);
        assertFalse(hello.directory());
        assertEquals(16, hello.size());
        assertTrue(listing.entries().get(0).directory());
        assertTrue(listing.entries().get(2).directory());

        Path dest = tmp.resolve("out4");
        ArchiveReader.ExtractResult result = ArchiveReader.extract(rar, new ExtractOptions(dest), new Job());
        assertEquals(4, result.files());
        assertEquals(2, result.folders());
        assertEquals("hello from a rar", Files.readString(dest.resolve("project/hello.txt")));
        assertEquals("a,b,c\n1,2,3\n", Files.readString(dest.resolve("project/sub/data.csv")));
        assertEquals(0, Files.size(dest.resolve("project/empty.txt")));
        assertEquals("the last one", Files.readString(dest.resolve("project/last.txt")));
        assertTrue(ArchiveReader.test(rar, new Job()).isEmpty());
    }

    @Test
    void readsRar5() throws Exception {
        Path rar = write("v5.rar", RarFixtures.rar5(PROJECT));
        assertEquals(Optional.of(ArchiveFormat.RAR), ArchiveFormat.detect(rar));

        Listing listing = ArchiveReader.list(rar);
        assertEquals(6, listing.entries().size());
        assertTrue(names(listing).contains("project/sub/data.csv"));

        Path dest = tmp.resolve("out5");
        ArchiveReader.extract(rar, new ExtractOptions(dest), new Job());
        assertEquals("hello from a rar", Files.readString(dest.resolve("project/hello.txt")));
        assertEquals("a,b,c\n1,2,3\n", Files.readString(dest.resolve("project/sub/data.csv")));
        assertEquals("the last one", Files.readString(dest.resolve("project/last.txt")));
        assertTrue(ArchiveReader.test(rar, new Job()).isEmpty());
    }

    @Test
    void aRenamedRarIsRecognisedByItsFirstBytes() throws Exception {
        assertEquals(Optional.of(ArchiveFormat.RAR), ArchiveFormat.detect(write("v4.dat", RarFixtures.rar4(PROJECT))));
        assertEquals(Optional.of(ArchiveFormat.RAR), ArchiveFormat.detect(write("v5.dat", RarFixtures.rar5(PROJECT))));
        assertEquals(Optional.of(ArchiveFormat.RAR), ArchiveFormat.detect(tmp.resolve("comic.CBR")));
        assertFalse(ArchiveFormat.RAR.canWrite());
        assertEquals("book", ArchiveFormat.baseName(tmp.resolve("book.rar")));
    }

    @Test
    void largeFilesStreamThroughInOrder() throws Exception {
        byte[] big = new byte[6 * 1024 * 1024 + 123];
        new java.util.Random(7).nextBytes(big);
        Path rar = write("big.rar", RarFixtures.rar5(List.of(Entry.file("a.bin", big), Entry.file("b.bin", "after"))));
        Path dest = tmp.resolve("big-out");
        ArchiveReader.extract(rar, new ExtractOptions(dest), new Job());
        assertArrayEquals(big, Files.readAllBytes(dest.resolve("a.bin")));
        assertEquals("after", Files.readString(dest.resolve("b.bin")));
    }

    @Test
    void extractsOnlyWhatWasSelected() throws Exception {
        for (byte[] bytes : List.of(RarFixtures.rar4(PROJECT), RarFixtures.rar5(PROJECT))) {
            Path rar = write("pick" + bytes.length + ".rar", bytes);
            Path dest = tmp.resolve("pick-out" + bytes.length);
            // One in the middle and one at the end: the entries before and between are skipped, not read.
            ArchiveReader.extract(rar, new ExtractOptions(dest, Set.of("project/sub", "project/last.txt"), Overwrite.REPLACE, true),
                    new Job());
            assertEquals("a,b,c\n1,2,3\n", Files.readString(dest.resolve("project/sub/data.csv")));
            assertEquals("the last one", Files.readString(dest.resolve("project/last.txt")));
            assertFalse(Files.exists(dest.resolve("project/hello.txt")));
            assertFalse(Files.exists(dest.resolve("project/empty.txt")));
        }
    }

    @Test
    void anEntryTheReaderStopsEarlyDoesNotHoldUpTheRest() throws Exception {
        byte[] big = new byte[3 * 1024 * 1024];
        Path rar = write("early.rar", RarFixtures.rar5(List.of(Entry.file("a.bin", big), Entry.file("b.txt", "second"))));
        List<String> seen = new ArrayList<>();
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            try (RarSource source = new RarSource(rar, null)) {
                source.forEach((info, data) -> {
                    try (InputStream in = data.open()) {
                        if (info.path().equals("a.bin")) {
                            assertEquals(1, in.read(new byte[1])); // takes a byte and walks away
                            seen.add("a");
                        } else {
                            seen.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                        }
                    }
                }, new ArrayList<>());
            }
        });
        assertEquals(List.of("a", "second"), seen);
    }

    @Test
    void aFailureWhileReadingStopsTheLibraryAndLeavesNothingRunning() throws Exception {
        byte[] big = new byte[8 * 1024 * 1024];
        Path rar = write("fail.rar", RarFixtures.rar5(List.of(Entry.file("a.bin", big), Entry.file("b.bin", big))));
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            try (RarSource source = new RarSource(rar, null)) {
                IOException exception = assertThrows(IOException.class, () -> source.forEach((info, data) -> {
                    try (InputStream in = data.open()) {
                        in.read(new byte[10]);
                        throw new IOException("disk full");
                    }
                }, new ArrayList<>()));
                assertEquals("disk full", exception.getMessage());
            }
        });
        assertTrue(Thread.getAllStackTraces().keySet().stream().noneMatch(thread -> thread.getName().equals("openzip-rar") && thread.isAlive()),
                "the extraction thread must have ended");
        // and the same archive still opens afterwards
        assertTrue(ArchiveReader.test(rar, new Job()).isEmpty());
    }

    @Test
    void cancellingStopsTheExtraction() throws Exception {
        byte[] big = new byte[8 * 1024 * 1024];
        Path rar = write("cancel.rar", RarFixtures.rar5(List.of(Entry.file("a.bin", big), Entry.file("b.bin", big))));
        Job job = new Job();
        Thread canceller = new Thread(() -> {
            while (job.done() == 0) Thread.onSpinWait();
            job.cancel();
        });
        canceller.setDaemon(true);
        canceller.start();
        assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
                assertThrows(CancellationException.class, () -> ArchiveReader.extract(rar, new ExtractOptions(tmp.resolve("cancel-out")), job)));
        assertFalse(Files.exists(tmp.resolve("cancel-out/b.bin")));
    }

    @Test
    void corruptDataIsReportedByTest() throws Exception {
        for (byte[] clean : List.of(RarFixtures.rar4(PROJECT), RarFixtures.rar5(PROJECT))) {
            byte[] bytes = clean.clone();
            int at = indexOf(bytes, "hello from a rar".getBytes(StandardCharsets.UTF_8));
            bytes[at + 2] ^= 0x55;
            Path rar = write("corrupt" + bytes.length + ".rar", bytes);
            List<String> failures = ArchiveReader.test(rar, new Job());
            assertEquals(1, failures.size(), failures.toString());
            assertTrue(failures.getFirst().startsWith("project/hello.txt"), failures.getFirst());
        }
    }

    @Test
    void passwordProtectedRar5() throws Exception {
        Path rar = write("locked.rar", RarFixtures.rar5(PROJECT, "s3cret"));

        Listing listing = ArchiveReader.list(rar);
        assertTrue(listing.encrypted(), "names are listed without the password, and it says one is needed");
        assertEquals(6, listing.entries().size());

        Path dest = tmp.resolve("locked-out");
        PasswordException missing = assertThrows(PasswordException.class,
                () -> ArchiveReader.extract(rar, new ExtractOptions(dest), new Job()));
        assertFalse(missing.isWrong());

        PasswordException wrong = assertThrows(PasswordException.class,
                () -> ArchiveReader.extract(rar, new ExtractOptions(dest, null, Overwrite.REPLACE, true, "nope".toCharArray()), new Job()));
        assertTrue(wrong.isWrong());
        assertFalse(Files.exists(dest.resolve("project/hello.txt")), "a wrong password must not leave a file behind");

        ArchiveReader.extract(rar, new ExtractOptions(dest, null, Overwrite.REPLACE, true, "s3cret".toCharArray()), new Job());
        assertEquals("hello from a rar", Files.readString(dest.resolve("project/hello.txt")));
        assertEquals("a,b,c\n1,2,3\n", Files.readString(dest.resolve("project/sub/data.csv")));
        assertEquals("the last one", Files.readString(dest.resolve("project/last.txt")));

        assertTrue(ArchiveReader.test(rar, "s3cret".toCharArray(), new Job()).isEmpty());
        assertThrows(PasswordException.class, () -> ArchiveReader.test(rar, new Job()));
    }

    @Test
    void namesThatClimbOutAreSkipped() throws Exception {
        Path rar = write("slip.rar", RarFixtures.rar4(List.of(Entry.file("ok.txt", "fine"), Entry.file("../evil.txt", "bad"))));
        Listing listing = ArchiveReader.list(rar);
        assertEquals(List.of("ok.txt"), names(listing));
        assertEquals(1, listing.warnings().size());
        Path dest = tmp.resolve("slip-out/inner");
        ArchiveReader.extract(rar, new ExtractOptions(dest), new Job());
        assertTrue(Files.exists(dest.resolve("ok.txt")));
        assertFalse(Files.exists(tmp.resolve("slip-out/evil.txt")));
    }

    @Test
    void notAnArchiveIsAnError() throws Exception {
        Path fake = write("fake.rar", "this is not a rar".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> ArchiveReader.list(fake));
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        throw new AssertionError("needle not found");
    }
}
