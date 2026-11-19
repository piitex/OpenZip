package me.piitex.openzip.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FinderActionTest {
    private static Document parse(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return factory.newDocumentBuilder().parse(file.toFile());
    }

    @Test
    void installsAWorkflowFinderCanReadAndRemovesIt(@TempDir Path services) throws Exception {
        assertFalse(FinderAction.isInstalled(services));
        FinderAction.install(services);
        assertTrue(FinderAction.isInstalled(services));

        Path contents = FinderAction.workflow(services).resolve("Contents");
        String info = Files.readString(contents.resolve("Info.plist"));
        assertTrue(info.contains("<string>Compress with OpenZip</string>"));
        assertTrue(info.contains("public.item"), "files as well as folders");
        assertFalse(info.contains("@"), "every placeholder is filled");
        parse(contents.resolve("Info.plist"));

        String wflow = Files.readString(contents.resolve("document.wflow"));
        assertTrue(wflow.contains("/usr/bin/open -b me.piitex.openzip &quot;$@&quot;") || wflow.contains("/usr/bin/open -b me.piitex.openzip \"$@\""));
        assertFalse(wflow.contains("@COMMAND@"));
        parse(contents.resolve("document.wflow"));

        FinderAction.remove(services);
        assertFalse(FinderAction.isInstalled(services));
        assertFalse(Files.exists(FinderAction.workflow(services)));
    }

    @Test
    void installingAgainReplacesTheOldCopyAndLeavesNoStagingFolder(@TempDir Path services) throws IOException {
        FinderAction.install(services);
        Files.writeString(FinderAction.workflow(services).resolve("Contents/stale.txt"), "old");
        FinderAction.install(services);
        assertFalse(Files.exists(FinderAction.workflow(services).resolve("Contents/stale.txt")));
        try (var list = Files.list(services)) {
            assertEquals(1, list.count());
        }
    }

    @Test
    void removingWhatIsNotThereIsFine(@TempDir Path services) throws IOException {
        FinderAction.remove(services);
        assertFalse(FinderAction.isInstalled(services));
    }

    @Test
    void onlyTheInstalledAppOffersIt() {
        assertTrue(FinderAction.runningFromApp("/Applications/OpenZip.app/Contents/MacOS/OpenZip"));
        assertFalse(FinderAction.runningFromApp("/Library/Java/bin/java"));
        assertFalse(FinderAction.runningFromApp(""));
    }
}
