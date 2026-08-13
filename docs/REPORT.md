# Laboratory 2 Report

## 1. Shared-state inventory

| Shared object | Mutable state | Readers | Writers | Possible invariant |
| :--- | :--- | :--- | :--- | :--- |
| **PackageQueue** | `pending` list of parcels | `takeNext()`, `pendingCount()` | `takeNext()` | No parcel is skipped or processed twice; the list size accurately reflects remaining items. |
| **DeliveryRegistry** | `nextPosition` integer, `deliveries` list | `snapshot()` | `register()` | `nextPosition` increments sequentially without duplicates; it always equals `deliveries.size() + 1`. |
| **WarehouseStatistics** | `processedParcels` int, `totalProcessingMillis` long | `processedParcels()`, `totalProcessingMillis()` | `recordProcessed()` | `processedParcels` accurately reflects the exact number of parcels processed without lost updates. |
| **SimulationControl** | `paused` boolean | `isPaused()`, `awaitIfPaused()` | `pause()`, `resume()` | The pause state is consistent, safely published, and visible to all worker threads simultaneously. |


## 2. Observed anomalies

**Evidence 1**
*   **Command used:** `java -cp target/classes edu.eci.arsw.warehouse.app.WarehouseMain 24 250`
*   **Execution number:** 1
*   **Relevant console output:** `[warehouse-robot-4] Queue anomaly: IndexOutOfBoundsException`
*   **Class/method suspected:** `PackageQueue.takeNext()`
*   **Explanation:** This is a classic "check-then-act" race condition. Multiple threads check `!pending.isEmpty()` and evaluate it to true. One thread removes the last element, and when the subsequent thread reaches `pending.remove(0)`, the list is empty, throwing an `IndexOutOfBoundsException`.

**Evidence 2**
*   **Command used:** `java -cp target/classes edu.eci.arsw.warehouse.verification.RaceConditionProbe 50 32 500`
*   **Execution number:** 1 (Run 01)
*   **Relevant console output:** `Run 01 -> RACE/ANOMALY | pending=0, processedCounter=489, registry=495, uniqueParcels=495, uniquePositions=480, positionsContiguous=false`
*   **Class/method suspected:** `WarehouseStatistics.recordProcessed()`
*   **Explanation:** The `processedCounter` (489) does not match the registry size (495). The counter suffers from "lost updates" because `processedParcels = current + 1` is not atomic and includes a `Thread.yield()`. Multiple threads read the same `current` value before writing back the incremented value, causing the counter to fall behind.

**Evidence 3**
*   **Command used:** `java -cp target/classes edu.eci.arsw.warehouse.verification.RaceConditionProbe`
*   **Execution number:** 3 (Run 03)
*   **Relevant console output:** `Run 03 -> RACE/ANOMALY | ... uniquePositions=230, positionsContiguous=false`
*   **Class/method suspected:** `DeliveryRegistry.register()`
*   **Explanation:** The probe reveals that out of 250 parcels, there are only 230 unique arrival positions, meaning multiple parcels were assigned the exact same position. This happens because `nextPosition` is read into `assignedPosition`, the thread yields, and then increments. Multiple threads capture the same `nextPosition` before any of them increment it.


## 3. Interleaving analysis

**Target:** `WarehouseStatistics.recordProcessed()` lost update race condition.

| Step | Thread A (Robot 1) | Thread B (Robot 2) | Shared state (`processedParcels`) |
| :--- | :--- | :--- | :--- |
| 1 | Reads `current = processedParcels` (value is 10) | --- | 10 |
| 2 | `Thread.yield()` executes | Reads `current = processedParcels` (value is 10) | 10 |
| 3 | --- | `Thread.yield()` executes | 10 |
| 4 | Writes `processedParcels = 10 + 1` (11) | --- | 11 |
| 5 | --- | Writes `processedParcels = 10 + 1` (11) | 11 (Lost Update!) |

**Answer:**
**Why is the final result dependent on scheduling?**
The final result depends on the exact sequence of CPU scheduling because the operation is composed of three distinct steps: read, modify, and write. If the operating system scheduler interleaves Thread B's "read" step *after* Thread A reads but *before* Thread A writes, both threads will calculate their increment based on the same stale data. If the scheduler happens to let Thread A complete all three steps before Thread B starts, the result is correct. This non-deterministic scheduling causes the race condition.

## 4. System invariants

## 5. Critical regions and synchronization decisions

## 6. Thread completion and pause/resume coordination

## 7. Verification results

## 8. Quality-attribute analysis
