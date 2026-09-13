# 3. Protocolo binario de Remote Offload

Nombre en clara: **MKOF** (Minecraft-KOffloader). Versión de protocolo `1` (`PROTOCOL_VERSION = 1`).

Todo multi-byte es **big-endian (network order)**. No hay varints en el header (fixed) para acelerar el parseo; los códecs de payload pueden usar varints según necesidad (`VarInt`/`VarLong`).

## 3.1 Frame (unidad en el wire)

```
+--------+-------------+--------+-------+--------+-------+------+---------------+------------+
|  MAGIC | PROTO_VER   |  TYPE  | FLAGS | TASKID |  SEQ  | FRAG | PAYLOAD_LEN   |  PAYLOAD   | CRC32C |
| 4B     | 2B  u16     | 1B u8  | 1B u8 | 8B i64 | 2B u16| 2B u16| 4B u32       | len bytes  | 4B     |
+--------+-------------+--------+-------+--------+-------+------+---------------+------------+

Header fijo = 24 bytes. CRC32C cubre HEADER + PAYLOAD tal como se transmiten (detección de corrupción).
```

Campos:
- `MAGIC` = `0x4D 0x4B 0x4F 0x46` ("MKOF").
- `PROTO_VER`: versión de protocolo del emisor.
- `TYPE`: `PacketType` (sección 3.3).
- `FLAGS`:
  - bit 0 → payload comprimido (deflate/zlib level negociado).
  - bit 1 → payload cifrado no usado en v1 (reservado).
  - bit 2 → urgente (pending de login en mismo socket).
  - bit 3..7 reservados (deben ser 0; el receptor rechaza tramas con bits desconocidos).
- `TASKID`: id único de la tarea de control (para `PING` = sequence; para `BYE`/`ERROR` = 0).
- `SEQ`, `FRAG`: fragmentación. Un **mensaje** lógico puede partirse en `FRAG` tramas con `SEQ` 0..FRAG-1. Para mensajes no fragmentados `SEQ=0, FRAG=1`.
- `PAYLOAD_LEN`: longitud del payload de ESTA trama (≤ `maxFramePayload`, config default 64 KiB). El límite de mensaje reensamblado es `maxPacketBytes` (default 1 MiB, configurable).
- `CRC32C`: checksum de integridad.

## 3.2 Límites y saneamiento

| Parámetro | Default | Límite |
|---|---|---|
| `maxFramePayload` | 64 KiB | 1 MiB |
| `maxPacketBytes` (mensaje lógico) | 1 MiB | 16 MiB hard |
| `maxConcurrentTasks` por cliente | 4 | 64 |
| `maxPending` en cola servidor | 1024 | — |
| `rateLimit` (msgs/seg por cliente) | 120 | — |
| `heartbeatTimeout` | 10 s | — |
| `workerTimeout` | 30 s | — |

El receptor **descarta** (y cierra tras N abusos) tramas que violen límites, tipos desconocidos, flags no válidos, SEQ/FRAG fuera de rango, o que lleguen con `TASKID` aleatorio fuera de la ventana del cliente.

## 3.3 Trie de paquetes

| ID | Nombre | Dirección | Propósito | Payload (Buf) |
|----|--------|-----------|-----------|----------------|
| 0x01 | `CLIENT_HELLO` | C→S | Handshake | `protoVer u16, clientId (16B), clientName utf, [caps u8...]` |
| 0x02 | `SERVER_WELCOME` | S→C | Acuerdo de versión y capacidades | `protoVer u16, minProto u16, authRequired bool, serverName utf, caps` |
| 0x03 | `AUTH_CHALLENGE` | S→C | Desafío auth (si `authRequired`) | `challenge (32B)` |
| 0x04 | `AUTH_RESPONSE` | C→S | `clientId (16B) , nonce (16B), mac (32B = HMAC-SHA256(token, challenge‖nonce))` | — |
| 0x05 | `AUTH_RESULT` | S→C | `ok bool, message utf` | — |
| 0x10 | `TASK_SUBMIT` | C→S | Encolar tarea | `taskType u8, createdAtMs i64, deadlineMs i64, flags u8, payload…` |
| 0x11 | `TASK_ACCEPT` | S→C | aceptada/responsabilidad | `taskId i64, slaMs i64` |
| 0x12 | `TASK_REJECT` | S→C | rechazada | `taskId i64, code u8, reason utf` |
| 0x13 | `TASK_RESULT` | S→C | resultado | `taskId i64, status u8 (0 ok / 1 err), processTimeUs i64, payload…` |
| 0x14 | `TASK_CANCEL` | C→S | cancelar | `taskId i64` |
| 0x15 | `TASK_PROGRESS` | S→C | avance opcional | `taskId i64, percent u8` |
| 0x1F | `TASK_LIST` | C→S | listar trabajos conocidos | `—` |
| 0x20 | `PING` | C→S | RTT | `seq i64, clientSentNanos i64` |
| 0x21 | `PONG` | S→C | RTT | `seq i64, clientSentNanos i64, serverNowNanos i64` |
| 0x30 | `ERROR` | bidir | error de protocolo | `code u16, message utf` |
| 0x31 | `BYE` | bidir | cierre ordenado | `reason utf` |

`TASK_SUBMIT` cuyo payload excede `maxFramePayload` se envía fragmentado; el receptor reensambla por `(taskId, type)` y verifica `ERR_FRAG`.

## 3.4 Handshake y autorización

```
C→S CLIENT_HELLO
S→C SERVER_WELCOME        (si protoVer incompatible → S→C ERROR + BYE; el cliente elige min/max)
S→C AUTH_CHALLENGE        (si authRequired)
C→S AUTH_RESPONSE         (HMAC-SHA256(token, challenge ‖ nonce) con constant-time compare en S)
S→C AUTH_RESULT(ok)       (ratio-limit aplica desde aquí; servidor registra clientId permitido)
C→S PING …                (ya en READY)
```

- `authRequired=false`: basta con `CLIENT_HELLO` y `clientId` válido.
- En `authRequired=true` y token vacío en el cliente → el cliente no envía y espera `ERROR_UNAUTHORIZED`; permanece en `BACKOFF`.
- El servidor puede configurar `allowedClientIds` (lista blanca de ids) además del token.

## 3.5 Compresión y fragmentación (resumen de reglas)

1. Antes de escribir la trama, si `payloadLen > compressionThreshold` (default 512 B) y ambas partes anuncian compresión en `caps`: comprimir con `Deflater` (zlib) nivel negociado (default 6) y marcar flag bit0.
2. Después comprimir: dividir en tramas de `maxFramePayload`; `FRAG = ceil(len/frame)`, `SEQ = 0..FRAG-1`. El reassembly buffer se limpia si `FRAG > 255` o si no llegan todas las tramas en `fragmentTimeoutMs` (default 5 s).
3. Si una trama supera `maxFramePayload` incluso sin comprimir y no se puede comprimir bajo el límite → `ERROR_PAYLOAD_TOO_LARGE` y se rechaza el mensaje.

## 3.6 Versionado futuro

`SERVER_WELCOME` incluye `minProto` y `protoVer` reales del server. El cliente:
- `protoVer == server.protoVer` → READY.
- `protoVer > server.protoVer` → cliente entra en modo **compat (envía solo tipos conocidos del server)** o rechaza (config `requireExactProto`).
- `protoVer < minProto` → ERROR `PROTO_TOO_OLD`, el cliente informa y usa fallback local.

El registro de `PacketType` y de `TaskType` están versionados en `common` (`ProtocolConstants`), de forma que añadir un tipo `0x1F+` no rompe clientes viejos (reciben `TASK_REJECT UNSUPPORTED_TASK`).