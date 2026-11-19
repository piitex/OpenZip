package me.piitex.openzip.ui;

import me.piitex.engine.ui.text.TextSpan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HighlighterTest {
    private static String painted(String text, String name, Object color) {
        StringBuilder out = new StringBuilder();
        for (TextSpan span : Highlighter.spans(text, name)) {
            if (span.color() == color) out.append('[').append(text, span.start(), span.end()).append(']');
        }
        return out.toString();
    }

    @Test
    void shellCommentsStringsNumbersAndKeywords() {
        String text = "if [ -f \"a b\" ]; then  # check\n  exit 12\nfi\n";
        assertEquals("[# check]", painted(text, "run.sh", Palette.MUTED));
        assertEquals("[\"a b\"]", painted(text, "run.sh", Palette.VALID));
        assertEquals("[12]", painted(text, "run.sh", Palette.WARN));
        assertEquals("[if][then][exit][fi]", painted(text, "run.sh", Palette.ACCENT_HOVER));
    }

    @Test
    void anApostropheDoesNotSwallowTheLine() {
        String text = "name: it's here\nother: 'quoted'\n";
        assertEquals("['quoted']", painted(text, "a.yml", Palette.VALID));
    }

    @Test
    void blockCommentsRunToTheirEnd() {
        String text = "int a; /* one\ntwo */ int b; // tail\n";
        assertEquals("[/* one\ntwo */][// tail]", painted(text, "A.java", Palette.MUTED));
    }

    @Test
    void wordsInsideNamesAreNotKeywordsOrNumbers() {
        String text = "format2 = notify + x1";
        assertEquals("", painted(text, "a.js", Palette.ACCENT_HOVER));
        assertEquals("", painted(text, "a.js", Palette.WARN));
    }

    @Test
    void plainTextGetsNoColor() {
        assertTrue(Highlighter.spans("if 12 \"x\" # y", "notes.txt").isEmpty());
    }

    @Test
    void markdownHeadingsAndFences() {
        String text = "# Title\ntext\n```\ncode #1\n```\n#tag\n";
        assertEquals("[# Title]", painted(text, "a.md", Palette.ACCENT_HOVER));
        assertEquals("[code #1]", painted(text, "a.md", Palette.VALID));
    }

    @Test
    void spansAreSortedAndDoNotOverlap() {
        List<TextSpan> spans = Highlighter.spans("a = \"x # y\" # z\nb = 3 // no\n", "a.sh");
        int last = 0;
        for (TextSpan span : spans) {
            assertTrue(span.start() >= last, spans.toString());
            assertTrue(span.end() > span.start());
            last = span.end();
        }
    }
}
