package me.piitex.openzip.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TextFilesTest {
    @Test
    void commonTextFilesAreRecognised() {
        for (String name : List.of("notes.txt", "README.md", "run.sh", "build.BAT", "config.yaml", "src/Main.java", "a/b/.gitignore",
                "LICENSE", "docs/README", "Makefile", "Dockerfile", "data.json", "x.ps1")) {
            assertTrue(TextFiles.isText(name), name);
        }
    }

    @Test
    void otherFilesAreNot() {
        for (String name : List.of("photo.jpg", "setup.exe", "tool.jar", "archive.zip", "a.out", "folder.d/data", "noextension", "movie.mp4")) {
            assertFalse(TextFiles.isText(name), name);
        }
    }

    @Test
    void markdownIsOnlyMarkdown() {
        assertTrue(TextFiles.isMarkdown("docs/Guide.MD"));
        assertTrue(TextFiles.isMarkdown("a.markdown"));
        assertFalse(TextFiles.isMarkdown("a.txt"));
        assertFalse(TextFiles.isMarkdown("README"));
    }

    @Test
    void macOpensTheDefaultTextEditorNotTheFilesOwnProgram() {
        Path file = Path.of("/tmp/run.sh");
        assertEquals(List.of("open", "-t", file.toString()), TextFiles.editorCommand("Mac OS X", file, () -> null)); // \ on Windows
    }

    @Test
    void windowsUsesNotepad() {
        assertEquals("notepad.exe", TextFiles.editorCommand("Windows 11", Path.of("C:/t/run.bat"), () -> null).getFirst());
    }

    @Test
    void linuxNamesTheEditorAndNeverFallsBackToXdgOpen() {
        Path file = Path.of("/tmp/run.sh");
        assertEquals(List.of("gtk-launch", "org.gnome.gedit.desktop", file.toString()),
                TextFiles.editorCommand("Linux", file, () -> "org.gnome.gedit.desktop"));
        assertNull(TextFiles.editorCommand("Linux", file, () -> null));
    }

    @Test
    void unknownSystemsHaveNoEditor() {
        assertNull(TextFiles.editorCommand("Plan 9", Path.of("/tmp/a.txt"), () -> "x"));
    }

    @Test
    void readsUtf8DropsTheBomAndNormalisesLineEndings(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.txt");
        byte[] body = "one\r\ntwo\rthree é\n".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);
        Files.write(file, withBom);
        TextFiles.Content content = TextFiles.read(file, 1000);
        assertEquals("one\ntwo\nthree é\n", content.text());
        assertFalse(content.truncated());
    }

    @Test
    void aBinaryFileIsNotText(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("fake.txt");
        Files.write(file, new byte[]{'a', 'b', 0, 'c'});
        assertNull(TextFiles.read(file, 1000));
    }

    @Test
    void aLongFileIsCutAndSaysSoWithoutHalfACharacter(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("long.txt");
        Files.writeString(file, "aé".repeat(50)); // 150 bytes
        TextFiles.Content content = TextFiles.read(file, 11); // ends between the two bytes of an é
        assertTrue(content.truncated());
        assertEquals("aéaéaéa", content.text()); // 11 bytes: three "aé", an "a", and half of the next é
    }
}
