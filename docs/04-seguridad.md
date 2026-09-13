# 4. Seguridad del servidor externo

El servidor escucha en TCP y puede estar expuesto a Internet. La superficie de entrada es el protocolo MKOF; toda trama es tratada como **no confiable**.

## 4.1 Autenticación

- **Token compartido** (config `token`). Comparación en tiempo constante (`MessageDigest.isEqual`) para evitar timing attacks.
- **Challenge–response HMAC**: servidor genera 32 B aleatorios por conexión; cliente devuelve `HMAC-SHA256(token, challenge‖nonce)`. Con el token derivado nunca viaja por la red.
- **Identificador de cliente**: 16 B aleatorios (`ClientId`) generados en la primera ejecución del mod y persistidos; el servidor puede exigir lista blanca (`allowedClientIds`).
- **Derivacion de claves** (futuro): `sessionKey = HMAC-SHA256(token, "mkof-session"‖challenge‖nonce)`, listo para cifrar por AES-GCM en protocol v2 (flag bit1 reservado).

## 4.2 Control de recursos y abuso

- **Rate limiting**: token-bucket por dirección IP (90 msg/s) y por cliente (120 msg/s). Las tramas de handshake (no autenticadas) tienen bucket separado y más estricto (10/s).
- **Límites de tamaño**: `maxFramePayload`/`maxPacketBytes`, validados en el decoder *antes* de asignar.
- **Tareas malformadas**: cada `TaskHandler` valida estrictamente su payload; un fallo de deserialización → `TASK_REJECT(ERR_MALFORMED)` + contador de infracciones del cliente. Con 3+ infracciones en 60 s: cierre de conexión y ban 15 min (configurable).
- **Workers acotados**: el pool por tipo está limitado; si la cola excede `maxPending`, `TASK_REJECT(BUSY)` en lugar de saturar.
- **Timeouts**:
  - handshake incompleto en `handshakeTimeout` (10 s) → close.
  - heartbeat: sin `PONG`/progreso en `heartbeatTimeout` (10 s) → close y limpieza (marca tareas como canceladas).
  - task worker: `workerTimeout` default 30 s → `TASK_REJECT(TIMEOUT)`.
  - reassembly de fragmentos: `fragmentTimeoutMs` (5 s).
- **Cierre seguro**: `BYE` mutuo; al cerrar se cancela la cola del cliente, se notifica con `TASK_REJECT(CONN_CLOSED)` y se cierra el socket con `SO_LINGER=0`.

## 4.3 Validación estricta de paquetes (saneamiento)

En `PacketValidator`, ejecutado en el hilo de lectura antes de cualquier dispatch:
1. CRC32C correcto (si no → descartar trama; 3 fallos seguidos → close).
2. `MAGIC` correcto.
3. `PROTO_VER` dentro de [min,máx].
4. tipo conocido y habilitado.
5. flags solo con bits definidos.
6. `PAYLOAD_LEN` ≤ `maxFramePayload` y, tras reensamble, mensaje ≤ `maxPacketBytes`.
7. `SEQ`/`FRAG` coherentes.
8. En fases previas a READY solo se aceptan los tipos válidos (hello/auth/ping/bye/error).
9. `TASKID` distinto a 0 para tareas; sin re-uso de ids activos duplicados.

## 4.4 Notas operativas

- Token vacío + `authRequired=false` → servidor **abierto** (solo para LAN/demos). No recomendado en Internet.
- Recomendación LAN: bind `0.0.0.0`, token propio.
- Recomendación Internet: token ≥ 32 chars, lista blanca de clientes, detrás de un firewall/proxy con TLS (+ documentación de puerto `19724/tcp`), o bien un túnel con TLS (WireGuard/STunnel) y MKOF dentro.
- Logs de eventos de seguridad (`AUTH_FAIL`, `RATE_LIMIT`, `BAN`, `BUF_OVERFLOW`) con severidad.