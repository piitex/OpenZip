package me.piitex.openzip.ui;

import me.piitex.openzip.archive.EntryInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** What the table shows for each row: sizes that add up, empty cells where the archive doesn't say, and folders to jump to. */
class NodeTest {
    private static EntryInfo file(String path, long size, long packed, long modified) {
        return new EntryInfo(path, false, size, packed, modified, 0644, null);
    }

    private static List<Node> sample() {
        return Node.build(List.of(
                file("docs/a.txt", 1000, 400, 1_700_000_000_000L),
                file("docs/deep/b.txt", 2500, -1, -1),
                file("top.txt", 10, 12, -1)));
    }

    @Test
    void foldersAddUpTheirContents() {
        Node docs = Node.folders(sample()).get("docs");
        assertEquals(3500, docs.totalSize());
        assertEquals(2, docs.fileCount());
        assertEquals("3.5 KB", docs.sizeText());
    }

    @Test
    void foldersTheArchiveNeverListedAreStillThere() {
        Map<String, Node> folders = Node.folders(sample());
        assertEquals(java.util.Set.of("docs", "docs/deep"), folders.keySet());
        assertEquals(1, folders.get("docs/deep").children.size());
    }

    @Test
    void cellsStayEmptyWhenTheArchiveDoesNotSay() {
        Map<String, Node> folders = Node.folders(sample());
        Node first = folders.get("docs/deep").children.getFirst();
        assertEquals("", first.packedText(), "no packed size recorded");
        assertEquals("", first.modifiedText(), "no date recorded");
        assertEquals(0, first.packedSize());
        assertEquals("", folders.get("docs").packedText(), "a folder isn't packed on its own");
    }

    @Test
    void packedAndModifiedAreFormattedWhenKnown() {
        Node file = Node.folders(sample()).get("docs").children.stream().filter(node -> node.name.equals("a.txt")).findFirst().orElseThrow();
        assertEquals("400 bytes", file.packedText());
        assertFalse(file.modifiedText().isEmpty());
    }

    @Test
    void topLevelIsFoldersFirst() {
        List<Node> roots = sample();
        assertEquals(List.of("docs", "top.txt"), roots.stream().map(node -> node.name).toList());
    }
}
