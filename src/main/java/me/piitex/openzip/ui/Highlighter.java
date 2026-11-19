package me.piitex.openzip.ui;

import me.piitex.engine.ui.color.Paint;
import me.piitex.engine.ui.text.TextSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Colors for the preview's source view: comments, strings, numbers and keywords. One pass over the text with a few
 * rules per kind of file, not a parser, so it is wrong in corners (a quote inside a heredoc) and right where it
 * matters, which is making a script or a config file easy to scan.
 */
final class Highlighter {
    private Highlighter() {
    }

    private enum Comments {
        NONE, HASH, SLASH, MARKUP, SEMICOLON
    }

    private static final Set<String> HASH = Set.of("sh", "bash", "zsh", "fish", "command", "py", "rb", "pl", "r", "yml", "yaml",
            "toml", "properties", "env", "gitignore", "gitattributes", "editorconfig", "ps1", "makefile", "dockerfile", "conf");
    private static final Set<String> SLASH = Set.of("java", "kt", "kts", "scala", "groovy", "gradle", "js", "mjs", "cjs", "ts", "tsx", "jsx",
            "c", "h", "cc", "cpp", "cxx", "hpp", "cs", "go", "rs", "swift", "m", "mm", "php", "dart", "vue", "css", "scss", "less", "jsonc", "json5");
    private static final Set<String> MARKUP = Set.of("xml", "html", "htm", "xhtml", "svg", "plist");
    private static final Set<String> SEMICOLON = Set.of("ini", "cfg", "config");
    private static final Set<String> QUOTED_ONLY = Set.of("json", "csv", "tsv");

    private static final Set<String> KEYWORDS = Set.of(
            "if", "else", "elif", "fi", "then", "for", "while", "do", "done", "case", "esac", "in", "return", "break", "continue",
            "function", "def", "class", "interface", "enum", "struct", "import", "from", "package", "export", "module", "use",
            "public", "private", "protected", "static", "final", "const", "let", "var", "val", "fun", "fn", "void", "new", "this",
            "try", "catch", "finally", "throw", "throws", "switch", "default", "extends", "implements", "async", "await",
            "true", "false", "null", "nil", "none", "None", "True", "False", "self", "int", "long", "double", "float", "boolean",
            "string", "char", "byte", "echo", "set", "local", "readonly", "exit", "source", "select", "where", "and", "or", "not");

    /** The colored runs of {@code text}, sorted and not overlapping, as the editor's styler wants them. */
    static List<TextSpan> spans(String text, String fileName) {
        String ext = extension(fileName);
        if (TextFiles.isMarkdown(fileName)) return markdown(text);
        Comments comments = HASH.contains(ext) ? Comments.HASH : SLASH.contains(ext) ? Comments.SLASH
                : MARKUP.contains(ext) ? Comments.MARKUP : SEMICOLON.contains(ext) ? Comments.SEMICOLON : Comments.NONE;
        boolean code = comments != Comments.NONE || QUOTED_ONLY.contains(ext);
        if (!code) return List.of();
        boolean keywords = comments == Comments.HASH || comments == Comments.SLASH;

        Paint quiet = Palette.MUTED, string = Palette.VALID, number = Palette.WARN, keyword = Palette.ACCENT_HOVER;
        List<TextSpan> out = new ArrayList<>();
        int length = text.length();
        int position = 0;
        while (position < length) {
            char character = text.charAt(position);
            if (startsComment(comments, text, position)) {
                int end = commentEnd(comments, text, position);
                out.add(new TextSpan(position, end, quiet));
                position = end;
            } else if (character == '"' || character == '\'' || character == '`') {
                int end = stringEnd(text, position, character);
                if (end < 0) {
                    position++; // an apostrophe, or a quote never closed on its line
                } else {
                    out.add(new TextSpan(position, end, string));
                    position = end;
                }
            } else if (Character.isDigit(character) && (position == 0 || !isWord(text.charAt(position - 1)))) {
                int end = position + 1;
                while (end < length && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '.' || text.charAt(end) == '_')) end++;
                out.add(new TextSpan(position, end, number));
                position = end;
            } else if (keywords && Character.isLetter(character) && (position == 0 || !isWord(text.charAt(position - 1)))) {
                int end = position + 1;
                while (end < length && isWord(text.charAt(end))) end++;
                if (KEYWORDS.contains(text.substring(position, end))) out.add(new TextSpan(position, end, keyword));
                position = end;
            } else {
                position++;
            }
        }
        return out;
    }

    /** Headings and code fences, in the source view of a Markdown file. */
    private static List<TextSpan> markdown(String text) {
        List<TextSpan> out = new ArrayList<>();
        boolean fenced = false;
        int pos = 0;
        while (pos < text.length()) {
            int eol = text.indexOf('\n', pos);
            int end = eol < 0 ? text.length() : eol;
            String line = text.substring(pos, end);
            if (line.stripLeading().startsWith("```")) {
                fenced = !fenced;
                out.add(new TextSpan(pos, end, Palette.MUTED));
            } else if (fenced) {
                out.add(new TextSpan(pos, end, Palette.VALID));
            } else if (line.startsWith("#") && line.length() > 1 && (line.indexOf(' ') > 0 && line.substring(0, line.indexOf(' ')).chars().allMatch(ch -> ch == '#'))) {
                out.add(new TextSpan(pos, end, Palette.ACCENT_HOVER));
            }
            pos = end + 1;
        }
        return out;
    }

    private static boolean startsComment(Comments kind, String text, int position) {
        return switch (kind) {
            case HASH -> text.charAt(position) == '#' && (position == 0 || text.charAt(position - 1) != '$');
            case SEMICOLON -> text.charAt(position) == ';' || text.charAt(position) == '#';
            case SLASH -> text.startsWith("//", position) || text.startsWith("/*", position);
            case MARKUP -> text.startsWith("<!--", position);
            case NONE -> false;
        };
    }

    private static int commentEnd(Comments kind, String text, int position) {
        String close = switch (kind) {
            case SLASH -> text.startsWith("/*", position) ? "*/" : null;
            case MARKUP -> "-->";
            default -> null;
        };
        if (close != null) {
            int end = text.indexOf(close, position + 2);
            return end < 0 ? text.length() : end + close.length();
        }
        int eol = text.indexOf('\n', position);
        return eol < 0 ? text.length() : eol;
    }

    /** The end of the string that opens at {@code start}, or -1 if it doesn't close on the same line. */
    private static int stringEnd(String text, int start, char quote) {
        for (int i = start + 1; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character == '\n') return -1;
            if (character == '\\') i++;
            else if (character == quote) return i + 1;
        }
        return -1;
    }

    private static boolean isWord(char character) {
        return Character.isLetterOrDigit(character) || character == '_';
    }

    private static String extension(String fileName) {
        String name = fileName.substring(fileName.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }
}
