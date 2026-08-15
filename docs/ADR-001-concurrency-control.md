# ADR-001: Concurrency control for warehouse shared state

## Context

El almacén simula robots autónomos (un `Thread` de plataforma por robot, sin
thread pools ni virtual threads, por restricción del laboratorio) que
compiten por cuatro objetos compartidos: `PackageQueue`, `DeliveryRegistry`,
`WarehouseStatistics` y `SimulationControl`. El código starter era
intencionalmente inseguro: `PackageQueue.takeNext()` tenía una condición
check-then-act (`isEmpty()` → `get(0)` → `remove(0)`) que producía
`IndexOutOfBoundsException`; `DeliveryRegistry.register()` incrementaba
`nextPosition` en tres pasos no atómicos, generando posiciones de entrega
duplicadas o saltadas; `WarehouseStatistics.recordProcessed()` perdía
incrementos porque `contador++` no es atómico; y `SimulationControl` pausaba
la simulación con espera activa (`while (paused) Thread.onSpinWait();`),
desperdiciando CPU. Además, el `main` original imprimía el reporte final sin
esperar a que los robots terminaran (`Thread.sleep(60)` en vez de `join()`).

Se necesitaba una decisión de diseño que eliminara estas condiciones de
carrera sin caer en dos extremos igual de malos: sincronizar todo con un
único lock global (prohibido explícitamente y además serializaría trabajo
que no comparte estado), o dejar sin proteger cualquier operación "porque
casi nunca falla".

## Decision

Se protege cada objeto compartido con el mecanismo mínimo que su propia
invariante exige, en vez de aplicar una única estrategia global:

- **`PackageQueue`**: `takeNext()` y `pendingCount()` son `synchronized`
  sobre la instancia. La región crítica cubre exactamente
  check-then-act-then-remove, que es la secuencia que debía ejecutarse como
  una sola unidad lógica.
- **`DeliveryRegistry`**: `register()` y `snapshot()` son `synchronized`
  sobre el mismo monitor (la propia instancia), de modo que
  leer-incrementar-`nextPosition` y agregar el registro ocurren como un
  paso atómico, y un snapshot nunca se toma a mitad de un `add()`.
- **`WarehouseStatistics`**: sin `synchronized`; sus dos campos pasan a ser
  `AtomicInteger`/`AtomicLong` actualizados con `incrementAndGet()` /
  `addAndGet()`, porque son contadores independientes entre sí, sin una
  invariante compuesta que los ligue en una sola operación.
- **`SimulationControl`**: se reemplaza la espera activa por un monitor
  clásico (`synchronized` + `wait()`/`notifyAll()`), con contadores
  `activeRobots`/`parkedRobots` para que `resume()` despierte a todos los
  robots con una sola llamada a `notifyAll()`, y para que el coordinador
  pueda esperar (`awaitAllPaused()`) hasta que *todos* los robots vivos
  estén efectivamente parados antes de considerar consistente un snapshot
  tomado en pausa.
- **Finalización de hilos**: `WarehouseSimulation.awaitCompletion()` llama
  `Thread.join()` sobre cada robot; `WarehouseMain` invoca `start()` y
  luego `awaitCompletion()` antes de imprimir el único reporte final,
  eliminando el `Thread.sleep()` como sustituto de sincronización.

## Alternatives considered

- **Un único lock global** para toda la simulación — descartado: viola la
  restricción explícita del enunciado y serializaría a los 32 robots aunque
  la mayor parte de su trabajo (`Thread.sleep` dentro de `process()`) no
  toca ningún estado compartido.
- **`ReentrantLock` + `Condition`** en vez de `synchronized`/`wait`/
  `notifyAll` — alternativa técnicamente válida (permite múltiples
  "wait-sets" con `newCondition()`), pero el objetivo pedagógico del
  laboratorio es dominar los primitivos de monitor de Java, así que se
  mantuvo `synchronized`.
- **`java.util.concurrent.BlockingQueue`** para `PackageQueue` — eliminaría
  la condición check-then-act por construcción (es, de hecho, el reto
  opcional sugerido al final del enunciado). Se dejó fuera de la solución
  obligatoria para que el ejercicio muestre una región crítica diseñada a
  mano, no delegada a una colección de la librería estándar.
- **`CopyOnWriteArrayList`** para `deliveries` en `DeliveryRegistry` —
  descartado porque solo protegería la lista en sí, no la secuencia
  compuesta leer→incrementar→`add()` sobre `nextPosition`, que era la causa
  real de las posiciones duplicadas/saltadas.
- **Reducir el tiempo de espera activa** en vez de eliminarla (por ejemplo
  con `Thread.sleep` corto dentro del ciclo de `SimulationControl`) —
  descartado: sigue gastando CPU en el hilo pausado y el enunciado pide
  explícitamente evitar la espera activa, no solo acortarla.

## Quality attributes affected

- **Corrección / confiabilidad** (atributo priorizado): pasa de fallar entre
  71% y 96% de las corridas (según escala, código starter) a 0/300 en la
  versión corregida, medido con `RaceConditionProbe` en tres
  configuraciones (8/100, 16/250, 32/500 — ver evidencia).
- **Rendimiento / throughput**: los locks se mantienen solo durante
  operaciones O(1) sobre la cola/lista, nunca durante `Thread.sleep`; el
  tiempo de pared medido para 32 robots/500 paquetes/100 corridas fue
  equivalente antes y después (~42 s), es decir, no hubo una regresión de
  throughput medible a esta escala a cambio de la corrección obtenida.
- **Mantenibilidad**: cada clase documenta y protege únicamente su propia
  invariante; la lógica de pausa/resume queda centralizada en
  `SimulationControl` en lugar de repetida en cada `WarehouseRobot`.

## Evidence

`RaceConditionProbe`, mismo número de corridas, comparando el código starter
(commit `c449528`) contra esta rama:

| Robots | Paquetes | Runs | Anomalías antes | Anomalías después |
|---:|---:|---:|---:|---:|
| 8 | 100 | 100 | 71/100 | 0/100 |
| 16 | 250 | 100 | 84/100 | 0/100 |
| 32 | 500 | 100 | 96/100 | 0/100 |

`mvn clean test` reporta BUILD SUCCESS (2/2 tests) según lo verificado por el
equipo al completar la Parte III. El detalle completo de la evidencia y la
metodología de medición está en `docs/REPORT.md`, sección 7.

## Consequences

- `PackageQueue` y `DeliveryRegistry` son ahora puntos de contención
  reales: todo robot debe adquirir su monitor una vez por paquete tomado y
  una vez por entrega registrada. A la escala de este laboratorio (hasta 32
  robots / 500 paquetes) eso no afectó el throughput medible, pero es un
  límite conocido si el número de robots creciera mucho más allá de eso.
- El ciclo de vida de cada robot es ligeramente más complejo que en el
  starter: debe registrarse (`registerRobot()`) y desregistrarse
  (`unregisterRobot()`, dentro de un `finally`) en `SimulationControl` para
  que el conteo de `activeRobots`/`parkedRobots` sea confiable incluso si
  el robot termina por una excepción.
- La corrección lograda es válida únicamente dentro de **una** JVM. Nada de
  este diseño protege la invariante si el sistema se despliega como varias
  instancias de proceso (ver Parte VII del laboratorio / sección 8 de
  `docs/REPORT.md`): eso requeriría un mecanismo de coordinación
  distribuido, fuera del alcance de este ADR.

## Risks

- Si en el futuro se agrega un nuevo campo mutable a `DeliveryRegistry` o
  `PackageQueue` sin incluirlo dentro del monitor ya existente, la
  condición de carrera reaparecería en silencio — nada en el código impide
  eso más allá de la disciplina de revisión de código.
- `notifyAll()` despierta a **todos** los robots en espera aunque solo el
  evento `resume()` los necesite despiertos; es una decisión deliberada
  (simplicidad y corrección por encima de una micro-optimización con
  `notify()` selectivo), pero conviene señalarlo como un costo de
  rendimiento asumido conscientemente si un perfilado futuro mostrara
  contención relevante en el despertar.
- El diseño asume hilos de plataforma en número moderado (decenas). Escalar
  a cientos o miles de robots concurrentes chocaría con el costo de
  hilo-por-robot (memoria de stack, cambios de contexto) antes que con la
  sincronización misma — está fuera de alcance aquí porque el enunciado
  prohíbe usar thread pools o virtual threads en este laboratorio, pero es
  relevante si el requisito cambiara.
