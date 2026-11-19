package me.piitex.openzip.archive;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Progress and cancellation for one long-running operation. The worker thread advances it and the UI reads it every
 * frame, so every method is safe from any thread and there are no callbacks to marshal onto the render thread.
 */
public final class Job {
    private final AtomicLong done = new AtomicLong();
    private volatile long total;
    private volatile String current = "";
    private volatile boolean cancelled;

    /**
     * Sets how many bytes the job will process, and resets the count.
     */
    public void start(long totalBytes) {
        total = Math.max(0, totalBytes);
        done.set(0);
    }

    public void advance(long bytes) {
        done.addAndGet(bytes);
    }

    public void setCurrent(String name) {
        current = name;
    }

    public String current() {
        return current;
    }

    public long done() {
        return done.get();
    }

    public long total() {
        return total;
    }

    /**
     * 0 to 1. An empty job (only folders, or empty files) reads as 0 until it is finished by the caller.
     */
    public double fraction() {
        long snapshot = total;
        return snapshot <= 0 ? 0 : Math.min(1.0, (double) done.get() / snapshot);
    }

    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Called by the workers between chunks.
     */
    public void throwIfCancelled() {
        if (cancelled) throw new CancellationException("Cancelled");
    }
}
