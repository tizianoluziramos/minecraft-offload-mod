# 1. Análisis de viabilidad de remote offloading en Minecraft Java 26.1.2

## 1.1 Contexto de la versión

Minecraft 26.1.2 (julio 2026) es la primera familia de versiones **no ofuscadas** de Java Edition. Implicaciones directas:

- Las clases de Minecraft se referencian con nombres oficiales de Mojang (p. ej. `net.minecraft.resources.Identifier`, `net.minecraft.client.gui.GuiGraphicsExtractor`).
- Fabric ya no publica Yarn mappings; el plugin `net.fabricmc.fabric-loom` **no remapea** (usa `implementation`/`jar` en lugar de `modImplementation`/`remapJar`).
- Se requiere Java 25 para ejecutar y compilar; el entorno de desarrollo necesita Gradle 9.4+ y Loom 1.15+.

## 1.2 Marco de evaluación

Para cada operación candidata aplicamos 10 criterios; una tarea solo se considera *candidata real* si supera simultáneamente:

1. **CPU-bound**: consume tiempo de CPU local sin depender de I/O que el servidor remoto no pueda replicar.
2. **Paralelizable**: se puede partir en unidades independientes.
3. **Independencia de estado (o dependencia minimizada)**: el resultado depende solo de datos serializables que el cliente ya posee (o de una semilla + configuración de mundo deterministas).
4. **Compactación**: el volumen de datos de entrada+salida es pequeño frente al trabajo CPU realizado → el *net win* sobrevive a la latencia de red.
5. **Cacheable**: el resultado es reutilizable (clave = semilla del mundo, coordenadas, config).
6. **Síncrono con el mundo o puramente derivado**: procesarlo en el cliente no altera el tick del servidor ni la simulación.
7. **Riesgo de desincronización nulo o controlable**.
8. **Ganancia neta real** (ahorro CPU local > coste de serialización + red + latencia).
9. **No interfiere con el pipeline de renderizado** (sobre todo con Sodium).
10. **Tolerancia de latencia**: la tarea admite esperar ≥100 ms sin daño (LAN) o ≥1 s (Internet).

---

## 1.3 Evaluación de operaciones candidatas

### 1.3.1 Preparación / generación de chunks

1. **Quién**: motor de generación del world (`ChunkGenerator` + `StructureTemplateManager` + biome noise). En single-player lo ejecuta el **integrated server**; los hilos de chunk generation (`ChunkMap` → `SingleThreadedChunkStorage` / workers del nivel).
2. **Hilo**: los **propios hilos de carga/generación del mundo del servidor integrado** (no el hilo principal del servidor, pero sí los autónomos del `ServerLevel`). No el render thread.
3. **Datos**: semilla, dimension type, generador × tipo de mundo, coordenada de chunk, puntos de interés (estructuras/loot) que dependen de chunks vecinos ya generados y del estado del mundo (stored structures).
4. **Resultado**: `ChunkAccess` completo → NBT (block states, biomas, heightmaps, block entities) + entradas en `featureChunkProvider`/stored structures.
5. **Volumen**: chunk con region ~100–250 kB comprimido (NBT + palettes). Un 3×3 de chunks ~1 MB. Alto.
6. **Latencia**: la generación es *izquierda*, el jugador solo necesita chunk *derecha* a nivel de `FullChunk` ~ pocos cientos de ms en el mismo PC. Extrapolarlo a LAN añade decenas de ms de red; a Internet, cientos.
7. **Paralela**: sí; los chunks son unidades independientes *salvo* estructuras vecinas.
8. **Cacheable**: sí por `(seed, dimension, coords)` pero el estado del mundo (campanas de corrupción, struck, estructuras generadas) rompe la cache tras cambios; la cache es desechable por mundo.
9. **Desincre**: **ALTO**. La generación externa que no use *exactamente* el mismo código (versión exacta del juego, generador, mundo, prioridad de estructuras) produce un mundo divergente. Los bloques "conflict" con el estado actual del servidor, y los block entities con referencias a objetivos del mundo dañarían la simulación. Inyectar chunk remoto en el `ServerLevel` activo es inviable sin asumir control total del almacenamiento.
10. **Mejora**: para un jugador único en el **mismo host**, la generación ya es multi-hilo y rara vez es el cuello de botella del renderer. El offload solo gana en el escenario "granja de pregeneración" remota.

**Veredicto**: offload "streaming de chunk aluminico en vivo" → **NO viable sin romper la simulación**. Offload "pregeneración remota hacia un almacén de mundo manejado por el servidor remoto (p. ej. para un server dedicado con el mismo código/version) + importer" → **viable como extensión**, pero el código de generación tiene que ejecutarse en el servidor remoto (bundled `minecraft_server.jar`), no puede replicarse con un predictor propio. Este repo implementa el **SPI de tareas** (`TaskHandler`) y un worker `ChunkGen` que delega a una instancia headless de MC 26.1.2 cuyo almacenaje después importa el importer del cliente (`net.minecraft.world.level.storage.LevelStorage`).

### 1.3.2 Cálculos de iluminación

1. **Quién**: `LevelLightEngine` (sky/block light), invocada por el `ServerLevel` tras cambios de chunks.
2. **Hilo**: hilos de mundo/luz del **servidor integrado**; el procesado de luz de *render* adicional lo hace Sodium en el cliente desde sus propios datos.
3. **Datos**: block states (opacidad, luminosidad emisiva) de un sub-chunk + vecinos (border), además de resultados previos de luz de cielo.
4. **Resultado**: mapa de luz `SkyLightStorage`/`BlockLightStorage`.
5. **Volumen**: 16×16×8·2 (sky+block) ≈ 32–64 kB de grid por subchunk sin comprimir.
6. **Latencia**: la luz debe estar lista *antes* de mostrar el chunk; es una dependencia sincrónica en la cadena de visualización → tolerancia baja.
7. **Paralela**: la solución de luz local SÍ es paralelizable por región cerrada; los bordes se procesan en pasadas adicionales.
8. **Cacheable**: solo si el chunk no ha cambiado (esa es de hecho la invarianza de los caches de luz del juego).
9. **Desincre**: medio-alto: reinsertar luz externa correctamente exige reencadenar con el `LightEngine` (update directions), lo que **requiere mixins** sobre el pipeline del servidor; cualquier error produce parpadeo/negros (visual error), además de coste de vuelto si se descarta.
10. **Mejora**: muy limitada en single-player (la luz del server ya es rápida); el render-light de Sodium corre en GPU/cliente y **no debe tocarse**.

**Veredicto**: **NO para v1. Inviable por dependencia sincrónica y riesgo de parpadeo; requiere idioma oficial de `LightEngine`, no se externaliza sin tocar la simulación.** Se deja documentado el `LightCalcTask` como *experimental* (solo cómputo de un mapa cerrado para prueba de benchmark, OFF por defecto, sin integración en `LightEngine`).

### 1.3.3 Procesamiento de estructuras

1. **Quién**: `StructureTemplateManager` + placement durante chunk gen; búsqueda `locate structure` por comandos/tooltips.
2. **Hilo**: hilo de world gen / comando.
3. **Datos**: semilla, configuración de estructura (spacing/seed), coords de búsqueda y radio.
4. **Resultado**: coordenadas de la estructura más cercana (o errores).
5. **Volumen**: petición ~40 B; respuesta ~30 B. **Mínimo**.
6. **Latencia**: tolera 100 ms–2 s (herramientas de exploración, minimaps avanzados, datapacks de progreso). 
7. **Paralela**: sí, por radio/sector.
8. **Cacheable**: **sí, para toda la vida del mundo** (`(seed, version, estructura, chunkHash)`).
9. **Desincre**: nula si el resultado se usa solo como **dato informativo/de planificación** (no se inyecta en el mundo). Es reproducible localmente como doble comprobación → no desincre por construcción.
10. **Mejora**: la búsqueda de estructuras a gran radio (5000+ chunks) es CPU real aunque esporádica; el ahorro es intermitente pero real.

**Veredicto**: **VIABLE** (worker `WorldQueryTask`, kind `STRUCTURE_LOCATE`) para usos *informativos*. Ejecuta el mismo algoritmo determinista en remoto y en local (same code path) y valida con la misma semilla. En este repo el kind `STRUCTURE_LOCATE` queda implementado con el predictor de posiciones y documentado como id único para versión exacta de MC (si la versión del mundo no coincide, se descarta — nunca se inyecta).

### 1.3.4 Pathfinding / navegación

1. **Quién**: `Mob`/`PathFinder` (A*, node evaluator por mob) en el **servidor** (integrated o dedicado).
2. **Hilo**: el tick del servidor (entities). *Esta es la parte sensible: en el cliente no existe pathfinding propio*.
3. **Datos**: snapshot del entorno (grid de nodos walkable/standable), normas de movimiento del mob, start/goal, límite de nodos.
4. **Resultado**: lista de waypoints o fallo.
5. **Volumen**: snapshot 64×64×4 ≈ 16–64 kB; respuesta ~1–2 kB. **Bajo**.
6. **Latencia**: el pathfinding *del juego* se llama sincrónicamente durante el tick → **no tolera offload**. PERO el pathfinding *asíncrono para navegación del jugador/mods* (waypoints, rutas largas de exploración, "dame el camino al destino") tolera 50–500 ms (LAN) sin problemas.
7. **Paralela**: la búsqueda A* sobre grid es trivialmente paralela (multi-request).
8. **Cacheable**: parcialmente por snippet cerrado (solo para islas de geometría estática).
9. **Desincre**: cero si es un servicio de *información* (el resultado se dibuja como overlay o se usa como guía; el tick del mundo no depende de él).
10. **Mejora**: real cuando un mod quiere rutas de largo alcance o rutas de surface (la columna "surface caster") que requieren explorar grandes grids. El cliente consumiría CPU de mod en A*; offload lo mueve.

**Veredicto**: **VIABLE como servicio asíncrono de rutas (no tick-sincrónico).** `PathfindTask` implementado con A* sobre un grid de superficie de hasta 128×128 (bytes por celda). El fallback local produce resultados **idénticos** (misma implementación en `common`), garantizando cero desincronización.

### 1.3.5 Procesamiento de datos / compresión

1. **Quién**: el juego comprime (zlib/zstd) chunks en red y `LevelStorage`; mods comprimen/descomprimen NBT, backups, datapacks, world exports.
2. **Hilo**: cualquiera (llamadas sincrónicas en hilos de red, autosaves).
3. **Datos**: blob binario arbitrario.
4. **Resultado**: blob comprimido + checksum.
5. **Volumen**: entrada/salida proporcional al blob; eficiencia del codec de nivel 1–9 (~3–10×). 
6. **Latencia**: 50 ms–5 s aceptables (operaciones no críticas de tick).
7. **Paralela**: sí, por trozo (blobs independientes).
8. **Cacheable**: **sí por hash de contenido**.
9. **Desincre**: **nula por construcción** (función pura).
10. **Mejora**: cuantificable y neta en blobs >64 kB: la compresión zlib nivel 6 de 1 MB tarda ~50–150 ms CPU; en remoto se ahorra esa CPU al coste de ~redigo de red (movidos con zstd a nivel LAN el tráfico es despreciable).

**Veredicto**: **VIABLE y recomendado.** `CompressTask` implementado (zlib deflate/gzip/raw + CRC32C) con cache por hash.

### 1.3.6 Preprocesamiento de información (world queries)

Deterministas por semilla: chunk de slime, spawn aproximado, (future) altura biome por (x,z), análisis de "dónde están los puntos de interés". El cliente en single-player puede leer `ServerLevel.getSeed()` del **integrated server**; en SMP la semilla del mundo no se expone al cliente → **el worker exige que el jugador indique la semilla** (con soporte para worlds de un jugador automática). Resultados solo informativos.

**Veredicto**: **VIABLE** (`WorldQueryTask`, kinds `SLIME_CHUNK` y `SPAWN` implementados, verificables contra la wiki de MC).

### 1.3.7 Otras (observables por profiling)

- **Luz de render (Sodium)**: NO se toca (construcción).
- **Culling o frustum**: NO (GPU, nada que hacer).
- **Serialización de regiones en autosave**: candidato (worker `CompressTask` aplicado a buffers de NBT) — dejado como hook.
- **Detección de colisiones / simulación**: NO (estado del mundo, no transferible).
- **Pathfinding del juego**: NO (tick-sincrónico) — se documenta.

---

## 1.4 Resultado

| Operación | ¿Viable? | Riesgo desincre | Caché | Tipo de resultado | Estado en el repo |
|---|---|---|---|---|---|
| Generación de chunks en vivo | No | Alto | Solo mundo completo | NBT chunk | Extensión SPI (`ChunkGen` + importer) |
| Luz (server) | No (v1) | Medio-alto | Parcial | LightMap | `LightCalcTask` OFF por defecto |
| Búsqueda de estructuras (informativa) | Sí | Bajo/nulo | Sí | Coord + salida | `WorldQueryTask` |
| Pathfinding asíncrono (rutas) | Sí | Nulo | Parcial | Waypoints | `PathfindTask` |
| Compresión/descompresión | Sí | Nulo | Sí (hash) | Bytes | `CompressTask` |
| World queries deterministas (slime/spawn) | Sí | Nulo | Sí (seed) | Scalar/Blob | `WorldQueryTask` |
| Benchmark/validación (diagnóstico) | Sí | Nulo | No | Stats | `BenchmarkTask` |

**Principio rector**: todo lo que se externaliza produce **información derivada** que el cliente decide cómo usar; nada inyecta estado en el `ServerLevel` ni en el renderer. El fallback local ejecuta exactamente el mismo código → resultados idénticos → **no hay desincronización posible por diseño**.