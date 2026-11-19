package me.piitex.openzip.ui;

import me.piitex.engine.Engine;
import me.piitex.engine.Window;
import me.piitex.engine.ui.Element;
import me.piitex.engine.utils.Browser;
import me.piitex.engine.input.FileDialog;
import me.piitex.engine.input.FileDrag;
import me.piitex.engine.scheduler.AsyncTask;
import me.piitex.engine.scheduler.Scheduler;
import me.piitex.engine.scheduler.Task;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.LinearGradient;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.containers.ModalContainer;
import me.piitex.engine.ui.containers.TableView;
import me.piitex.engine.ui.icons.Feather;
import me.piitex.engine.ui.image.IconAsset;
import me.piitex.engine.ui.layout.HorizontalLayout;
import me.piitex.engine.ui.layout.Layout;
import me.piitex.engine.ui.layout.StackLayout;
import me.piitex.engine.ui.layout.VerticalLayout;
import me.piitex.engine.ui.menu.MenuBarOverlay;
import me.piitex.engine.ui.menu.MenuItem;
import me.piitex.engine.ui.menu.PopupMenu;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.CheckBoxOverlay;
import me.piitex.engine.ui.overlays.DropdownOverlay;
import me.piitex.engine.ui.overlays.IconOverlay;
import me.piitex.engine.ui.overlays.ProgressBarOverlay;
import me.piitex.engine.ui.overlays.SeparatorOverlay;
import me.piitex.engine.ui.overlays.SliderOverlay;
import me.piitex.engine.ui.overlays.TextFieldOverlay;
import me.piitex.engine.ui.overlays.TextFlowOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.text.TextAlign;
import me.piitex.engine.ui.theme.ThemeManager;
import me.piitex.openzip.archive.ArchiveFormat;
import me.piitex.openzip.archive.ArchiveReader;
import me.piitex.openzip.archive.ArchiveReader.ExtractOptions;
import me.piitex.openzip.archive.ArchiveReader.Listing;
import me.piitex.openzip.archive.ArchiveReader.Overwrite;
import me.piitex.openzip.archive.ArchiveWriter;
import me.piitex.openzip.archive.ArchiveWriter.CreateOptions;
import me.piitex.openzip.archive.Destinations;
import me.piitex.openzip.archive.EntryInfo;
import me.piitex.openzip.archive.Job;
import me.piitex.openzip.archive.PasswordException;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * The OpenZip window: a header, the archive's contents as a table (one folder at a time) on a rounded panel, and a
 * status line with progress.
 * <p>
 * Everything slow (listing, extracting, compressing, testing) runs on a worker thread through {@link Scheduler#async}
 * and is described by a {@link Job}. The render thread polls the job every few frames to move the progress bar, so no
 * worker thread ever touches an element.
 * <p>
 * The elements are built once and positioned by {@link #layout()}, which runs on every resize and whenever something
 * appears or disappears.
 * <p>
 * State worth knowing about: {@code roots} is the top level of the open archive, {@code folders} holds every folder in
 * it by path, and {@code dir} is the path of the one being shown ("" is the top). {@code openedIn} lists the folders
 * made to open a file from an archive; they go when OpenZip quits. {@code password} is for the open archive, once the
 * user has typed it, and is dropped when another archive is opened or this one is closed. {@code afterOpening} is what
 * to say once the archive being listed is on screen, in place of the usual hint, such as the result of adding to it.
 * {@code systemChanged} records that the system switched between light and dark while a job was running, to be applied
 * when it ends. {@code chosen} is what the View menu says is chosen, and starts as the saved choice.
 */
public final class OpenZipApp {
    private static final double MARGIN = 28;
    private static final double HEADER_HEIGHT = 76;
    private static final double STATUS_HEIGHT = 60;
    private static final double DETAIL_HEIGHT = 46;
    private static final double INSET = 10;
    private static final double PATH_HEIGHT = 44;
    private static final double GAP = 10;

    private enum Tone {IDLE, BUSY, OK, ERROR}

    private final Window window;
    private Container root;
    private int width;
    private int height;
    private double menuOffset;

    private StackLayout badge;
    private TextOverlay wordmark;
    private ButtonOverlay openButton;
    private ButtonOverlay newButton;
    private SeparatorOverlay rule;

    private TextOverlay title;
    private TextOverlay subtitle;
    private ButtonOverlay extractAllButton;
    private ButtonOverlay extractSelectedButton;
    private ButtonOverlay testButton;
    private ButtonOverlay closeButton;

    private Container panel;
    private ButtonOverlay upButton;
    private HorizontalLayout crumbs;
    private TableView<Node> table;
    private TableView.Column<Node> nameColumn;
    private TextOverlay emptyFolder;
    private double lastTableWidth;
    private SeparatorOverlay detailRule;
    private TextOverlay detailName;
    private TextOverlay detailRest;
    private VerticalLayout emptyState;

    private IconOverlay statusIcon;
    private TextOverlay statusText;
    private ProgressBarOverlay progress;
    private ButtonOverlay cancelButton;
    private ButtonOverlay revealButton;

    private Path archive;
    private Listing listing;
    private List<Node> roots = List.of();
    private Map<String, Node> folders = Map.of();
    private String dir = "";

    /**
     * An archive that was open when one inside it was opened, as it was left. Oldest first.
     */
    private record Outer(Path archive, Listing listing, String dir, char[] password) {
    }

    private final List<Outer> outer = new ArrayList<>();
    private final List<Path> openedIn = new CopyOnWriteArrayList<>();
    private TextPreview shown;
    private MenuItem finderItem;
    private MenuBarOverlay menu;
    private MenuItem[] appearanceItems;
    private char[] password;
    private Path lastOutput;
    private String afterOpening;
    private Job job;
    private boolean cancellable;
    private Task poller;
    private Tone tone = Tone.IDLE;
    private boolean systemChanged;
    private String statusFull = "Ready.";
    private String detailNameText = "";
    private String detailRestText = "";

    private static Appearance.Mode chosen = Appearance.Mode.SYSTEM;

    /**
     * Switches every themed widget to OpenZip's look, light or dark as last chosen. Call before the window is built.
     */
    public static void installTheme() {
        installTheme(Appearance.load());
    }

    static void installTheme(Appearance.Mode mode) {
        chosen = mode;
        Palette.use(mode.scheme());
        ThemeManager.setCurrent(new OpenZipTheme());
    }

    public OpenZipApp(Window window, int width, int height) {
        this.window = window;
        this.width = width;
        this.height = height;

        build();
        window.addContainer(root);
        window.onResize((newWidth, newHeight) -> {
            this.width = newWidth;
            this.height = newHeight;
            layout();
        });
        window.onFileDrop(this::openPaths);
        Engine.onSystemAppearanceChange(mode -> {
            if (chosen != Appearance.Mode.SYSTEM) return;
            if (job != null)
                systemChanged = true; // rebuilding the window under a running job would orphan its progress
            else setScheme(Appearance.Mode.SYSTEM.scheme());
        });
        Runtime.getRuntime().addShutdownHook(new Thread(this::removeOpened));
        showArchiveControls(false);
        setStatus(Tone.IDLE, "Open an archive, or drop one here.");
    }

    /**
     * Creates every element in the colors of the scheme in use. Run again when the look changes.
     */
    private void build() {
        root = new Container(width, height);
        Components.clearStyle(root);

        badge = Components.gradientBadge(36, Feather.ARCHIVE, 18);
        wordmark = Components.text("OpenZip", 19f, Palette.TEXT);
        openButton = Components.secondaryButton("Open", 92, this::chooseArchive);
        openButton.setTooltip("Open an archive to browse it");
        newButton = Components.accentButton("+ New Archive", Components.pillWidth("+ New Archive"), this::showNewArchiveChooser);
        newButton.setTooltip("Compress files or a folder");
        rule = new SeparatorOverlay(Math.max(0, width - 2 * MARGIN));
        rule.setLineColor(Palette.BORDER);

        title = Components.text("", 24f, Palette.TEXT);
        subtitle = Components.text("", 13f, Palette.MUTED);
        extractAllButton = Components.accentButton("Extract All", Components.pillWidth("Extract All"), () -> extract(null));
        extractAllButton.setTooltip("Extract everything into a new folder");
        extractSelectedButton = Components.secondaryButton("Extract Selected", Components.pillWidth("Extract Selected"), this::extractSelected);
        extractSelectedButton.setTooltip("Extract only the selected files and folders");
        extractSelectedButton.onDisabledClick(() -> fail("Select a file or folder in the list first."));
        testButton = Components.iconButton(Feather.SHIELD, "Check every file for damage", Components.PILL_HEIGHT, Palette.MUTED, this::testArchive);
        closeButton = Components.iconButton(Feather.X, "Close this archive", Components.PILL_HEIGHT, Palette.MUTED, this::closeArchive);

        panel = Components.panel(100, 100);
        upButton = Components.iconButton(Feather.CORNER_LEFT_UP, "Up one folder", 30, Palette.MUTED, this::up);
        crumbs = new HorizontalLayout(100, 30);
        crumbs.setSpacing(6f);
        crumbs.setAlignment(Layout.Alignment.CENTER_LEFT);
        Components.clearStyle(crumbs);
        table = new ArchiveTable(100, 100, this::dragOutSource);
        styleTable();
        emptyFolder = Components.text("This folder is empty", 14f, Palette.MUTED);
        detailRule = new SeparatorOverlay(100);
        detailRule.setLineColor(Palette.BORDER);
        detailName = Components.text("", 14f, Palette.TEXT);
        detailRest = Components.text("", 13f, Palette.MUTED);
        emptyState = buildEmptyState();
        panel.addElement(upButton);
        panel.addElement(crumbs);
        panel.addElement(table);
        panel.addElement(emptyFolder);
        panel.addElement(detailRule);
        panel.addElement(detailName);
        panel.addElement(detailRest);
        panel.addElement(emptyState);

        statusIcon = new IconOverlay(Feather.INFO, 16, 16);
        statusText = Components.text(statusFull, 14f, Palette.MUTED);
        progress = new ProgressBarOverlay(170, 6);
        progress.setFillColor(new LinearGradient(Palette.ACCENT, Palette.ACCENT_END));
        progress.getStyling().setBackgroundColor(Palette.BUTTON_HOVER);
        progress.getStyling().setBorderThickness(0f);
        progress.getStyling().setCornerRadius(3f);
        cancelButton = Components.secondaryButton("Cancel", Components.pillWidth("Cancel"), this::cancelJob);
        cancelButton.setTooltip("Stop the current job");
        revealButton = Components.secondaryButton("Show in " + Platforms.fileManager(),
                Components.pillWidth("Show in " + Platforms.fileManager()), this::reveal);
        revealButton.setTooltip("Show the result in " + Platforms.fileManager());

        root.addElements(badge, wordmark, openButton, newButton, rule,
                title, subtitle, extractAllButton, extractSelectedButton, testButton, closeButton,
                panel,
                statusIcon, statusText, progress, cancelButton, revealButton);
    }

    /**
     * Files the user dropped on the window or asked the OS to open with OpenZip: one archive is opened, anything
     * else is added to the zip that is open (into the folder being shown), or offered for compressing if no zip is.
     */
    public void openPaths(List<Path> paths) {
        if (paths.isEmpty() || busy()) return;
        Path first = paths.getFirst();
        if (paths.size() == 1 && Files.isRegularFile(first) && ArchiveFormat.detect(first).isPresent()) {
            openArchive(first);
        } else if (hasArchive() && ArchiveFormat.detect(archive).filter(ArchiveWriter::canAddTo).isPresent()) {
            addToArchive(paths);
        } else {
            showCreateDialog(paths);
        }
    }

    /**
     * Adds dropped files and folders to the open zip, after asking if any of them would replace something in it.
     */
    private void addToArchive(List<Path> paths) {
        if (!outer.isEmpty()) {
            fail(archive.getFileName() + " was opened from inside " + outer.getLast().archive().getFileName()
                    + ", so what is added to it wouldn't reach the original.");
            return;
        }
        if (listing.encrypted()) {
            fail("Adding to a password protected zip isn't supported: the new files would not be protected.");
            return;
        }
        Path target = archive;
        String folder = dir;
        Set<String> taken = new HashSet<>(folders.keySet());
        for (EntryInfo entry : listing.entries()) taken.add(entry.path());
        List<String> clashes = new ArrayList<>();
        for (Path path : paths) {
            Path name = path.toAbsolutePath().normalize().getFileName();
            String base = name == null ? "root" : name.toString();
            if (ArchiveWriter.isJunk(base)) continue;
            if (taken.contains(folder.isEmpty() ? base : folder + "/" + base)) clashes.add(base);
        }
        if (clashes.isEmpty()) {
            runAdd(target, folder, paths);
        } else {
            askToReplace(clashes, paths, () -> runAdd(target, folder, paths));
        }
    }

    private void askToReplace(List<String> clashes, List<Path> paths, Runnable replace) {
        double dialogWidth = 440, inner = dialogWidth - 48;
        VerticalLayout dialog = Components.dialogPanel(dialogWidth, 206);
        ModalContainer modal = new ModalContainer(dialog);
        boolean one = clashes.size() == 1;
        dialog.addElement(Components.text(one ? "Replace " + shorten(clashes.getFirst(), 28) + "?" : "Replace " + clashes.size() + " items?",
                20f, Palette.TEXT, inner));
        String names = String.join(", ", clashes.stream().limit(3).map(name -> shorten(name, 24)).toList())
                + (clashes.size() > 3 ? " and " + (clashes.size() - 3) + " more" : "");
        String where = dir.isEmpty() ? "this archive" : "this folder";
        TextFlowOverlay hint = new TextFlowOverlay(one
                ? names + " is already in " + where + ". Adding it replaces what is there. A folder is merged: files with the same name are replaced."
                : names + " are already in " + where + ". Adding them replaces what is there. Folders are merged: files with the same name are replaced.",
                inner, 13.5f, Palette.MUTED);
        hint.setTextColor(Palette.MUTED);
        dialog.addElement(hint);
        double half = (inner - 12) / 2;
        ButtonOverlay cancel = Components.secondaryButton("Cancel", half, () -> {
            modal.close();
            say("Nothing was added.");
        });
        ButtonOverlay yes = Components.accentButton("Replace", half, () -> {
            modal.close();
            replace.run();
        });
        dialog.addElement(Components.dialogButtons(inner, cancel, yes));
        modal.show(window);
    }

    /**
     * Adds {@code paths} to the zip or tar {@code target} in the background, then shows it again with them in.
     */
    private void runAdd(Path target, String folder, List<Path> paths) {
        if (busy()) return;
        Job job = new Job();
        begin("Adding to " + target.getFileName(), job, true);
        AsyncTask<ArchiveWriter.CreateResult> task = Scheduler.async(() -> {
            try {
                return ArchiveWriter.addTo(target, folder, paths, true, job);
            } catch (IOException exception) {
                throw new IllegalStateException(exception.getMessage() == null ? exception.toString() : exception.getMessage(), exception);
            }
        });
        task.onSuccess(result -> {
            finish();
            lastOutput = result.archive();
            String what = result.folders() > 0
                    ? Format.count(result.files(), "file", "files") + " and " + Format.count(result.folders(), "folder", "folders")
                    : Format.count(result.files(), "file", "files");
            afterOpening = "Added " + what + " (" + Format.size(result.uncompressedBytes()) + ") to "
                    + target.getFileName() + (folder.isEmpty() ? "" : " in " + folder);
            openArchive(target, password);
        });
        task.onError(exception -> {
            finish();
            fail(exception instanceof CancellationException ? "Adding cancelled. " + target.getFileName() + " is unchanged."
                    : "Couldn't add to " + target.getFileName() + ": " + message(exception));
        });
    }

    /**
     * Asks, then removes the selected files and folders from the open zip or tar.
     */
    private void deleteSelected() {
        if (busy()) return;
        List<Node> selected = table.getSelectedItems();
        if (archive == null) {
            fail("Open an archive first.");
        } else if (selected.isEmpty()) {
            fail("Select a file or folder in the list first.");
        } else if (!ArchiveFormat.detect(archive).filter(ArchiveWriter::canDeleteFrom).isPresent()) {
            fail("OpenZip can only delete from zip and tar archives.");
        } else if (!outer.isEmpty()) {
            fail(archive.getFileName() + " was opened from inside " + outer.getLast().archive().getFileName()
                    + ", so what is deleted from it wouldn't reach the original.");
        } else if (listing.encrypted()) {
            fail("Deleting from a password protected zip isn't supported yet.");
        } else {
            Path target = archive;
            Set<String> paths = new HashSet<>();
            for (Node node : selected) paths.add(node.path);
            askToDelete(selected, () -> runDelete(target, paths));
        }
    }

    private void askToDelete(List<Node> selected, Runnable delete) {
        double dialogWidth = 440, inner = dialogWidth - 48;
        VerticalLayout dialog = Components.dialogPanel(dialogWidth, 206);
        ModalContainer modal = new ModalContainer(dialog);
        boolean one = selected.size() == 1;
        dialog.addElement(Components.text(one ? "Delete " + shorten(selected.getFirst().name, 28) + "?" : "Delete " + selected.size() + " items?",
                20f, Palette.TEXT, inner));
        long inside = 0; // files that go with a folder
        int folders = 0;
        for (Node node : selected) {
            if (!node.isFolder()) continue;
            folders++;
            inside += node.fileCount();
        }
        String what = one ? shorten(selected.getFirst().name, 24) : selected.size() + " items";
        String hint = what + (one ? " is" : " are") + " removed from the archive itself"
                + (folders > 0 ? ", with the " + Format.count((int) inside, "file", "files") + " in " + (folders == 1 ? "the folder" : "the folders") : "")
                + ". Nothing on your disk is touched, and this can't be undone.";
        TextFlowOverlay text = new TextFlowOverlay(hint, inner, 13.5f, Palette.MUTED);
        text.setTextColor(Palette.MUTED);
        dialog.addElement(text);
        double half = (inner - 12) / 2;
        ButtonOverlay cancel = Components.secondaryButton("Cancel", half, modal::close);
        ButtonOverlay yes = Components.dangerButton("Delete", half, () -> {
            modal.close();
            delete.run();
        });
        dialog.addElement(Components.dialogButtons(inner, cancel, yes));
        modal.show(window);
    }

    /**
     * Deletes {@code paths} from the zip or tar {@code target} in the background, then shows it again without them.
     */
    private void runDelete(Path target, Set<String> paths) {
        if (busy()) return;
        Job job = new Job();
        begin("Deleting from " + target.getFileName(), job, true);
        AsyncTask<ArchiveWriter.DeleteResult> task = Scheduler.async(() -> {
            try {
                return ArchiveWriter.deleteFrom(target, paths, job);
            } catch (IOException exception) {
                throw new IllegalStateException(exception.getMessage() == null ? exception.toString() : exception.getMessage(), exception);
            }
        });
        task.onSuccess(result -> {
            finish();
            String what = result.folders() > 0
                    ? Format.count(result.files(), "file", "files") + " and " + Format.count(result.folders(), "folder", "folders")
                    : Format.count(result.files(), "file", "files");
            afterOpening = "Deleted " + what + " from " + target.getFileName();
            openArchive(target, password);
        });
        task.onError(exception -> {
            finish();
            fail(exception instanceof CancellationException ? "Deleting cancelled. " + target.getFileName() + " is unchanged."
                    : "Couldn't delete from " + target.getFileName() + ": " + message(exception));
        });
    }

    /**
     * Builds the macOS system menu bar (or an in-window one elsewhere, which pushes the content down).
     */
    public MenuBarOverlay menuBar(int width) {
        MenuBarOverlay bar = new MenuBarOverlay(width);
        bar.setUseNativeMenuBar(true);
        List<MenuItem> file = new ArrayList<>(List.of(
                new MenuItem("Open Archive...", this::chooseArchive),
                new MenuItem("New Archive from Files...", this::chooseFilesToCompress),
                new MenuItem("New Archive from Folder...", this::chooseFolderToCompress),
                MenuItem.separator(),
                new MenuItem("Close Archive", this::closeArchive)));
        if (FinderAction.supported()) {
            finderItem = MenuItem.check("Add to Finder's Right-Click Menu",
                    FinderAction.isInstalled(FinderAction.servicesFolder()), this::setFinderAction);
            file.add(MenuItem.separator());
            file.add(finderItem);
        }
        file.add(new MenuItem("Quit", window::close));
        bar.addMenu("File", file.toArray(new MenuItem[0]));
        bar.addMenu("Archive",
                new MenuItem("Extract All...", () -> extract(null)),
                new MenuItem("Extract Selected...", this::extractSelected),
                new MenuItem("Delete Selected...", this::deleteSelected),
                new MenuItem("Test Integrity", this::testArchive));
        appearanceItems = new MenuItem[Appearance.Mode.values().length];
        appearanceItems[0] = MenuItem.check("Match System", chosen == Appearance.Mode.SYSTEM, on -> chooseAppearance(Appearance.Mode.SYSTEM));
        appearanceItems[1] = MenuItem.check("Light", chosen == Appearance.Mode.LIGHT, on -> chooseAppearance(Appearance.Mode.LIGHT));
        appearanceItems[2] = MenuItem.check("Dark", chosen == Appearance.Mode.DARK, on -> chooseAppearance(Appearance.Mode.DARK));
        bar.addMenu("View", appearanceItems);
        menu = bar;
        root.addElement(bar); // appended: an index that is already taken would replace what is there
        menuOffset = bar.getHeight();
        layout();
        return bar;
    }

    private static final String ASKED_FINDER = "askedAboutFinderMenu";

    /**
     * The first time the installed app is opened, asks whether to add "Compress with OpenZip" to Finder's right-click
     * menu: a .dmg can't run an installer step, and changing Finder's menu isn't something to do without asking.
     * Asked once, whatever the answer; the File menu has the same choice. Not asked while something else is on screen
     * (the app was opened with a file), in which case it waits for the next launch.
     */
    public void offerFinderAction() {
        if (!FinderAction.supported() || !FinderAction.runningFromApp() || Appearance.flag(ASKED_FINDER)) return;
        if (FinderAction.isInstalled(FinderAction.servicesFolder())) {
            Appearance.setFlag(ASKED_FINDER, true);
            return;
        }
        Scheduler.after(0.8f, () -> {
            if (hasArchive() || job != null || !window.getModals().isEmpty()) return;
            Appearance.setFlag(ASKED_FINDER, true);
            showFinderOffer();
        });
    }

    void showFinderOffer() {
        double dialogWidth = 440, inner = dialogWidth - 48;
        VerticalLayout dialog = Components.dialogPanel(dialogWidth, 206);
        ModalContainer modal = new ModalContainer(dialog);
        dialog.addElement(Components.text("Add OpenZip to Finder's menu?", 20f, Palette.TEXT));
        TextFlowOverlay hint = new TextFlowOverlay("Right-click any file or folder in Finder, choose Quick Actions, then "
                + "Compress with OpenZip. You can change this later in the File menu.", inner, 13.5f, Palette.MUTED);
        hint.setTextColor(Palette.MUTED);
        dialog.addElement(hint);
        double half = (inner - 12) / 2;
        ButtonOverlay no = Components.secondaryButton("Not Now", half, modal::close);
        ButtonOverlay yes = Components.accentButton("Add", half, () -> {
            modal.close();
            finderItem.setChecked(true);
            setFinderAction(true);
        });
        dialog.addElement(Components.dialogButtons(inner, no, yes));
        modal.show(window);
    }

    /**
     * Adds or removes "Compress with OpenZip" from Finder's right-click menu, as the menu item was just switched.
     */
    private void setFinderAction(boolean on) {
        Path services = FinderAction.servicesFolder();
        try {
            if (on) {
                FinderAction.install(services);
                say("Added \"" + FinderAction.NAME + "\" to Finder. Right-click a file or folder, then choose Quick Actions.");
            } else {
                FinderAction.remove(services);
                say("Removed \"" + FinderAction.NAME + "\" from Finder's right-click menu.");
            }
        } catch (IOException exception) {
            finderItem.setChecked(FinderAction.isInstalled(services)); // it didn't change, so the check mustn't either
            fail("Can't change Finder's menu: " + message(exception));
        }
    }

    private void chooseAppearance(Appearance.Mode mode) {
        if (job != null) {
            syncAppearanceItems(); // a menu check flips itself; put it back
            busy();
            return;
        }
        chosen = mode;
        Appearance.save(mode);
        syncAppearanceItems();
        setScheme(mode.scheme());
    }

    private void syncAppearanceItems() {
        if (appearanceItems == null) return;
        for (int i = 0; i < appearanceItems.length; i++) {
            appearanceItems[i].setChecked(Appearance.Mode.values()[i] == chosen);
        }
    }

    /**
     * Shows the window in {@code scheme}. Every element is made again in the new colors; an open archive stays open,
     * but the row selection and which folders were expanded start over.
     */
    void setScheme(Palette.Scheme scheme) {
        if (scheme == Palette.current()) return;
        Palette.use(scheme);
        ThemeManager.setCurrent(new OpenZipTheme());
        window.removeContainer(root);
        build();
        if (menu != null) root.addElement(menu);
        window.addContainer(root);
        if (hasArchive()) {
            showListing();
        } else {
            showArchiveControls(false);
        }
        setStatus(tone, statusFull);
        layout();
    }

    private void styleTable() {
        table.getStyling().setBackgroundColor(Color.TRANSPARENT);
        table.getStyling().setBorderThickness(0f);
        table.setRowHeight(36f);
        table.setHeaderHeight(34f);
        table.setFontSize(14.5f);
        table.setIconSize(16f);
        table.setRowCornerRadius(9f);
        table.setTextColor(Palette.TEXT);
        table.setDimTextColor(Palette.MUTED);
        table.setHeaderTextColor(Palette.MUTED);
        table.setDimmed(Node::isLink);
        table.setMultiSelect(true);

        nameColumn = table.addColumn("Name", node -> node.name).setWidth(360).setMinWidth(160);
        nameColumn.setIconProvider(OpenZipApp::iconFor);
        nameColumn.setIconColorProvider(node -> node.isFolder() ? Palette.ACCENT : null);
        nameColumn.setComparator(Comparator.comparing((Node node) -> node.name, String.CASE_INSENSITIVE_ORDER));
        // Folders stay on top whichever way a column is sorted, so the way in is always where you'd look.
        table.setSortGroup(Node::isFolder);
        table.addColumn("Size", Node::sizeText).setWidth(100).setAlignment(TextAlign.RIGHT)
                .setComparator(Comparator.comparingLong(Node::totalSize));
        table.addColumn("Packed", Node::packedText).setWidth(100).setAlignment(TextAlign.RIGHT)
                .setComparator(Comparator.comparingLong(Node::packedSize));
        table.addColumn("Modified", Node::modifiedText).setWidth(190)
                .setComparator(Comparator.comparingLong(Node::modified));

        table.setContextMenu(contextMenu());
        table.onSelectionChange(this::showSelection);
        table.onActivate(this::open);
        table.onKey((key, mods) -> {
            // Delete, or Cmd+Backspace as on a Mac keyboard, deletes the selection after asking.
            if (key == GLFW.GLFW_KEY_DELETE || (key == GLFW.GLFW_KEY_BACKSPACE && (mods & GLFW.GLFW_MOD_SUPER) != 0)) {
                deleteSelected();
                return true;
            }
            // Backspace, or Cmd+Up, goes up a folder like Finder does; Space previews a text file like Quick Look.
            boolean up = key == GLFW.GLFW_KEY_BACKSPACE || (key == GLFW.GLFW_KEY_UP && (mods & (GLFW.GLFW_MOD_SUPER | GLFW.GLFW_MOD_ALT)) != 0);
            if (up) this.up();
            if (key == GLFW.GLFW_KEY_SPACE) {
                preview();
                return true;
            }
            return up;
        });
    }

    private VerticalLayout buildEmptyState() {
        VerticalLayout box = new VerticalLayout(520, 340);
        box.setAlignment(Layout.Alignment.TOP_CENTER);
        box.setSpacing(14f);
        Components.clearStyle(box);

        box.addElement(Components.gradientBadge(76, Feather.ARCHIVE, 34));
        TextOverlay heading = Components.text("Zip, unzip, and look inside", 24f, Palette.TEXT);
        box.addElement(heading);
        TextFlowOverlay hint = new TextFlowOverlay("Open an archive to browse it and pull out what you need, "
                + "or compress files and folders into a new one.", 420, 14f, Palette.MUTED);
        hint.setTextColor(Palette.MUTED);
        hint.setTextAlign(TextAlign.CENTER);
        box.addElement(hint);

        HorizontalLayout buttons = new HorizontalLayout(520, Components.PILL_HEIGHT + 8);
        buttons.setSpacing(12f);
        buttons.setAlignment(Layout.Alignment.CENTER);
        Components.clearStyle(buttons);
        buttons.addElement(Components.accentButton("Open Archive", Components.pillWidth("Open Archive") + 16, this::chooseArchive));
        buttons.addElement(Components.secondaryButton("New Archive", Components.pillWidth("New Archive") + 16, this::showNewArchiveChooser));
        box.addElement(buttons);

        TextOverlay formats = Components.text("ZIP   TAR.GZ   TAR.XZ   TAR.BZ2   TAR   7Z   RAR", 12f, Palette.MUTED);
        formats.setOpacity(0.7f);
        box.addElement(formats);
        return box;
    }

    private static IconAsset iconFor(Node node) {
        if (node.isLink()) return Feather.LINK;
        if (node.isFolder()) return Feather.FOLDER;
        String name = node.name.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1);
        return switch (ext) {
            case "png", "jpg", "jpeg", "gif", "webp", "svg", "heic", "bmp", "tif", "tiff", "ico" -> Feather.IMAGE;
            case "mp3", "wav", "flac", "m4a", "ogg", "aac", "aiff" -> Feather.MUSIC;
            case "mp4", "mov", "mkv", "avi", "webm", "m4v" -> Feather.FILM;
            case "java", "kt", "py", "js", "ts", "c", "cpp", "h", "rs", "go", "rb", "sh", "json", "xml", "html",
                 "css", "yml", "yaml", "toml", "sql", "swift" -> Feather.CODE;
            case "txt", "md", "pdf", "doc", "docx", "rtf", "csv", "log" -> Feather.FILE_TEXT;
            case "zip", "tar", "gz", "xz", "bz2", "7z", "rar", "tgz", "jar" -> Feather.ARCHIVE;
            default -> Feather.FILE;
        };
    }

    private boolean hasArchive() {
        return archive != null;
    }

    private void showArchiveControls(boolean open) {
        title.setEnabled(open);
        subtitle.setEnabled(open);
        extractAllButton.setEnabled(open);
        extractSelectedButton.setEnabled(open);
        testButton.setEnabled(open);
        closeButton.setEnabled(open);
        upButton.setEnabled(open);
        crumbs.setEnabled(open);
        table.setEnabled(open);
        emptyFolder.setEnabled(open && table.getItems().isEmpty());
        detailRule.setEnabled(open);
        detailName.setEnabled(open);
        detailRest.setEnabled(open);
        emptyState.setEnabled(!open);
    }

    /**
     * Puts everything where it belongs for the current window size and state.
     */
    private void layout() {
        root.setWidth(width);
        root.setHeight(height);
        double top = menuOffset;
        double inner = Math.max(0, width - 2 * MARGIN);

        badge.setPosition(MARGIN, top + 20);
        wordmark.setPosition(MARGIN + 36 + 12, top + 20 + (36 - wordmark.getHeight()) / 2.0);
        double buttonY = top + 19;
        newButton.setPosition(width - MARGIN - newButton.getWidth(), buttonY);
        openButton.setPosition(newButton.getX() - GAP - openButton.getWidth(), buttonY);
        rule.setWidth(inner);
        rule.setPosition(MARGIN, top + HEADER_HEIGHT);

        double panelY = top + HEADER_HEIGHT + 24;
        if (hasArchive()) {
            double actionsY = top + HEADER_HEIGHT + 22;
            double x = width - MARGIN;
            closeButton.setPosition(x -= closeButton.getWidth(), actionsY);
            testButton.setPosition(x -= GAP + testButton.getWidth(), actionsY);
            extractSelectedButton.setPosition(x -= GAP + extractSelectedButton.getWidth(), actionsY);
            extractAllButton.setPosition(x -= GAP + extractAllButton.getWidth(), actionsY);

            double textWidth = Math.max(0, x - GAP * 2 - MARGIN);
            String archiveName = archive.getFileName().toString();
            title.setMaxWidth(textWidth);
            title.setText(archiveName);
            title.setTooltip(Components.widthOf(archiveName, 24f) > textWidth ? archiveName : null);
            title.setPosition(MARGIN, actionsY - 3);
            subtitle.setPosition(MARGIN, actionsY + 29);
            panelY = top + HEADER_HEIGHT + 22 + Components.PILL_HEIGHT + 22;
        }

        double panelHeight = Math.max(80, height - panelY - STATUS_HEIGHT);
        panel.setPosition(MARGIN, panelY);
        panel.setWidth(inner);
        panel.setHeight(panelHeight);
        if (hasArchive()) {
            double tableWidth = inner - 2 * INSET;
            double tableTop = INSET + PATH_HEIGHT;
            double tableHeight = Math.max(40, panelHeight - tableTop - INSET - DETAIL_HEIGHT);
            upButton.setPosition(INSET + 4, INSET + (PATH_HEIGHT - upButton.getHeight()) / 2);
            crumbs.setPosition(INSET + 4 + upButton.getWidth() + 12, INSET + (PATH_HEIGHT - crumbs.getHeight()) / 2);
            crumbs.setWidth(Math.max(0, tableWidth - upButton.getWidth() - 12 - 8));
            table.setPosition(INSET, tableTop);
            table.setWidth(tableWidth);
            table.setHeight(tableHeight);
            if (tableWidth != lastTableWidth) {
                // The name takes what the other columns leave, until someone resizes it by hand.
                lastTableWidth = tableWidth;
                double others = 0;
                for (TableView.Column<Node> column : table.getColumns())
                    if (column != nameColumn) others += column.getWidth();
                nameColumn.setWidth((float) Math.max(nameColumn.getMinWidth(), tableWidth - others - 12));
            }
            rebuildCrumbs();
            emptyFolder.setPosition((tableWidth - emptyFolder.getWidth()) / 2 + INSET,
                    tableTop + table.getHeaderHeight() + (tableHeight - table.getHeaderHeight() - emptyFolder.getHeight()) / 2);
            detailRule.setWidth(tableWidth);
            detailRule.setPosition(INSET, panelHeight - DETAIL_HEIGHT);
            layoutDetails(inner, panelHeight);
        } else {
            emptyState.setPosition((inner - emptyState.getWidth()) / 2, (panelHeight - emptyState.getHeight()) / 2);
        }

        layoutStatus();
    }

    private void layoutDetails(double panelWidth, double panelHeight) {
        double y = panelHeight - DETAIL_HEIGHT + (DETAIL_HEIGHT - detailName.getHeight()) / 2.0 - 1;
        double room = panelWidth - 2 * (INSET + 12);
        detailName.setMaxWidth(room * 0.55);
        detailName.setText(detailNameText);
        detailName.setPosition(INSET + 12, y);
        double used = detailNameText.isEmpty() ? 0 : detailName.getWidth() + 14;
        detailRest.setMaxWidth(Math.max(0, room - used));
        detailRest.setText(detailRestText);
        detailRest.setPosition(INSET + 12 + used, y + 1);
    }

    private void layoutStatus() {
        double centerY = height - STATUS_HEIGHT / 2.0 - 2;
        boolean busy = job != null;
        boolean showReveal = !busy && lastOutput != null;
        cancelButton.setEnabled(busy && cancellable);
        revealButton.setEnabled(showReveal);
        progress.setEnabled(busy);

        double right = width - MARGIN;
        if (showReveal) {
            revealButton.setPosition(right - revealButton.getWidth(), centerY - Components.PILL_HEIGHT / 2);
            right -= revealButton.getWidth() + GAP;
        }
        if (busy && cancellable) {
            cancelButton.setPosition(right - cancelButton.getWidth(), centerY - Components.PILL_HEIGHT / 2);
            right -= cancelButton.getWidth() + GAP;
        }
        if (busy) {
            progress.setPosition(right - progress.getWidth(), centerY - 3);
            right -= progress.getWidth() + 16;
        }

        statusIcon.setPosition(MARGIN, centerY - 8);
        double textX = MARGIN + 26;
        double room = Math.max(0, right - textX - 8);
        statusText.setMaxWidth(room);
        statusText.setText(statusFull);
        statusText.setTooltip(Components.widthOf(statusFull, 14f) > room ? statusFull : null);
        statusText.setPosition(textX, centerY - statusText.getHeight() / 2.0);
    }

    private void setStatus(Tone newTone, String text) {
        tone = newTone;
        statusFull = text;
        switch (newTone) {
            case OK -> statusIcon.setIcon(Feather.CHECK_CIRCLE);
            case ERROR -> statusIcon.setIcon(Feather.ALERT_CIRCLE);
            default -> statusIcon.setIcon(Feather.INFO);
        }
        statusIcon.setTint(switch (newTone) {
            case OK -> Palette.VALID;
            case ERROR -> Palette.DANGER;
            case BUSY -> Palette.ACCENT_HOVER;
            case IDLE -> Palette.MUTED;
        });
        statusText.setTextColor(newTone == Tone.IDLE ? Palette.MUTED : Palette.TEXT);
        layoutStatus();
    }

    private void say(String text) {
        setStatus(Tone.OK, text);
    }

    private void fail(String text) {
        setStatus(Tone.ERROR, text);
    }

    private void chooseArchive() {
        if (busy()) return;
        FileDialog.open()
                .filter("Archives", ArchiveFormat.readableExtensions())
                .onSelect(this::openArchive)
                .onError(this::fail)
                .show();
    }

    /**
     * Lists {@code path} on a worker and shows it. Also what dropping or double-clicking an archive calls.
     */
    public void openArchive(Path path) {
        openArchive(path, null, false);
    }

    private void openArchive(Path path, char[] typed) {
        openArchive(path, typed, false);
    }

    /**
     * @param typed  a password already known for this archive (just made, or asked for because the names are encrypted)
     * @param nested the archive came out of the one that is open: keep that one to go back to instead of replacing it
     */
    private void openArchive(Path path, char[] typed, boolean nested) {
        if (busy()) return;
        begin("Reading " + path.getFileName() + "...", new Job(), false);
        AsyncTask<Listing> task = Scheduler.async(() -> {
            try {
                return ArchiveReader.list(path, typed);
            } catch (IOException exception) {
                throw new IllegalStateException(exception.getMessage() == null ? exception.toString() : exception.getMessage(), exception);
            }
        });
        task.onSuccess(result -> {
            finish();
            if (nested && archive != null) {
                outer.add(new Outer(archive, listing, dir, password));
                password = null; // kept for going back, so not wiped by the next line
                dir = "";
            } else {
                if (!path.equals(archive)) dir = "";
                forgetOuter();
            }
            archive = path;
            listing = result;
            setPassword(typed);
            showListing();
            String note = afterOpening;
            afterOpening = null;
            if (note != null && result.warnings().isEmpty()) {
                say(note);
            } else if (result.warnings().isEmpty()) {
                setStatus(Tone.IDLE, result.encrypted() && password == null
                        ? "Password protected. You'll be asked for the password when you extract."
                        : ArchiveFormat.detect(path).filter(ArchiveWriter::canAddTo).isPresent() && !result.encrypted() && outer.isEmpty()
                        ? "Drop files here to add them, or select something to extract or delete it."
                        : "Select something to extract just that, or use Extract All.");
            } else {
                fail(Format.count(result.warnings().size(), "entry was", "entries were") + " skipped as unsafe.");
            }
        });
        task.onError(exception -> {
            finish();
            PasswordException problem = passwordProblem(exception);
            if (problem != null) {
                // The names themselves are encrypted, so there is nothing to show until the password is right.
                askPassword(path, problem, pw -> openArchive(path, pw, nested), () -> fail("Can't open " + path.getFileName() + " without its password."));
            } else {
                fail("Can't open " + path.getFileName() + ": " + message(exception));
            }
        });
    }

    /**
     * Fills the window from {@link #archive} and {@link #listing}: the folder shown, the heading and the details line.
     */
    private void showListing() {
        roots = Node.build(listing.entries());
        folders = Node.folders(roots);
        // A folder that is gone (everything in it was deleted) leaves you in the nearest one that is still there.
        while (!dir.isEmpty() && !folders.containsKey(dir)) dir = dir.substring(0, Math.max(0, dir.lastIndexOf('/')));
        table.setItems(childrenOf(dir));
        table.clearSelection();
        showArchiveControls(true);
        window.setTitle(archive.getFileName() + " - OpenZip");

        long files = listing.entries().stream().filter(entry -> !entry.directory()).count();
        String kind = ArchiveFormat.detect(archive).map(format -> format.label().toUpperCase(Locale.ROOT)).orElse("Archive");
        long onDisk = 0;
        try {
            onDisk = Files.size(archive);
        } catch (IOException ignored) {
            // The listing already worked; the size is only for the subtitle.
        }
        subtitle.setText(kind + "   ·   " + Format.count((int) files, "file", "files") + "   ·   "
                + Format.size(listing.totalSize()) + " unpacked   ·   " + Format.size(onDisk) + " on disk"
                + (listing.encrypted() ? "   ·   Password protected" : ""));
        showDetails(null);
        upButton.setDisabled(dir.isEmpty());
        closeButton.setTooltip(outer.isEmpty() ? "Close this archive" : "Back to " + outer.getLast().archive().getFileName());
        layout();
    }

    private List<Node> childrenOf(String path) {
        return path.isEmpty() ? roots : folders.get(path).children;
    }

    /**
     * Shows the contents of the folder at {@code path} ("" is the top of the archive).
     */
    private void navigate(String path) {
        if (!hasArchive() || !(path.isEmpty() || folders.containsKey(path))) return;
        dir = path;
        table.setItems(childrenOf(path));
        table.clearSelection();
        emptyFolder.setEnabled(table.getItems().isEmpty());
        upButton.setDisabled(path.isEmpty());
        showDetails(null);
        layout();
    }

    private void up() {
        if (!hasArchive() || dir.isEmpty()) return;
        int slash = dir.lastIndexOf('/');
        navigate(slash < 0 ? "" : dir.substring(0, slash));
    }

    /**
     * Where you are, as clickable names: the archive, then each folder down to this one. Shortened from the left to fit.
     */
    private void rebuildCrumbs() {
        for (Element old : new ArrayList<>(crumbs.getElements().values())) crumbs.removeElement(old);
        if (!hasArchive()) return;

        // The archives this one was opened from come first; each takes you back to it as you left it.
        List<String> names = new ArrayList<>();
        List<Runnable> targets = new ArrayList<>();
        for (int i = 0; i < outer.size(); i++) {
            int depth = i;
            names.add(outer.get(i).archive().getFileName().toString());
            targets.add(() -> goBackTo(depth));
        }
        names.add(archive.getFileName().toString());
        targets.add(() -> navigate(""));
        String walked = "";
        if (!dir.isEmpty()) {
            for (String part : dir.split("/")) {
                walked = walked.isEmpty() ? part : walked + "/" + part;
                String target = walked;
                names.add(part);
                targets.add(() -> navigate(target));
            }
        }

        float size = 14f;
        double gap = crumbs.getSpacing(), chevron = 12, cap = 220;
        double used = 0;
        int first = names.size();
        while (first > 0) {
            int index = first - 1;
            double crumbWidth = Math.min(cap, Components.widthOf(names.get(index), size)) + (index < names.size() - 1 ? gap * 2 + chevron : 0);
            // The folder you are in always shows; older ones drop off the left, leaving room for the "…".
            if (index < names.size() - 1 && used + crumbWidth > crumbs.getWidth() - 40) break;
            used += crumbWidth;
            first--;
        }
        if (first > 0) {
            Runnable target = targets.get(first - 1);
            TextOverlay more = Components.text("…", size, Palette.MUTED);
            more.setHoverColor(Palette.TEXT);
            Components.clickable(target, more);
            crumbs.addElement(more);
            crumbs.addElement(chevron());
        }
        for (int i = first; i < names.size(); i++) {
            boolean last = i == names.size() - 1;
            Runnable target = targets.get(i);
            TextOverlay crumb = Components.text(names.get(i), size, last ? Palette.TEXT : Palette.MUTED, cap);
            crumb.setEllipsizeMiddle(true);
            if (!last) {
                crumb.setHoverColor(Palette.TEXT);
                Components.clickable(target, crumb);
            }
            crumbs.addElement(crumb);
            if (!last) crumbs.addElement(chevron());
        }
    }

    private static IconOverlay chevron() {
        IconOverlay icon = new IconOverlay(Feather.CHEVRON_RIGHT, 12, 12);
        icon.setTint(Palette.MUTED);
        icon.setOpacity(0.7f);
        return icon;
    }

    /**
     * Leaves the archive that was opened from another and shows that one again; with nothing to go back to, closes it.
     */
    private void closeArchive() {
        if (busy()) return;
        if (!outer.isEmpty()) {
            goBackTo(outer.size() - 1);
            return;
        }
        archive = null;
        listing = null;
        roots = List.of();
        folders = Map.of();
        dir = "";
        setPassword(null);
        table.setItems(List.of());
        showArchiveControls(false);
        window.setTitle("OpenZip");
        setStatus(Tone.IDLE, "Open an archive, or make a new one.");
        layout();
    }

    /**
     * Shows the archive {@code depth} levels down the trail again, in the folder it was left in.
     */
    private void goBackTo(int depth) {
        if (busy() || depth < 0 || depth >= outer.size()) return;
        Outer back = outer.get(depth);
        while (outer.size() > depth) outer.removeLast();
        setPassword(back.password());
        archive = back.archive();
        listing = back.listing();
        dir = back.dir();
        showListing();
        setStatus(Tone.IDLE, "Back in " + archive.getFileName() + ".");
    }

    /**
     * Drops the trail of archives to go back to, wiping the passwords kept with it.
     */
    private void forgetOuter() {
        for (Outer ancestor : outer) {
            if (ancestor.password() != null) java.util.Arrays.fill(ancestor.password(), '\0');
        }
        outer.clear();
    }

    /**
     * Goes to the folder holding an entry path and selects its row, as a click would. For the screenshot tool.
     */
    void select(String path) {
        selectMany(path);
    }

    /**
     * Selects several rows at once, for the screenshot harness. They are all looked for in the first one's folder.
     */
    void selectMany(String... paths) {
        int slash = paths[0].lastIndexOf('/');
        navigate(slash < 0 ? "" : paths[0].substring(0, slash));
        Set<String> wanted = Set.of(paths);
        List<Node> nodes = new ArrayList<>();
        for (Node node : table.getItems()) {
            if (wanted.contains(node.path)) nodes.add(node);
        }
        table.setSelectedItems(nodes);
        showSelection(nodes);
    }

    /**
     * Opens the entry at {@code path} as a double-click would. For the screenshot tool.
     */
    void activate(String path) {
        select(path);
        for (Node node : table.getItems()) {
            if (node.path.equals(path)) open(node);
        }
    }

    /**
     * Sorts by the column with this title, as a click on its header would. For the screenshot tool.
     */
    void sortBy(String title, boolean ascending) {
        for (TableView.Column<Node> column : table.getColumns()) {
            if (column.getTitle().equals(title)) table.sortBy(column, ascending);
        }
    }

    /**
     * Opens a folder, or extracts a file to a scratch folder and opens it with whatever the system uses for it.
     */
    private void open(Node node) {
        if (node.isFolder()) {
            navigate(node.path);
        } else if (node.isLink()) {
            fail(node.name + " is a link to " + node.info.linkTarget() + ". Extract it to follow it.");
        } else {
            openFile(node);
        }
    }

    private void openFile(Node node) {
        extractToScratch(node, file -> {
            // A jar is a zip, but the system's opener treats it as a program and the engine refuses to run it.
            // Listing it here is safe: nothing in it is executed.
            if (node.name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                openArchive(file, null, true);
            } else if (TextFiles.isText(node.name)) {
                // A text editor never runs what it opens, so scripts are fine here; the engine's check is for programs.
                if (TextFiles.openInEditor(file)) {
                    say("Opened " + node.name + " in " + TextFiles.editorName() + ". Changes you make to it are not saved back into the archive.");
                } else {
                    showPreview(node, file);
                }
            } else if (Browser.open(file)) {
                say("Opened " + node.name + ". Changes you make to it are not saved back into the archive.");
            } else {
                fail("OpenZip won't run " + node.name + " from inside an archive. Extract it first if you trust it.");
            }
        });
    }

    /**
     * Extracts one file to a scratch folder that goes when OpenZip quits, then hands its path to {@code then}.
     */
    private void extractToScratch(Node node, Consumer<Path> then) {
        if (busy()) return;
        Path folder;
        try {
            folder = Files.createTempDirectory("openzip-");
        } catch (IOException exception) {
            fail("Can't open " + node.name + ": " + message(exception));
            return;
        }
        openedIn.add(folder);
        runExtract(archive, folder, Set.of(node.path), Overwrite.REPLACE, () -> then.accept(folder.resolve(node.path)));
    }

    /**
     * What dragging {@code nodes} out of the window carries. This is asked for the moment the pointer leaves the
     * window, and that is when extracting starts, on a worker, into a scratch folder that goes when OpenZip quits (the
     * target may still be copying after the drop). The window keeps drawing and shows progress with Cancel, as for any
     * job. The source only waits for that work: the engine asks it for the files on its main thread at the drop, while
     * the target waits, so doing the extraction there would leave a big folder's drop and the window hanging. Null
     * when there is nothing to drag out right now.
     */
    FileDrag.Source dragOutSource(List<Node> nodes) {
        if (archive == null || job != null) return null;
        if (needsPassword()) {
            fail("Extract something once to enter the password. After that, rows can be dragged out.");
            return null;
        }
        Path folder;
        try {
            folder = Files.createTempDirectory("openzip-");
        } catch (IOException exception) {
            fail("Couldn't drag that out: " + message(exception));
            return null;
        }
        openedIn.add(folder);
        Path source = archive;
        char[] pw = password;
        List<Node> dragged = List.copyOf(nodes);
        Set<String> only = new HashSet<>();
        for (Node node : dragged) only.add(node.path);

        Job job = new Job();
        CompletableFuture<List<Path>> ready = new CompletableFuture<>();
        begin("Preparing " + Format.count(dragged.size(), "item", "items") + " to drag out", job, true);
        AsyncTask<List<Path>> task = Scheduler.async(() -> {
            try {
                ArchiveReader.ExtractResult result = ArchiveReader.extract(source, new ExtractOptions(folder, only, Overwrite.REPLACE, true, pw), job);
                List<Path> files = new ArrayList<>();
                for (Node node : dragged) {
                    try {
                        Path file = folder.resolve(node.path);
                        if (Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) files.add(file);
                    } catch (java.nio.file.InvalidPathException ignored) {
                        // A name Windows can't hold; extraction skipped it and said so.
                    }
                }
                if (files.isEmpty()) {
                    throw new IOException(result.warnings().isEmpty() ? "nothing could be extracted" : result.warnings().getFirst());
                }
                ready.complete(files);
                return files;
            } catch (IOException | RuntimeException exception) {
                // Nothing was handed over yet, so the half-made folder can go now.
                deleteTree(folder);
                openedIn.remove(folder);
                ready.completeExceptionally(exception);
                throw exception instanceof IOException io ? new IllegalStateException(io.getMessage() == null ? io.toString() : io.getMessage(), io) : (RuntimeException) exception;
            }
        });
        task.onSuccess(files -> {
            finish();
            say("Prepared " + Format.count(dragged.size(), "item", "items") + " to drag out.");
        });
        task.onError(exception -> {
            finish();
            fail(exception instanceof CancellationException ? "Drag cancelled." : "Couldn't drag that out: " + message(exception));
        });
        return () -> {
            try {
                return ready.get();
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                throw cause instanceof Exception ex ? ex : exception;
            }
        };
    }

    /**
     * The selected file, shown inside OpenZip: Markdown set out as a document, other text as source.
     */
    private void preview() {
        List<Node> selected = table.getSelectedItems();
        if (selected.size() != 1) return;
        Node node = selected.getFirst();
        if (node.isFolder() || node.isLink() || !TextFiles.isText(node.name)) {
            fail(node.isFolder() ? "Select a text file to preview it." : node.name + " isn't a text file OpenZip can preview.");
            return;
        }
        extractToScratch(node, file -> showPreview(node, file));
    }

    void showPreview(Node node, Path file) {
        TextFiles.Content content;
        try {
            content = TextFiles.read(file, TextFiles.PREVIEW_LIMIT);
        } catch (IOException exception) {
            fail("Can't read " + node.name + ": " + message(exception));
            return;
        }
        if (content == null) {
            fail(node.name + " looks like a binary file, not text.");
            return;
        }
        String details = Format.size(node.totalSize())
                + (content.truncated() ? "   ·   Showing the first " + Format.size(TextFiles.PREVIEW_LIMIT) : "");
        shown = new TextPreview(node.name, details, content, () -> {
            if (TextFiles.openInEditor(file)) {
                say("Opened " + node.name + " in " + TextFiles.editorName() + ". Changes you make to it are not saved back into the archive.");
            } else {
                fail("This system has no text editor OpenZip can start. Extract the file to open it.");
            }
        }, width, height);
        shown.show(window);
    }

    /**
     * Flips the open preview between the formatted document and the source. For the screenshot tool.
     */
    void togglePreview() {
        if (shown != null) shown.toggle();
    }

    /**
     * Previews the entry at {@code path}, as pressing Space on it would. For the screenshot tool.
     */
    void previewPath(String path) {
        select(path);
        preview();
    }

    private void removeOpened() {
        for (Path folder : openedIn) deleteTree(folder);
    }

    private static void deleteTree(Path folder) {
        try (var walk = Files.walk(folder)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException ignored) {
            // It is in the system's temp folder; whatever is left will be cleaned up there.
        }
    }

    private void showSelection(List<Node> nodes) {
        if (nodes.size() <= 1) {
            showDetails(nodes.isEmpty() ? null : nodes.getFirst());
            return;
        }
        long files = 0, bytes = 0;
        for (Node node : nodes) {
            files += node.fileCount();
            bytes += node.totalSize();
        }
        detailNameText = nodes.size() + " selected";
        detailRestText = Format.count((int) files, "file", "files") + "   ·   " + Format.size(bytes);
        extractSelectedButton.setDisabled(false);
        if (hasArchive()) layoutDetails(panel.getWidth(), panel.getHeight());
    }

    private void showDetails(Node node) {
        if (node == null) {
            detailNameText = "";
            detailRestText = table.getItems().isEmpty() ? "" : "Select a file or folder to see its details, or to extract only that.";
            extractSelectedButton.setDisabled(true);
        } else {
            detailNameText = node.name;
            extractSelectedButton.setDisabled(false);
            List<String> parts = new ArrayList<>();
            if (node.isLink()) {
                parts.add("Link to " + node.info.linkTarget());
            } else if (node.isFolder()) {
                parts.add("Folder");
                parts.add(Format.count(node.fileCount(), "file", "files"));
                parts.add(Format.size(node.totalSize()));
            } else {
                parts.add(Format.size(node.totalSize()));
                if (node.packedSize() > 0) parts.add(Format.size(node.packedSize()) + " packed");
            }
            if (node.modified() > 0) parts.add("Modified " + node.modifiedText());
            detailRestText = String.join("   ·   ", parts);
        }
        if (hasArchive()) layoutDetails(panel.getWidth(), panel.getHeight());
    }

    private void extractSelected() {
        List<Node> selected = table.getSelectedItems();
        if (archive == null) {
            fail("Open an archive first.");
        } else if (selected.isEmpty()) {
            fail("Select a file or folder in the list first.");
        } else {
            Set<String> paths = new HashSet<>();
            for (Node node : selected) paths.add(node.path);
            extract(paths);
        }
    }

    /**
     * @param only the entries to extract, or null for everything
     */
    private void extract(Set<String> only) {
        if (busy()) return;
        if (archive == null) {
            fail("Open an archive first.");
            return;
        }
        Path source = archive;
        Listing snapshot = listing;
        FileDialog.pickFolder()
                .defaultPath(source.toAbsolutePath().getParent())
                .onSelect(parent -> {
                    // Whole archives get a tidy folder of their own; a chosen file or folder goes where the user said.
                    Path destination = only == null ? Destinations.choose(parent, source, snapshot) : parent;
                    Overwrite overwrite = only == null ? Overwrite.REPLACE : Overwrite.SKIP;
                    runExtract(source, destination, only, overwrite);
                })
                .onError(this::fail)
                .show();
    }

    private void runExtract(Path source, Path destination, Set<String> only, Overwrite overwrite) {
        runExtract(source, destination, only, overwrite, null);
    }

    /**
     * @param afterwards what to do instead of reporting the extraction, or null (opening a file is one extraction and then a launch)
     */
    private void runExtract(Path source, Path destination, Set<String> only, Overwrite overwrite, Runnable afterwards) {
        if (busy()) return;
        if (needsPassword()) {
            askPassword(source, null, pw -> {
                setPassword(pw);
                runExtract(source, destination, only, overwrite, afterwards);
            }, () -> fail("Extraction cancelled."));
            return;
        }
        char[] pw = password;
        Job job = new Job();
        begin("Extracting " + source.getFileName(), job, true);
        AsyncTask<ArchiveReader.ExtractResult> task = Scheduler.async(() -> {
            try {
                return ArchiveReader.extract(source, new ExtractOptions(destination, only, overwrite, true, pw), job);
            } catch (IOException exception) {
                throw new IllegalStateException(exception.getMessage() == null ? exception.toString() : exception.getMessage(), exception);
            }
        });
        task.onSuccess(result -> {
            finish();
            if (afterwards != null) {
                afterwards.run();
                return;
            }
            lastOutput = destination;
            String text = "Extracted " + Format.count(result.files(), "file", "files") + " (" + Format.size(result.bytes()) + ") to " + destination;
            if (result.skipped() > 0) text += ". " + result.skipped() + " skipped";
            if (!result.warnings().isEmpty()) text += " - " + result.warnings().getFirst();
            say(text);
        });
        task.onError(exception -> {
            finish();
            PasswordException problem = passwordProblem(exception);
            if (problem != null) {
                setPassword(null);
                askPassword(source, problem, typed -> {
                    setPassword(typed);
                    runExtract(source, destination, only, overwrite, afterwards);
                }, () -> fail("Extraction cancelled."));
            } else {
                fail(exception instanceof CancellationException ? "Extraction cancelled." : "Extraction failed: " + message(exception));
            }
        });
    }

    private void testArchive() {
        if (busy()) return;
        if (archive == null) {
            fail("Open an archive first.");
            return;
        }
        Path source = archive;
        if (needsPassword()) {
            askPassword(source, null, pw -> {
                setPassword(pw);
                testArchive();
            }, () -> fail("Check cancelled."));
            return;
        }
        char[] pw = password;
        Job job = new Job();
        begin("Checking " + source.getFileName(), job, true);
        AsyncTask<List<String>> task = Scheduler.async(() -> {
            try {
                return ArchiveReader.test(source, pw, job);
            } catch (IOException exception) {
                throw new IllegalStateException(exception.getMessage() == null ? exception.toString() : exception.getMessage(), exception);
            }
        });
        task.onSuccess(failures -> {
            finish();
            if (failures.isEmpty()) {
                say(source.getFileName() + " is intact: every file checked out.");
            } else {
                fail(Format.count(failures.size(), "file is", "files are") + " damaged. First: " + failures.getFirst());
            }
        });
        task.onError(exception -> {
            finish();
            PasswordException problem = passwordProblem(exception);
            if (problem != null) {
                setPassword(null);
                askPassword(source, problem, typed -> {
                    setPassword(typed);
                    testArchive();
                }, () -> fail("Check cancelled."));
            } else {
                fail(exception instanceof CancellationException ? "Check cancelled." : "Check failed: " + message(exception));
            }
        });
    }

    private boolean needsPassword() {
        return listing != null && listing.encrypted() && password == null;
    }

    private void setPassword(char[] typed) {
        if (password != null && password != typed) java.util.Arrays.fill(password, '\0');
        password = typed;
    }

    /**
     * The password problem behind {@code e}, if that is what went wrong. Workers wrap what they throw.
     */
    private static PasswordException passwordProblem(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof PasswordException problem) return problem;
        }
        return null;
    }

    /**
     * Asks for the password of {@code archive}. {@code problem} is why it is being asked again, or null the first time.
     * Cancel runs {@code onCancel}; Unlock hands the typed password to {@code onAccept}.
     */
    private void askPassword(Path archive, PasswordException problem, Consumer<char[]> onAccept, Runnable onCancel) {
        double dialogWidth = 400, inner = dialogWidth - 48;
        VerticalLayout dialog = Components.dialogPanel(dialogWidth, 228);
        ModalContainer modal = new ModalContainer(dialog);

        dialog.addElement(Components.text("Password needed", 20f, Palette.TEXT));
        dialog.addElement(Components.text(archive.getFileName() + " is password protected.", 13.5f, Palette.MUTED, inner));

        TextFieldOverlay field = new TextFieldOverlay(inner, 38);
        field.setPassword(true);
        field.setPlaceholder("Password");
        field.setFontSize(14f);
        dialog.addElement(field);

        boolean wrong = problem != null && problem.isWrong();
        dialog.addElement(Components.text(wrong ? problem.getMessage() + ". Try again." : " ", 12.5f, Palette.DANGER));

        Runnable accept = () -> {
            char[] typed = field.getText().toCharArray();
            if (typed.length == 0) return;
            modal.close();
            onAccept.accept(typed);
        };
        field.onSubmit(text -> accept.run());
        double half = (inner - 12) / 2;
        ButtonOverlay cancel = Components.secondaryButton("Cancel", half, () -> {
            modal.close();
            onCancel.run();
        });
        ButtonOverlay unlock = Components.accentButton("Unlock", half, accept);
        dialog.addElement(Components.dialogButtons(inner, cancel, unlock));

        modal.show(window);
        window.requestFocus(field);
    }

    /**
     * Files or a folder? Asked in one small dialog so the header needs only one "new" button.
     */
    void showNewArchiveChooser() {
        if (busy()) return;
        double dialogWidth = 400, inner = dialogWidth - 48;
        VerticalLayout dialog = Components.dialogPanel(dialogWidth, 284);
        ModalContainer modal = new ModalContainer(dialog);

        dialog.addElement(Components.text("New archive", 20f, Palette.TEXT));
        dialog.addElement(Components.optionRow(Feather.FILE_PLUS, "Files...", "Pick one or more files", inner, () -> {
            modal.close();
            chooseFilesToCompress();
        }));
        dialog.addElement(Components.optionRow(Feather.FOLDER_PLUS, "A folder...", "Compress a whole folder", inner, () -> {
            modal.close();
            chooseFolderToCompress();
        }));
        ButtonOverlay cancel = Components.secondaryButton("Cancel", inner, modal::close);
        dialog.addElement(cancel);
        modal.show(window);
    }

    private void chooseFilesToCompress() {
        if (busy()) return;
        FileDialog.openMultiple()
                .onSelectAll(this::showCreateDialog)
                .onError(this::fail)
                .show();
    }

    private void chooseFolderToCompress() {
        if (busy()) return;
        FileDialog.pickFolder()
                .onSelect(folder -> showCreateDialog(List.of(folder)))
                .onError(this::fail)
                .show();
    }

    /**
     * Asks for the format and strength, then where to save.
     */
    void showCreateDialog(List<Path> inputs) {
        Map<String, ArchiveFormat> formats = new LinkedHashMap<>();
        for (ArchiveFormat format : ArchiveFormat.values()) {
            if (format.canWrite()) formats.put(format.label() + "  (" + format.extension() + ")", format);
        }

        double dialogWidth = 440, inner = dialogWidth - 48;
        VerticalLayout dialog = Components.dialogPanel(dialogWidth, 432);
        ModalContainer modal = new ModalContainer(dialog);

        String what = inputs.size() == 1 ? inputs.getFirst().getFileName().toString() : inputs.size() + " items";
        dialog.addElement(Components.text("Compress " + what, 20f, Palette.TEXT, inner));

        dialog.addElement(Components.text("Format", 12.5f, Palette.MUTED));
        DropdownOverlay<String> format = new DropdownOverlay<>(inner, 38);
        format.setItems(new ArrayList<>(formats.keySet()));
        format.setSelectedIndex(0);
        format.setFontSize(14f);
        dialog.addElement(format);

        TextOverlay levelText = Components.text("Compression   ·   balanced", 12.5f, Palette.MUTED);
        dialog.addElement(levelText);
        SliderOverlay level = new SliderOverlay(inner, 0, 9, 6);
        level.setStep(1);
        level.setTrackThickness(6f);
        level.setTrackColor(Palette.BUTTON_HOVER);
        level.setThumbColor(Palette.THUMB);
        level.setThumbActiveColor(Palette.ACCENT_HOVER);
        level.onValueChange(value -> levelText.setText("Compression   ·   " + describeLevel((int) Math.round(value))));
        dialog.addElement(level);

        TextOverlay passwordText = Components.text("Password", 12.5f, Palette.MUTED);
        dialog.addElement(passwordText);
        TextFieldOverlay password = new TextFieldOverlay(inner, 38);
        password.setPassword(true);
        password.setFontSize(14f);
        dialog.addElement(password);
        TextFieldOverlay repeat = new TextFieldOverlay(inner, 38);
        repeat.setPassword(true);
        repeat.setFontSize(14f);
        repeat.setPlaceholder("Repeat password");
        dialog.addElement(repeat);

        Runnable passwordRules = () -> {
            ArchiveFormat chosen = formats.get(format.getSelected());
            boolean can = chosen.supportsPassword();
            password.setReadOnly(!can);
            repeat.setReadOnly(!can);
            password.setOpacity(can ? 1f : 0.45f);
            repeat.setOpacity(can ? 1f : 0.45f);
            password.setPlaceholder(can ? "Optional" : "Not available for " + chosen.label());
            repeat.setPlaceholder(can ? "Repeat password" : "");
            if (!can) {
                password.setText("");
                repeat.setText("");
            }
            passwordText.setTextColor(Palette.MUTED);
            passwordText.setText(!can ? "Password"
                    : chosen == ArchiveFormat.ZIP ? "Password   ·   AES-256, opens in OpenZip and 7-Zip but not Finder"
                    : "Password   ·   AES-256");
        };
        passwordRules.run();
        format.onSelect(item -> passwordRules.run());

        HorizontalLayout junkRow = new HorizontalLayout(inner, 28);
        junkRow.setSpacing(10);
        junkRow.setAlignment(Layout.Alignment.CENTER_LEFT);
        Components.clearStyle(junkRow);
        CheckBoxOverlay junk = new CheckBoxOverlay(20, true);
        junk.setCheckColor(Palette.ACCENT_HOVER);
        TextOverlay junkLabel = Components.text("Leave out .DS_Store and other Finder clutter", 14f, Palette.TEXT);
        Components.clickable(junk::toggle, junkLabel);
        junkRow.addElement(junk);
        junkRow.addElement(junkLabel);
        dialog.addElement(junkRow);

        double half = (inner - 12) / 2;
        ButtonOverlay cancel = Components.secondaryButton("Cancel", half, modal::close);
        ButtonOverlay choose = Components.accentButton("Choose Location...", half, () -> {
            ArchiveFormat chosen = formats.get(format.getSelected());
            int lvl = (int) Math.round(level.getValue());
            boolean skipJunk = junk.isChecked();
            String typed = chosen.supportsPassword() ? password.getText() : "";
            if (!typed.equals(chosen.supportsPassword() ? repeat.getText() : "")) {
                passwordText.setText("Passwords don't match");
                passwordText.setTextColor(Palette.DANGER);
                return;
            }
            modal.close();
            askWhereToSave(inputs, new CreateOptions(chosen, lvl, skipJunk, typed.isEmpty() ? null : typed.toCharArray()));
        });
        dialog.addElement(Components.dialogButtons(inner, cancel, choose));

        modal.show(window);
    }

    private static String describeLevel(int level) {
        if (level == 0) return "none (fastest)";
        if (level <= 3) return "fast";
        if (level <= 6) return "balanced";
        if (level < 9) return "strong";
        return "smallest (slowest)";
    }

    private void askWhereToSave(List<Path> inputs, CreateOptions options) {
        FileDialog.save()
                .filter(options.format().label(), options.format().extension().substring(1))
                .defaultName(Destinations.archiveName(inputs, options.format()))
                .defaultPath(inputs.getFirst().toAbsolutePath().getParent())
                .onSelect(chosen -> compress(inputs, withExtension(chosen, options.format()), options))
                .onError(this::fail)
                .show();
    }

    /**
     * Packs {@code inputs} into {@code output} in the background, then opens the new archive.
     */
    private void compress(List<Path> inputs, Path output, CreateOptions options) {
        Job compressJob = new Job();
        begin("Compressing into " + output.getFileName(), compressJob, true);
        AsyncTask<ArchiveWriter.CreateResult> task = Scheduler.async(() -> {
            try {
                return ArchiveWriter.create(output, inputs, options, compressJob);
            } catch (IOException exception) {
                throw new IllegalStateException(exception.getMessage() == null ? exception.toString() : exception.getMessage(), exception);
            }
        });
        task.onSuccess(result -> {
            finish();
            lastOutput = result.archive();
            long saved = Math.max(0, Math.round((1 - result.ratio()) * 100));
            say("Created " + result.archive().getFileName() + ": " + Format.count(result.files(), "file", "files")
                    + ", " + Format.size(result.uncompressedBytes()) + " became " + Format.size(result.archiveBytes()) + " (" + saved + "% smaller)");
            String summary = statusFull;
            openArchive(result.archive(), options.password());
            // Opening replaces the status line; the result of the compression is the news, so put it back.
            Scheduler.after(0.05f, () -> {
                if (job == null || !cancellable) say(summary);
            });
        });
        task.onError(exception -> {
            finish();
            fail(exception instanceof CancellationException ? "Compression cancelled." : "Compression failed: " + message(exception));
        });
    }

    /**
     * The save dialog doesn't add an extension, and "photos" is not a useful archive name.
     */
    private static Path withExtension(Path chosen, ArchiveFormat format) {
        String name = chosen.getFileName().toString();
        if (ArchiveFormat.detect(chosen).filter(detected -> detected == format).isPresent()
                && name.toLowerCase().endsWith(format.extension())) {
            return chosen;
        }
        return chosen.resolveSibling(name + format.extension());
    }

    private boolean busy() {
        if (job != null) {
            fail("Wait for the current job to finish, or cancel it.");
            return true;
        }
        return false;
    }

    /**
     * Shows the progress bar and starts polling {@code j} each few frames.
     */
    private void begin(String text, Job running, boolean canCancel) {
        job = running;
        cancellable = canCancel;
        progress.setProgress(0);
        progress.setIndeterminate(!canCancel);
        setStatus(Tone.BUSY, text);
        poller = Scheduler.every(0.05f, () -> {
            if (job == null) return;
            if (job.total() > 0) {
                progress.setIndeterminate(false);
                progress.setProgress(job.fraction());
            }
            String current = job.current();
            if (!current.isEmpty()) {
                String line = text + "   ·   " + shorten(current, 70);
                if (!line.equals(statusFull)) setStatus(Tone.BUSY, line);
            }
        });
    }

    private void finish() {
        if (poller != null) poller.cancel();
        poller = null;
        job = null;
        cancellable = false;
        layoutStatus();
        if (systemChanged) {
            systemChanged = false;
            if (chosen == Appearance.Mode.SYSTEM) setScheme(Appearance.Mode.SYSTEM.scheme());
        }
    }

    private void cancelJob() {
        if (job != null) {
            job.cancel();
            setStatus(Tone.BUSY, "Cancelling...");
        }
    }

    private void reveal() {
        if (lastOutput != null) Browser.reveal(lastOutput);
    }

    private static String message(Throwable exception) {
        Throwable cause = exception instanceof IllegalStateException && exception.getCause() != null ? exception.getCause() : exception;
        String text = cause.getMessage();
        return text == null || text.isBlank() ? cause.getClass().getSimpleName() : text;
    }

    private static String shorten(String text, int max) {
        return text.length() <= max ? text : "..." + text.substring(text.length() - (max - 3));
    }

    private PopupMenu contextMenu() {
        return new PopupMenu(
                new MenuItem("Open", () -> {
                    List<Node> selected = table.getSelectedItems();
                    if (selected.size() == 1) open(selected.getFirst());
                }),
                new MenuItem("Preview", this::preview),
                MenuItem.separator(),
                new MenuItem("Extract Selected...", this::extractSelected),
                new MenuItem("Extract All...", () -> extract(null)),
                MenuItem.separator(),
                new MenuItem("Delete...", this::deleteSelected),
                new MenuItem("Test Integrity", this::testArchive));
    }
}
