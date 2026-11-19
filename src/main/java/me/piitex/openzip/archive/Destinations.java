package me.piitex.openzip.archive;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

/**
 * Decides where an archive's contents go, so extracting never buries files in the parent folder or merges into old ones.
 */
public final class Destinations {

    private Destinations() {
    }

    /**
     * Where to extract {@code archive} when the user picked the folder {@code parent}:
     * <ul>
     *   <li>If everything in the archive is inside one top-level entry (a project folder, say) and nothing by that
     *       name is in {@code parent} yet, extract into {@code parent} itself: the archive already brings its own folder.</li>
     *   <li>Otherwise make a new folder named after the archive: {@code photos.zip} becomes {@code parent/photos}, or
     *       {@code parent/photos 2} if that is taken. Nothing already on disk is touched.</li>
     * </ul>
     */
    public static Path choose(Path parent, Path archive, ArchiveReader.Listing listing) {
        Set<String> roots = topLevelNames(listing);
        if (roots.size() == 1 && SafePaths.unusableHere(roots.iterator().next()) == null
                && !Files.exists(parent.resolve(roots.iterator().next()))) {
            return parent; // a name Windows can't hold (a:b) is skipped on extraction, so it can't be the folder that is brought along
        }
        return unique(parent, ArchiveFormat.baseName(archive));
    }

    /**
     * {@code parent/name}, or {@code parent/name 2}, {@code name 3}... for the first one that doesn't exist.
     */
    public static Path unique(Path parent, String name) {
        Path candidate = parent.resolve(name);
        for (int number = 2; Files.exists(candidate); number++) {
            candidate = parent.resolve(name + " " + number);
        }
        return candidate;
    }

    /**
     * {@code parent/photos.zip}, or {@code parent/photos 2.zip}, {@code photos 3.zip}... for the first name not taken.
     */
    public static Path uniqueFile(Path parent, String name, String extension) {
        Path candidate = parent.resolve(name + extension);
        for (int number = 2; Files.exists(candidate); number++) {
            candidate = parent.resolve(name + " " + number + extension);
        }
        return candidate;
    }

    /**
     * The default file name for a new archive: the single input's name, or "Archive" for several.
     */
    public static String archiveName(java.util.List<Path> inputs, ArchiveFormat format) {
        return archiveBase(inputs) + format.extension();
    }

    /**
     * {@link #archiveName} without the extension.
     */
    public static String archiveBase(java.util.List<Path> inputs) {
        return inputs.size() == 1 && inputs.getFirst().getFileName() != null
                ? inputs.getFirst().getFileName().toString() : "Archive";
    }

    static Set<String> topLevelNames(ArchiveReader.Listing listing) {
        Set<String> roots = new TreeSet<>();
        for (EntryInfo entry : listing.entries()) {
            if (SafePaths.isJunkPath(entry.path()))
                continue; // __MACOSX beside the real folder doesn't count as a second root
            int slash = entry.path().indexOf('/');
            roots.add(slash < 0 ? entry.path() : entry.path().substring(0, slash));
        }
        return roots;
    }
}
