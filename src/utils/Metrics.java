package utils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class Metrics {
    private static final long START_TIME = System.currentTimeMillis();
    private static final AtomicLong TOTAL_REQUESTS = new AtomicLong(0);
    private static final AtomicInteger ACTIVE_CONNECTIONS = new AtomicInteger(0);
    private static final Map<Integer, AtomicLong> STATUS_CODES = new ConcurrentHashMap<>();

    public static void incrementRequests() {
        TOTAL_REQUESTS.incrementAndGet();
    }

    public static void incrementActiveConnections() {
        ACTIVE_CONNECTIONS.incrementAndGet();
    }

    public static void decrementActiveConnections() {
        ACTIVE_CONNECTIONS.decrementAndGet();
    }

    public static void recordResponseStatus(int status) {
        STATUS_CODES.computeIfAbsent(status, k -> new AtomicLong(0)).incrementAndGet();
    }

    public static long getUptimeMs() {
        return System.currentTimeMillis() - START_TIME;
    }

    public static long getTotalRequests() {
        return TOTAL_REQUESTS.get();
    }

    public static int getActiveConnections() {
        return ACTIVE_CONNECTIONS.get();
    }

    public static Map<Integer, Long> getStatusCodeCounts() {
        Map<Integer, Long> counts = new ConcurrentHashMap<>();
        for (Map.Entry<Integer, AtomicLong> entry : STATUS_CODES.entrySet()) {
            counts.put(entry.getKey(), entry.getValue().get());
        }
        return counts;
    }
}
