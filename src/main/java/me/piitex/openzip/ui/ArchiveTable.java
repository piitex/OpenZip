package me.piitex.openzip.ui;

import me.piitex.engine.gl.renderer.Renderer;
import me.piitex.engine.input.FileDrag;
import me.piitex.engine.ui.FileDraggable;
import me.piitex.engine.ui.containers.TableView;
import org.lwjgl.glfw.GLFW;

import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The table of an archive's entries. Dragging a row past the edge of the window carries the selected entries on to
 * Explorer or the desktop as files (RenGL does that on Windows only; elsewhere the drag just ends at the edge).
 * <p>
 * Dragging from the empty space below the rows draws a selection box, as on the desktop, and selects the rows it
 * touches. With Ctrl (Cmd on a Mac) held the box toggles rows against what was selected, with Shift it adds to it.
 * The table scrolls on its own while the pointer is held above or below it.
 * <p>
 * A press within {@code SCROLL_BAR_BAND} points of the right edge may be on the scroll bar, which must not start a drag
 * of files. A press on empty space becomes a box rather than a click once the pointer has moved {@code BOX_THRESHOLD}
 * points. Past the top or bottom edge the table scrolls at {@code AUTO_SCROLL_BASE} points per second plus
 * {@code AUTO_SCROLL_PER_POINT} for each point the pointer is away, up to {@code AUTO_SCROLL_MAX}.
 * <p>
 * The box keeps both its corners, the press and the pointer, as a distance into the table's content (below the header,
 * scrolled with the rows), so scrolling keeps the first corner on the row it started at.
 */
final class ArchiveTable extends TableView<Node> implements FileDraggable {
    private static final double SCROLL_BAR_BAND = 16;
    private static final double BOX_THRESHOLD = 4;
    private static final double AUTO_SCROLL_BASE = 240, AUTO_SCROLL_PER_POINT = 10, AUTO_SCROLL_MAX = 1800;

    private final Function<List<Node>, FileDrag.Source> dragSource;
    private List<Node> carried = List.of();
    private Consumer<List<Node>> selectionListener;

    private boolean boxing, boxVisible;
    private double boxStartX, boxStartY, pointerX, pointerY;
    private boolean boxToggle, boxAdd;
    private List<Node> boxRows = List.of();
    private Set<Node> boxBase = Set.of();
    private int boxLo = 1, boxHi = 0;

    ArchiveTable(double width, double height, Function<List<Node>, FileDrag.Source> dragSource) {
        super(width, height);
        this.dragSource = dragSource;
    }

    /**
     * Kept so the box can report the selection it makes: the table's own notification is private.
     */
    @Override
    public void onSelectionChange(Consumer<List<Node>> onSelectionChange) {
        this.selectionListener = onSelectionChange;
        super.onSelectionChange(onSelectionChange);
    }

    @Override
    public void onDragStart(double x, double y) {
        List<Node> before = getSelectedItems();
        super.onDragStart(x, y);
        carried = List.of();
        boxing = boxVisible = false;
        double fromTop = y - getAbsoluteY();
        if (fromTop < getHeaderHeight() || x > getAbsoluteX() + getWidth() - SCROLL_BAR_BAND || thumbAt(x, y)) return;
        List<Node> now = getSelectedItems();
        // Pressing a row that is one of several selected picks only that row. Dragging it should still take them all.
        int row = (int) ((fromTop + getScrollOffsetY() - getHeaderHeight()) / getRowHeight());
        List<Node> rows = getItems();
        Node pressed = row >= 0 && row < rows.size() ? rows.get(row) : null;
        if (pressed == null) {
            beginBox(x, y, rows, before);
            return;
        }
        boolean collapsed = before.size() > 1 && before.contains(pressed) && now.equals(List.of(pressed));
        carried = collapsed ? before : now;
    }

    @Override
    public void onDragUpdate(double x, double y) {
        super.onDragUpdate(x, y);
        if (!boxing) return;
        pointerX = x;
        pointerY = y;
        updateBox();
    }

    @Override
    public void onDragEnd(double x, double y) {
        boxing = boxVisible = false;
        boxRows = List.of();
        boxBase = Set.of();
        super.onDragEnd(x, y);
    }

    @Override
    public FileDrag.Source fileDragOut() {
        return carried.isEmpty() ? null : dragSource.apply(carried);
    }

    @Override
    public void onDragCancel() {
        // The drag became the system's: put back the selection the press collapsed.
        if (carried.size() > 1 && !carried.equals(getSelectedItems())) setSelectedItems(carried);
        carried = List.of();
        boxing = boxVisible = false;
        boxRows = List.of();
        boxBase = Set.of();
        super.onDragCancel();
    }

    private void beginBox(double x, double y, List<Node> rows, List<Node> before) {
        if (rows.isEmpty()) return;
        int mods = heldModifiers();
        boxToggle = (mods & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;
        boxAdd = !boxToggle && (mods & GLFW.GLFW_MOD_SHIFT) != 0;
        boxing = true;
        boxVisible = false;
        boxRows = rows;
        boxBase = new HashSet<>(before);
        boxLo = 1;
        boxHi = 0;
        boxStartX = x - getAbsoluteX() + getScrollOffsetX();
        boxStartY = y - getAbsoluteY() + getScrollOffsetY();
        pointerX = x;
        pointerY = y;
        // A click on empty space clears the selection, but with a modifier held it is the start of adding to it.
        if ((boxToggle || boxAdd) && !before.isEmpty()) select(before);
    }

    /**
     * Selects the rows the box touches, once the pointer has moved far enough that it is a box and not a click.
     */
    private void updateBox() {
        double toX = pointerX - getAbsoluteX() + getScrollOffsetX(), toY = pointerY - getAbsoluteY() + getScrollOffsetY();
        if (!boxVisible) {
            if (Math.abs(toX - boxStartX) < BOX_THRESHOLD && Math.abs(toY - boxStartY) < BOX_THRESHOLD) return;
            boxVisible = true;
        }
        double top = Math.min(boxStartY, toY), bottom = Math.max(boxStartY, toY);
        double header = getHeaderHeight(), rowHeight = getRowHeight();
        int lo = Math.max(0, (int) Math.floor((top - header) / rowHeight));
        int hi = Math.min(boxRows.size() - 1, (int) Math.floor((bottom - header) / rowHeight));
        if (lo == boxLo && hi == boxHi) return;
        boxLo = lo;
        boxHi = hi;

        List<Node> picked = new ArrayList<>();
        for (int i = 0; i < boxRows.size(); i++) {
            Node node = boxRows.get(i);
            boolean inside = i >= lo && i <= hi;
            boolean base = boxBase.contains(node);
            if (boxToggle ? inside != base : inside || (boxAdd && base)) picked.add(node);
        }
        select(picked);
    }

    private void select(List<Node> rows) {
        if (rows.equals(getSelectedItems())) return;
        setSelectedItems(rows);
        if (selectionListener != null) selectionListener.accept(getSelectedItems());
        notifyAccessibleChange();
    }

    /**
     * Scrolls while the pointer is held above or below the rows, so a box can reach rows that are out of view.
     */
    @Override
    public void update(float delta) {
        super.update(delta);
        if (!boxing || !boxVisible) return;
        double top = getAbsoluteY() + getHeaderHeight(), bottom = getAbsoluteY() + getHeight();
        double away = pointerY < top ? top - pointerY : pointerY > bottom ? pointerY - bottom : 0;
        if (away <= 0) return;
        double speed = Math.min(AUTO_SCROLL_MAX, AUTO_SCROLL_BASE + away * AUTO_SCROLL_PER_POINT);
        double before = getScrollOffsetY();
        scrollBy(0, (pointerY < top ? -speed : speed) * delta);
        if (getScrollOffsetY() != before) updateBox();
    }

    @Override
    public void render(Renderer renderer) {
        super.render(renderer);
        if (!isEnabled() || !boxing || !boxVisible) return;
        double scrollX = getScrollOffsetX(), scrollY = getScrollOffsetY();
        double toX = pointerX - getAbsoluteX() + scrollX, toY = pointerY - getAbsoluteY() + scrollY;
        float x = (float) (getX() + Math.min(boxStartX, toX) - scrollX), y = (float) (getY() + Math.min(boxStartY, toY) - scrollY);
        float width = (float) Math.abs(toX - boxStartX), height = (float) Math.abs(toY - boxStartY);
        float bodyTop = (float) (getY() + getHeaderHeight());
        renderer.pushClip((float) getX(), bodyTop, (float) getWidth(), (float) getHeight() - getHeaderHeight());
        renderer.pushAlpha(0.18f);
        renderer.drawQuad(x, y, width, height, Palette.ACCENT, 0f);
        renderer.popAlpha();
        renderer.pushAlpha(0.85f);
        renderer.drawBorder(x, y, width, height, Palette.ACCENT, 1f, 0f);
        renderer.popAlpha();
        renderer.popClip();
    }
}
