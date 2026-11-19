package me.piitex.openzip.archive;

import me.piitex.openzip.archive.ArchiveReader.ExtractOptions;
import me.piitex.openzip.archive.ArchiveReader.Listing;
import me.piitex.openzip.archive.ArchiveReader.Overwrite;
import me.piitex.openzip.archive.ArchiveWriter.CreateOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PasswordTest {
    private static final char[] SECRET = "correct horse".toCharArray();
    private static final char[] WRONG = "battery staple".toCharArray();

    @TempDir
    Path tmp;
    Path source;

    @BeforeEach
    void makeSource() throws IOException {
        source = Files.createDirectories(tmp.resolve("vault"));
        Files.writeString(source.resolve("secret.txt"), "the launch codes");
        Files.createDirectories(source.resolve("docs/deep"));
        Files.writeString(source.resolve("docs/deep/plan.md"), "# plan\n".repeat(200));
        Files.write(source.resolve("big.bin"), new byte[500_000]);
        Files.createDirectories(source.resolve("empty"));
        Files.createFile(source.resolve("zero.txt"));
    }

    private Path encrypted(ArchiveFormat format) throws IOException {
        Path out = tmp.resolve("locked" + format.extension());
        ArchiveWriter.create(out, List.of(source), new CreateOptions(format, -1, true, SECRET.clone()), new Job());
        return out;
    }

    @ParameterizedTest
    @EnumSource(value = ArchiveFormat.class, names = {"ZIP", "SEVEN_Z"})
    void roundTripWithThePassword(ArchiveFormat format) throws IOException {
        Path archive = encrypted(format);
        Path dest = tmp.resolve("out");
        ArchiveReader.extract(archive, new ExtractOptions(dest, null, Overwrite.REPLACE, true, SECRET), new Job());

        assertEquals("the launch codes", Files.readString(dest.resolve("vault/secret.txt")));
        assertEquals(500_000, Files.size(dest.resolve("vault/big.bin")));
        assertEquals("# plan\n".repeat(200), Files.readString(dest.resolve("vault/docs/deep/plan.md")));
        assertTrue(Files.isDirectory(dest.resolve("vault/empty")));
        assertEquals(0, Files.size(dest.resolve("vault/zero.txt")));
        assertEquals(List.of(), ArchiveReader.test(archive, SECRET, new Job()));
    }

    @ParameterizedTest
    @EnumSource(value = ArchiveFormat.class, names = {"ZIP", "SEVEN_Z"})
    void theContentsReallyAreEncrypted(ArchiveFormat format) throws IOException {
        byte[] raw = Files.readAllBytes(encrypted(format));
        assertEquals(-1, indexOf(raw, "the launch codes".getBytes()), "plain text must not appear in the file");
        // 500 kB of zeros compresses to almost nothing when it is only compressed; encrypted data cannot be smaller
        // than what was stored, but the zero run is the same either way, so check the plain-text lines instead.
        assertEquals(-1, indexOf(raw, "# plan\n# plan\n".getBytes()));
    }

    @ParameterizedTest
    @EnumSource(value = ArchiveFormat.class, names = {"ZIP", "SEVEN_Z"})
    void namesCanBeListedWithoutThePasswordAndAreFlagged(ArchiveFormat format) throws IOException {
        Listing listing = ArchiveReader.list(encrypted(format));
        assertTrue(listing.encrypted());
        assertTrue(listing.entries().stream().anyMatch(entry -> entry.path().equals("vault/secret.txt") && entry.size() == 16));
    }

    @ParameterizedTest
    @EnumSource(value = ArchiveFormat.class, names = {"ZIP", "SEVEN_Z"})
    void extractingWithoutThePasswordAsksForIt(ArchiveFormat format) throws IOException {
        Path archive = encrypted(format);
        Path dest = tmp.resolve("out");
        PasswordException exception = assertThrows(PasswordException.class,
                () -> ArchiveReader.extract(archive, new ExtractOptions(dest), new Job()));
        assertFalse(exception.isWrong());
        assertNoFiles(dest);
    }

    @ParameterizedTest
    @EnumSource(value = ArchiveFormat.class, names = {"ZIP", "SEVEN_Z"})
    void aWrongPasswordIsRefusedAndLeavesNothingBehind(ArchiveFormat format) throws IOException {
        Path archive = encrypted(format);
        Path dest = tmp.resolve("out");
        PasswordException exception = assertThrows(PasswordException.class,
                () -> ArchiveReader.extract(archive, new ExtractOptions(dest, null, Overwrite.REPLACE, true, WRONG), new Job()));
        assertTrue(exception.isWrong());
        assertNoFiles(dest);
        assertThrows(PasswordException.class, () -> ArchiveReader.test(archive, WRONG, new Job()));
    }

    @Test
    void anUnencryptedArchiveIgnoresAPasswordAndIsNotFlagged() throws IOException {
        Path archive = tmp.resolve("plain.zip");
        ArchiveWriter.create(archive, List.of(source), new CreateOptions(ArchiveFormat.ZIP), new Job());
        assertFalse(ArchiveReader.list(archive).encrypted());
        Path dest = tmp.resolve("out");
        ArchiveReader.extract(archive, new ExtractOptions(dest, null, Overwrite.REPLACE, true, WRONG), new Job());
        assertEquals("the launch codes", Files.readString(dest.resolve("vault/secret.txt")));
    }

    @ParameterizedTest
    @EnumSource(value = ArchiveFormat.class, names = {"ZIP", "SEVEN_Z"})
    void extractingJustOneFileFromAnEncryptedArchive(ArchiveFormat format) throws IOException {
        Path archive = encrypted(format);
        Path dest = tmp.resolve("out");
        ArchiveReader.extract(archive, new ExtractOptions(dest, java.util.Set.of("vault/docs"), Overwrite.REPLACE, true, SECRET), new Job());
        assertTrue(Files.exists(dest.resolve("vault/docs/deep/plan.md")));
        assertFalse(Files.exists(dest.resolve("vault/secret.txt")));
    }

    @ParameterizedTest
    @EnumSource(value = ArchiveFormat.class, names = {"ZIP", "SEVEN_Z"})
    void keepsPermissionsAndLinksWhenEncrypted(ArchiveFormat format) throws IOException {
        assumeTrue(Files.getFileStore(tmp).supportsFileAttributeView("posix"));
        Path script = source.resolve("run.sh");
        Files.writeString(script, "#!/bin/sh\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.createSymbolicLink(source.resolve("latest"), Path.of("secret.txt"));

        Path dest = tmp.resolve("out");
        ArchiveReader.extract(encrypted(format), new ExtractOptions(dest, null, Overwrite.REPLACE, true, SECRET), new Job());
        assertTrue(Files.isExecutable(dest.resolve("vault/run.sh")));
        assertEquals(Path.of("secret.txt"), Files.readSymbolicLink(dest.resolve("vault/latest")));
    }

    @Test
    void tarCannotBeEncrypted() {
        assertThrows(IllegalArgumentException.class,
                () -> new CreateOptions(ArchiveFormat.TAR_GZ, -1, true, SECRET.clone()));
        assertTrue(ArchiveFormat.ZIP.supportsPassword());
        assertTrue(ArchiveFormat.SEVEN_Z.supportsPassword());
        assertFalse(ArchiveFormat.TAR_XZ.supportsPassword());
    }

    @Test
    void anEmptyPasswordMeansNoEncryption() throws IOException {
        Path archive = tmp.resolve("empty-pw.zip");
        ArchiveWriter.create(archive, List.of(source), new CreateOptions(ArchiveFormat.ZIP, -1, true, new char[0]), new Job());
        assertFalse(ArchiveReader.list(archive).encrypted());
    }

    private static void assertNoFiles(Path dest) throws IOException {
        if (!Files.exists(dest)) return;
        try (var walk = Files.walk(dest)) {
            assertEquals(List.of(), walk.filter(Files::isRegularFile).toList(), "no partial file may be left");
        }
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
}
