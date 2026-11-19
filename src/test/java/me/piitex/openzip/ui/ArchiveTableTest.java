package me.piitex.openzip.ui;

import me.piitex.openzip.archive.EntryInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Dragging across empty space below the rows draws a box and selects what it touches; dragging a row does not.
 * <p>
 * Rows are 24 tall under a 28 tall header: row i spans 28 + 24i to 52 + 24i. Four rows end at y = 124.
 */
class ArchiveTableTest {
    private ArchiveTable table;
    private List<Node> rows;
    private final List<List<Node>> changes = new ArrayList<>();

    private static double rowY(int row) {
        return 28 + row * 24 + 12;
    }

    @BeforeEach
    void setUp() {
        List<EntryInfo> entries = new ArrayList<>();
        for (String name : List.of("a.txt", "b.txt", "c.txt", "d.txt")) {
            entries.add(new EntryInfo(name, false, 10, 5, -1, 0644, null));
        }
        rows = Node.build(entries);
        table = new ArchiveTable(400, 200, picked -> null);
        table.addColumn("Name", node -> node.name).setWidth(150);
        table.setMultiSelect(true);
        table.setItems(rows);
        table.onSelectionChange(changes::add);
    }

    private void drag(double fromX, double fromY, double toX, double toY) {
        table.onDragStart(fromX, fromY);
        table.onDragUpdate(toX, toY);
        table.onDragEnd(toX, toY);
    }

    @Test
    void aBoxFromEmptySpaceSelectsTheRowsItTouches() {
        drag(300, 170, 300, rowY(1));
        assertEquals(table.getItems().subList(1, 4), table.getSelectedItems());
    }

    @Test
    void theBoxCanBeDraggedAnyWayAcrossTheRows() {
        drag(300, 170, 20, 60); // up and to the left
        assertEquals(table.getItems().subList(1, 4), table.getSelectedItems());
        drag(20, 190, 300, rowY(0)); // to the top row
        assertEquals(table.getItems(), table.getSelectedItems());
    }

    @Test
    void theBoxFollowsThePointerBackDown() {
        table.onDragStart(300, 170);
        table.onDragUpdate(300, rowY(0));
        assertEquals(4, table.getSelectedItems().size());
        table.onDragUpdate(300, rowY(2));
        assertEquals(table.getItems().subList(2, 4), table.getSelectedItems());
        table.onDragUpdate(300, 190); // back below every row
        assertEquals(List.of(), table.getSelectedItems());
        table.onDragEnd(300, 190);
    }

    @Test
    void theSelectionIsReportedAsTheBoxChangesIt() {
        table.setSelectedItems(List.of(table.getItems().get(0)));
        changes.clear();
        drag(300, 170, 300, rowY(2));
        assertEquals(table.getItems().subList(2, 4), changes.getLast());
    }

    @Test
    void aBoxThatTouchesNothingSelectsNothing() {
        table.setSelectedItems(List.of(table.getItems().get(0)));
        drag(300, 150, 20, 190);
        assertEquals(List.of(), table.getSelectedItems());
    }

    @Test
    void aClickOnEmptySpaceStillClearsTheSelection() {
        table.setSelectedItems(table.getItems());
        drag(300, 170, 301, 171); // less than the few points that make a box
        assertEquals(List.of(), table.getSelectedItems());
    }

    @Test
    void pressingARowDragsTheRowNotABox() {
        table.onDragStart(20, rowY(0));
        table.onDragUpdate(20, rowY(3));
        table.onDragEnd(20, rowY(3));
        assertEquals(List.of(table.getItems().get(0)), table.getSelectedItems());
    }

    @Test
    void theHeaderStartsNoBox() {
        drag(20, 14, 20, rowY(3));
        assertEquals(List.of(), table.getSelectedItems());
    }
}
