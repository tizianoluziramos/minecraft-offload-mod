# 5. Operaciones candidatas (matriz 10 puntos)

Para cada tarea implementada en este repo, la justificación completa exigida por las especificaciones (10 puntos) y el estado real en el código.

## 5.1 CompressTask (COMPRESS)

1. Ejecutada en client por: mods/genéricos (backups, exports NBT, payloads grandes, autosave hooks).
2. Hilo: el que llama (a respetar), aquí siempre background.
3. Datos: blob binario + `algorithm (zlib/gzip/raw)` + `level`.
4. Resultado: blob comprimido + crc + tamaños/ratio.
5. Volumen: ~1× entrada (eficiencia 3–10×).
6. Latencia: 50 ms–5 s OK.
7. Paralelizable: sí (blobs independientes).
8. Cacheable: sí, clave = hash(input)+level+algo.
9. Desincre: nula (función pura).
10. Mejora: sí, CP real movida fuera (ver §2 del doc 05-benchmarks).

## 5.2 PathfindTask (PATHFIND)

1. Ejecutada en MC por: `Mob`/`PathFinder` (server). Aquí se externaliza una **variante asíncrona** para uso de mods (rutas de jugador/waypoints/guías), NO la llamada tick-sincrónica del mob.
2. Hilo: servidor de MC (tick mob) — nuestro servicio va en hilos propios del agente.
3. Datos: grid W×H (default 96, máx 128) de `surfaceColumn` (byte: suelo/agua/bloque), start/goal, costes, límite de nodos.
4. Resultado: waypoints + nodos expandidos + coste.
5. Volumen: req ~W×H bytes + ≤64 B; resp ~2 kB.
6. Latencia: 100 ms–500 ms LAN OK; 1–2 s Internet para usos informativos.
7. Paralela: sí (varias peticiones).
8. Cache: parcial (sí llamada con hash de grid+border+start).
9. Desincre: **cero por diseño** — el resultado solo se dibuja/se usa como guía; el fallback local es la misma implementación.
10. Mejora: evita A* en el cliente (o en un mod) para rutas de 128×128 (decenas de miles de nodos, 10–80 ms CPU cada una).

## 5.3 WorldQueryTask (WORLD_QUERY): SLIME_CHUNK, SPAWN

1. En MC: `LevelBasedOnSim`/preguntas a `ChunkGenerator` o utilidades (slime chunk se calcula en `SlimeSpawner`).
2. Hilo: server (world tick) o client (calls puntuales).
3. Datos: seed real (o config usada), dimension, coords.
4. Resultado: booleano/coords.
5. Volumen: 40 B → 16 B.
6. Latencia: 100 ms–2 s.
7. Paralela: sí.
8. Cache: **sí por (seed, kind, coords)** — sirve para el resto del mundo.
9. Desincre: nula (heurística determinista; si la semilla difiere de la real el resultado no se usa — bandera `seedVerified`).
10. Mejora: baja en CPU absoluta pero casi coste cero de red; sirve como **muestra de world service** y para caches de larga vida (slime chunks, spawn) usados por minimaps.

*Nota*: `STRUCTURE_LOCATE` queda como ID reservado e implementación "verificador contra mismatches de versión del mundo" — solo actúa si la versión coincide; nunca inyecta nada.

## 5.4 BenchmarkTask (diagnóstico)

Requisito específico del proyecto (perfilado). Ejecuta kernels CPU (`fib`, `prm`, `sha`, `sort`) con checksum; mide local vs remoto. No es una operación de producción (CPU “gratis” para medir capacidad/host).

## 5.5 No externalizados (documentación de "por qué no")

Ver `01-analisis-viabilidad.md`; resumen: chunk-gen en vivo (desincre + dependencia del estado), luz en vivo (sincronía + mixins en `LightEngine`), pathfinding tick del mob (sincronía), simulación física (estado).

---

# 6. Benchmarks: metodología y métricas

## 6.1 Metodología

Para cada tarea se mide el **ciclo completo**:

```
t_total = t_consume_capture + t_serialize_request + RTT/2(tx + queue + worker + rx) + t_serialize_response + t_apply
```

El ahorro real frente a ejecutar en el cliente:

```
local  = t_std_local_exec + t_apply                (medido: TaskRecord)
remote = t_ser + rtt + t_remote_exec + t_deser     (medido)
speedup_effective = local / remote                  (sin contar capture/apply comunes)
```

Antes de cada campaña:
1. Cliente y servidor en reposo (sin otros benchmarks activos).
2. Sample ≥ 30 iteraciones por tamaño; descartar p95 (el peor latido) para el setup LAN.
3. Comparar a través de la **misma red**: loopback (fallback), LAN (switch 1 Gb), Internet (≥20 ms).
4. Correr el `BenchmarkTask` por ambos lados para conocer CPU remota, y `PING` para RTT.
5. Los resultados se escriben a `bench-results/<task>-<mode>.csv` (o log si no hay directorio).

## 6.2 Métricas del HUD / pantalla de diagnóstico

- RTT (ms, EMA de PING/PONG).
- Estado de conexión (`READY / LAN / backlog`).
- Tareas pendientes/en vuelo.
- Total local ms, total remote ms, **tiempo ahorrado estimado** = Σ(remote − local de records emparejados).
- Ser/deser ms por tipo, bytes tx/rx, ratio de compresión real, hits de caché remotos.
- Uso de CPU del cliente atribuible al mod (aproximado con contadores propios, no `ManagementFactory` en hot path).

## 6.3 Cómo ejecutar los benchmarks del repo

```
# 1) Server (otro host):
java -jar remote-server.jar --config server/remote-server.properties --bench Enabled=true

# 2) Cliente: dentro del juego, config → Diagnostics → “Run benchmark”, o:
#    chat:  /remote bench corpus=(64k|512k|2m) iters=20
# Luego: chat: /remote report  → escribe stats (y CSV si activado)
```

El comando `/remote` (client-side) documentado en la README produce el reporte comparativo local vs remoto, este es el entregable 12 de la especificación.