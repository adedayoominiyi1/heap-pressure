package com.gradle.metrics.heap;

import reactor.core.publisher.Flux;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.util.Locale;

/**
 * Run with -Xmx256M or similar
 */
public class HeapPressureSample {
    private static final ThreadLocal<DecimalFormat> DECIMAL = ThreadLocal.withInitial(() -> {
        // the following will ensure a dot ('.') as decimal separator
        DecimalFormatSymbols otherSymbols = new DecimalFormatSymbols(Locale.US);
        return new DecimalFormat("##0.0#####",otherSymbols);
    });

    public static String humanReadableByteCountSI(long bytes) {
        String s = bytes < 0 ? "-" : "";
        long b = bytes == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(bytes);
        return b < 1000L ? bytes + " B"
                : b < 999_950L ? String.format("%s%.1f kB", s, b / 1e3)
                : (b /= 1000) < 999_950L ? String.format("%s%.1f MB", s, b / 1e3)
                : (b /= 1000) < 999_950L ? String.format("%s%.1f GB", s, b / 1e3)
                : (b /= 1000) < 999_950L ? String.format("%s%.1f TB", s, b / 1e3)
                : (b /= 1000) < 999_950L ? String.format("%s%.1f PB", s, b / 1e3)
                : String.format("%s%.1f EB", s, b / 1e6);
    }

    public static void main(String[] args) {
        JvmHeapPressureMetrics metrics = new JvmHeapPressureMetrics(Duration.ofSeconds(10), Duration.ofSeconds(2));

        System.out.println("\n=================> Heap pressure test started..\n");
        Flux.interval(Duration.ofNanos(10))
                .doOnEach(n -> {
                    int[] ignored = new int[10000 + n.get().intValue() * 100];
                    if(n.get() % 1000 == 0) {
                        System.out.println("Free Mem: " + humanReadableByteCountSI(Runtime.getRuntime().freeMemory()) +
                                " Old Gen Pool Memory: " + DECIMAL.get().format(metrics.oldGenPoolUsedAfterGc() * 100) + "% " +
                                " GC Overhead: " + DECIMAL.get().format(metrics.gcOverhead() * 100) + "%");
                    }
                })
                .blockLast();
    }
}
