package me.piitex.openzip.archive;

import me.piitex.openzip.archive.ArchiveReader.ExtractOptions;
import me.piitex.openzip.archive.ArchiveWriter.CreateOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Archives made by the system's own tools must open here, and ours must open there. Skipped where the tools are missing. */
class SystemToolsInteropTest {
    @TempDir
    Path tmp;
    Path project;

    @BeforeEach
    void makeProject() throws IOException {
        project = Files.createDirectories(tmp.resolve("work/project"));
        Files.writeString(project.resolve("hello.txt"), "hello from the system tools");
        Files.createDirectories(project.resolve("sub/deeper"));
        Files.writeString(project.resolve("sub/deeper/data.csv"), "a,b,c\n1,2,3\n");
        Path script = project.resolve("run.sh");
        Files.writeString(script, "#!/bin/sh\necho hi\n");
        script.toFile().setExecutable(true);
    }

    private static String run(Path cwd, String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "timed out: " + String.join(" ", command));
        assertEquals(0, process.exitValue(), String.join(" ", command) + "\n" + output);
        return output;
    }

    private static boolean has(String tool) {
        try {
            Process process = new ProcessBuilder("which", tool).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException exception) {
            return false;
        }
    }

    @Test
    void readsAZipMadeBySystemZip() throws Exception {
        assumeTrue(has("zip"));
        Path zip = tmp.resolve("system.zip");
        run(tmp.resolve("work"), "zip", "-r", "-q", zip.toString(), "project");

        Path dest = tmp.resolve("out");
        ArchiveReader.extract(zip, new ExtractOptions(dest), new Job());
        assertEquals("hello from the system tools", Files.readString(dest.resolve("project/hello.txt")));
        assertEquals("a,b,c\n1,2,3\n", Files.readString(dest.resolve("project/sub/deeper/data.csv")));
        assertTrue(Files.isExecutable(dest.resolve("project/run.sh")), "the executable bit from Info-ZIP's Unix extra field");
        assertTrue(ArchiveReader.test(zip, new Job()).isEmpty());
    }

    @Test
    void readsATarGzMadeBySystemTar() throws Exception {
        assumeTrue(has("tar"));
        Path tgz = tmp.resolve("system.tar.gz");
        run(tmp.resolve("work"), "tar", "czf", tgz.toString(), "project");

        Path dest = tmp.resolve("out");
        ArchiveReader.extract(tgz, new ExtractOptions(dest), new Job());
        assertEquals("hello from the system tools", Files.readString(dest.resolve("project/hello.txt")));
        assertTrue(Files.isExecutable(dest.resolve("project/run.sh")));
    }

    /** Finder's "Compress" runs ditto with --sequesterRsrc, which moves extended attributes into a __MACOSX folder. */
    @Test
    void readsAFinderZipAndLeavesItsMetadataFolderOut() throws Exception {
        assumeTrue(has("ditto"));
        Files.writeString(project.resolve(".DS_Store"), "junk");
        run(tmp, "xattr", "-w", "com.apple.test", "value", project.resolve("hello.txt").toString());
        Path zip = tmp.resolve("finder.zip");
        run(tmp.resolve("work"), "ditto", "-c", "-k", "--keepParent", "--sequesterRsrc", "project", zip.toString());

        ArchiveReader.Listing listing = ArchiveReader.list(zip);
        assertTrue(listing.entries().stream().anyMatch(entry -> entry.path().startsWith("__MACOSX/")),
                "the archive really does contain Finder's metadata folder");
        assertEquals(tmp.resolve("dest"), Destinations.choose(tmp.resolve("dest"), zip, listing),
                "__MACOSX beside the project folder doesn't count as a second top-level entry");

        Path dest = tmp.resolve("out");
        ArchiveReader.ExtractResult result = ArchiveReader.extract(zip, new ExtractOptions(dest), new Job());
        assertTrue(Files.exists(dest.resolve("project/hello.txt")));
        assertTrue(Files.exists(dest.resolve("project/run.sh")));
        assertFalse(Files.exists(dest.resolve("__MACOSX")), "Finder's metadata folder is not extracted");
        assertFalse(Files.exists(dest.resolve("project/.DS_Store")));
        assertEquals(0, result.skipped());
    }

    /** Plain ditto (and many other tools) put the ._ file next to the one it describes. */
    @Test
    void readsAZipWithAppleDoubleFilesBesideTheRealOnes() throws Exception {
        assumeTrue(has("ditto"));
        run(tmp, "xattr", "-w", "com.apple.test", "value", project.resolve("hello.txt").toString());
        Path zip = tmp.resolve("plain-ditto.zip");
        run(tmp.resolve("work"), "ditto", "-c", "-k", "--keepParent", "project", zip.toString());

        assertTrue(ArchiveReader.list(zip).entries().stream().anyMatch(entry -> entry.name().equals("._hello.txt")));
        Path dest = tmp.resolve("out");
        ArchiveReader.extract(zip, new ExtractOptions(dest), new Job());
        assertTrue(Files.exists(dest.resolve("project/hello.txt")));
        assertFalse(Files.exists(dest.resolve("project/._hello.txt")));
    }

    @Test
    void ourZipPassesUnzipTest() throws Exception {
        assumeTrue(has("unzip"));
        Path zip = tmp.resolve("ours.zip");
        ArchiveWriter.create(zip, List.of(project), new CreateOptions(ArchiveFormat.ZIP), new Job());

        String listing = run(tmp, "unzip", "-t", zip.toString());
        assertTrue(listing.contains("No errors detected"), listing);

        Path dest = Files.createDirectories(tmp.resolve("unzipped"));
        run(dest, "unzip", "-q", zip.toString());
        assertEquals("hello from the system tools", Files.readString(dest.resolve("project/hello.txt")));
        assertTrue(Files.isExecutable(dest.resolve("project/run.sh")), "unzip restores the executable bit we recorded");
    }

    @Test
    void ourTarGzIsReadableByTar() throws Exception {
        assumeTrue(has("tar"));
        Path tgz = tmp.resolve("ours.tar.gz");
        ArchiveWriter.create(tgz, List.of(project), new CreateOptions(ArchiveFormat.TAR_GZ), new Job());

        Path dest = Files.createDirectories(tmp.resolve("untarred"));
        run(dest, "tar", "xzf", tgz.toString());
        assertEquals("a,b,c\n1,2,3\n", Files.readString(dest.resolve("project/sub/deeper/data.csv")));
        assertTrue(Files.isExecutable(dest.resolve("project/run.sh")));
    }

    @Test
    void ourZipOpensWithDittoTheWayFinderWouldOpenIt() throws Exception {
        assumeTrue(has("ditto"));
        Path zip = tmp.resolve("ours.zip");
        ArchiveWriter.create(zip, List.of(project), new CreateOptions(ArchiveFormat.ZIP), new Job());
        Path dest = Files.createDirectories(tmp.resolve("ditto-out"));
        run(tmp, "ditto", "-x", "-k", zip.toString(), dest.toString());
        assertEquals("hello from the system tools", Files.readString(dest.resolve("project/hello.txt")));
    }

    @Test
    void ourSevenZIsReadableByBsdtar() throws Exception {
        assumeTrue(has("bsdtar"));
        Path sevenZ = tmp.resolve("ours.7z");
        ArchiveWriter.create(sevenZ, List.of(project), new CreateOptions(ArchiveFormat.SEVEN_Z), new Job());

        Path dest = Files.createDirectories(tmp.resolve("bsdtar-7z"));
        run(dest, "bsdtar", "-xf", sevenZ.toString());
        assertEquals("a,b,c\n1,2,3\n", Files.readString(dest.resolve("project/sub/deeper/data.csv")));
        assertTrue(Files.isExecutable(dest.resolve("project/run.sh")), "the Unix mode we record in the 7z attributes");
    }

    /** {@code zip -P} makes the old ZipCrypto kind, which is what a lot of existing encrypted zips are. */
    @Test
    void readsAZipEncryptedBySystemZip() throws Exception {
        assumeTrue(has("zip"));
        Path zip = tmp.resolve("legacy.zip");
        run(tmp.resolve("work"), "zip", "-r", "-q", "-P", "hunter2", zip.toString(), "project");

        assertTrue(ArchiveReader.list(zip).encrypted());
        assertThrows(PasswordException.class, () -> ArchiveReader.extract(zip, new ExtractOptions(tmp.resolve("none")), new Job()));
        Path dest = tmp.resolve("out");
        ArchiveReader.extract(zip, new ExtractOptions(dest, null, ArchiveReader.Overwrite.REPLACE, true, "hunter2".toCharArray()), new Job());
        assertEquals("hello from the system tools", Files.readString(dest.resolve("project/hello.txt")));
        assertTrue(Files.isExecutable(dest.resolve("project/run.sh")));
        assertTrue(assertThrows(PasswordException.class, () -> ArchiveReader.extract(zip,
                new ExtractOptions(tmp.resolve("bad"), null, ArchiveReader.Overwrite.REPLACE, true, "nope".toCharArray()), new Job())).isWrong());
    }

    /** libarchive reads WinZip AES when it was built with crypto support; skipped when it can't. */
    @Test
    void ourAesZipOpensInBsdtarWithThePassphrase() throws Exception {
        assumeTrue(has("bsdtar"));
        Path zip = tmp.resolve("aes.zip");
        ArchiveWriter.create(zip, List.of(project), new CreateOptions(ArchiveFormat.ZIP, -1, true, "hunter2".toCharArray()), new Job());

        Path dest = Files.createDirectories(tmp.resolve("bsdtar-aes"));
        Process process = new ProcessBuilder("bsdtar", "-xf", zip.toString(), "--passphrase", "hunter2")
                .directory(dest.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assumeTrue(process.exitValue() == 0 || !output.contains("not supported"), "this bsdtar can't read AES zips: " + output);
        assertEquals(0, process.exitValue(), output);
        assertEquals("hello from the system tools", Files.readString(dest.resolve("project/hello.txt")));
    }
}
