package com.gradle.metrics.heap;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An implementation of a moving-window sum based on a configurable ring buffer.
 */
public class TimeWindowSum {
    private static final AtomicIntegerFieldUpdater<TimeWindowSum> rotatingUpdater =
            AtomicIntegerFieldUpdater.newUpdater(TimeWindowSum.class, "rotating");

    private final long durationBetweenRotatesMillis;
    private AtomicLong[] ringBuffer;
    private int currentBucket;
    private volatile long lastRotateTimestampMillis;

    @SuppressWarnings({"unused", "FieldCanBeLocal"})
    private volatile int rotating = 0; // 0 - not rotating, 1 - rotating

    public TimeWindowSum(int bufferLength, Duration expiry) {
        this.durationBetweenRotatesMillis = expiry.toMillis();
        this.lastRotateTimestampMillis = System.currentTimeMillis();
        this.currentBucket = 0;

        this.ringBuffer = new AtomicLong[bufferLength];
        for (int i = 0; i < bufferLength; i++) {
            this.ringBuffer[i] = new AtomicLong();
        }
    }

    /**
     * For use by timer implementations.
     *
     * @param sampleMillis The value to record, in milliseconds.
     */
    public void record(long sampleMillis) {
        rotate();
        for (AtomicLong sum : ringBuffer) {
            sum.addAndGet(sampleMillis);
        }
    }

    /**
     * @return The sum, in milliseconds.
     */
    public double poll() {
        rotate();
        synchronized (this) {
            return ringBuffer[currentBucket].get();
        }
    }

    private void rotate() {
        long timeSinceLastRotateMillis = System.currentTimeMillis() - lastRotateTimestampMillis;
        if (timeSinceLastRotateMillis < durationBetweenRotatesMillis) {
            // Need to wait more for next rotation.
            return;
        }

        if (!rotatingUpdater.compareAndSet(this, 0, 1)) {
            // Being rotated by other thread already.
            return;
        }

        try {
            int iterations = 0;
            synchronized (this) {
                do {
                    ringBuffer[currentBucket].set(0);
                    if (++currentBucket >= ringBuffer.length) {
                        currentBucket = 0;
                    }
                    timeSinceLastRotateMillis -= durationBetweenRotatesMillis;
                    lastRotateTimestampMillis += durationBetweenRotatesMillis;
                } while (timeSinceLastRotateMillis >= durationBetweenRotatesMillis && ++iterations < ringBuffer.length);
            }
        } finally {
            rotating = 0;
        }
    }
}
