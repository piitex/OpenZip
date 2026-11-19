package me.piitex.openzip.archive;

import me.piitex.openzip.archive.ArchiveReader.ExtractOptions;
import me.piitex.openzip.archive.ArchiveReader.ExtractResult;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Names an archive may carry that Windows can't store, or would store somewhere else than the name says. */
class WindowsNamesTest {
    @TempDir
    Path tmp;

    @Test
    void namesWindowsCannotStoreAreFound() {
        for (String bad : List.of("a:b", "dir/file.txt:stream", "what?.txt", "star*.txt", "a|b", "q\"uote", "lt<gt>", "x/CON", "nul",
                "NUL.txt", "aux.tar.gz", "com1", "LPT9.log", "dir/prn.", "trailing.", "trailing ", "a/b /c", "...", "ctrl\u0001char")) {
            assertNotNull(SafePaths.windowsProblem(bad), bad);
        }
    }

    @Test
    void ordinaryNamesAreFine() {
        for (String ok : List.of("file.txt", "dir/sub/file.tar.gz", "Holiday Photos/2025/Paris/tower.jpg", "console.log", "auxiliary.txt",
                "com10", "lpt", ".gitignore", "naïve café.txt", "日本語/ファイル.txt", "a-b_c (1).txt", "nullable.java")) {
            assertNull(SafePaths.windowsProblem(ok), ok);
        }
    }

    @Test
    void onlyWindowsAppliesTheRules() {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        assertEquals(windows, SafePaths.onWindows());
        assertEquals(windows, SafePaths.unusableHere("a:b") != null);
        assertNull(SafePaths.unusableHere("fine.txt"));
    }

    @Test
    void anArchiveWithUnstorableNamesExtractsTheRestAndSaysWhatItSkipped() throws Exception {
        assumeTrue(SafePaths.onWindows());
        Path zip = tmp.resolve("hostile.zip");
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(zip)) {
            for (String name : List.of("ok.txt", "nul.txt", "visible.txt:hidden", "dots.", "sub/ok2.txt", "sub/aux")) {
                out.putArchiveEntry(new ZipArchiveEntry(name));
                out.write(name.getBytes(StandardCharsets.UTF_8));
                out.closeArchiveEntry();
            }
        }
        Path dest = tmp.resolve("out");
        ExtractResult result = ArchiveReader.extract(zip, new ExtractOptions(dest), new Job());

        assertEquals(2, result.files());
        assertEquals(4, result.skipped());
        assertEquals(4, result.warnings().size(), result.warnings().toString());
        assertEquals("ok.txt", Files.readString(dest.resolve("ok.txt")));
        assertEquals("sub/ok2.txt", Files.readString(dest.resolve("sub/ok2.txt")));
        try (Stream<Path> all = Files.walk(dest)) {
            assertEquals(List.of("ok.txt", "ok2.txt"), all.filter(Files::isRegularFile).map(path -> path.getFileName().toString()).sorted().toList());
        }
        // Nothing went to a device or into a stream of another file.
        assertEquals("ok.txt".length(), Files.size(dest.resolve("ok.txt")));
    }

    @Test
    void aReadOnlyFileIsReplacedWhenExtractingOverIt() throws Exception {
        Path zip = tmp.resolve("new.zip");
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(zip)) {
            out.putArchiveEntry(new ZipArchiveEntry("data.txt"));
            out.write("new".getBytes(StandardCharsets.UTF_8));
            out.closeArchiveEntry();
        }
        Path dest = Files.createDirectories(tmp.resolve("existing"));
        Path old = dest.resolve("data.txt");
        Files.writeString(old, "old");
        assumeTrue(old.toFile().setReadOnly());

        ArchiveReader.extract(zip, new ExtractOptions(dest), new Job());
        assertEquals("new", Files.readString(old));
    }

    @Test
    void filesWithoutUnixModesAreExecutableOnlyIfTheyAreProgramsOrScripts() {
        assertEquals(0644, ArchiveWriter.defaultMode("notes.txt", false));
        assertEquals(0644, ArchiveWriter.defaultMode("photo.JPG", false));
        assertEquals(0644, ArchiveWriter.defaultMode("README", false));
        assertEquals(0644, ArchiveWriter.defaultMode(".gitignore", false));
        assertEquals(0755, ArchiveWriter.defaultMode("setup.exe", false));
        assertEquals(0755, ArchiveWriter.defaultMode("Build.BAT", false));
        assertEquals(0755, ArchiveWriter.defaultMode("run.sh", false));
        assertEquals(0755, ArchiveWriter.defaultMode("folder", true));
    }

    @Test
    void aZipMadeFromWindowsFilesDoesNotMarkEveryFileExecutable() throws IOException {
        assumeTrue(SafePaths.onWindows());
        Path src = Files.createDirectories(tmp.resolve("src/project"));
        Files.writeString(src.resolve("notes.txt"), "hi");
        Files.writeString(src.resolve("go.bat"), "echo hi");
        Path zip = tmp.resolve("made.zip");
        ArchiveWriter.create(zip, List.of(src), new ArchiveWriter.CreateOptions(ArchiveFormat.ZIP), new Job());

        var entries = ArchiveReader.list(zip).entries();
        assertFalse(entries.stream().filter(entry -> entry.path().equals("project/notes.txt")).findFirst().orElseThrow().isExecutable());
        assertTrue(entries.stream().filter(entry -> entry.path().equals("project/go.bat")).findFirst().orElseThrow().isExecutable());
    }
}
