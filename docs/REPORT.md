# Laboratory 2 Report

## 1. Shared-state inventory
| Objeto / Clase | Estado mutable compartido | Quién lee | Quién modifica | Riesgo identificado                                                                                                                                                                                                                                                                                                  |
|---|---|---|---|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| PackageQueue | La lista de paquetes pendientes (pending) | takeNext(), pendingCount() | El constructor al inicio y takeNext() cuando saca un paquete | Cuando el robot revisa si hay paquetes y cuál es el primero, y despues lo elimina de la lista. Mientras, otro robot puede meterse y eliminar ese mismo paquete, causando un error porque la lista ya cambió.                                                                               |
| DeliveryRegistry | El número de la próxima posición de entrega (nextPosition) y la lista de entregas registradas (deliveries) | register() y snapshot() | register() (cada vez que un robot entrega un paquete) | Dos problemas: 1) Dos robots pueden calcular la misma "posición de llegada" al mismo tiempo (por eso hay posiciones repetidas o saltadas). 2) Cuando se pide una copia de la lista de entregas, puede que otro robot esté justo agregando un nuevo registro al mismo tiempo, y eso rompe el programa (NullPointerException). |
| WarehouseStatistics | El contador de paquetes procesados (processedParcels) y el tiempo total de procesamiento (totalProcessingMillis) | processedParcels(), totalProcessingMillis() | recordProcessed() (cada vez que un robot termina un paquete) | Sumar 1 al contador no es una sola operación "de un solo paso" para el computador: primero lee el valor, luego lo aumenta y luego lo guarda. Si dos robots hacen esto casi al mismo tiempo, uno de los aumentos se "pierde" y el contador final queda más bajo de lo que debería.                                    |
| SimulationControl | El estado de pausa (paused) | awaitIfPaused(), isPaused() | pause() y resume() | Aquí no se pierden datos ni hay error, pero la forma de pausar la simulación es ineficiente: el robot se queda preguntando todo el tiempo "¿ya puedo seguir? ¿ya puedo seguir?" en un bucle, gastando procesador en vez de simplemente "dormirse" hasta que alguien lo despierte.                                    |
## 2. Observed anomalies

## 3. Interleaving analysis

## 4. System invariants

## 5. Critical regions and synchronization decisions

## 6. Thread completion and pause/resume coordination

## 7. Verification results

## 8. Quality-attribute analysis
