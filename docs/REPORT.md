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

**Respuesta:**

El resultado depende del scheduling porque el código no obliga a que un robot termine sus pasos (revisar el paquete y luego eliminarlo) antes de que otro empiece los suyos. Quién se ejecuta primero, o si dos robots quedan intercalados justo en la mitad de esos pasos, lo decide el sistema operativo, y ese orden cambia cada vez que se corre el programa. Por eso a veces la simulación funciona bien y otras veces falla con el mismo código, no es un problema de lógica, sino de que el resultado final queda a merced de un orden de ejecución que nadie controla.

## 4. System invariants

## 5. Critical regions and synchronization decisions

## 6. Thread completion and pause/resume coordination

## 7. Verification results

## 8. Quality-attribute analysis
