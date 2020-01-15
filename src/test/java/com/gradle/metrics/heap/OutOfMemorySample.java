package com.gradle.metrics.heap;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.util.Locale;

/**
 * Run with -Xmx256M or similar
 */
public class OutOfMemorySample {
    private static final ThreadLocal<DecimalFormat> DECIMAL = ThreadLocal.withInitial(() -> {
        // the following will ensure a dot ('.') as decimal separator
        DecimalFormatSymbols otherSymbols = new DecimalFormatSymbols(Locale.US);
        return new DecimalFormat("##0.0#####",otherSymbols);
    });

    public static void main(String[] args) throws InterruptedException {
        JvmHeapPressureMetrics metrics = new JvmHeapPressureMetrics(Duration.ofSeconds(10), Duration.ofSeconds(2));

        int iteratorValue = 10;
        System.out.println("\n=================> OOM test started..\n");
        for (int outerIterator = 1; outerIterator < 20; outerIterator++) {
            System.out.println("Iteration " + outerIterator + " Free Mem: " + Runtime.getRuntime().freeMemory() +
                    " Old Gen Pool Memory: " + DECIMAL.get().format(metrics.oldGenPoolUsedAfterGc() * 100) + "% " +
                    " GC Overhead: " + DECIMAL.get().format(metrics.gcOverhead() * 100) + "%");

            int loop1 = 2;
            int[] memoryFillIntVar = new int[iteratorValue];
            // feel memoryFillIntVar array in loop..
            do {
                memoryFillIntVar[loop1] = 0;
                loop1--;
            } while (loop1 > 0);
            iteratorValue = iteratorValue * 2;

            System.gc();

            System.out.println("\nRequired Memory for next loop: " + iteratorValue);

            Thread.sleep(1000);
        }
    }
}
