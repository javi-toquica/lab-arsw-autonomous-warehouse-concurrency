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
| `PackageQueue` |  |  |  |  |
| `DeliveryRegistry` |  |  |  |  |
| `WarehouseStatistics` |  |  |  |  |
| `SimulationControl` |  |  |  |  |

## 2. Evidence of incorrect behavior

Record at least **three different anomalies** observed during execution.

For each anomaly include:

- command used;
- execution number;
- relevant console output;
- class/method suspected;
- explanation.

### Evidence 1

```text
<your evidence>
```

### Evidence 2

```text
<your evidence>
```

### Evidence 3

```text
<your evidence>
```

## 3. Interleaving analysis

Choose one race condition and describe a possible interleaving.

Example format:

| Step | Thread A | Thread B | Shared state |
|---:|---|---|---|
| 1 |  |  |  |
| 2 |  |  |  |
| 3 |  |  |  |
| 4 |  |  |  |

Answer:

**Why is the final result dependent on scheduling?**

> _Write your answer here._

---

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
I1:
I2:
I3:
...
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
| 8 | 100 |  |  |  |
| 16 | 250 |  |  |  |
| 32 | 500 |  |  |  |

---

# Part VII — Architectural analysis

This laboratory is about more than Java syntax.

## 1. Decision analysis

For your main synchronization decision answer:

- What problem were you solving?
- What invariant had to be preserved?
- What alternatives did you consider?
- Why did you choose the final mechanism?
- What are its consequences?

## 2. Quality attributes

Discuss the impact of your solution on at least:

- **Correctness / reliability**
- **Performance / throughput**
- **Maintainability**

## 3. Architectural boundary question

Assume tomorrow the warehouse is deployed as **three independent JVM instances** behind a load balancer.

Answer:

> Would your `synchronized` blocks still protect the business invariant across all three instances? Why or why not?

> What type of architectural mechanism would then be required?

Do not implement a distributed solution. Analyze it.

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
