package me.piitex.openzip.archive;

import net.sf.sevenzipjbinding.ArchiveFormat;
import net.sf.sevenzipjbinding.ExtractAskMode;
import net.sf.sevenzipjbinding.ExtractOperationResult;
import net.sf.sevenzipjbinding.IArchiveExtractCallback;
import net.sf.sevenzipjbinding.IArchiveOpenCallback;
import net.sf.sevenzipjbinding.ICryptoGetTextPassword;
import net.sf.sevenzipjbinding.IInArchive;
import net.sf.sevenzipjbinding.ISequentialOutStream;
import net.sf.sevenzipjbinding.PropID;
import net.sf.sevenzipjbinding.SevenZip;
import net.sf.sevenzipjbinding.SevenZipException;
import net.sf.sevenzipjbinding.SevenZipNativeInitializationException;
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Reads RAR 3, 4 and 5 archives through 7-Zip-JBinding. RAR can only be read: nobody but RARLAB may write it.
 * <p>
 * The library pushes: it calls us with each entry's bytes as it decodes them, in archive order. The rest of OpenZip
 * pulls from an {@link InputStream}. {@link Run} bridges the two by letting the library work on a thread of its own and
 * handing the bytes over through a small bounded queue, so a big file is never held in memory. The library is only
 * started when some entry's data is actually opened, and entries nobody opens are skipped by it, so listing an archive
 * decodes nothing and extracting a few files from a solid archive decodes the stretch up to them once, not once per file.
 */
final class RarSource implements ArchiveReader.Source {
    private static final int QUEUE_CHUNKS = 8;
    private static final long POLL_MS = 100;
    private static final byte[] END = new byte[0];

    private final String password;
    private final RandomAccessFile file;
    private final IInArchive archive;
    private boolean encrypted;

    /**
     * The name 7-Zip-JBinding gives this computer's natives, or null when we don't know it. Every image carries all four
     * platform jars, and each has a same-named platforms file; the library only reads the first one on the class path, so
     * left to itself it would pick the Linux natives on Windows. Naming the platform ourselves avoids that.
     */
    static String platformName(String osName, String osArch) {
        String os = osName == null ? "" : osName.toLowerCase(java.util.Locale.ROOT);
        String arch = osArch == null ? "" : osArch.toLowerCase(java.util.Locale.ROOT);
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        boolean arm64 = arch.equals("aarch64") || arch.equals("arm64");
        if (os.startsWith("windows")) return x64 ? "Windows-amd64" : null; // there is no Windows ARM build
        if (os.startsWith("linux")) return x64 ? "Linux-amd64" : arm64 ? "Linux-arm64" : null;
        if (os.startsWith("mac")) return "Mac";
        return null;
    }

    /**
     * Loads the native library the first time it is needed.
     */
    static synchronized void loadNatives() throws IOException {
        if (SevenZip.isInitializedSuccessfully()) return;
        try {
            String platform = platformName(System.getProperty("os.name"), System.getProperty("os.arch"));
            if (platform != null) SevenZip.initSevenZipFromPlatformJAR(platform);
            else SevenZip.initSevenZipFromPlatformJAR();
        } catch (SevenZipNativeInitializationException | RuntimeException | LinkageError exception) {
            throw new IOException("RAR archives can't be opened on this system: " + exception.getMessage(), exception);
        }
    }

    RarSource(Path path, char[] password) throws IOException {
        loadNatives();
        this.password = password == null || password.length == 0 ? null : new String(password);
        this.file = new RandomAccessFile(path.toFile(), "r");
        OpenCallback callback = new OpenCallback();
        try {
            this.archive = SevenZip.openInArchive(null, new RandomAccessFileInStream(file), callback);
        } catch (SevenZipException | RuntimeException exception) {
            file.close();
            // Archives that hide their names ask for the password while opening.
            if (callback.passwordAsked)
                throw this.password == null ? PasswordException.required() : PasswordException.wrongOrDamaged(exception);
            throw new IOException("Not a RAR archive, or it is damaged: " + exception.getMessage(), exception);
        }
        ArchiveFormat format = archive.getArchiveFormat();
        if (format != ArchiveFormat.RAR && format != ArchiveFormat.RAR5) {
            close();
            throw new IOException("Not a RAR archive");
        }
    }

    private final class OpenCallback implements IArchiveOpenCallback, ICryptoGetTextPassword {
        boolean passwordAsked;

        @Override
        public void setTotal(Long files, Long bytes) {
        }

        @Override
        public void setCompleted(Long files, Long bytes) {
        }

        @Override
        public String cryptoGetTextPassword() throws SevenZipException {
            passwordAsked = true;
            if (password == null) throw new SevenZipException("password required");
            return password;
        }
    }

    private record Item(int index, EntryInfo info, boolean locked) {
    }

    private List<Item> readItems(List<String> skipped) throws IOException {
        List<Item> items = new ArrayList<>();
        try {
            for (int i = 0, count = archive.getNumberOfItems(); i < count; i++) {
                String path = ArchiveReader.cleanName((String) archive.getProperty(i, PropID.PATH), skipped);
                if (path == null) continue;
                boolean directory = Boolean.TRUE.equals(archive.getProperty(i, PropID.IS_FOLDER));
                boolean locked = !directory && Boolean.TRUE.equals(archive.getProperty(i, PropID.ENCRYPTED));
                encrypted |= locked;

                Object link = directory ? null : archive.getProperty(i, PropID.SYM_LINK);
                String linkTarget = link instanceof String text && !text.isEmpty() ? text : null;

                Date modified = (Date) archive.getProperty(i, PropID.LAST_MODIFICATION_TIME);
                EntryInfo info = new EntryInfo(path, directory, directory ? 0 : number(archive.getProperty(i, PropID.SIZE)),
                        number(archive.getProperty(i, PropID.PACKED_SIZE)), modified == null ? -1 : modified.getTime(),
                        mode(i), linkTarget);
                items.add(new Item(i, info, locked));
            }
        } catch (SevenZipException exception) {
            throw new IOException("Can't read the RAR archive's file list: " + exception.getMessage(), exception);
        }
        return items;
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : -1;
    }

    /**
     * The Unix permission bits when the archive was made on a Unix system, otherwise -1.
     */
    private int mode(int index) throws SevenZipException {
        Object posix = archive.getProperty(index, PropID.POSIX_ATTRIB);
        if (posix instanceof Number number && number.intValue() > 0) return number.intValue() & 07777;
        Object attrs = archive.getProperty(index, PropID.ATTRIBUTES);
        if (attrs instanceof Number number && (number.intValue() & 0x8000) != 0) { // the high half holds the Unix mode when this bit is set
            int mode = number.intValue() >>> 16;
            if (mode != 0) return mode & 07777;
        }
        return -1;
    }

    @Override
    public void forEach(ArchiveReader.EntryVisitor visitor, List<String> skipped) throws IOException {
        List<Item> items = readItems(skipped);
        Run run = null;
        try {
            for (int index = 0; index < items.size(); index++) {
                Item item = items.get(index);
                if (item.info().directory()) {
                    visitor.visit(item.info(), () -> {
                        throw new IOException("a folder has no data");
                    });
                    continue;
                }
                // Once the library is running it asks for every file in turn, so each one has to be answered, read or not.
                Run.Slot slot = run == null ? null : run.next(item.index());
                Run[] started = {run};
                Run.Slot[] current = {slot};
                int from = index;
                ArchiveReader.DataSource data = () -> {
                    if (item.locked() && password == null) throw PasswordException.required();
                    if (started[0] == null) {
                        started[0] = new Run(indicesFrom(items, from));
                        current[0] = started[0].next(item.index());
                    }
                    return current[0].open(item.locked());
                };
                try {
                    visitor.visit(item.info(), data);
                } finally {
                    run = started[0];
                    if (current[0] != null) current[0].release();
                }
            }
        } finally {
            if (run != null) run.stop();
        }
    }

    private static int[] indicesFrom(List<Item> items, int from) {
        return items.subList(from, items.size()).stream().filter(item -> !item.info().directory()).mapToInt(Item::index).toArray();
    }

    @Override
    public boolean sawEncrypted() {
        return encrypted;
    }

    @Override
    public void close() throws IOException {
        try {
            archive.close();
        } catch (SevenZipException | RuntimeException ignored) {
            // closing twice, or a stream the library already closed
        } finally {
            file.close();
        }
    }

    /**
     * One run of the library over a set of entries, on a thread of its own.
     */
    private final class Run {
        private final BlockingQueue<Slot> ready = new LinkedBlockingQueue<>();
        private final Thread thread;
        private volatile boolean aborted;
        private volatile boolean finished;
        private volatile Throwable failure;
        private volatile boolean passwordAsked;

        Run(int[] indices) {
            thread = new Thread(() -> {
                try {
                    archive.extract(indices, false, new ExtractCallback());
                } catch (Throwable exception) {
                    failure = exception;
                } finally {
                    finished = true;
                }
            }, "openzip-rar");
            thread.setDaemon(true);
            thread.start();
        }

        /**
         * Waits for the library to come to the entry with this index.
         */
        Slot next(int index) throws IOException {
            try {
                while (true) {
                    Slot slot = ready.poll(POLL_MS, TimeUnit.MILLISECONDS);
                    if (slot != null) {
                        if (slot.index != index) throw new IOException("the RAR reader got out of step");
                        return slot;
                    }
                    if (finished && ready.isEmpty()) throw ended();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", exception);
            }
        }

        /**
         * Why the library stopped before the entries were done.
         */
        IOException ended() {
            if (passwordAsked) return PasswordException.required();
            Throwable problem = failure;
            return new IOException("The RAR reader stopped" + (problem == null ? "" : ": " + problem.getMessage()), problem);
        }

        /**
         * Stops the library, which may be waiting for us, and waits for its thread.
         */
        void stop() {
            aborted = true;
            try {
                thread.join(10_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        private final class ExtractCallback implements IArchiveExtractCallback, ICryptoGetTextPassword {
            private Slot current;

            @Override
            public ISequentialOutStream getStream(int index, ExtractAskMode mode) throws SevenZipException {
                current = null;
                if (aborted) throw new SevenZipException("stopped");
                if (mode != ExtractAskMode.EXTRACT) return null;
                Slot slot = new Slot(index);
                ready.add(slot);
                try {
                    // The reader decides whether it wants the bytes. Until it does, the library waits here.
                    while (!slot.decided.await(POLL_MS, TimeUnit.MILLISECONDS)) {
                        if (aborted) throw new SevenZipException("stopped");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new SevenZipException("interrupted");
                }
                if (!slot.wanted) return null;
                current = slot;
                return data -> {
                    slot.push(data);
                    return data.length;
                };
            }

            @Override
            public void prepareOperation(ExtractAskMode mode) {
            }

            @Override
            public void setOperationResult(ExtractOperationResult result) throws SevenZipException {
                Slot slot = current;
                current = null;
                if (slot != null) slot.finish(result);
            }

            @Override
            public void setTotal(long total) throws SevenZipException {
                if (aborted) throw new SevenZipException("stopped");
            }

            @Override
            public void setCompleted(long complete) throws SevenZipException {
                if (aborted) throw new SevenZipException("stopped");
            }

            @Override
            public String cryptoGetTextPassword() throws SevenZipException {
                if (password == null) {
                    passwordAsked = true;
                    throw new SevenZipException("password required");
                }
                return password;
            }
        }

        /**
         * One entry's bytes on their way from the library to the reader.
         */
        private final class Slot {
            final int index;
            final CountDownLatch decided = new CountDownLatch(1);
            private final ArrayBlockingQueue<byte[]> chunks = new ArrayBlockingQueue<>(QUEUE_CHUNKS);
            private volatile boolean wanted;
            private volatile boolean discard;
            private volatile ExtractOperationResult result;

            Slot(int index) {
                this.index = index;
            }

            InputStream open(boolean locked) {
                wanted = true;
                decided.countDown();
                return new InputStream() {
                    private byte[] chunk = END;
                    private int pos;
                    private boolean eof;

                    @Override
                    public int read() throws IOException {
                        byte[] one = new byte[1];
                        int count = read(one, 0, 1);
                        return count < 0 ? -1 : one[0] & 0xFF;
                    }

                    @Override
                    public int read(byte[] buffer, int off, int len) throws IOException {
                        if (len == 0) return 0;
                        if (eof) return -1;
                        if (pos == chunk.length) {
                            chunk = take();
                            pos = 0;
                            if (chunk == END) {
                                eof = true;
                                check(locked);
                                return -1;
                            }
                        }
                        int count = Math.min(len, chunk.length - pos);
                        System.arraycopy(chunk, pos, buffer, off, count);
                        pos += count;
                        return count;
                    }
                };
            }

            /**
             * The reader is done with this entry, whether or not it read everything: the library must be let past it.
             */
            void release() {
                discard = true;
                decided.countDown();
                chunks.clear();
            }

            private byte[] take() throws IOException {
                try {
                    while (true) {
                        byte[] chunk = chunks.poll(POLL_MS, TimeUnit.MILLISECONDS);
                        if (chunk != null) return chunk;
                        if (finished && chunks.isEmpty()) throw ended();
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", exception);
                }
            }

            /**
             * What the library concluded about the entry, once all of it has been read.
             */
            private void check(boolean locked) throws IOException {
                ExtractOperationResult outcome = result;
                if (outcome == null || outcome == ExtractOperationResult.OK) return;
                switch (outcome) {
                    case WRONG_PASSWORD -> throw PasswordException.wrong(new IOException(outcome.name()));
                    case CRCERROR, DATAERROR, UNEXPECTED_END, DATA_AFTER_END -> {
                        // RAR 3 and encrypted headers can't tell a wrong password from damaged data.
                        if (locked) throw PasswordException.wrongOrDamaged(new IOException(outcome.name()));
                        throw new IOException(outcome == ExtractOperationResult.CRCERROR ? "checksum mismatch, the data is corrupt"
                                : "the data is corrupt");
                    }
                    case UNSUPPORTEDMETHOD -> throw new IOException("uses a compression method OpenZip can't read");
                    case UNAVAILABLE ->
                            throw new IOException("its data is missing; it may continue in another part of a multi-volume archive");
                    case HEADERS_ERROR -> throw new IOException("its header is damaged");
                    default -> throw new IOException("can't be read (" + outcome + ")");
                }
            }

            void push(byte[] data) throws SevenZipException {
                if (data.length == 0 || discard) return;
                offer(data.clone()); // the library reuses its buffer
            }

            void finish(ExtractOperationResult outcome) throws SevenZipException {
                result = outcome;
                if (!discard) offer(END);
            }

            private void offer(byte[] chunk) throws SevenZipException {
                try {
                    while (!chunks.offer(chunk, POLL_MS, TimeUnit.MILLISECONDS)) {
                        if (aborted) throw new SevenZipException("stopped");
                        if (discard) return;
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new SevenZipException("interrupted");
                }
            }
        }
    }
}
