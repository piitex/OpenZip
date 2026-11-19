package me.piitex.openzip.ui;

import me.piitex.engine.ui.color.Color;
import me.piitex.engine.ui.theme.BaseTheme;

/**
 * The look of every themed widget (fields, dropdowns, sliders, tooltips, scrollbars), taken from whichever
 * {@link Palette} scheme is in use when it is made. Anything OpenZip styles by hand uses {@link Palette} directly, so
 * the two stay in step.
 */
final class OpenZipTheme extends BaseTheme {

    OpenZipTheme() {
        setText(Palette.TEXT);
        setWindowBackground(Palette.WINDOW);

        setContainerBackground(Palette.PANEL);
        setContainerBorder(Palette.BORDER);

        setFieldBackground(Palette.FIELD);
        setFieldHoverBackground(Palette.FIELD_HOVER);
        setFieldBorder(Palette.BORDER);
        setPlaceholderText(Palette.MUTED);
        setCaret(Palette.TEXT);
        setSelection(Palette.TINT_STRONG);

        // Slider fill, ticks, toggles and the selected row follow these.
        setAccent(Palette.ACCENT);
        setAccentTint(Palette.TINT_STRONG);

        setButtonBackground(Palette.BUTTON);
        setButtonHoverBackground(Palette.BUTTON_HOVER);
        setButtonBorder(Color.TRANSPARENT);

        setScrollThumb(Palette.SCROLL_THUMB);
        setScrollTrack(Palette.SCROLL_TRACK);

        setTooltipBackground(Palette.TOOLTIP);
        setTooltipBorder(Palette.BORDER);
        setTooltipText(new Color(0.95f, 0.95f, 0.97f, 1f)); // tooltips are dark in both schemes

        setCornerRadius(10f);
        setBorderThickness(1f);
    }
}
