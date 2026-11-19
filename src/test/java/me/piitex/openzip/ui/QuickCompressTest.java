package me.piitex.openzip.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QuickCompressTest {
    @Test
    void aFileProblemLeadsWithTheReasonAndNamesOnlyTheFile() {
        Throwable error = new FileSystemException("C:\\Users\\Someone\\Documents\\A long folder\\big.bin", null,
                "The process cannot access the file because it is being used by another process.");
        assertEquals("The process cannot access the file because it is being used by another process (big.bin)",
                QuickCompress.describe(error));
    }

    @Test
    void aFileProblemWithoutAReasonKeepsTheJdkMessage() {
        assertEquals("/tmp/gone.txt", QuickCompress.describe(new NoSuchFileException("/tmp/gone.txt")));
        assertEquals("/tmp/x", QuickCompress.describe(new AccessDeniedException("/tmp/x")));
    }

    @Test
    void otherErrorsKeepTheirMessageOrFallBackToTheirClassName() {
        assertEquals("disk full", QuickCompress.describe(new IOException("  disk full ")));
        assertEquals("IllegalStateException", QuickCompress.describe(new IllegalStateException()));
    }
}
