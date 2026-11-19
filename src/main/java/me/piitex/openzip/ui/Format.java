package me.piitex.openzip.ui;

import java.util.Locale;

final class Format {
    private Format() {
    }

    /** 1536 becomes "1.5 KB". Uses 1000s like Finder does, not 1024s. */
    static String size(long bytes) {
        if (bytes < 1000) return bytes + " bytes";
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1000;
            unit++;
        } while (value >= 1000 && unit < units.length - 1);
        return String.format(Locale.ROOT, value < 10 ? "%.1f %s" : "%.0f %s", value, units[unit]);
    }

    static String count(int count, String singular, String plural) {
        return count + " " + (count == 1 ? singular : plural);
    }
}
