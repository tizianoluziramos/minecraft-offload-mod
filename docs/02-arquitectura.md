# 2. Arquitectura propuesta

```
+--------------------------------------------------------------+
| CLIENTE (PC del jugador)                                      |
|                                                              |
|  Minecraft 26.1.2 + Fabric Loader 0.18.4 + Fabric API        |
|  + Sodium (renderer intacto; este mod NO toca el pipeline)   |
|                                                              |
|  Modalidades: single-player (integrated server) o SMP        |
|                                                              |
|  [ Render thread ]  → HUD overlay (latencia/estado/stat)     |
|  [ Tick hilo ]      → capturas de snapshot (LIGERAS)          |
|                                                              |
|  RemoteProcessingEngine (agente fuera de los hilos MC)       |
|    ├─ Session: estado, handshake, auth, heartbeat, reconnect |
|    ├─ Queue: cola de tareas, cancelación, deadline, máx sim. |
|    ├─ Netty-free socket client (virtual thread + non-blocking)|
|    ├─ Fallback: ejecutor LOCAL = mismo código que el server   |
|    └─ Stats: perfilado (local/remote, ser/deser, RTT, bytes)  |
|                                                              |
|  Funciones de task invocadas DESDE main-thread gan hooks:     |
|    PathfindQueries, WorldQueries, Compression, Benchmark     |
|                                                              |
|  ── Protocolo binario MKOF v1 (24B header + payload + crc) ─►|
+--------------------+-----------------------+------------------+
                     |  TCP (LAN o Internet)
+--------------------v-----------------------v------------------+
| SERVIDOR REMOTO (otro dispositivo)                            |
|                                                              |
|  Java 25, java -jar remote-server.jar                         |
|  ├─ TCP listener (virtual threads, accept loop)               |
|  ├─ Auth: token + client-id + challenge/HMAC, rate limit     |
|  ├─ Packet validator: tamaños, tipos, CRC, malformed guard    |
|  ├─ Worker pool acotado (por tipo de tarea, cola priorizada)  |
|  ├─ Cache de resultados (disco + LRU, clave por tipo+hash)    |
|  ├─ TaskHandlers: Compress / Pathfind / WorldQuery / Benchmark|
|  │   └─ (extensión) ChunkGen: lanza minecraft_server.jar      |
|  │         headless para pregeneración remota + importer      |
|  └─ Consola/reportes de uso CPU remoto                        |
+--------------------------------------------------------------+
```

## 2.1 Decisiones clave

1. **El mod es un *cliente de red* independiente.** No usa el stack de networking de Minecraft (`fabric-networking-api-v1`), porque esas APIs están ligadas al *server de MC* al que el jugador está conectado y a los ticks del juego. El mod abre su propio socket TCP hacia el servicio de offload en un hilo propio.

2. **Aislamiento de threads (regla de oro):**
   - Network I/O → un *virtual thread* de conexión + un hilo de lectura.
   - Work dispatch → `ThreadPoolExecutor` propio (no `ExcutorService` del juego).
   - Serialización pesada → background.
   - Captura de snapshots del mundo y aplicación de resultados *visibles* → `client.execute(...)` (main thread), mínimo coste.
   - **Prohibido** bloquear render/tick; toda API prohibida se documenta en el `docs/06` (limites).

3. **Mismo código en remoto y en local (fallback).** Las implementaciones de cada tarea viven en `common` (`TaskExecutors`) y son invocadas tanto por el worker del servidor como por el fallback del cliente. Dos propiedades se derivan de esto:
   - Resultados idénticos → cero desincronización.
   - Los benchmarks "local vs remote" comparan el mismo algoritmo.

4. **Sin dependencias externas en `common` y `server`** excepto JDK: compresión `java.util.zip`, CRC `java.util.zip.CRC32C`, sockets/virtual threads del JDK 25, JSON de config hand-rolled (o Gson en el mod, disponible con MC).

5. **Caché en servidor** clave `(taskKind, hash(request canonizado))` persistida en `cache/` del servidor; es inválida (firma del mundo/semilla incluida en la clave) para no servir datos de mundos distintos → evita desincronización por caché.

6. **El render final y el pipeline de Sodium no se tocan.** El mod solo dibuja un overlay opcional mediante `HudElementRegistry` (API oficial de Fabric) que se inserta *antes de chat* y no intercepta nada.

## 2.2 Estados de sesión del cliente

```
DISCONNECTED → CONNECTING → HANDSHAKE → AUTH (opcional) → READY
                                                              ↘  LINK_LOST → BACKOFF → CONNECTING
READY ── timeout/peer close → BACKOFF (exp. backoff 1s..60s) ─▶ CONNECTING
READY ── config deshabilitada / bye → DISCONNECTED
```

Heartbeat: `PING→PONG` cada 5 s (configurable); sin respuesta en `timeoutMs*2` → link lost. Mientras no hay sesión `READY`, `TaskQueue` redirige las peticiones al **fallback local** silenciosamente.

## 2.3 Gestión de tareas

- `TaskQueue` asigna `long taskId` (atomic counter, 63 bits), mantiene `map taskId → Pending`.
- `submit(client, taskType, payload)` devuelve un `CompletableFuture<Buf>`.
- Si no hay conexión `READY` o server rechaza → resuelve con el resultado del fallback local y *marca el record* para stat.
- `cancel(taskId)` → envía `TASK_CANCEL` (best effort) y completa el futuro con cancelado.
- `maxConcurrent` limita las peticiones en vuelo (semáforo); el resto espera en la cola local.

## 2.4 Perfilado (requisito del proyecto)

`ProfilingStats` acumula, por tipo de tarea:
- tiempo local (fallback), tiempo remoto, RTT, ser/deser, tamaños req/resp (bytes), computado "tiempo total ahorrado" (remote wall - local wall), bytes enviados/recibidos, y ratio de uso (tasa de hits de caché remotos).
- El `BenchmarkTask` otorga además una *chequeo funcional*: verifica checksum remoto = local y devuelve GPU/CPU del server remoto (con `ManagedThread.sleep` tuneado) para comparar hosts.

Los datos se exponen en el HUD (si `showStatus`/`showLatency`) y en pantalla de config en la pestaña de diagnóstico.