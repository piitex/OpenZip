package me.piitex.openzip.ui;

import me.piitex.engine.Window;
import me.piitex.engine.scheduler.AsyncTask;
import me.piitex.engine.scheduler.Scheduler;
import me.piitex.engine.scheduler.Task;
import me.piitex.engine.ui.color.LinearGradient;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.icons.Feather;
import me.piitex.engine.ui.layout.StackLayout;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.ProgressBarOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.utils.Browser;
import me.piitex.openzip.archive.ArchiveFormat;
import me.piitex.openzip.archive.ArchiveWriter;
import me.piitex.openzip.archive.ArchiveWriter.CreateOptions;
import me.piitex.openzip.archive.Destinations;
import me.piitex.openzip.archive.Job;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * What Explorer's "Compress to ZIP" opens instead of the full OpenZip window: one small window that makes the archive
 * next to what was right-clicked and reports on it. No menu, no table, no questions.
 * <p>
 * The window starts hidden and appears only if the job is still running after {@link #SHOW_AFTER} seconds, so a small
 * folder is zipped without anything flashing on screen. When it is done the window says so for a few seconds with a
 * button to show the archive in the file manager, then goes. A failure keeps the window until it is closed.
 * <p>
 * A failure's reason can run to several lines: the first goes in {@code detail} and the second and later in
 * {@code moreDetail}, which is empty until then. {@code over} says whether the job has ended; the timer that brings the
 * window up checks it, and a close request waits on it.
 */
public final class QuickCompress {
    public static final int WIDTH = 460;
    public static final int HEIGHT = 176;
    private static final double MARGIN = 24;
    private static final double BADGE = 40;
    private static final double TEXT_X = MARGIN + BADGE + 16;
    private static final double BUTTON_Y = HEIGHT - MARGIN - Components.PILL_HEIGHT;
    private static final float SHOW_AFTER = 0.6f;
    private static final float LINGER = 5f;
    private static final float DETAIL_SIZE = 13f;
    private static final int PROBLEM_LINES = 3;
    private static final double LINE_HEIGHT = 17;

    private final Window window;
    private final ArchiveFormat format;
    private final double textWidth;
    private final TextOverlay title;
    private final TextOverlay detail;
    private final List<TextOverlay> moreDetail = new ArrayList<>();
    private final TextOverlay figures;
    private final ProgressBarOverlay progress;
    private final ButtonOverlay cancelButton;
    private final ButtonOverlay revealButton;
    private final ButtonOverlay closeButton;

    private Job job;
    private Task poller;
    private Task closer;
    private Path output;
    private String intoName = "";
    private boolean over;

    public QuickCompress(Window window, ArchiveFormat format) {
        this.window = window;
        this.format = format;

        Container root = new Container(WIDTH, HEIGHT);
        Components.clearStyle(root);

        StackLayout badge = Components.gradientBadge(BADGE, Feather.ARCHIVE, 20);
        badge.setPosition(MARGIN, MARGIN);
        textWidth = WIDTH - TEXT_X - MARGIN;
        title = Components.text("Compressing...", 17f, Palette.TEXT, textWidth);
        title.setPosition(TEXT_X, MARGIN - 1);
        detail = Components.text("", DETAIL_SIZE, Palette.MUTED, textWidth);
        detail.setPosition(TEXT_X, MARGIN + 25);
        for (int i = 1; i < PROBLEM_LINES; i++) {
            TextOverlay line = Components.text("", DETAIL_SIZE, Palette.DANGER, textWidth);
            line.setPosition(TEXT_X, MARGIN + 25 + i * LINE_HEIGHT);
            moreDetail.add(line);
        }

        progress = new ProgressBarOverlay(WIDTH - 2 * MARGIN, 8);
        progress.setFillColor(new LinearGradient(Palette.ACCENT, Palette.ACCENT_END));
        progress.getStyling().setBackgroundColor(Palette.BUTTON_HOVER);
        progress.getStyling().setBorderThickness(0f);
        progress.getStyling().setCornerRadius(4f);
        progress.setIndeterminate(true);
        progress.setPosition(MARGIN, 88);

        figures = Components.text("", 13f, Palette.MUTED, 200);
        figures.setPosition(MARGIN, BUTTON_Y + (Components.PILL_HEIGHT - 16) / 2);

        cancelButton = Components.secondaryButton("Cancel", Components.pillWidth("Cancel"), this::cancel);
        closeButton = Components.secondaryButton("Close", Components.pillWidth("Close"), window::close);
        String show = "Show in " + Platforms.fileManager();
        revealButton = Components.accentButton(show, Components.pillWidth(show), this::reveal);
        placeButtons(cancelButton);
        placeButtons(closeButton);
        closeButton.setEnabled(false);
        revealButton.setEnabled(false);

        root.addElements(badge, title, detail, progress, figures, cancelButton, revealButton, closeButton);
        for (TextOverlay line : moreDetail) root.addElement(line);
        window.addContainer(root);
        window.onCloseRequest(this::closeRequested);
    }

    /**
     * Right-aligned, with {@code first} at the far right.
     */
    private void placeButtons(ButtonOverlay first) {
        first.setPosition(WIDTH - MARGIN - first.getWidth(), BUTTON_Y);
    }

    /**
     * Compresses the paths into a new archive next to the first of them: {@code photos} becomes {@code photos.zip}, or
     * {@code photos 2.zip} if that is taken. Only the first request counts.
     */
    public void start(List<Path> paths) {
        if (job != null || paths.isEmpty()) return;
        Path parent = paths.getFirst().toAbsolutePath().getParent();
        if (parent == null) { // a drive root has no folder to put the archive in
            problem("Nowhere to put it", "A whole drive can't be compressed from its right-click menu.");
            return;
        }
        String base = Destinations.archiveBase(paths);
        output = Destinations.uniqueFile(parent, base, format.extension());
        intoName = output.getFileName().toString();
        title.setText("Compressing " + base);
        detail.setText("Into " + intoName);

        Job newJob = new Job();
        job = newJob;
        poller = Scheduler.every(0.05f, () -> update(newJob));
        Scheduler.after(SHOW_AFTER, () -> {
            if (!over) appear();
        });

        CreateOptions options = new CreateOptions(format);
        AsyncTask<ArchiveWriter.CreateResult> task = Scheduler.async(() -> {
            try {
                return ArchiveWriter.create(output, paths, options, newJob);
            } catch (IOException exception) {
                throw new IllegalStateException(exception.getMessage() == null ? exception.toString() : exception.getMessage(), exception);
            }
        });
        task.onSuccess(result -> done(result));
        task.onError(exception -> {
            if (exception instanceof CancellationException) {
                end();
                window.close(); // cancelled on purpose: nothing to say, and the writer has removed its temporary file
                return;
            }
            Throwable cause = exception instanceof IllegalStateException && exception.getCause() != null ? exception.getCause() : exception;
            problem("Couldn't compress " + base, describe(cause));
        });
    }

    private void update(Job job) {
        if (over) return;
        if (job.total() > 0) {
            progress.setIndeterminate(false);
            progress.setProgress(job.fraction());
            figures.setText(Math.round(job.fraction() * 100) + "%   ·   " + Format.size(job.done()) + " of " + Format.size(job.total()));
        }
        String current = job.current();
        if (!current.isEmpty()) detail.setText(shorten(current, 52));
    }

    private void done(ArchiveWriter.CreateResult result) {
        end();
        if (window.isHidden()) { // quick enough that nobody saw the window: don't show it now
            window.close();
            return;
        }
        long saved = Math.max(0, Math.round((1 - result.ratio()) * 100));
        title.setText("Created " + intoName);
        detail.setTextColor(Palette.MUTED);
        detail.setText(Format.count(result.files(), "file", "files") + ", " + Format.size(result.uncompressedBytes()) + " became "
                + Format.size(result.archiveBytes()) + " (" + saved + "% smaller)");
        progress.setIndeterminate(false);
        progress.setProgress(1);
        figures.setText("");
        cancelButton.setEnabled(false);
        revealButton.setEnabled(true);
        closeButton.setEnabled(true);
        placeButtons(closeButton);
        revealButton.setPosition(WIDTH - MARGIN - closeButton.getWidth() - 10 - revealButton.getWidth(), BUTTON_Y);
        closer = Scheduler.after(LINGER, window::close);
    }

    private void problem(String heading, String message) {
        end();
        appear();
        title.setText(heading);
        detail.setTextColor(Palette.DANGER);
        List<String> lines = wrap(message, DETAIL_SIZE, textWidth, PROBLEM_LINES);
        detail.setText(lines.getFirst());
        for (int i = 0; i < moreDetail.size(); i++)
            moreDetail.get(i).setText(i + 1 < lines.size() ? lines.get(i + 1) : "");
        detail.setTooltip(message);
        progress.setEnabled(false);
        progress.setOpacity(0f); // the reason needs its room
        figures.setText("");
        cancelButton.setEnabled(false);
        closeButton.setEnabled(true);
        placeButtons(closeButton);
    }

    /**
     * Stops the polling. After this the job is over, whichever way it went.
     */
    private void end() {
        over = true;
        if (poller != null) poller.cancel();
        poller = null;
    }

    private void appear() {
        if (!window.isHidden()) return;
        window.center();
        window.show();
        window.focus();
    }

    private void cancel() {
        if (job == null || over) return;
        job.cancel();
        cancelButton.setEnabled(false);
        figures.setText("Cancelling...");
    }

    /**
     * The close button: stops a running job first (the window goes when it has stopped), otherwise just closes.
     */
    private void closeRequested() {
        if (job != null && !over) cancel();
        else window.close();
    }

    private void reveal() {
        if (closer != null) closer.cancel();
        if (output != null) Browser.reveal(output);
        window.close();
    }

    /**
     * What went wrong, reason first. The JDK's message for a file problem is {@code <full path>: <reason>}, and the path
     * fills the window before the reason starts, so this gives the reason and then just the file's name.
     */
    static String describe(Throwable cause) {
        if (cause instanceof FileSystemException problem && problem.getReason() != null && !problem.getReason().isBlank()) {
            String reason = problem.getReason().strip();
            if (reason.endsWith(".")) reason = reason.substring(0, reason.length() - 1);
            Path file = problem.getFile() == null ? null : Path.of(problem.getFile()).getFileName();
            return file == null ? reason : reason + " (" + file + ")";
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message.strip();
    }

    /**
     * {@code text} cut into at most {@code maxLines} lines that fit {@code width} at {@code size}, at a space or a path
     * separator where there is one. The last line takes what is left, and the label shortens it with an ellipsis.
     */
    static List<String> wrap(String text, float size, double width, int maxLines) {
        List<String> lines = new ArrayList<>();
        String rest = text.strip();
        while (lines.size() < maxLines - 1 && Components.widthOf(rest, size) > width) {
            int fit = 1;
            while (fit < rest.length() && Components.widthOf(rest.substring(0, fit + 1), size) <= width) fit++;
            int cut = fit;
            for (int i = fit; i > fit / 2; i--) {
                char before = rest.charAt(i - 1);
                if (before == ' ' || before == '\\' || before == '/') {
                    cut = i;
                    break;
                }
            }
            lines.add(rest.substring(0, cut).stripTrailing());
            rest = rest.substring(cut).stripLeading();
        }
        lines.add(rest);
        return lines;
    }

    private static String shorten(String text, int max) {
        return text.length() <= max ? text : "..." + text.substring(text.length() - (max - 3));
    }
}
