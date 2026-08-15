# Laboratory 2 Report

## 1. Shared-state inventory

| Objeto / Clase | Estado mutable compartido | Quién lee | Quién modifica                                             | Riesgo identificado                                                                                                                                                                                                                                                   |
|---|---|---|------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| PackageQueue | La lista de paquetes pendientes (pending) | takeNext(), pendingCount() | El constructor al inicio y takeNext() cuando saca un paquete | Cuando el robot revisa si hay paquetes y cuál es el primero, y despues lo elimina de la lista. Mientras, otro robot puede meterse y eliminar ese mismo paquete, causando un error porque la lista ya cambió.                                                          |
| DeliveryRegistry | El número de la próxima posición de entrega (nextPosition) y la lista de entregas registradas (deliveries) | register() y snapshot() | register() cada vez que un robot entrega un paquete        | Dos robots pueden calcular la misma posición de llegada al mismo tiempo, provoca posiciones repetidas o saltadas. Tambien cuando se pide una copia de la lista de entregas, otro robot puede estar agregando un nuevo registro al mismo tiempo, y eso rompe el programa |
| WarehouseStatistics | El contador de paquetes procesados (processedParcels) y el tiempo total de procesamiento (totalProcessingMillis) | processedParcels(), totalProcessingMillis() | recordProcessed() cada vez que un robot termina un paquete | Sumar 1 al contador primero lee el valor, luego lo aumenta y luego lo guarda. Si dos robots lo hacen al mismo tiempo, uno de los aumentos se pierde y el contador final queda más bajo de lo que debería.                                                             |
| SimulationControl | El estado de pausa (paused) | awaitIfPaused(), isPaused() | pause() y resume()                                         | La forma de pausar la simulación es ineficiente, el robot se queda en un bucle, gastando procesador en vez de que alguien lo llame                                                                                                                                    |

## 2. Observed anomalies

## Race Condition #1

**Clase / método involucrado:**
WarehouseMain / WarehouseSimulation

**Estado compartido involucrado:**
La cola de paquetes pendientes y el registro de entregas

**Comportamiento observado:**
El programa imprime el reporte final cuando la simulación apenas lleva una parte del trabajo hecho, no cuando ya terminó

**¿Por qué ocurre?**
El hilo principal lanza a todos los robots y, sin esperar a que terminen, pasa directo a imprimir el reporte. No hay ningún mecanismo que le diga al hilo principal que todos los robots acaben antes de continuar

**Evidencia de ejecución:**

```text
Comando: java -cp target/classes edu.eci.arsw.warehouse.app.WarehouseMain
Starting warehouse with 12 robots and 100 parcels...

--- STARTER REPORT (intentionally premature) ---
Initial parcels : 100
Pending parcels : 69
Processed count : 21
Registry size   : 21
Current leader  : Robot-01 / parcel 1 / position 1
----------------------------------------------
```

---

## Race Condition #2

**Clase / método involucrado:**
WarehouseStatistics.recordProcessed() y DeliveryRegistry.register()

**Estado compartido involucrado:**
El contador de paquetes procesados, la lista de entregas registradas y el número de la próxima posición de entrega

**Comportamiento observado:**
Al final de la simulación, el número de paquetes procesados, el tamaño del registro de entregas y el número de paquetes únicos entregados no coinciden entre sí, cuando deberían ser el mismo valor

**¿Por qué ocurre?**
Aumentar el contador no se hace en un solo paso, primero lee el valor actual, luego lo aumenta y luego lo guarda. Si dos robots hacen esto casi al mismo tiempo, uno de los aumentos se pierde. Algo parecido pasa con la posición de entrega, dos robots pueden calcular la misma posición antes de que ninguno la haya guardado todavía, generando posiciones repetidas o saltadas

**Evidencia de ejecución:**

```text
Comando: java -cp target/classes edu.eci.arsw.warehouse.verification.RaceConditionProbe

Run 01 -> RACE/ANOMALY | pending=0, processedCounter=233, registry=246,
uniqueParcels=226, uniquePositions=228, positionsContiguous=false

Anomalous runs: 30/30
```

---

## Race Condition #3

**Clase / método involucrado:**
PackageQueue.takeNext()

**Estado compartido involucrado:**
La lista de paquetes pendientes

**Comportamiento observado:**
Algunos robots lanzan un error inesperado al intentar tomar un paquete de la cola, y el programa lo reporta en consola en vez de asignarles un paquete normalmente

**¿Por qué ocurre?**
El robot primero revisa cuál es el paquete disponible y después lo elimina de la lista, en dos pasos separados. Entre esos dos pasos, otro robot puede meterse, tomar el mismo paquete y eliminarlo antes que el primero. Cuando el primer robot intenta eliminarlo también, la lista ya cambió de tamaño y el programa falla

**Evidencia de ejecución:**

```text
Comando: java -cp target/classes edu.eci.arsw.warehouse.verification.RaceConditionProbe

[warehouse-robot-7] Queue anomaly: IndexOutOfBoundsException
Run 03 -> RACE/ANOMALY | pending=0, processedCounter=225, registry=251,
uniqueParcels=227, uniquePositions=215, positionsContiguous=false
```

## 3. Interleaving analysis

**Condición seleccionada:**
PackageQueue.takeNext() — dos robots pueden tomar el mismo paquete y luego chocar al intentar eliminarlo de la lista

| Paso | Thread A (Robot 1)                                     | Thread B (Robot 2)                                                                                 | Estado compartido |
|---:|--------------------------------------------------------|----------------------------------------------------------------------------------------------------|---|
| 1 | Revisa si la lista está vacía (no lo está)             |                                                                                                    | pending tiene 1 paquete |
| 2 |                                                        | Revisa si la lista está vacía (no lo está)                                                         | pending tiene 1 paquete |
| 3 | Mira cuál es el primer paquete de la lista y lo guarda |                                                                                                    | pending tiene 1 paquete |
| 4 |                                                        | Mira cuál es el primer paquete de la lista (el mismo que ya vio Thread A)                          | pending tiene 1 paquete |
| 5 | Elimina ese paquete de la lista                        |                                                                                                    | pending queda vacía |
| 6 |                                                        | Intenta eliminar el mismo paquete, pero la lista ya está vacía entocnes el programa lanza un error | pending vacía, error IndexOutOfBoundsException |

### Explicación

¿Por qué el resultado final depende de la programación??

**Respuesta:**

El resultado depende del scheduling porque el código no obliga a que un robot termine sus pasos (revisar el paquete y luego eliminarlo) antes de que otro empiece los suyos. Quién se ejecuta primero, o si dos robots quedan intercalados justo en la mitad de esos pasos, lo decide el sistema operativo, y ese orden cambia cada vez que se corre el programa. Por eso a veces la simulación funciona bien y otras veces falla con el mismo código, no es un problema de lógica, sino de que el resultado final queda a merced de un orden de ejecución que nadie controla.

## 4. System invariants

I1: Un paquete, una vez tomado de la cola por un robot (takeNext()),
no puede ser entregado a ningún otro robot.

I2: La suma de (paquetes pendientes + paquetes procesados) siempre
debe ser igual al número total de paquetes iniciales. Ningún
paquete se pierde ni se duplica.

I3: Cada posición de entrega asignada por DeliveryRegistry.register()
debe ser única — ningún dos robots pueden recibir el mismo
assignedPosition.

I4: (Derivada de I1 + I3) Las posiciones asignadas forman una
secuencia contigua de 1 a N, sin huecos ni repeticiones.

I5: El contador processedParcels de WarehouseStatistics debe ser
siempre igual al tamaño (size) del snapshot de DeliveryRegistry.

I6: El reporte final solo puede imprimirse cuando todos los threads
robot han terminado su ejecución (join() completado) y, en ese
momento, pendingCount() debe ser 0.

## 5. Critical regions and synchronization decisions

| Clase | Región crítica | Invariante protegida | Mecanismo usado | ¿Por qué ese tamaño? |
|---|---|---|---|---|
| PackageQueue | takeNext() (revisar si está vacía, leer el primer elemento y eliminarlo) y pendingCount() | I1, I2 | Métodos synchronized | Los tres pasos dependen entre sí y deben ejecutarse como uno solo; separar la revisión de la eliminación es justo lo que causaba el IndexOutOfBoundsException. |
| DeliveryRegistry | register() (leer/incrementar nextPosition y agregar a deliveries) y snapshot() (copiar deliveries) | I3, I4, I5 | Métodos synchronized sobre el mismo monitor | nextPosition y deliveries deben cambiar juntos como una unidad; snapshot() debe usar el mismo candado que register(), si no podría copiar la lista a mitad de un add(), que era la causa del NullPointerException. |
| WarehouseStatistics | recordProcessed() (incrementar contador y sumar tiempo) | I5 | AtomicInteger / AtomicLong (sin locks) | Cada contador se actualiza de forma independiente, no necesitan cambiar juntos como una unidad, así que un lock sería innecesario. Los atomics evitan bloqueos y mejoran el throughput sin perder correctitud. |
| SimulationControl | pause(), resume(), awaitIfPaused(), registerRobot(), unregisterRobot(), awaitAllPaused() | I6 | Métodos synchronized + wait()/notifyAll() | El flag paused y los contadores activeRobots/parkedRobots deben leerse y modificarse juntos para que el mecanismo de wait()/notify() funcione; si usaran candados distintos, un robot podría perder la notificación y quedar bloqueado para siempre. |


## 6. Thread completion and pause/resume coordination

### Finalización de threads (join)

WarehouseSimulation guarda todas las instancias de WarehouseRobot al crearlas.
awaitCompletion() recorre esa lista y llama robot.join() sobre cada una.
WarehouseMain llama start() y luego awaitCompletion() antes de imprimir el
reporte, así que este solo se genera una vez que todos los robots terminaron,
y se imprime exactamente una vez.

Thread.sleep() no sería una alternativa válida porque solo pausa por un tiempo
fijo, sin relación real con si los demás threads ya terminaron. Los robots
tardan tiempos distintos según cuántos paquetes queden, así que cualquier
tiempo fijo es solo una suposición: si es muy corto, el reporte sale
incompleto (el error visto en la Parte I); si es muy largo, se pierde tiempo
esperando de más. join() en cambio bloquea hasta que el thread específico
realmente termine, sin importar cuánto tarde.

### Pause / Resume

SimulationControl reemplaza la espera activa por un monitor: pause(), resume()
y awaitIfPaused() están sincronizados sobre el mismo objeto, y awaitIfPaused()
usa wait() en vez de un ciclo. Al pausar, los robots dejan de consumir CPU y
quedan bloqueados hasta que resume() llama notifyAll(), despertándolos a todos
de una vez.

Cada robot llama registerRobot() al iniciar y unregisterRobot() dentro de un
finally (para que se ejecute incluso si hay una excepción), permitiendo que
SimulationControl sepa cuántos robots siguen activos y cuántos están
actualmente pausados.

### Snapshot consistente durante la pausa

awaitAllPaused() bloquea al thread principal hasta que parkedRobots sea igual
a activeRobots, es decir, hasta que todos los robots vivos ya estén realmente
pausados y hayan dejado de tocar el estado compartido. Solo entonces se toma
el snapshot, garantizando que ningún robot está a mitad de una actualización
cuando se leen los valores.

## 7. Verification results

Después de aplicar la sincronización (Parte III), el join() (Parte IV) y el
reemplazo del busy waiting por wait()/notifyAll() (Parte V), se ejecutó
`mvn clean test` (BUILD SUCCESS, 2/2 tests pasados) y luego el `RaceConditionProbe`
con tres configuraciones distintas, cada una con 100 corridas.

Para que la comparación "antes / después" fuera real (no una estimación), la
columna "antes" no se tomó de una corrida suelta de la Parte I: se ejecutó el
mismo `RaceConditionProbe`, con el mismo número de corridas, contra el código
starter original (commit `c449528`, previo a la Parte III), y la columna
"después" contra el código ya corregido (esta rama). Así ambas columnas son
directamente comparables:

| Robots | Paquetes | Runs | Anomalías antes | Anomalías después |
|---:|---:|---:|---:|---:|
| 8 | 100 | 100 | 71/100 | 0/100 |
| 16 | 250 | 100 | 84/100 | 0/100 |
| 32 | 500 | 100 | 96/100 | 0/100 |

En las 300 corridas "después", cada ejecución terminó con pending=0, y con
processedCounter, registry, uniqueParcels y uniquePositions siempre iguales
al número de paquetes de la configuración. positionsContiguous
se mantuvo en true en el 100% de las corridas, confirmando que las posiciones
de entrega forman una secuencia 1..N sin huecos ni repeticiones.

Un dato interesante: la tasa de anomalías del starter no es constante, sube
con la escala (71% → 84% → 96% a medida que crecen robots y paquetes). Esto
es coherente con la naturaleza de una condición de carrera: la probabilidad
de que dos hilos se intercalen exactamente en la ventana insegura crece con
el número de hilos concurrentes, no solo con el tamaño del problema. Por eso
el enunciado exige varias configuraciones y no solo una corrida: un único
`0/N` no demuestra que las invariantes se cumplan "por diseño" y no "por
suerte"; tres configuraciones distintas, todas en 0/100, sí lo hacen.

**Antes (starter, 32 robots / 500 parcels / 100 runs):**

```text
Run 01 -> RACE/ANOMALY | pending=0, processedCounter=497, registry=501, uniqueParcels=486, uniquePositions=496, positionsContiguous=false
Run 02 -> RACE/ANOMALY | pending=0, processedCounter=492, registry=500, uniqueParcels=482, uniquePositions=490, positionsContiguous=false
...
Anomalous runs: 96/100
```

**Después (esta rama, 32 robots / 500 parcels / 100 runs):**

```text
Run 01 -> OK | pending=0, processedCounter=500, registry=500, uniqueParcels=500, uniquePositions=500, positionsContiguous=true
...
Run 100 -> OK | pending=0, processedCounter=500, registry=500, uniqueParcels=500, uniquePositions=500, positionsContiguous=true

Anomalous runs: 0/100
```

**Nota de ejecución:** el `RaceConditionProbe` se ejecutó compilando el
proyecto directamente con `javac` (sin plugins de Maven), ya que el entorno
donde se generó esta evidencia no tiene salida a Maven Central. Esto no
afecta el resultado porque el código de `src/main` no tiene dependencias
externas (solo JUnit, usado exclusivamente en `src/test`); el resultado de
`mvn clean test` (BUILD SUCCESS, 2/2 tests) fue verificado por el equipo en
sus propias máquinas al completar la Parte III.

## 8. Quality-attribute analysis

### Análisis de la decisión principal de sincronización

**¿Qué problema se resolvía?**
Cuatro objetos compartidos (`PackageQueue`, `DeliveryRegistry`,
`WarehouseStatistics`, `SimulationControl`) son leídos y escritos por hasta
32 robots concurrentes. Había que eliminar toda condición de carrera sin
serializar la simulación completa (está explícitamente prohibido resolverlo
con un único lock global, y el objetivo del laboratorio no es quitar
concurrencia).

**¿Qué invariante había que preservar?**
Principalmente I1–I5 (un paquete se toma una sola vez, ningún paquete se
pierde, las posiciones de llegada son únicas y forman 1..N, el contador de
procesados coincide con el tamaño del registro), más I6 para el hilo
coordinador (join() antes del reporte final) y, para la pausa, que ningún
robot esté a mitad de una mutación cuando se toma el snapshot.

**¿Qué alternativas se consideraron?**
- Un único lock global: descartado, está prohibido por el enunciado y
  serializaría a los 32 robots aunque la mayor parte de su trabajo
  (`Thread.sleep` en `process()`) no toca estado compartido.
- `ReentrantLock` + `Condition` en vez de `synchronized`/`wait`/`notifyAll`:
  alternativa válida y más flexible, pero el objetivo del laboratorio es
  dominar los primitivos de monitor de Java directamente.
- `BlockingQueue` para `PackageQueue`: eliminaría la condición
  check-then-act por construcción; se dejó deliberadamente para el reto
  opcional, para que el ejercicio obligatorio muestre una región crítica
  construida a mano.
- `CopyOnWriteArrayList` para `DeliveryRegistry.deliveries`: descartado
  porque solo protegería la lista, no la secuencia compuesta
  leer-`nextPosition`→incrementar→`add()`, que es justamente el origen de
  las posiciones duplicadas/saltadas.
- `AtomicInteger`/`AtomicLong` para `WarehouseStatistics`: adoptado, porque
  sus dos contadores son independientes entre sí (no hay una invariante que
  los ate en una sola operación), así que una actualización sin lock (CAS)
  es correcta y más barata que un monitor.

**¿Por qué el mecanismo final?**
Se hizo corresponder cada mecanismo con la forma de su invariante:
`synchronized` acotado a la operación compuesta mínima en
`PackageQueue.takeNext()` y en `DeliveryRegistry.register()`/`snapshot()`
(estos dos últimos comparten monitor para que un snapshot nunca se tome a
mitad de un `add()`); atomics sin lock en `WarehouseStatistics`; un monitor
dedicado (`SimulationControl`) con `wait()`/`notifyAll()` más el conteo de
`activeRobots`/`parkedRobots` para que `resume()` despierte a todos con una
sola llamada y el coordinador pueda saber cuándo *todos* los robots vivos ya
están realmente pausados; y `Thread.join()` desde el único hilo coordinador
para leer el estado final solo después de que todos los productores
terminaron.

**¿Cuáles son sus consecuencias?**
La corrección se sostuvo en las 300 corridas de verificación (0 anomalías en
tres escalas distintas). El costo es real pero pequeño: `PackageQueue` y
`DeliveryRegistry` son ahora puntos de contención — cada robot adquiere su
monitor una vez por paquete —, pero como el lock se mantiene solo durante la
operación O(1) sobre la cola/lista y nunca durante el `Thread.sleep` que
simula el procesamiento, el tiempo de pared medido para 32 robots / 500
paquetes / 100 corridas fue prácticamente el mismo entre el starter y la
versión corregida (~42 s en ambos casos): se ganó corrección sin una
regresión medible de throughput a esta escala.

### Atributos de calidad

- **Corrección / confiabilidad.** Fue el atributo prioritario y el único que
  no se sacrificó. El starter fallaba entre 71% y 96% de las corridas según
  la escala; la versión corregida falló 0/300. Cada invariante de la Parte 4
  ahora está garantizada por un monitor o una operación atómica, no por
  "normalmente se cumple".
- **Rendimiento / throughput.** Los locks están acotados a la región mínima
  que lee y escribe los campos ligados por una invariante; todo lo demás
  (el `Thread.sleep` que simula procesamiento, el jitter aleatorio) corre sin
  sincronización y en paralelo en los 32 robots. `WarehouseStatistics` evita
  locks por completo con atomics. El costo es que `PackageQueue` y
  `DeliveryRegistry` se vuelven puntos de paso obligado para todos los
  robots; con muchos más robots que paquetes, o con un tiempo de
  procesamiento por paquete casi nulo, la contención en esos dos monitores
  terminaría dominando — a la escala exigida por este laboratorio, no lo
  hizo.
- **Mantenibilidad.** Cada objeto compartido tiene exactamente la
  sincronización que su propia invariante necesita, así que quien lee el
  código no tiene que reconstruir un razonamiento global para entender por
  qué un método es `synchronized` — la invariante está documentada junto a
  la clase. `SimulationControl` centraliza toda la lógica de pausa/resume
  (en vez de un flag de espera activa repetido en cada robot), así que
  cambiar la política de coordinación implica tocar una sola clase, no cada
  worker.

### Pregunta de frontera arquitectónica

**¿Los bloques `synchronized` seguirían protegiendo la invariante de negocio
en tres instancias de JVM independientes? ¿Por qué o por qué no?**

No. Un `synchronized` bloquea un monitor que vive en el heap de una sola
JVM. Si el almacén se despliega como tres instancias de JVM independientes
detrás de un balanceador, cada instancia construiría sus **propios** objetos
`PackageQueue`, `DeliveryRegistry`, `WarehouseStatistics` y
`SimulationControl`, en su propia memoria. Un `synchronized` en la JVM A no
tiene forma de enterarse de, ni de afectar, hilos que corren en la JVM B o
C — no hay memoria compartida que bloquear entre procesos. En concreto: dos
robots en dos instancias distintas podrían ambos creer que están asignando
la posición de entrega 1, o ambos podrían tomar el "mismo" paquete lógico si
el estado subyacente estuviera compartido (por ejemplo vía una base de
datos común) sin coordinación adicional — el monitor en memoria simplemente
nunca ve al otro proceso.

**¿Qué tipo de mecanismo arquitectónico se necesitaría entonces?**

Un mecanismo de coordinación **entre procesos / distribuido**, porque la
unidad de consistencia deja de ser "el heap de una JVM" y pasa a ser "el
sistema completo". Según el diseño concreto, eso implica una combinación de:

- mover el estado compartido fuera del proceso, a algo que ofrezca
  operaciones atómicas entre clientes — una base de datos relacional con
  transacciones/locking a nivel de fila, o un almacén como Redis con
  primitivas atómicas — para que las tres instancias lean y escriban la
  *misma* cola/registro en lugar de tener tres copias privadas;
- un lock distribuido o un servicio de consenso (ZooKeeper, etcd, un lock
  distribuido tipo Redis) para serializar el equivalente de
  `takeNext()`/`register()` entre instancias, si el estado no puede vivir
  simplemente en un almacén transaccional;
- o, muchas veces la mejor respuesta arquitectónica: evitar necesitar un
  lock compartido desde el diseño, **particionando** el trabajo de
  antemano (cada instancia dueña de un subconjunto disjunto de paquetes) y
  usando un message broker (Kafka/RabbitMQ) para todo lo que deba
  observarse globalmente, como eventos de entrega o estadísticas
  agregadas.

En resumen: un monitor local es un mecanismo de corrección de un solo
proceso; un despliegue multi-instancia necesita uno *distribuido*, y elegir
entre "almacén transaccional compartido", "lock distribuido" o "particionar
y evitar compartir" es en sí mismo un trade-off arquitectónico entre
consistencia, disponibilidad y latencia — algo que `synchronized` no puede
cubrir por más que se estire.
