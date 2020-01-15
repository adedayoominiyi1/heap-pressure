package com.gradle.metrics.heap;

import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.GcInfo;

import javax.management.ListenerNotFoundException;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.lang.management.*;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.function.ToLongFunction;

/**
 * Provides methods to access measurements of low pool memory and heavy GC overhead as described in
 * <a href="https://www.jetbrains.com/help/teamcity/teamcity-memory-monitor.html">TeamCity's Memory Monitor</a>.
 */
public class JvmHeapPressureMetrics implements AutoCloseable {
    private final java.util.List<Runnable> notificationListenerCleanUpRunnables = new CopyOnWriteArrayList<>();

    private final long startOfMonitoring = System.nanoTime();
    private final Duration lookback;
    private final TimeWindowSum gcPauseSum;
    private final DoubleAdder lastOldGenUsageAfterGc = new DoubleAdder();

    private String oldGenPoolName;

    public JvmHeapPressureMetrics(Duration lookback, Duration testEvery) {
        this.lookback = lookback;
        this.gcPauseSum = new TimeWindowSum((int) lookback.dividedBy(testEvery.toMillis()).toMillis(), testEvery);

        for (MemoryPoolMXBean mbean : ManagementFactory.getMemoryPoolMXBeans()) {
            String name = mbean.getName();
            if (isOldGenPool(name)) {
                oldGenPoolName = name;
            }
        }

        monitor();
    }

    /**
     * @return The percentage of old gen heap used after the last GC event, in the range [0..1].
     */
    public double oldGenPoolUsedAfterGc() {
        return lastOldGenUsageAfterGc.sum();
    }

    /**
     * @return An approximation of the percent of CPU time used by GC activities over the last {@link #lookback} period
     * or since monitoring began, whichever is shorter, in the range [0..1].
     */
    public double gcOverhead() {
        double overIntervalMillis = Math.min(System.nanoTime() - startOfMonitoring, lookback.toNanos()) / 1e6;
        return gcPauseSum.poll() / overIntervalMillis;
    }

    private void monitor() {
        double maxOldGen = getOldGen().map(mem -> getUsageValue(mem, MemoryUsage::getMax)).orElse(0.0);

        for (GarbageCollectorMXBean mbean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (!(mbean instanceof NotificationEmitter)) {
                continue;
            }
            NotificationListener notificationListener = (notification, ref) -> {
                if (!notification.getType().equals(GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION)) {
                    return;
                }

                CompositeData cd = (CompositeData) notification.getUserData();
                GarbageCollectionNotificationInfo notificationInfo = GarbageCollectionNotificationInfo.from(cd);

                String gcCause = notificationInfo.getGcCause();
                GcInfo gcInfo = notificationInfo.getGcInfo();
                long duration = gcInfo.getDuration();

                if (!isConcurrentPhase(gcCause)) {
                    gcPauseSum.record(duration);
                }

                Map<String, MemoryUsage> after = gcInfo.getMemoryUsageAfterGc();

                if (oldGenPoolName != null) {
                    final long oldAfter = after.get(oldGenPoolName).getUsed();

                    lastOldGenUsageAfterGc.reset();
                    lastOldGenUsageAfterGc.add(oldAfter / maxOldGen);
                }
            };
            NotificationEmitter notificationEmitter = (NotificationEmitter) mbean;
            notificationEmitter.addNotificationListener(notificationListener, null, null);
            notificationListenerCleanUpRunnables.add(() -> {
                try {
                    notificationEmitter.removeNotificationListener(notificationListener);
                } catch (ListenerNotFoundException ignore) {
                }
            });
        }
    }

    public void close() {
        notificationListenerCleanUpRunnables.forEach(Runnable::run);
    }

    private static boolean isOldGenPool(String name) {
        return name.endsWith("Old Gen") || name.endsWith("Tenured Gen");
    }

    private static boolean isConcurrentPhase(String cause) {
        return "No GC".equals(cause);
    }

    private static Optional<MemoryPoolMXBean> getOldGen() {
        return ManagementFactory
                .getPlatformMXBeans(MemoryPoolMXBean.class)
                .stream()
                .filter(JvmHeapPressureMetrics::isHeap)
                .filter(mem -> isOldGenPool(mem.getName()))
                .findAny();
    }

    private static boolean isHeap(MemoryPoolMXBean memoryPoolBean) {
        return MemoryType.HEAP.equals(memoryPoolBean.getType());
    }

    static double getUsageValue(MemoryPoolMXBean memoryPoolMXBean, ToLongFunction<MemoryUsage> getter) {
        MemoryUsage usage = getUsage(memoryPoolMXBean);
        if (usage == null) {
            return Double.NaN;
        }
        return getter.applyAsLong(usage);
    }

    private static MemoryUsage getUsage(MemoryPoolMXBean memoryPoolMXBean) {
        try {
            return memoryPoolMXBean.getUsage();
        } catch (InternalError e) {
            // Defensive for potential InternalError with some specific JVM options. Based on its Javadoc,
            // MemoryPoolMXBean.getUsage() should return null, not throwing InternalError, so it seems to be a JVM bug.
            return null;
        }
    }
}
