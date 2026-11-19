package me.piitex.openzip.archive;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SafePathsTest {
    @Test
    void normalizeCleansHarmlessNames() {
        assertEquals("a/b.txt", SafePaths.normalize("./a//b.txt"));
        assertEquals("dir", SafePaths.normalize("dir/"));
        assertEquals("a/b", SafePaths.normalize("a\\b"));
        assertEquals("a/b", SafePaths.normalize("a/./b"));
    }

    @Test
    void normalizeRejectsNamesThatCouldEscape() {
        for (String bad : new String[]{"../x", "a/../../x", "a/..", "/etc/passwd", "\\windows\\x", "C:\\x", "c:/x", "", "./", "a\0b"}) {
            assertNull(SafePaths.normalize(bad), bad);
        }
        assertNull(SafePaths.normalize(null));
    }

    @Test
    void linksMustStayInside() {
        Path root = Path.of("/dest");
        Path link = Path.of("/dest/a/link");
        assertTrue(SafePaths.linkStaysInside(root, link, "file.txt"));
        assertTrue(SafePaths.linkStaysInside(root, link, "../b/file.txt"));
        assertFalse(SafePaths.linkStaysInside(root, link, "../../outside"));
        assertFalse(SafePaths.linkStaysInside(root, link, "/etc/passwd"));
        assertFalse(SafePaths.linkStaysInside(root, link, ""));
    }

    @Test
    void junkDetection() {
        assertTrue(SafePaths.isJunkPath("__MACOSX/foo/._bar"));
        assertTrue(SafePaths.isJunkPath("a/b/.DS_Store"));
        assertTrue(SafePaths.isJunkPath("a/._b"));
        assertFalse(SafePaths.isJunkPath("a/b/c.txt"));
        assertFalse(SafePaths.isJunkPath(".gitignore"));
    }
}
