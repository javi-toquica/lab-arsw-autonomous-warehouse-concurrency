package edu.eci.arsw.warehouse.core;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class WarehouseStatistics {

    private final AtomicInteger processedParcels = new AtomicInteger(0);
    private final AtomicLong totalProcessingMillis = new AtomicLong(0);

    public void recordProcessed(long elapsedMillis) {
        processedParcels.incrementAndGet();
        totalProcessingMillis.addAndGet(elapsedMillis);
    }

    public int processedParcels() {
        return processedParcels.get();
    }

    public long totalProcessingMillis() {
        return totalProcessingMillis.get();
    }
}