package me.piitex.openzip.ui;

import me.piitex.engine.ui.color.Color;

/**
 * Every color OpenZip draws with, in one place. A dark scheme (near-black neutrals with a faint cool tint) and a light
 * one (the same tint on near-white), each with one violet accent and muted text for everything that is not the thing
 * you are looking at. Same family as CCA.
 * <p>
 * The constants are plain fields that {@link #use} reassigns, so the rest of the UI reads {@code Palette.TEXT} as
 * before and picks up a new scheme the next time it builds an element. Switching the look rebuilds the window's
 * contents, see {@code OpenZipApp.setScheme}. Only the render thread calls {@code use}.
 * <p>
 * The light accent is a little deeper than the dark one so white text on it and violet icons on white both read.
 * {@code TINT} is a faint wash of the accent, used for the selected row.
 */
final class Palette {

    private Palette() {

    }

    record Scheme(String name,
                  Color window, Color panel, Color border, Color text, Color muted,
                  Color accent, Color accentHover, Color accentEnd, Color tint, Color tintStrong,
                  Color button, Color buttonHover, Color rowHover,
                  Color valid, Color warn, Color danger,
                  Color field, Color fieldHover, Color thumb, Color scrollThumb, Color scrollTrack, Color tooltip) {
    }

    static final Scheme DARK = new Scheme("Dark",
            new Color(0.063f, 0.063f, 0.075f, 1f),
            new Color(0.098f, 0.098f, 0.114f, 1f),
            new Color(0.20f, 0.20f, 0.235f, 1f),
            new Color(0.93f, 0.93f, 0.95f, 1f),
            new Color(0.60f, 0.60f, 0.67f, 1f),
            new Color(0.545f, 0.43f, 0.97f, 1f),
            new Color(0.63f, 0.53f, 1f, 1f),
            new Color(0.36f, 0.55f, 0.98f, 1f),
            new Color(0.545f, 0.43f, 0.97f, 0.16f),
            new Color(0.545f, 0.43f, 0.97f, 0.28f),
            new Color(0.165f, 0.165f, 0.195f, 1f),
            new Color(0.215f, 0.215f, 0.255f, 1f),
            new Color(1f, 1f, 1f, 0.05f),
            new Color(0.30f, 0.78f, 0.50f, 1f),
            new Color(0.95f, 0.70f, 0.28f, 1f),
            new Color(0.93f, 0.40f, 0.40f, 1f),
            new Color(0.075f, 0.075f, 0.09f, 1f),
            new Color(0.09f, 0.09f, 0.108f, 1f),
            Color.WHITE,
            new Color(0.40f, 0.40f, 0.46f, 0.9f),
            new Color(1f, 1f, 1f, 0.04f),
            new Color(0.16f, 0.16f, 0.19f, 0.98f));

    static final Scheme LIGHT = new Scheme("Light",
            new Color(0.955f, 0.955f, 0.972f, 1f),
            new Color(1f, 1f, 1f, 1f),
            new Color(0.875f, 0.875f, 0.91f, 1f),
            new Color(0.10f, 0.10f, 0.14f, 1f),
            new Color(0.40f, 0.40f, 0.48f, 1f),
            new Color(0.47f, 0.33f, 0.93f, 1f),
            new Color(0.40f, 0.27f, 0.86f, 1f),
            new Color(0.27f, 0.46f, 0.94f, 1f),
            new Color(0.47f, 0.33f, 0.93f, 0.10f),
            new Color(0.47f, 0.33f, 0.93f, 0.18f),
            new Color(0.925f, 0.925f, 0.955f, 1f),
            new Color(0.875f, 0.875f, 0.925f, 1f),
            new Color(0f, 0f, 0f, 0.04f),
            new Color(0.12f, 0.58f, 0.34f, 1f),
            new Color(0.78f, 0.48f, 0.05f, 1f),
            new Color(0.84f, 0.24f, 0.24f, 1f),
            new Color(0.972f, 0.972f, 0.985f, 1f),
            new Color(0.94f, 0.94f, 0.968f, 1f),
            new Color(0.47f, 0.33f, 0.93f, 1f),
            new Color(0.62f, 0.62f, 0.70f, 0.9f),
            new Color(0f, 0f, 0f, 0.05f),
            new Color(0.12f, 0.12f, 0.16f, 0.96f));

    static Color WINDOW, PANEL, BORDER;
    static Color TEXT, MUTED;
    static Color ACCENT, ACCENT_HOVER, ACCENT_END;
    static Color TINT, TINT_STRONG;
    static Color BUTTON, BUTTON_HOVER, ROW_HOVER;
    static Color VALID, WARN, DANGER;
    static Color FIELD, FIELD_HOVER, THUMB, SCROLL_THUMB, SCROLL_TRACK, TOOLTIP;

    static final Color TRANSPARENT = Color.TRANSPARENT;

    private static Scheme current;

    static {
        use(DARK);
    }

    static Scheme current() {
        return current;
    }

    static boolean isDark() {
        return current == DARK;
    }

    static void use(Scheme scheme) {
        current = scheme;
        WINDOW = scheme.window();
        PANEL = scheme.panel();
        BORDER = scheme.border();
        TEXT = scheme.text();
        MUTED = scheme.muted();
        ACCENT = scheme.accent();
        ACCENT_HOVER = scheme.accentHover();
        ACCENT_END = scheme.accentEnd();
        TINT = scheme.tint();
        TINT_STRONG = scheme.tintStrong();
        BUTTON = scheme.button();
        BUTTON_HOVER = scheme.buttonHover();
        ROW_HOVER = scheme.rowHover();
        VALID = scheme.valid();
        WARN = scheme.warn();
        DANGER = scheme.danger();
        FIELD = scheme.field();
        FIELD_HOVER = scheme.fieldHover();
        THUMB = scheme.thumb();
        SCROLL_THUMB = scheme.scrollThumb();
        SCROLL_TRACK = scheme.scrollTrack();
        TOOLTIP = scheme.tooltip();
    }
}
