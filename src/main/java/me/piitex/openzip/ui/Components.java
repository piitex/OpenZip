package me.piitex.openzip.ui;

import me.piitex.engine.ui.CursorMode;
import me.piitex.engine.ui.Element;
import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.color.LinearGradient;
import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.containers.Container;
import me.piitex.engine.ui.image.IconAsset;
import me.piitex.engine.ui.layout.HorizontalLayout;
import me.piitex.engine.ui.layout.Layout;
import me.piitex.engine.ui.layout.StackLayout;
import me.piitex.engine.ui.layout.VerticalLayout;
import me.piitex.engine.ui.overlays.ButtonOverlay;
import me.piitex.engine.ui.overlays.IconOverlay;
import me.piitex.engine.ui.overlays.TextOverlay;

/**
 * The buttons, badges and rows every screen uses, so they all look the same.
 */
final class Components {
    static final double PILL_HEIGHT = 38;
    static final float PANEL_RADIUS = 18f;

    private Components() {
    }

    /**
     * The main action on a screen: filled with the accent.
     */
    static ButtonOverlay accentButton(String text, double width, Runnable action) {
        return pill(text, width, Palette.ACCENT, Palette.ACCENT_HOVER, Color.WHITE, action);
    }

    /**
     * The button that confirms something that can't be undone.
     */
    static ButtonOverlay dangerButton(String text, double width, Runnable action) {
        return pill(text, width, Palette.DANGER, Palette.DANGER, Color.WHITE, action);
    }

    /**
     * Everything else: a quiet filled pill.
     */
    static ButtonOverlay secondaryButton(String text, double width, Runnable action) {
        return pill(text, width, Palette.BUTTON, Palette.BUTTON_HOVER, Palette.TEXT, action);
    }

    private static ButtonOverlay pill(String text, double width, Paint fill, Paint hover, Paint textColor, Runnable action) {
        ButtonOverlay button = new ButtonOverlay(text, 14f, textColor, width, PILL_HEIGHT);
        button.setTextColor(textColor);
        button.getStyling().setBackgroundColor(fill);
        button.getStyling().setHoverColor(hover);
        button.getStyling().setBorderThickness(0f);
        button.getStyling().setCornerRadius((float) (PILL_HEIGHT / 2));
        button.onAction(action);
        return button;
    }

    /**
     * A pill sized to its label.
     */
    static double pillWidth(String text) {
        return Math.ceil(new TextOverlay(text, 14f, Palette.TEXT).getWidth()) + 40;
    }

    /**
     * A round button with just an icon. Every one has a tooltip, since there is no label to say what it does.
     */
    static ButtonOverlay iconButton(IconAsset icon, String tooltip, double size, Color tint, Runnable action) {
        ButtonOverlay button = new ButtonOverlay("", 14f, tint, size, size);
        button.setIcon(icon);
        button.setIconSize(Math.round(size * 0.46));
        button.setTextColor(tint);
        button.setTooltip(tooltip);
        button.getStyling().setBackgroundColor(Palette.BUTTON);
        button.getStyling().setHoverColor(Palette.BUTTON_HOVER);
        button.getStyling().setBorderThickness(0f);
        button.getStyling().setCornerRadius((float) (size / 2));
        button.onAction(action);
        return button;
    }

    /**
     * Makes every part of a custom control react to a click, and shows the pointer over it.
     */
    static void clickable(Runnable action, Element... parts) {
        for (Element part : parts) {
            part.setCursorMode(CursorMode.POINTER);
            part.onMouseClick(event -> action.run());
        }
    }

    /**
     * The round gradient mark with a white icon in it. Used sparingly so it stays special.
     */
    static StackLayout gradientBadge(double size, IconAsset icon, double iconSize) {
        StackLayout badge = new StackLayout(size, size);
        badge.setAlignment(Layout.Alignment.CENTER);
        badge.getStyling().setBackgroundColor(new LinearGradient(Palette.ACCENT, Palette.ACCENT_END));
        badge.getStyling().setBorderThickness(0f);
        badge.getStyling().setCornerRadius((float) (size / 2));
        IconOverlay overlay = new IconOverlay(icon, iconSize, iconSize);
        overlay.setTint(Color.WHITE);
        badge.addElement(overlay);
        return badge;
    }

    static TextOverlay text(String text, float size, Paint color) {
        TextOverlay overlay = new TextOverlay(text, size, color);
        overlay.setTextColor(color);
        return overlay;
    }

    /**
     * Text that is cut off with an ellipsis instead of running past {@code maxWidth}.
     */
    static TextOverlay text(String text, float size, Paint color, double maxWidth) {
        TextOverlay overlay = text(text, size, color);
        overlay.setMaxWidth(maxWidth);
        return overlay;
    }

    /**
     * How wide {@code text} is when drawn at {@code size}, for deciding whether it needs a tooltip.
     */
    static double widthOf(String text, float size) {
        return new TextOverlay(text, size, Palette.TEXT).getWidth();
    }

    static void clearStyle(Element element) {
        element.getStyling().setBackgroundColor(Color.TRANSPARENT);
        element.getStyling().setHoverColor(Color.TRANSPARENT);
        element.getStyling().setBorderThickness(0f);
    }

    /**
     * The rounded, softly bordered surface content sits on.
     */
    static Container panel(double width, double height) {
        Container panel = new Container(width, height);
        panel.getStyling().setBackgroundColor(Palette.PANEL);
        panel.getStyling().setBorderColor(Palette.BORDER);
        panel.getStyling().setBorderThickness(1f);
        panel.getStyling().setCornerRadius(PANEL_RADIUS);
        return panel;
    }

    static VerticalLayout dialogPanel(double width, double height) {
        VerticalLayout panel = new VerticalLayout(width, height);
        panel.getStyling().setBackgroundColor(Palette.PANEL);
        panel.getStyling().setHoverColor(Palette.PANEL);
        panel.getStyling().setBorderColor(Palette.BORDER);
        panel.getStyling().setBorderThickness(1f);
        panel.getStyling().setCornerRadius(PANEL_RADIUS + 2);
        panel.setPadding(24f);
        panel.setSpacing(14f);
        panel.setAlignment(Layout.Alignment.TOP_LEFT);
        return panel;
    }

    /**
     * A row of equal buttons filling a dialog's width: Cancel on the left, the main action on the right.
     */
    static HorizontalLayout dialogButtons(double width, ButtonOverlay left, ButtonOverlay right) {
        HorizontalLayout row = new HorizontalLayout(width, PILL_HEIGHT + 2);
        row.setSpacing(12f);
        clearStyle(row);
        row.addElement(left);
        row.addElement(right);
        return row;
    }

    /**
     * A big tappable row: icon, a title and a line under it. For choosing between a few things.
     */
    static Container optionRow(IconAsset icon, String title, String hint, double width, Runnable action) {
        double height = 68;
        Container row = new Container(width, height);
        row.getStyling().setBackgroundColor(Palette.BUTTON);
        row.getStyling().setHoverColor(Palette.BUTTON_HOVER);
        row.getStyling().setBorderThickness(0f);
        row.getStyling().setCornerRadius(14f);

        double chip = 40;
        Container iconChip = new Container(chip, chip);
        iconChip.getStyling().setBackgroundColor(Palette.TINT);
        iconChip.getStyling().setHoverColor(Palette.TINT);
        iconChip.getStyling().setBorderThickness(0f);
        iconChip.getStyling().setCornerRadius(12f);
        iconChip.setPosition(14, (height - chip) / 2);
        IconOverlay glyph = new IconOverlay(icon, 20, 20);
        glyph.setTint(Palette.ACCENT_HOVER);
        glyph.setPosition((chip - 20) / 2, (chip - 20) / 2);
        iconChip.addElement(glyph);
        row.addElement(iconChip);

        TextOverlay heading = text(title, 15f, Palette.TEXT);
        heading.setPosition(14 + chip + 14, 14);
        TextOverlay sub = text(hint, 12.5f, Palette.MUTED);
        sub.setPosition(14 + chip + 14, 38);
        row.addElement(heading);
        row.addElement(sub);

        clickable(action, row, iconChip, glyph, heading, sub);
        return row;
    }
}
