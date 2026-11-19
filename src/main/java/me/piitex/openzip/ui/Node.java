package me.piitex.openzip.ui;

import me.piitex.openzip.archive.EntryInfo;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One row of the archive browser. Archives list entries flat ({@code a/b/c.txt}); this builds the folders around them,
 * including folders the archive never listed on their own.
 * <p>
 * {@code info} is null for a folder the archive only implies by the paths of the files inside it. The table reads the
 * totals and the size texts for every visible row on every frame, so folders add up their contents once and keep them.
 */
final class Node {
    final String path;
    final String name;
    final EntryInfo info;
    final List<Node> children = new ArrayList<>();

    private long total = -1;
    private int files = -1;
    private String sizeText, packedText, modifiedText;

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT);

    private Node(String path, String name, EntryInfo info) {
        this.path = path;
        this.name = name;
        this.info = info;
    }

    boolean isFolder() {
        return info == null || info.directory();
    }

    boolean isLink() {
        return info != null && info.isSymlink();
    }

    /**
     * Bytes in this file, or in every file under this folder.
     */
    long totalSize() {
        if (total < 0) {
            if (!isFolder()) {
                total = Math.max(0, info.size());
            } else {
                long sum = 0;
                for (Node child : children) sum += child.totalSize();
                total = sum;
            }
        }
        return total;
    }

    int fileCount() {
        if (files < 0) {
            if (!isFolder()) {
                files = 1;
            } else {
                int count = 0;
                for (Node child : children) count += child.fileCount();
                files = count;
            }
        }
        return files;
    }

    /**
     * Bytes this file takes inside the archive, or 0 when that isn't known (folders, and formats that don't say).
     */
    long packedSize() {
        return isFolder() || info.compressedSize() < 0 ? 0 : info.compressedSize();
    }

    /**
     * Milliseconds since 1970, or 0 when the archive doesn't say.
     */
    long modified() {
        return info == null ? 0 : Math.max(0, info.modified());
    }

    String sizeText() {
        if (sizeText == null) sizeText = Format.size(totalSize());
        return sizeText;
    }

    String packedText() {
        if (packedText == null) packedText = packedSize() > 0 ? Format.size(packedSize()) : "";
        return packedText;
    }

    String modifiedText() {
        if (modifiedText == null) {
            modifiedText = modified() > 0 ? WHEN.format(Instant.ofEpochMilli(modified()).atZone(ZoneId.systemDefault())) : "";
        }
        return modifiedText;
    }

    /**
     * Every folder by its path, so the browser can jump to one. The empty path is not in it: that is {@code roots}.
     */
    static Map<String, Node> folders(List<Node> roots) {
        Map<String, Node> map = new HashMap<>();
        Deque<Node> pending = new ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            Node node = pending.pop();
            if (!node.isFolder()) continue;
            map.put(node.path, node);
            pending.addAll(node.children);
        }
        return map;
    }

    /**
     * The top-level rows for a list of entries, folders first and each level sorted by name.
     */
    static List<Node> build(List<EntryInfo> entries) {
        Map<String, Node> byPath = new HashMap<>();
        List<Node> roots = new ArrayList<>();
        for (EntryInfo entry : entries) {
            Node node = byPath.get(entry.path());
            if (node != null && node.info == null) {
                // The folder was created earlier from a file below it; now its own entry has arrived.
                Node real = new Node(entry.path(), entry.name(), entry);
                real.children.addAll(node.children);
                replace(byPath, roots, node, real);
                continue;
            }
            if (node != null) continue; // a duplicate name; the first one wins
            node = new Node(entry.path(), entry.name(), entry);
            byPath.put(entry.path(), node);
            attach(byPath, roots, node);
        }
        sort(roots);
        return roots;
    }

    private static void attach(Map<String, Node> byPath, List<Node> roots, Node node) {
        String parentPath = parentOf(node.path);
        if (parentPath.isEmpty()) {
            roots.add(node);
            return;
        }
        Node parent = byPath.get(parentPath);
        if (parent == null) {
            parent = new Node(parentPath, parentPath.substring(parentPath.lastIndexOf('/') + 1), null);
            byPath.put(parentPath, parent);
            attach(byPath, roots, parent);
        }
        parent.children.add(node);
    }

    private static void replace(Map<String, Node> byPath, List<Node> roots, Node old, Node real) {
        byPath.put(real.path, real);
        List<Node> siblings = roots;
        String parentPath = parentOf(old.path);
        if (!parentPath.isEmpty()) siblings = byPath.get(parentPath).children;
        siblings.set(siblings.indexOf(old), real);
    }

    private static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static void sort(List<Node> nodes) {
        nodes.sort(Comparator.comparing((Node node) -> !node.isFolder()).thenComparing(node -> node.name.toLowerCase(), String::compareTo));
        for (Node node : nodes) sort(node.children);
    }
}
