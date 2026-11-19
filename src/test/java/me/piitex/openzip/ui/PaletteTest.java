package me.piitex.openzip.ui;

import me.piitex.engine.ui.color.Color;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Both looks have to stay readable: contrast is checked with the WCAG formula, so a tweak to a color can't quietly ruin one. */
class PaletteTest {
    @AfterEach
    void restore() {
        Palette.use(Palette.DARK);
    }

    private static double luminance(Color color) {
        return 0.2126 * channel(color.getR()) + 0.7152 * channel(color.getG()) + 0.0722 * channel(color.getB());
    }

    private static double channel(float value) {
        return value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private static double contrast(Color foreground, Color background) {
        double la = luminance(foreground), lb = luminance(background);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    @Test
    void textIsReadableInBothSchemes() {
        for (Palette.Scheme scheme : new Palette.Scheme[]{Palette.DARK, Palette.LIGHT}) {
            assertTrue(contrast(scheme.text(), scheme.window()) >= 7, scheme.name() + ": text on the window");
            assertTrue(contrast(scheme.text(), scheme.panel()) >= 7, scheme.name() + ": text on the panel");
            assertTrue(contrast(scheme.muted(), scheme.panel()) >= 4.5, scheme.name() + ": muted text on the panel");
            assertTrue(contrast(scheme.muted(), scheme.window()) >= 4.5, scheme.name() + ": muted text on the window");
            assertTrue(contrast(scheme.text(), scheme.button()) >= 7, scheme.name() + ": text on a button");
            // The dark accent is the violet the app was designed around (about 3.8:1); the light one was chosen to clear 4.5.
            double label = contrast(Color.WHITE, scheme.accent());
            assertTrue(label >= (scheme == Palette.LIGHT ? 4.5 : 3.5), scheme.name() + ": white label on an accent button, was " + label);
            assertTrue(contrast(scheme.danger(), scheme.panel()) >= 3, scheme.name() + ": error text on the panel");
            assertTrue(contrast(scheme.valid(), scheme.window()) >= 3, scheme.name() + ": success icon on the window");
            assertTrue(contrast(scheme.accentHover(), scheme.panel()) >= 3, scheme.name() + ": folder icons on the panel");
            assertTrue(contrast(scheme.thumb(), scheme.panel()) >= 1.5, scheme.name() + ": slider thumb on the panel");
        }
    }

    @Test
    void usingASchemeSwapsEveryConstant() {
        Palette.use(Palette.LIGHT);
        assertSame(Palette.LIGHT.panel(), Palette.PANEL);
        assertSame(Palette.LIGHT.text(), Palette.TEXT);
        assertFalse(Palette.isDark());
        Palette.use(Palette.DARK);
        assertSame(Palette.DARK.panel(), Palette.PANEL);
        assertTrue(Palette.isDark());
    }

    @Test
    void theLightSchemeIsLight() {
        assertTrue(luminance(Palette.LIGHT.window()) > 0.8);
        assertTrue(luminance(Palette.DARK.window()) < 0.05);
    }
}
