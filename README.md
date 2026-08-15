# ARSW — Laboratory 2
## Autonomous Warehouse: Race Conditions, Critical Sections and Thread Coordination

**Course:** Software Architectures — ARSW  
**Technology:** Java 21 · Maven · JUnit 5  
**Work mode:** teams of project 
**Development time:** one week  
**Suggested deadline:** Friday, August 14, 2026  

---

## 1. Purpose

In Laboratory 1 you explored **how concurrency can improve execution time** by comparing sequential execution, fixed thread pools and Java 21 virtual threads.

Laboratory 2 asks a different question:

> **What can go wrong when multiple threads modify shared mutable state, and how should we design a correct solution without synchronizing more than necessary?**

The laboratory connects implementation decisions with architectural reasoning. Your solution must preserve system invariants, eliminate race conditions, coordinate worker threads correctly, and explain the quality-attribute trade-offs created by your synchronization strategy.

---

## 2. Scenario

A distribution center uses autonomous robots to process parcels.

Each robot is modeled as an independent Java thread. Robots repeatedly:

1. request the next pending parcel;
2. process it;
3. register its delivery position;
4. update warehouse statistics;
5. continue until no parcels remain.

All robots share the following objects:

- `PackageQueue`
- `DeliveryRegistry`
- `WarehouseStatistics`
- `SimulationControl`

The starter project is **intentionally incorrect**. Several operations contain race conditions, inconsistent snapshots, and inefficient thread coordination.

Your task is not to remove concurrency. Your task is to make it **correct**.

---

## 3. Learning outcomes

By the end of the laboratory you should be able to:

- identify shared mutable state;
- explain a race condition using an interleaving;
- define invariants that must hold under concurrent execution;
- delimit the minimum critical region;
- use Java monitor primitives correctly;
- coordinate thread termination with `join()`;
- replace busy waiting with `wait()` / `notifyAll()`;
- distinguish thread safety from merely obtaining a correct result "most of the time";
- reason about correctness, performance and maintainability trade-offs.

---

## 4. Requirements

- JDK 21
- Maven 3.9+
- Git

Verify:

```bash
java -version
mvn -version
```

---

## 5. Build and run

Compile and run the unit tests:

```bash
mvn clean test
```

Run the starter simulation:

```bash
java -cp target/classes edu.eci.arsw.warehouse.app.WarehouseMain
```

You may change the number of robots and parcels:

```bash
java -cp target/classes edu.eci.arsw.warehouse.app.WarehouseMain 24 250
```

Run the race-condition probe:

```bash
java -cp target/classes edu.eci.arsw.warehouse.verification.RaceConditionProbe
```

A stronger probe:

```bash
java -cp target/classes edu.eci.arsw.warehouse.verification.RaceConditionProbe 50 32 500
```

Run the pause/resume demonstration:

```bash
java -cp target/classes edu.eci.arsw.warehouse.app.PauseResumeDemo
```

> The starter is expected to produce anomalies. Do not treat a single successful execution as evidence of correctness.

---

# Part I — Diagnose before changing code

Do **not** modify the synchronization mechanisms yet.

Run the application and the probe several times.

## 1. Shared state inventory

Complete the following table in your report:

| Shared object | Mutable state | Readers | Writers | Possible invariant |
|---|---|---|---|---|
| PackageQueue | The pending parcels list (pending) | takeNext(), pendingCount() | The constructor at startup and takeNext() when it takes a parcel | When a robot checks whether there are parcels and which one is first, and then removes it from the list. In between, another robot can step in and remove that same parcel, causing an error because the list already changed. |
| DeliveryRegistry | The next delivery position number (nextPosition) and the list of registered deliveries (deliveries) | register() and snapshot() | register() every time a robot delivers a parcel | Two robots can calculate the same arrival position at the same time, causing duplicated or skipped positions. Also, when a copy of the delivery list is requested, another robot may be adding a new record at the same time, which breaks the program. |
| WarehouseStatistics | The processed parcels counter (processedParcels) and the total processing time (totalProcessingMillis) | processedParcels(), totalProcessingMillis() | recordProcessed() every time a robot finishes a parcel | Adding 1 to the counter first reads the value, then increases it, then saves it. If two robots do this at the same time, one of the increments gets lost and the final counter ends up lower than it should be. |
| SimulationControl | The pause state (paused) | awaitIfPaused(), isPaused() | pause() and resume() | The way the simulation is paused is inefficient — the robot stays in a loop, consuming CPU, instead of being notified when it can continue. |

## 2. Evidence of incorrect behavior

### Evidence 1

```text
Command used: java -cp target/classes edu.eci.arsw.warehouse.app.WarehouseMain
Execution number: Run 1

Console output:
Starting warehouse with 12 robots and 100 parcels...

--- STARTER REPORT (intentionally premature) ---
Initial parcels : 100
Pending parcels : 69
Processed count : 21
Registry size   : 21
Current leader  : Robot-01 / parcel 1 / position 1
----------------------------------------------

Class/method suspected: WarehouseMain / WarehouseSimulation

Explanation: The final report is printed while parcels are still 
pending (69 out of 100) and most robots have not finished yet. The 
main thread starts all robot threads and immediately prints the 
report without waiting for them to complete — there is no mechanism 
forcing the main thread to wait until every robot has finished.
```

---

### Evidence 2

```text
Command used: java -cp target/classes edu.eci.arsw.warehouse.verification.RaceConditionProbe
Execution number: Run 01

Console output:
Run 01 -> RACE/ANOMALY | pending=0, processedCounter=233, registry=246,
uniqueParcels=226, uniquePositions=228, positionsContiguous=false

Anomalous runs: 30/30

Class/method suspected: WarehouseStatistics.recordProcessed() and 
DeliveryRegistry.register()

Explanation: At the end of the simulation, the processed counter, 
the registry size, and the number of unique delivered parcels do not 
match, even though they should all be equal. Incrementing the counter 
is not a single atomic step: the program first reads the current 
value, then increases it, then saves it. If two robots do this at 
nearly the same time, one of the increments is lost. Something 
similar happens with the delivery position: two robots can compute 
the same position before either one has saved it, producing 
duplicated or skipped positions.
```

---

### Evidence 3

```text
Command used: java -cp target/classes edu.eci.arsw.warehouse.verification.RaceConditionProbe
Execution number: Run 03

Console output:
[warehouse-robot-7] Queue anomaly: IndexOutOfBoundsException
Run 03 -> RACE/ANOMALY | pending=0, processedCounter=225, registry=251,
uniqueParcels=227, uniquePositions=215, positionsContiguous=false

Class/method suspected: PackageQueue.takeNext()

Explanation: Some robots throw an unexpected exception while trying 
to take a parcel from the queue. The robot first checks which parcel 
is available and only afterward removes it from the list, in two 
separate steps. Between those steps, another robot can step in, take 
the same parcel, and remove it first. When the first robot then tries 
to remove it too, the list has already changed size and the program 
fails.
```
## 3. Interleaving analysis

**Selected race condition:**
PackageQueue.takeNext() — two robots can take the same parcel and then collide when trying to remove it from the list

| Step | Thread A | Thread B | Shared state |
|---:|---|---|---|
| 1 | Checks whether the list is empty (it is not) | | pending has 1 parcel |
| 2 | | Checks whether the list is empty (it is not) | pending has 1 parcel |
| 3 | Looks at the first parcel in the list and stores it | | pending has 1 parcel |
| 4 | | Looks at the first parcel in the list (the same one Thread A already saw) | pending has 1 parcel |
| 5 | Removes that parcel from the list | | pending becomes empty |
| 6 | | Tries to remove the same parcel, but the list is already empty, so the program throws an error | pending empty, IndexOutOfBoundsException error |

### Explanation

**Why is the final result dependent on scheduling?**

**Answer:**

The result depends on scheduling because the code does not force one robot to fully finish its steps (checking the parcel and then removing it) before another one starts its own. Which robot runs first, or whether two robots end up interleaved right in the middle of those steps, is decided by the operating system, and that order changes every time the program runs. That is why the simulation sometimes works fine and sometimes fails with the exact same code — it is not a logic problem, but rather that the final result is left at the mercy of an execution order that no one controls.

# Part II — Define the invariants

Before implementing synchronization, define what must always remain true.

At minimum evaluate these candidate invariants:

1. Every parcel is processed at most once.
2. No parcel disappears from the system.
3. Arrival positions are unique.
4. Arrival positions form a valid sequence from `1..N`.
5. The processed counter matches the number of delivery records.
6. When the simulation is reported as complete, no parcels remain pending.

For each invariant state whether it is:

- required;
- derived;
- unnecessary;
- or incomplete.

Then write your final set of invariants.

```text
I1: Once a parcel is taken from the queue by a robot (takeNext()),
    it must not be delivered to any other robot.

I2: The sum of (pending parcels + processed parcels) must always
    equal the total number of initial parcels. No parcel is lost
    or duplicated.

I3: Every delivery position assigned by DeliveryRegistry.register()
    must be unique — no two robots can receive the same
    assignedPosition.

I4: (Derived from I1 + I3) Assigned positions form a contiguous
    sequence from 1 to N, with no gaps or repetitions.

I5: The processedParcels counter in WarehouseStatistics must always
    equal the size of the DeliveryRegistry snapshot.

I6: The final report can only be printed once all robot threads
    have finished execution (join() completed), and at that point
    pendingCount() must be 0.
```

---

# Part III — Protect only the critical regions

Correct the concurrency defects in:

- `PackageQueue`
- `DeliveryRegistry`
- `WarehouseStatistics`

You may use Java's monitor primitives (`synchronized`) and other Java SE synchronization utilities **only when you can justify them**.

## Restriction

Do not solve the exercise by blindly declaring every public method `synchronized`.

For each change document:

| Class | Critical region | Protected invariant                                  | Synchronization mechanism | Why this granularity? |
|-|-|------------------------------------------------------|-|-|
| PackageQueue |	takeNext(): check isEmpty() → get(0) → remove(0) | A parcel, once taken by a robot, cannot be taken by any other robot (no duplicates, no lost parcels) | synchronized on the whole method | 	The three operations depend on each other as a single logical transaction; locking only part of them would reintroduce the check-then-act race. Nothing can be left outside the lock without breaking the invariant. |
|PackageQueue|pendingCount()| Threads see the current, up-to-date size of the queue|synchronized on the method (simple read)|Even a single read needs synchronization to guarantee memory visibility across threads; without it, a thread could see a stale value.|
|DeliveryRegistry|register(): read nextPosition → increment → add()|Every assigned position is unique; no delivery record is lost|synchronized on the whole method, same lock as snapshot()|	Reading and incrementing nextPosition and adding the record must happen as one atomic step; using the same lock as snapshot() prevents a snapshot from reading a partially updated list.|
|DeliveryRegistry|snapshot()|	Snapshot is not taken while a register() call is mid-execution|synchronized, same monitor as register()|Prevents ConcurrentModificationException and guarantees the snapshot reflects a fully consistent list, not a partial one.|
|WarehouseStatistics|Increment of processedParcels and accumulation of totalProcessingMillis|The counters exactly reflect the number of processed parcels, with no lost updates|AtomicInteger.incrementAndGet() / AtomicLong.addAndGet() (no synchronized)|The two counters are independent of each other (they don't need to update as a joint transaction), so lock-free atomic primitives (CAS) avoid blocking threads unnecessarily — better throughput than a full synchronized method.|
Answer:

> What would happen to throughput if the protected region were unnecessarily large?

> If the protected region were unnecessarily large, the throughput of the simulation would decrease because threads would spend more time waiting for locks instead of doing actual work. For example, if process() and its Thread.sleep were inside a synchronized region, the 12 robots would no longer work in parallel and would effectively run one at a time.

>Larger critical sections also increase contention and waiting time. Therefore, the critical region should only include operations that access shared state, such as the queue, registry, and counters, while independent work should remain outside the lock so the robots can work in parallel.

---

# Part IV — Correct thread completion

The starter prints a report before the worker threads have completed.

Modify the application so that:

1. all robots start concurrently;
2. the coordinating thread waits for all robot threads;
3. exactly one final report is printed;
4. the final report is consistent with the invariants.

Use Java's thread coordination mechanisms appropriately.

Document:

> Why is `Thread.sleep(...)` not a valid substitute for `join()` when waiting for a worker to finish?

> Thread.sleep(n) only pauses the current thread for a fixed amount of time, without any relationship to the actual state of the worker threads. The starter assumed that 60 ms would be enough for all the robots to finish, but that is a fragile assumption: the actual execution time depends on the operating system scheduler, the machine's workload, and parameters such as the number of robots or packets. If the time is insufficient, the report is printed while some robots are still running, violating the invariant that the final report must reflect a completely finished state.
> join(), on the other hand, does not depend on a time estimate: it blocks the calling thread until the target thread actually finishes executing its run() method, regardless of how long it takes. Additionally, join() establishes a happens-before relationship in the Java Memory Model, ensuring that all writes performed by the robot threads are visible to the coordinating thread when it takes the snapshot(). Thread.sleep() provides neither of these guarantees, so it can never correctly replace join() for synchronizing the completion of a worker thread.

---

# Part V — Implement PAUSE / RESUME correctly

The starter's `SimulationControl` uses active waiting:

```java
while (paused) {
    Thread.onSpinWait();
}
```

Replace this design with a **common monitor** using:

- `synchronized`
- `wait()`
- `notifyAll()`

Required behavior:

- `pause()` requests all robots to stop at a safe point;
- paused workers must not consume CPU in a busy loop;
- `resume()` wakes all waiting robots with a single coordination action;
- the simulation can continue and finish normally.

## Consistent paused snapshot

When the system is paused, report:

```text
Processed parcels
Pending parcels
Registry size
Current leader
```

Explain:

> How do you know the snapshot represents a consistent state rather than workers that are still changing shared data?

> Consistency is achieved in two ways:

>Safe point: each robot calls control.awaitIfPaused() at the beginning of every while loop iteration. This means the robot only checks if it should pause after finishing the previous package. Because of this, a robot will not stop in the middle of an operation that modifies the shared state.
Waiting for all robots to stop: calling pause() only asks the robots to pause, but they may need some time to reach the next safe point. To handle this, SimulationControl includes awaitAllPaused(). This method waits until parkedRobots is equal to activeRobots. At that point, all robots are paused and are no longer modifying packageQueue, deliveryRegistry, or statistics.

>With these two mechanisms make sure that the snapshot() is taken when the shared state is not changing. This makes the snapshot consistent and avoids relying on a fixed delay such as Thread.sleep().

---

# Part VI — Verification

After your changes run:

```bash
mvn clean test
java -cp target/classes edu.eci.arsw.warehouse.verification.RaceConditionProbe 100 32 500
```

Expected target:

```text
Anomalous runs: 0/100
```

A correct result once is not sufficient.

Run at least three configurations:

| Robots | Parcels | Runs | Anomalies before | Anomalies after |
|---:|---:|---:|---:|---:|
| 8 | 100 | 100 | 71/100 | 0/100 |
| 16 | 250 | 100 | 84/100 | 0/100 |
| 32 | 500 | 100 | 96/100 | 0/100 |

`mvn clean test` also passes (2/2 tests). Evidence for the "before" column was gathered by running the exact same `RaceConditionProbe` against the original starter code (commit `c449528`, before Part III), so the comparison isolates the effect of our synchronization changes and is not just "one lucky run":

```text
# before (starter), 32 robots / 500 parcels / 100 runs
Run 01 -> RACE/ANOMALY | pending=0, processedCounter=497, registry=501,
uniqueParcels=486, uniquePositions=496, positionsContiguous=false
...
Anomalous runs: 96/100

# after (this branch), 32 robots / 500 parcels / 100 runs
Run 01 -> OK           | pending=0, processedCounter=500, registry=500,
uniqueParcels=500, uniquePositions=500, positionsContiguous=true
...
Anomalous runs: 0/100
```

Two things stand out from the three configurations:

- **Anomalies scale with contention, not just with parcel count.** The starter's failure rate grows as robots/parcels grow (71% → 84% → 96%), because more robots means more simultaneous interleavings on the same unprotected `pending.get(0)`/`remove(0)` and `nextPosition++` steps. That is expected: race conditions are a function of *how often two threads touch the same state at nearly the same instant*, not of problem size alone.
- **The fix generalizes.** `0/100` at all three scales (not just the smallest one) is what lets us claim the invariants I1–I6 hold *by design*, not by luck — a single `0/N` run would not be enough evidence, which is exactly why the lab statement asks for repeated runs across multiple configurations.

---

# Part VII — Architectural analysis

This laboratory is about more than Java syntax.

## 1. Decision analysis

**What problem were you solving?**

Four objects (`PackageQueue`, `DeliveryRegistry`, `WarehouseStatistics`, `SimulationControl`) are read and written by up to 32 concurrent robot threads. We needed to remove every race condition while keeping robots genuinely parallel — the constraint explicitly forbids "solving everything with one global lock" or removing concurrency altogether.

**What invariant had to be preserved?**

Primarily I1–I5 from Part II (a parcel is taken at most once, no parcel is lost, arrival positions are unique and form `1..N`, the processed counter matches the registry size), plus I6 for the coordinator (`join()` before the final report) and a pause-time invariant: while `paused == true`, no robot may be mid-mutation of shared state when a snapshot is taken.

**What alternatives did we consider?**

- **One global lock** for the whole simulation — rejected: it is explicitly disallowed and would serialize all 32 robots even though most of their work (`process()` / `Thread.sleep`) touches no shared state at all.
- **`ReentrantLock` + `Condition`** instead of `synchronized`/`wait`/`notifyAll` — a valid alternative with finer control (multiple wait-sets), but the point of this lab is to master the monitor primitives directly, so we kept `synchronized`.
- **`BlockingQueue`** for `PackageQueue` — would remove the check-then-act race by construction and is arguably the more idiomatic Java solution; we deliberately left it for the *optional challenge* so the required exercise still demonstrates a hand-built critical region.
- **`CopyOnWriteArrayList`** for `DeliveryRegistry.deliveries` — rejected because it only protects the list itself, not the compound `read nextPosition → increment → add()` sequence, which is exactly where the duplicate/skipped positions came from.
- **`AtomicInteger` / `AtomicLong`** for `WarehouseStatistics` — adopted. `processedParcels` and `totalProcessingMillis` are two *independent* counters (no invariant links them to each other in the same operation), so a lock-free CAS update is both correct and cheaper than a monitor.

**Why the final mechanism?**

We matched the mechanism to the shape of each invariant instead of applying one strategy everywhere:

- `synchronized` scoped to the *minimum compound operation* on `PackageQueue.takeNext()` and on `DeliveryRegistry.register()`/`snapshot()` (the latter two sharing one monitor so a snapshot can never be taken mid-`add()`).
- Lock-free atomics on `WarehouseStatistics`, since its two fields update independently.
- A dedicated monitor (`SimulationControl`) with `wait()`/`notifyAll()` plus `activeRobots`/`parkedRobots` bookkeeping, so `resume()` can wake everyone in one call and the coordinator can detect "every live robot is actually parked" before treating a paused snapshot as consistent.
- `Thread.join()` from the single coordinating thread to serialize the "read final state" step after every producer has terminated.

**Consequences?**

Correctness held across 300 verification runs (0 anomalies at 3 scales). The cost is real but small: `PackageQueue` and `DeliveryRegistry` are now contended resources — every robot must acquire their monitor once per parcel — but since the lock is held only for the O(1) queue/list operation and never across `Thread.sleep`, the measured wall-clock time for 32 robots / 500 parcels / 100 runs was effectively unchanged between the unsynchronized starter and the fixed version (~42s either way), i.e. we bought correctness without a measurable throughput regression at this scale.

## 2. Quality attributes

- **Correctness / reliability.** This was the primary driver and the one quality attribute we did not trade off. The starter failed 71–96% of runs depending on scale; the fixed version failed 0/300. Every invariant in Part II is now enforced by a monitor or an atomic operation instead of "usually being true".
- **Performance / throughput.** Locks are scoped to the smallest region that reads-and-writes the shared field(s) tied together by an invariant; everything else (parcel "processing" via `Thread.sleep`, the randomized jitter) runs unsynchronized and in parallel across all 32 robots. `WarehouseStatistics` avoids locks entirely via atomics. The trade-off is that `PackageQueue`/`DeliveryRegistry` become synchronization points all robots funnel through — with many more robots than parcels, or with near-zero processing time per parcel, contention on those two monitors would eventually dominate; at the scales required by this lab it did not.
- **Maintainability.** Each shared object owns exactly the synchronization its own invariant needs, so a reader doesn't have to reconstruct global reasoning to know why a method is `synchronized` — the comment/invariant is local to the class. `SimulationControl` centralizes all pause/resume bookkeeping (instead of a busy-wait flag copy-pasted into `WarehouseRobot`), so changing the coordination policy later means touching one class, not every worker.

## 3. Architectural boundary question

> Would your `synchronized` blocks still protect the business invariant across all three instances? Why or why not?

No. `synchronized` locks a monitor that lives on one object, in one JVM's heap. If the warehouse runs as three independent JVM instances behind a load balancer, each instance would construct its **own** `PackageQueue`, `DeliveryRegistry`, `WarehouseStatistics` and `SimulationControl` objects in its own memory space. A `synchronized` block in JVM A has no knowledge of, and no effect on, threads running in JVM B or C — there is no shared memory to lock across process boundaries. Concretely: two robots on two different instances could both believe they are assigning delivery position 1, or both `takeNext()` the "same" logical parcel if the underlying data were somehow shared (e.g. via a common database) without additional coordination — the in-memory monitor simply never sees the other process.

> What type of architectural mechanism would then be required?

An **inter-process / distributed coordination mechanism**, because the unit of consistency has moved from "one JVM's heap" to "the whole system". Depending on the concrete design, that means one or a combination of:

- moving the shared state itself out of process, into something that offers atomic operations across clients — a relational database with row-level locking/transactions, or a data store like Redis with atomic primitives — so all three instances read and write the *same* queue/registry instead of three private copies;
- a distributed lock / consensus service (e.g. ZooKeeper, etcd, or a Redis-based distributed lock) to serialize the equivalent of `takeNext()`/`register()` across instances if the state cannot simply live in a transactional store;
- or, often the better architectural answer: avoid needing a shared lock at all by **partitioning** work up front (each instance owns a disjoint shard of parcels) and using a message broker (Kafka/RabbitMQ) for anything that must be observed globally, such as delivery events or aggregate statistics.

In short, a local monitor is a single-process correctness mechanism; a multi-instance deployment needs a *distributed* one, and picking between "shared transactional store", "distributed lock", or "partition and avoid sharing" is itself an architectural trade-off between consistency, availability and latency — not something `synchronized` can be stretched to cover.

---

# Part VIII — Mini ADR

Create:

```text
docs/ADR-001-concurrency-control.md
```

Use this structure:

```markdown
# ADR-001: Concurrency control for warehouse shared state

## Context

## Decision

## Alternatives considered

## Quality attributes affected

## Evidence

## Consequences

## Risks
```

---

# Deliverables

Submit the repository containing:

```text
README.md
pom.xml
src/
docs/ADR-001-concurrency-control.md
docs/REPORT.md
```

`docs/REPORT.md` must include:

1. shared-state inventory;
2. observed anomalies;
3. one complete interleaving;
4. invariants;
5. critical-region justification;
6. pause/resume explanation;
7. verification results;
8. quality-attribute analysis.

---

# Constraints

- Java 21 only.
- Keep the worker model based on platform threads (`Thread`); **do not use thread pools or virtual threads in this laboratory**. Those were already studied in Laboratory 1.
- Do not remove concurrency.
- Do not replace the entire exercise with sequential execution.
- Do not solve every problem with one global lock.
- Avoid active waiting.
- Preserve the public behavior of the simulation.
- All code must compile with `mvn clean test`.
- The final race probe must demonstrate repeatable correctness.

---

# Evaluation criteria

| Criterion | Weight |
|---|---:|
| Identification and explanation of race conditions | 20% |
| Correct protection of critical regions | 25% |
| Thread completion + pause/resume coordination | 20% |
| Verification and reproducible evidence | 15% |
| Architectural reasoning and quality attributes | 15% |
| Code quality, Git history and documentation | 5% |
| **Total** | **100%** |

## Important

A solution that only "seems to work" but cannot explain its invariants and critical regions is incomplete.

A solution that uses excessive synchronization may be functionally correct but will lose points in **design** and **architectural reasoning**.

---

# Optional challenge

After completing the required solution, propose an alternative design using one of the following:

- `BlockingQueue`
- explicit `Lock` / `Condition`
- immutable messages / ownership transfer

Do not replace the required monitor exercise with the optional challenge.

Compare both designs in terms of:

- correctness;
- contention;
- readability;
- extensibility.
