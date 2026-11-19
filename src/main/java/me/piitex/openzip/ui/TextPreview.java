package me.piitex.openzip.ui;

import me.piitex.engine.Window;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.containers.ModalContainer;
import me.piitex.engine.ui.icons.Feather;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.FormattedTextOverlay;
import me.piitex.engine.ui.overlays.RichTextAreaOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;
import me.piitex.engine.ui.text.Font;
import me.piitex.engine.ui.text.MarkdownFormatPreset;
import me.piitex.engine.ui.text.TextStyle;

/**
 * A text file shown inside OpenZip: Markdown set out as a document, anything else as source with line numbers and a
 * little color. It is a viewer on purpose. Nothing can be saved back into an archive, so an editor here would let
 * someone type changes that vanish; "Open in editor" hands the file to the system's.
 */
final class TextPreview {
    private static final double MAX_WIDTH = 880, MAX_HEIGHT = 680, SIDE = 24, BODY_TOP = 84;

    private final ModalContainer modal;
    private final FormattedTextOverlay document;
    private final RichTextAreaOverlay source;
    private final ButtonOverlay modeButton;
    private final boolean markdown;
    private boolean showingSource;

    /**
     * @param name     the file's name, for the title and to pick the colors
     * @param details  the line under the title: its size, and that it was cut if it was
     * @param inEditor what "Open in editor" does
     */
    TextPreview(String name, String details, TextFiles.Content content, Runnable inEditor, int windowWidth, int windowHeight) {
        double width = Math.min(MAX_WIDTH, windowWidth - 64);
        double height = Math.min(MAX_HEIGHT, windowHeight - 64);
        double bodyWidth = width - 2 * SIDE, bodyHeight = height - BODY_TOP - SIDE;
        markdown = TextFiles.isMarkdown(name);

        Container dialog = Components.panel(width, height);
        dialog.getStyling().setHoverColor(Palette.PANEL);
        dialog.getStyling().setCornerRadius(Components.PANEL_RADIUS + 2);
        modal = new ModalContainer(dialog);

        // Header: what it is on the left, what can be done with it on the right.
        ButtonOverlay close = Components.iconButton(Feather.X, "Close", Components.PILL_HEIGHT, Palette.MUTED, modal::close);
        close.setPosition(width - SIDE - close.getWidth(), 20);
        ButtonOverlay editor = Components.secondaryButton("Open in " + TextFiles.editorName(), Components.pillWidth("Open in " + TextFiles.editorName()), () -> {
            modal.close();
            inEditor.run();
        });
        editor.setTooltip("Open it in your text editor. Changes there are not saved back into the archive.");
        editor.setPosition(close.getX() - 10 - editor.getWidth(), 20);
        modeButton = Components.secondaryButton("Show Source", Components.pillWidth("Show Formatted"), this::toggle);
        modeButton.setPosition(editor.getX() - 10 - modeButton.getWidth(), 20);
        modeButton.setEnabled(markdown);

        double textRoom = (markdown ? modeButton.getX() : editor.getX()) - SIDE - 16;
        TextOverlay title = Components.text(name.substring(name.lastIndexOf('/') + 1), 19f, Palette.TEXT, textRoom);
        title.setPosition(SIDE, 20);
        TextOverlay sub = Components.text(details, 13f, Palette.MUTED, textRoom);
        sub.setPosition(SIDE, 20 + 28);
        dialog.addElements(title, sub, modeButton, editor, close);

        // Body: a quiet inset surface the text sits on.
        Container body = new Container(bodyWidth, bodyHeight);
        body.getStyling().setBackgroundColor(Palette.FIELD);
        body.getStyling().setHoverColor(Palette.FIELD);
        body.getStyling().setBorderColor(Palette.BORDER);
        body.getStyling().setBorderThickness(1f);
        body.getStyling().setCornerRadius(12f);
        body.setPosition(SIDE, BODY_TOP);
        dialog.addElement(body);

        double inset = 14;
        source = new RichTextAreaOverlay(inset, inset, bodyWidth - 2 * inset, bodyHeight - 2 * inset);
        Components.clearStyle(source);
        source.setTypeface(Font.monospace());
        source.setFontSize(13f);
        source.setTextColor(Palette.TEXT);
        source.setLineNumbers(true);
        source.setLineNumberColor(Palette.MUTED);
        source.setReadOnly(true);
        source.setWordWrap(true);
        source.setText(content.text());
        source.setStyler(text -> Highlighter.spans(text, name));
        body.addElement(source);

        if (markdown) {
            MarkdownFormatPreset preset = new MarkdownFormatPreset();
            preset.setHeadingStyle(1, new TextStyle(26f, true, false, Palette.TEXT));
            preset.setHeadingStyle(2, new TextStyle(21f, true, false, Palette.TEXT));
            preset.setHeadingStyle(3, new TextStyle(17f, true, false, Palette.TEXT));
            preset.setHeadingStyle(4, new TextStyle(15f, true, false, Palette.TEXT));
            preset.setHeadingStyle(5, new TextStyle(14f, true, false, Palette.TEXT));
            preset.setHeadingStyle(6, new TextStyle(14f, true, false, Palette.MUTED));
            preset.setBodyStyle(new TextStyle(14.5f, false, false, Palette.TEXT));
            preset.setCodeStyle(new TextStyle(13f, false, false, Palette.TEXT));
            preset.setCodeBackground(Palette.BUTTON);
            document = new FormattedTextOverlay(inset, inset, bodyWidth - 2 * inset, bodyHeight - 2 * inset, preset);
            Components.clearStyle(document);
            document.setSource(content.text());
            body.addElement(document);
            source.setEnabled(false);
        } else {
            document = null;
        }
    }

    void toggle() {
        if (document == null) return;
        showingSource = !showingSource;
        document.setEnabled(!showingSource);
        source.setEnabled(showingSource);
        modeButton.setText(showingSource ? "Show Formatted" : "Show Source");
    }

    void show(Window window) {
        modal.show(window);
    }
}
