# Reporte técnico: dataset, limpieza, arquitecturas Lambda y Kappa, y APIs REST/gRPC

Este documento es la referencia técnica del proyecto. Explica qué datos usamos, cómo se limpian, cómo está construida cada arquitectura de ingesta (Lambda y Kappa), cómo se diseñan las dos APIs (REST y gRPC) y cómo se medirá todo en el benchmark. Describe lo que **ya está implementado** y marca lo que está **pendiente**.

> El entregable de la actividad (informe de 2 páginas con referencias SCOPUS / ScienceDirect) se deriva de este documento.

---

## 1. Base de datos y dataset

### 1.1 Motor de base de datos

| Elemento | Valor |
|---|---|
| Motor | PostgreSQL 18 (en Docker) |
| Base de datos | `bidb` |
| Esquemas | `lambda` (arquitectura Lambda) y `kappa` (arquitectura Kappa) |
| Migraciones | Flyway (scripts versionados en `src/main/resources/db/migration`) |

Cada arquitectura escribe en su propio esquema, con las mismas tablas. Así los resultados no se mezclan y se pueden comparar.

### 1.2 Dataset

**eCommerce behavior data from multi-category store** (REES46 Marketing Platform, Kaggle). Contiene eventos de comportamiento de una tienda en línea con muchas categorías. Cada fila es **un evento** entre un usuario y un producto.

- Archivo original usado: `2020-Apr.csv` (abril de 2020, ~9 GB).
- Muestra de trabajo: `data/sample.csv` (1 de cada 100 líneas, 665 893 líneas, ~91 MB). Cubre todo el mes (del 2020-04-01 al 2020-04-30).
- Los archivos CSV **no se suben a GitHub** (están en `.gitignore`).

> **Aviso sobre la muestra:** al tomar 1 de cada 100 filas, se rompen muchas sesiones y se pierden compras de algunos usuarios. Las métricas de sesiones y de usuario serán **aproximadas**. Para comparar Lambda contra Kappa no afecta, porque ambas procesan exactamente el mismo archivo. Pero esos números no deben presentarse como cifras reales del negocio.

### 1.3 Significado de cada columna

| Columna | Tipo en BD | Qué es | Ejemplo |
|---|---|---|---|
| `event_time` | `timestamptz` | Momento exacto del evento, en UTC | `2020-04-01 00:00:38 UTC` |
| `event_type` | `text` | Qué hizo el usuario: `view` (vio el producto), `cart` (lo agregó al carrito), `remove_from_cart` (lo quitó del carrito), `purchase` (lo compró) | `view` |
| `product_id` | `bigint` | Identificador del producto | `1201565` |
| `category_id` | `bigint` | Identificador de la categoría del producto | `2232732101407408685` |
| `category_code` | `text` | Nombre jerárquico de la categoría. Puede venir vacío (sobre todo en accesorios) | `apparel.shoes.slipons` |
| `brand` | `text` | Marca en minúsculas. Puede venir vacía | `apple` |
| `price` | `numeric(10,2)` | Precio del producto en el momento del evento | `406.68` |
| `user_id` | `bigint` | Identificador permanente del usuario | `635165586` |
| `user_session` | `text` | Identificador temporal de la sesión. Cambia cuando el usuario vuelve tras una pausa larga | `48d05455-e287-...` |

Una sesión puede tener varios eventos `purchase`: se considera **una sola orden**.

---

## 2. Limpieza de datos: qué se hace con cada problema

### 2.1 Principio general

La limpieza debe ser **determinista**: la misma fila siempre produce el mismo resultado. Esto es obligatorio porque Lambda y Kappa deben obtener **exactamente las mismas tablas**; si una de ellas "reconstruyera" valores con un método aleatorio o dependiente del orden de llegada, ya no se podría validar la exactitud.

### 2.2 Tabla de decisiones

| Problema encontrado | Decisión | ¿Se pierde la fila? |
|---|---|---|
| `brand` vacío (frecuente) | Se guarda como `unknown` | No |
| `category_code` vacío (frecuente) | Se guarda como `unknown` | No |
| `price` nulo, 0, negativo o no numérico | Se rechaza la fila completa | Sí (queda en `rejected_events`) |
| `event_type` distinto de `view`, `cart`, `remove_from_cart`, `purchase` | Se rechaza | Sí |
| `event_time` ilegible | Se rechaza | Sí |
| Línea con menos de 9 columnas o ids no numéricos | Se rechaza | Sí |
| `user_session` vacío | Se rechaza | Sí |
| Evento duplicado exacto (mismo tiempo, tipo, producto, usuario y sesión) | Se conserva uno solo | Se descartan las copias |
| Espacios y mayúsculas en `brand` / `category_code` | `trim` y minúsculas | No |

Cada fila rechazada se guarda en la tabla `rejected_events` con su número de línea, el texto original y **el motivo**. El informe podrá decir, por ejemplo, "el 0,3 % de filas se rechazó por precio inválido".

### 2.3 ¿Por qué se descarta toda la fila cuando el precio es 0 o negativo?

No se borra "solo la columna", se rechaza el evento completo. Razones:

1. **El precio no es un dato "perdido" que se pueda inferir.** Es un hecho del mundo real (cuánto costaba ese producto). Un precio `<= 0` suele ser un error del sistema, un producto de prueba o una devolución mal registrada.
2. **Dejar la fila con precio vacío contamina las métricas.** Una compra con precio 0 suma 0 a los ingresos pero sí cuenta como venta, lo que baja artificialmente el ticket promedio.
3. **Mantiene el pipeline simple y rápido**, que es lo que se mide en el benchmark.

### 2.4 ¿Y si se reconstruyen los valores (media, Random Forest)?

Es una pregunta válida. La respuesta es: **depende del campo, y para este proyecto no recomendamos imputación estadística.**

| Método | ¿Sirve aquí? | Motivo |
|---|---|---|
| **Imputar `price` con la media** | No | Hay precios desde 1 hasta más de 2 000. La media de la tienda no representa a un producto concreto y falsea ingresos |
| **Random Forest u otro modelo de ML** | No | No es determinista entre ejecuciones, es lento para millones de filas y haría imposible comparar Lambda contra Kappa. Además distorsiona el benchmark: se mediría el modelo, no la arquitectura |
| **Reconstruir `brand` / `category_code` desde otras filas del mismo `product_id`** | Sí, como mejora opcional | Un mismo producto aparece muchas veces; si en otra fila trae marca y categoría, se puede copiar. Es exacto, no es una estimación |
| **Reconstruir `price` desde otra fila del mismo `product_id`** | Con cuidado | El precio de un producto puede variar en el tiempo; solo sería válido usando el precio más cercano en fecha |
| **Marcar como `unknown`** | Sí (decisión actual) | Conserva el evento, es simple y no inventa información |

**Decisión actual:** `unknown` para marca y categoría, rechazo para precio inválido.

**Mejora planeada (opcional):** una tabla `product_catalog` construida con las filas completas de cada producto, para rellenar `brand` y `category_code` por `product_id` de forma exacta. Se mide el costo extra de ese paso en el benchmark y se decide si vale la pena. En Lambda es fácil (el batch ve todos los datos); en Kappa es más difícil, porque el stream solo conoce lo que ya llegó. Ese contraste es un buen punto para el informe.

### 2.5 Información que se mide durante la limpieza

Para cada ejecución se registra:

- filas leídas,
- filas guardadas,
- filas rechazadas (por cada motivo),
- duplicados descartados,
- tiempo total y eventos por segundo.

---

## 3. Infraestructura y stack tecnológico

### 3.1 Componentes y versiones

| Componente | Versión / detalle |
|---|---|
| Lenguaje (ingesta y APIs) | Java 21 (Temurin 21.0.12) |
| Framework | Spring Boot 4.1.1 (Spring Framework 7.0.9) |
| Cliente Kafka | `spring-kafka` 4.1.1, `kafka-clients` y `kafka-streams` 4.2.1 |
| Estado de Kafka Streams | RocksDB (`rocksdbjni` 10.1.3) |
| Base de datos | PostgreSQL 18.6 en Docker (puerto del host 5433) |
| Acceso a datos | JDBC con `JdbcTemplate`, `postgresql` 42.7.13, HikariCP 7.0.2, `reWriteBatchedInserts=true` |
| Migraciones | Flyway 12.4.0 |
| Broker | Apache Kafka, 1 broker en Docker (puerto 9092) |
| Simulador | C++17, `librdkafka` (vcpkg), CMake, MSVC |
| Entorno de ejecución | Windows; las apps Spring se ejecutan desde IntelliJ; Postgres y Kafka en Docker Compose (`infra/`) |

### 3.2 Topics de Kafka

| Topic | Lo consume | Particiones | Réplicas | Clave del mensaje | Valor |
|---|---|---|---|---|---|
| `events` | Lambda (capa speed), grupo `lambda-speed` | 3 | 1 | `user_id` | línea CSV de 9 columnas |
| `events-kappa` | Kappa (Kafka Streams), `application.id = kappa-ingestion` | 3 | 1 | `user_id` | igual |

La clave es `user_id`, de modo que todos los eventos de un usuario caen en la misma partición y conservan su orden relativo. Cada arquitectura usa su propio topic para que no se mezclen.

### 3.3 Aislamiento entre arquitecturas

- Un schema de Postgres por arquitectura (`lambda`, `kappa`) con su propio historial de Flyway.
- Un topic por arquitectura.
- Las apps se pueden apagar y reiniciar de forma independiente. `reset-env.ps1` vacía las tablas y borra los topics (incluidos los internos de Kafka Streams) y la carpeta `kappa-state` sin tocar la base de datos.
- Para el benchmark, **una sola arquitectura corre a la vez** (si corren juntas compiten por CPU y Postgres y los tiempos se contaminan).

---

## 4. Simulador de eventos (C++)

Genera eventos con el mismo formato que el CSV de REES46 (una línea CSV por mensaje) y los publica en Kafka. Es el único productor de carga en tiempo real para ambas arquitecturas.

### 4.1 Cómo genera los datos

- **Catálogo real:** lee `sample.csv`, guarda cada producto distinto y usa cuántas veces aparece como su peso. La popularidad simulada se parece a la real (pocos productos concentran las vistas).
- **Sesiones:** hasta 2 000 sesiones activas a la vez. Cada evento elige una sesión; con probabilidad 4 % la sesión termina y se crea otra.
- **Tipo de evento:** si la sesión tiene carrito, 6 % `purchase` y 1 % `remove_from_cart`; si ya vio productos, 4 % `cart`; el resto son `view`.
- **Reloj simulado:** empieza el `2020-05-01` y avanza 100 ms por evento, así los eventos nuevos (mayo) no chocan con el histórico (abril).
- **Reproducible:** semilla fija (`--seed 42` por defecto), misma secuencia en cada corrida.

### 4.2 Datos sucios inyectados a propósito

| Anomalía | Tasa por defecto | Qué simula | Quién la maneja |
|---|---|---|---|
| Duplicado exacto | 0,2 % (`--dup-rate`) | Reenvíos | Batch: índice único. Kappa: store de deduplicación. Speed de Lambda: no deduplica |
| Precio inválido (0 o negativo) | 0,1 % (`--invalid-rate`) | Datos basura | `CleaningRules` rechaza la fila |
| Evento fuera de orden | 1 % (`--ooo-rate`) | Llega hasta 5 min tarde | Se agrega por el día del evento, no hay ventanas |

### 4.3 Modos y parámetros

| Parámetro | Valor por defecto | Descripción |
|---|---|---|
| `--brokers` | `localhost:9092` | Broker de Kafka |
| `--topic` | `events` | `events` para Lambda, `events-kappa` para Kappa |
| `--total` | 1 000 000 | Eventos a enviar |
| `--rate` | 5 000 | Eventos por segundo (`0` = máxima velocidad) |
| `--seed` | 42 | Semilla |
| `--replay-csv` | (vacío) | Publica un CSV existente en lugar de generar eventos (histórico en Kappa) |
| `--catalog`, `--start-date`, `--sim-step-ms`, `--max-sessions` | ver código | Catálogo, fecha inicial, avance del reloj, sesiones activas |

Productor: `linger.ms=5`, `batch.num.messages=10000`, compresión `lz4`, cola de 1 000 000 mensajes y control de tasa en ticks de 10 ms. Imprime cada segundo enviados, tasa, confirmados y fallidos (callbacks de entrega) y un resumen final.

**Latencia de extremo a extremo:** el timestamp del registro de Kafka (momento en que el simulador lo envía) se compara con el momento en que el dato ya está en la base de datos. Funciona igual con el replay del CSV, porque el timestamp es el de envío y no el del evento.

---

## 5. Arquitectura Lambda (implementada)

### 5.1 Idea general

Lambda combina **dos caminos** que producen el mismo tipo de resultado y una capa que los une:

- **Capa batch:** procesa el histórico completo de forma exacta, con SQL.
- **Capa speed:** procesa eventos nuevos desde Kafka, de forma incremental.
- **Capa serving:** vistas SQL que unen ambos resultados para que la API consulte un solo lugar.

```
                 ┌────────────► CAPA BATCH ───────────────────────────┐
 sample.csv ─────┤  leer → limpiar → guardar (events) → agregar (SQL)  │
 (abril)         │                                                     ├──► CAPA SERVING ──► API
                 │                                                     │    vistas serving_*
 simulador C++ ──┴─► Kafka (events) ─► CAPA SPEED ────────────────────┘
 (mayo)               3 particiones    limpiar → pre-agregar → upsert incremental
```

Las dos capas se activan de forma independiente con `ingestion.batch.enabled` e `ingestion.speed.enabled` (con `spring.main.keep-alive=true` la app se queda escuchando Kafka después de terminar el batch). Para el benchmark oficial cada capa se mide sola.

### 5.2 Capa batch

```
sample.csv → [LEER] → [LIMPIAR] → [GUARDAR eventos limpios] → [AGREGAR con SQL] → tablas analíticas
                          │
                          └→ filas descartadas → rejected_events (con el motivo)
```

1. **Arranque limpio:** `BenchmarkRunner` vacía `events` y `rejected_events` (y las `speed_*` solo si la capa speed está apagada, porque Kafka recuerda hasta dónde leyó y vaciarlas perdería datos ya consumidos).
2. **Leer, limpiar y guardar:** `IngestionJob` lee el CSV por bloques (`ingestion.chunk-size = 10 000`), `EventProcessor` aplica `CleaningRules`, `EventDbWriter` inserta los eventos limpios en `lambda.events` por lotes y `RejectedWriter` guarda los rechazados.
3. **Deduplicación:** el índice único `uq_events_dedup (event_time, event_type, product_id, user_id, user_session)` descarta los duplicados exactos. Se calculan como *aceptadas − insertadas*.
4. **Agregar:** `AggregationService.rebuildAll()` vacía las tablas `daily_*` y las reconstruye con tres sentencias `INSERT ... SELECT ... GROUP BY` sobre `events`. Siempre recalcula todo, por eso la capa batch es exacta.
5. **Registro:** cada corrida se guarda en `run_log` (líneas leídas, rechazadas, duplicados, insertadas, tiempos y eventos/s).

Definición de las métricas del día en `daily_sales`: `revenue` = suma de precios de las compras; `units` = `purchases` = número de compras; `orders` = sesiones distintas con compra; `avg_ticket` = `revenue / orders`; `active_users` = usuarios distintos. `remove_from_cart` no se cuenta.

### 5.3 Capa speed

```
Kafka (events) → @KafkaListener (lote de hasta 5 000 registros, una transacción)
   → limpiar con CleaningRules (las mismas reglas del batch)
   → pre-agregar en memoria por (día), (día, producto), (día, categoría)
   → UPSERT incremental: columna = columna + valor nuevo (ON CONFLICT DO UPDATE)
   → tablas speed_daily_sales, speed_product_stats, speed_category_sales
```

- Listener en modo *batch* (`spring.kafka.listener.type=batch`, `max-poll-records=5000`), grupo `lambda-speed`, `auto-offset-reset=earliest`.
- El método es `@Transactional`: los tres upserts de un lote se confirman juntos.
- **Pre-agregar antes de escribir** reduce las filas por lote (muchos eventos caen en la misma clave) y las escrituras a Postgres.
- `SpeedMetrics` imprime cada 5 s `[SPEED] eventos/s | total | rechazados | latencia media y máxima` y un resumen al apagar.
- Definición idéntica a la del batch: `units = purchases`, `revenue` = suma de precios de las compras, `remove_from_cart` no cuenta.

### 5.4 Capa serving

La migración `V4__speed_layer.sql` crea tres vistas que suman batch y speed por clave (`UNION ALL` + `GROUP BY`):

| Vista | Une |
|---|---|
| `serving_daily_sales` | `daily_sales` + `speed_daily_sales` |
| `serving_product_stats` | `daily_product_stats` + `speed_product_stats` |
| `serving_category_sales` | `daily_category_sales` + `speed_category_sales` |

Con los datos actuales se ve abril (batch) y mayo (speed) en una sola consulta. La API de Lambda leerá estas vistas.

### 5.5 Estructura del código

Paquete base `com.bi.ingestionlambda`:

```
ingestion-lambda/src/main/
├── java/com/bi/ingestionlambda/
│   ├── IngestionLambdaApplication.java
│   ├── config/       IngestionProperties          ruta del CSV, tamaño de chunk
│   ├── batch/        EventCsvReader, EventProcessor, EventDbWriter,
│   │                 RejectedWriter, IngestionJob
│   ├── cleaning/     CleaningRules, CleaningResult
│   ├── domain/       RawEvent, CleanEvent, RejectedRow, RejectReason
│   ├── aggregation/  AggregationService           SQL de agregación del batch
│   ├── metrics/      RunMetrics                   tiempos y contadores del batch
│   ├── runner/       BenchmarkRunner              lanza el batch y mide
│   └── speed/        KafkaTopicConfig, SpeedLayerListener, SpeedMetrics
└── resources/
    ├── application.properties
    └── db/migration/
        ├── V1__events.sql
        ├── V2__rejected_and_dedup.sql    rechazados, índice único, run_log, event_date
        ├── V3__analytics_tables.sql      daily_sales, daily_product_stats, daily_category_sales
        └── V4__speed_layer.sql           speed_* y vistas serving_*
```

`CleaningRules` está aislada porque **Kappa usa exactamente las mismas reglas**. Así la comparación es justa.

### 5.6 Garantías y limitaciones (para el informe)

| Aspecto | Comportamiento actual | Consecuencia |
|---|---|---|
| Entrega en la capa speed | *At-least-once*: se confirma la transacción en Postgres y después el offset en Kafka | Si la app cae entre ambos pasos, el lote se reprocesa y el upsert aditivo **cuenta dos veces** |
| Deduplicación | El batch deduplica (índice único); la capa speed **no** | Un duplicado inyectado por el simulador suma dos veces en speed |
| Rechazados | El batch los guarda en `rejected_events`; la capa speed solo los **cuenta** | No hay detalle de rechazos en tiempo real |
| Unión batch + speed | Se suman por clave | Correcto mientras **cada evento entre por un solo camino**. Si un mismo evento pasara por ambos se contaría doble |
| `orders`, `avg_ticket`, `active_users` | Solo existen en `daily_sales` (batch) | No se pueden sumar entre días ni entre capas (ver 9.4); las vistas `serving_*` no los incluyen |
| Eventos crudos | `events` solo guarda lo que entra por el batch | La capa speed no conserva eventos individuales |

### 5.7 Resultados preliminares (pruebas de humo, no son la medición oficial)

| Prueba | Resultado |
|---|---|
| Batch sobre `sample.csv` | 665 892 líneas leídas; 665 310 aceptadas; 582 rechazadas (0,09 %, todas `INVALID_PRICE`); 1 duplicado; 665 309 insertadas |
| Batch: tiempos | Carga 6 534 ms (**101 912 eventos/s**); agregación 2 052 ms |
| Speed: humo con 39 eventos | Latencia media 176 ms, máxima 192 ms (muestra demasiado pequeña para comparar) |

---

## 6. Arquitectura Kappa (implementada)

### 6.1 Idea general

Kappa tiene **un solo camino**: todo es un stream. El histórico no se lee con un job aparte; el simulador lo publica en Kafka (`--replay-csv`) y lo procesa el mismo código que procesa los eventos nuevos. No hay capa batch ni capa serving: una sola tabla por analítico.

```
 sample.csv ──┐  (replay)
              ├──► Kafka (events-kappa) ──► KAFKA STREAMS ──► KappaSink ──► schema kappa ──► API
 simulador ───┘                              (limpiar, deduplicar,   (UPSERT cada 500 ms)
                                              agregar con estado)
```

### 6.2 Topología de Kafka Streams

```
events-kappa
   └─► CleanDedup: limpiar (CleaningRules) + deduplicar (store RocksDB "dedup-store")
          ├─► selectKey(día)             ─► aggregate (agg-daily)    ─┐
          ├─► selectKey(día|producto)    ─► aggregate (agg-product)   ├─► KappaSink ─► Postgres
          └─► selectKey(día|categoría)   ─► aggregate (agg-category) ─┘
```

| Elemento | Detalle |
|---|---|
| Limpieza | Las mismas `CleaningRules`; los rechazados se envían al sink y se guardan en `kappa.rejected_events` con su motivo |
| Deduplicación | Store persistente `dedup-store`; clave `event_time\|tipo\|producto\|usuario\|sesión`. Mismo criterio que el índice único del batch |
| Agregaciones con estado | Tres `groupByKey().aggregate()` con stores RocksDB (`agg-daily`, `agg-product`, `agg-category`) respaldados por *changelog topics* |
| Re-particionado | Al cambiar la clave (día, producto, categoría) Kafka Streams crea topics internos `kappa-ingestion-rep-*-repartition` |
| Tareas | 4 sub-topologías × 3 particiones = **12 tareas con estado**, repartidas en 3 hilos (`num.stream.threads=3`) |
| Fuera de orden | Se agrega por el día del evento, sin ventanas, así que un evento tardío solo actualiza su día |
| Valores | Cadenas separadas por `\|` (`ingreso\|vistas\|carritos\|compras\|timestampMax`) |

### 6.3 Escritura a Postgres (`KappaSink`)

Cada agregación emite el **valor total** de su clave. El sink guarda el último total por clave en memoria y cada **500 ms** hace un `INSERT ... ON CONFLICT DO UPDATE SET columna = EXCLUDED.columna` por lotes. Como escribe totales y no sumas, el upsert es **idempotente**. Si una escritura falla, los datos se reintentan en el siguiente ciclo.

Configuración relevante: `commit.interval.ms=500`, caché de stores de 32 MB, `state.dir=kappa-state`, garantía de procesamiento `at_least_once` (valor por defecto).

### 6.4 Estructura del código

Paquete base `com.bi.ingestionkappa`:

```
ingestion-kappa/src/main/
├── java/com/bi/ingestionkappa/
│   ├── cleaning/   CleaningRules, CleaningResult     (copia de Lambda)
│   ├── domain/     RawEvent, CleanEvent, RejectReason (copia de Lambda)
│   ├── metrics/    KappaMetrics                      eventos/s, rechazados, duplicados, latencia
│   └── stream/     KafkaTopicConfig, KappaTopology, KappaSink
└── resources/
    ├── application.properties
    └── db/migration/V1__kappa.sql                    daily_sales, daily_product_stats,
                                                      daily_category_sales, rejected_events
```

### 6.5 Garantías y limitaciones

| Aspecto | Comportamiento actual | Consecuencia |
|---|---|---|
| Garantía de procesamiento | `at_least_once` | Tras una caída, Kafka Streams puede reaplicar eventos al estado y **sobrecontar**. Mejora posible: `exactly_once_v2`, con costo de rendimiento a medir en el benchmark |
| Deduplicación | Sí, en el stream | Los totales descartan los duplicados exactos (a diferencia de la capa speed de Lambda) |
| Eventos crudos | No se guardan | Solo existen los agregados. No hay tabla `events` |
| Métricas `orders`, `avg_ticket`, `active_users` | No implementadas | Requieren conteos distintos y más estado; se documenta como limitación |
| Reset completo | Hay que borrar topics internos y `kappa-state` | Cubierto por `reset-env.ps1` |

### 6.6 Resultados preliminares (prueba de humo)

10 000 eventos a 2 000 eventos/s: 12 rechazados (0,12 %), 23 duplicados descartados (0,23 %), coherente con las tasas de 0,1 % y 0,2 % del simulador. Latencia media entre 563 y 666 ms (máxima 1 246 ms); incluye la ventana de escritura de 500 ms. **Aún falta** validar el histórico completo con `--replay-csv`.

---

## 7. Lambda vs Kappa: comparación de diseño

| Aspecto | Lambda | Kappa |
|---|---|---|
| Caminos de procesamiento | Dos (batch SQL + speed en Java) | Uno (stream) |
| Histórico | Capa batch lee el CSV directo | Replay del CSV a Kafka |
| Tiempo real | Consumidor Kafka (Spring Kafka) | Kafka Streams |
| Dónde se agrega | Batch: SQL en Postgres. Speed: Java, en memoria por lote | Java, con estado en RocksDB |
| Estado | Postgres (tablas `speed_*`) | RocksDB + *changelog topics* en Kafka |
| Resultado | Tablas batch + tablas speed + vistas `serving_*` (merge) | Una tabla por analítico, sin merge |
| Eventos crudos | Sí (`events`, solo batch) | No |
| Deduplicación | Batch sí; speed no | Sí |
| Rechazados | Batch: tabla. Speed: solo contador | Tabla |
| Garantía de entrega | At-least-once, upsert aditivo (no idempotente) | At-least-once, sink idempotente (estado puede sobrecontar tras una caída) |
| Métricas del día | Batch: completas (`orders`, `avg_ticket`, `active_users`). Speed y serving: solo aditivas | Solo aditivas |
| Código a mantener | Dos lógicas de agregación (SQL y Java) | Una |
| Dependencia operativa | Postgres + Kafka | Kafka retiene el log (+ RocksDB local) |

**Métrica de latencia, diferencias a tener en cuenta**

- **Lambda speed:** promedio por evento de *(instante en que termina de escribir el lote − timestamp del registro)*, medido dentro del método justo antes del commit de la transacción.
- **Kappa:** por cada fila escrita, *(instante del flush − timestamp máximo de los eventos que la componen)*. Incluye el ciclo de 500 ms del sink y favorece a los eventos más recientes de cada clave.

No son idénticas. Antes del benchmark oficial hay que unificar la forma de medir (ver decisiones abiertas).

---

## 8. Tablas por esquema

### 8.1 Esquema `lambda` (implementado)

| Tabla / vista | Una fila por | Contenido |
|---|---|---|
| `events` | evento limpio | Columnas del CSV + `event_date` (columna generada) |
| `rejected_events` | fila rechazada | `line_number`, `raw_line`, `reason` |
| `run_log` | corrida del batch | Contadores, tiempos y eventos/s |
| `daily_sales` | día | `revenue`, `orders`, `units`, `views`, `carts`, `purchases`, `avg_ticket`, `active_users` |
| `daily_product_stats` | día + producto | `views`, `carts`, `purchases`, `revenue` |
| `daily_category_sales` | día + categoría | `revenue`, `units`, `views`, `carts`, `purchases` |
| `speed_daily_sales` | día | `revenue`, `units`, `views`, `carts`, `purchases` |
| `speed_product_stats` | día + producto | igual que `daily_product_stats` |
| `speed_category_sales` | día + categoría | igual que `daily_category_sales` |
| `serving_daily_sales`, `serving_product_stats`, `serving_category_sales` (vistas) | igual que su tabla | Suma de la tabla batch y la tabla speed |

### 8.2 Esquema `kappa` (implementado)

| Tabla | Una fila por | Contenido |
|---|---|---|
| `daily_sales` | día | `revenue`, `units`, `views`, `carts`, `purchases` |
| `daily_product_stats` | día + producto | `views`, `carts`, `purchases`, `revenue` |
| `daily_category_sales` | día + categoría | `revenue`, `units`, `views`, `carts`, `purchases` |
| `rejected_events` | fila rechazada | `line_number`, `raw_line`, `reason` |

### 8.3 Tablas planeadas (no implementadas)

`daily_funnel`, `daily_brand_sales`, `hourly_traffic`, `daily_sessions` y `user_stats`. La base de todo es el **día**: semana, mes y año se obtienen **sumando días** (un año son solo 365 filas, sumar cuesta de 1 a 3 ms), así que no hay que guardar una tabla por período y los datos nunca quedan obsoletos.

---

## 9. Analíticos útiles (qué se puede responder)

### 9.1 Los más valiosos

| Analítico | Qué responde | Tabla de origen | Cómo se obtiene por semana / mes |
|---|---|---|---|
| **Top productos más vendidos** | ¿Qué productos venden más (unidades o ingresos) en un día, semana o mes? | `daily_product_stats` | Suma los días del rango, ordena y toma los N primeros |
| **Top productos más vistos** | ¿Qué productos atraen más interés? | `daily_product_stats` | Igual, ordenando por `views` |
| **Ventas por período** | ¿Cuánto se vende por día, semana, mes o año y cómo crece? | `daily_sales` | Suma por `date_trunc` y calcula crecimiento contra el período anterior |
| **Embudo de conversión** | ¿Cuántas vistas terminan en carrito y en compra? | `daily_funnel` | Suma días y recalcula las tasas |
| **Ventas por categoría** | ¿Qué categorías generan más ingresos? | `daily_category_sales` | Suma por mes y categoría |

### 9.2 Complementarios

| Analítico | Qué responde |
|---|---|
| Ventas por marca | ¿Qué marcas lideran? (`daily_brand_sales`) |
| Horas pico | ¿A qué hora hay más tráfico y compras? (`hourly_traffic`) |
| Ticket promedio | ¿Cuánto gasta una orden en promedio? (`daily_sales`) |
| Productos con muchas vistas y pocas compras | Oportunidades de mejora de precio o ficha del producto |
| Productos más agregados y abandonados | `cart` alto con `purchase` bajo |
| Valor de cliente | ¿Quiénes gastan más? (`user_stats`) |

### 9.3 Ejemplo concreto: top 10 de la semana

La API no recorre millones de eventos. Consulta `serving_product_stats` (vista que une batch y speed sobre `daily_product_stats` y `speed_product_stats`), que ya tiene una fila por día y producto:

```sql
SELECT product_id, SUM(purchases) AS purchases, SUM(revenue) AS revenue
FROM lambda.serving_product_stats
WHERE day BETWEEN '2020-04-06' AND '2020-04-12'
GROUP BY product_id
ORDER BY revenue DESC
LIMIT 10;
```

Esta consulta recorre unas decenas de miles de filas pequeñas en lugar de millones de eventos. De ahí viene la velocidad en milisegundos.

### 9.4 Consideración sobre usuarios únicos

No se pueden sumar "usuarios únicos por día" para obtener los de la semana, porque el mismo usuario aparece en varios días. Esas cifras se calculan aparte (conteo directo o un contador aproximado tipo HyperLogLog). Es un detalle técnico que se documenta en el informe.

---


---

## 10. Arquitectura de las APIs (REST y gRPC)

### 10.1 Objetivo

Comparar **REST** y **gRPC** exponiendo exactamente las mismas consultas, sobre los mismos datos y bajo las mismas condiciones, de modo que la única variable sea el protocolo. Las APIs no escriben ni procesan eventos: **solo leen** los resultados que dejaron las arquitecturas de ingesta.

### 10.2 Decisión: dos proyectos independientes

| Proyecto | Artifact | Paquete | Puertos |
|---|---|---|---|
| `api-rest` | `api-rest` | `com.bi.apirest` | HTTP 8080 |
| `api-grpc` | `api-grpc` | `com.bi.apigrpc` | gRPC 9090 (Netty), métricas 8081 |

Se separan por tres razones:

1. **Medición aislada.** Cada API es un proceso distinto con su propia JVM, su pool de conexiones y su CPU/RAM. Así Prometheus y Grafana atribuyen los recursos a un solo protocolo. Con un solo proceso, el servidor que está inactivo igual consumiría memoria y compartiría el pool.
2. **Stacks distintos.** REST corre sobre Tomcat (HTTP/1.1, JSON) y gRPC sobre Netty (HTTP/2, Protobuf). Mezclarlos en una app dificulta saber qué servidor atiende cada petición.
3. **Coherencia con el repositorio.** Igual que `ingestion-lambda` e `ingestion-kappa` (que comparten las reglas de limpieza por copia), ambos proyectos mantienen **la misma estructura de capas y el mismo SQL**. Duplicar unas tres consultas es un costo menor frente a lo que se gana en aislamiento.

Los dos proyectos reemplazan al esqueleto `API` inicial (que traía una migración `raw_events` en el schema `public` que ya no se usa).

### 10.3 Arquitectura en capas (igual en ambos)

```
Cliente (JMeter)
   │  HTTP/1.1 + JSON                      HTTP/2 + Protobuf
   ▼                                        ▼
[ Controlador REST ]                  [ Servicio gRPC ]       ← capa de transporte (lo único distinto)
          └──────────────┬───────────────┘
                         ▼
              [ Servicio de negocio ]    validación, límites, ensamblado de la respuesta
                         ▼
                [ Repositorio JDBC ]     SQL parametrizado, sin ORM
                         ▼
            [ HikariCP ] → PostgreSQL     vistas/tablas de agregados (nunca `events`)
```

Principios de diseño:

- **Solo lectura y sin estado:** no hay sesiones ni caché. Cualquier instancia responde cualquier petición.
- **Sin Flyway:** el esquema pertenece a los proyectos de ingesta; la API solo consulta.
- **Consultas sobre agregados:** se lee `serving_*` (días × clave), no millones de eventos. Es lo que permite responder en milisegundos.
- **Sin ORM:** SQL explícito con `JdbcTemplate`, para controlar el costo de cada consulta.
- **Misma configuración en ambas:** mismo tamaño de pool de Hikari, misma versión de Java y mismas opciones de JVM; se fijan y se documentan en el benchmark.

### 10.4 Contrato: las mismas tres operaciones

| Operación | REST | gRPC | Fuente |
|---|---|---|---|
| Ventas por período | `GET /api/v1/sales?from=&to=&granularity=day\|week\|month` | `GetSales(SalesRequest)` | `serving_daily_sales` |
| Top productos | `GET /api/v1/products/top?from=&to=&metric=revenue\|views\|purchases&limit=` | `GetTopProducts(TopProductsRequest)` | `serving_product_stats` |
| Ventas por categoría | `GET /api/v1/categories/sales?from=&to=&limit=` | `GetCategorySales(CategorySalesRequest)` | `serving_category_sales` |

Las tres tienen costo distinto a propósito: ventas por período devuelve pocas filas (liviana), top productos agrega decenas de miles de filas y ordena (media), ventas por categoría sobre un mes agrupa por categoría y día (más pesada). Así el benchmark no depende de una sola consulta.

Reglas comunes:

- `from` ≤ `to` y rango máximo de un año; `limit` entre 1 y 100.
- Las respuestas llevan los mismos campos, el mismo orden y el mismo redondeo en ambas APIs.
- **Errores:** REST con códigos HTTP y cuerpo `application/problem+json`; gRPC con códigos de estado (`INVALID_ARGUMENT`, `INTERNAL`, `DEADLINE_EXCEEDED`).
- **Contratos:** REST documentado con OpenAPI; gRPC definido en un archivo `.proto` (`src/main/proto/`), que además genera el código.

### 10.4.1 Origen de los datos

La API lee el esquema que se configure (`lambda` o `kappa`) con el **mismo nombre de vistas** `serving_*`. Lambda ya las tiene (V4). Para Kappa se añadirá una migración `V2` con vistas del mismo nombre sobre sus tablas (sin merge, porque Kappa no tiene capas), de modo que la API cambia de arquitectura solo con la configuración.

### 10.5 Diferencias técnicas que se van a medir

| Aspecto | REST | gRPC |
|---|---|---|
| Servidor | Tomcat embebido | Netty embebido |
| Protocolo | HTTP/1.1 (una petición por conexión a la vez) | HTTP/2 (multiplexa muchas llamadas en una conexión) |
| Serialización | JSON (texto, Jackson) | Protobuf (binario) |
| Tamaño de respuesta | Mayor | Menor |
| Contrato | OpenAPI (opcional) | `.proto` (obligatorio, genera código) |
| Modelo de hilos | Pool de hilos del servidor | Ejecutor de llamadas; las llamadas JDBC bloqueantes no deben correr en el event loop |

El modelo de hilos se iguala hasta donde el framework lo permite (por ejemplo, hilos virtuales de Java 21 en ambos) y se documenta lo que no se pueda igualar. En Lambda ya se usan hilos virtuales (`spring.threads.virtual.enabled=true`).

### 10.6 Observabilidad de las APIs

- Spring Boot Actuator + Micrometer exponen `/actuator/prometheus` (en `api-grpc`, en un puerto de gestión separado, para no mezclarlo con el tráfico gRPC).
- Métricas: CPU, memoria y GC de la JVM, hilos, conexiones activas y en espera del pool de Hikari, y latencia por operación. REST usa `http.server.requests`; en gRPC se registra un `Timer` de Micrometer con un interceptor.

### 10.6.1 Lo que NO se incluye en la línea base

| Elemento | Por qué no está | Cuándo tendría sentido |
|---|---|---|
| Redis (caché) | Mediría la caché, no la API | Experimento opcional posterior ("con caché mejora X %") |
| Balanceador de carga | Exige varias instancias y mide otra cosa | Experimento opcional de escalado horizontal |
| *Rate limiting* | JMeter envía todo desde una sola IP; bloquearía la prueba y daría errores 429 sin valor | Se menciona como medida de producción en el informe |
| Autenticación y TLS | Fuera del alcance de la comparación | Se declara como limitación |

---

## 11. Metodología de benchmark y observabilidad

### 11.1 Qué mide cada herramienta

| Herramienta | Punto de vista | Qué responde |
|---|---|---|
| **Apache JMeter** | Desde fuera (cliente) | Latencia (p50, p95, p99), peticiones por segundo, tasa de error y punto de quiebre |
| **Prometheus + Grafana** | Desde dentro (servidor) | CPU, memoria, GC, conexiones a Postgres, hilos y, en la ingesta, eventos/s y latencia |
| **Logs `[SPEED]` / `[KAPPA]` y `run_log`** | Aplicación de ingesta | Eventos/s, rechazados, duplicados y latencia de extremo a extremo |

JMeter dice *qué* pasó ("a 800 req/s el p95 pasó de 120 ms a 900 ms"); Prometheus y Grafana dicen *por qué* ("porque se agotó el pool de conexiones" o "porque la CPU llegó al 90 %"). Para evaluar qué tan bien soporta la arquitectura se necesitan las dos.

El plugin de gRPC para JMeter es de la comunidad y no viene incluido. Hay que confirmar con el docente cómo se prefiere probar gRPC (alternativa: la herramienta `ghz`).

### 11.2 Métricas

| Capa | Métricas |
|---|---|
| Ingesta batch | Tiempo de carga, eventos/s, tiempo de agregación, filas rechazadas (por motivo), duplicados |
| Ingesta streaming | Eventos/s sostenidos, latencia de extremo a extremo (media y máxima), rechazados, duplicados, atraso (*lag*) del consumidor |
| APIs | p50 / p95 / p99, peticiones por segundo, tasa de error, tamaño medio de respuesta, CPU, RAM, conexiones del pool |
| Exactitud | Las tablas de Lambda coinciden con las de Kappa para los mismos datos; REST y gRPC devuelven resultados idénticos |

### 11.3 Reglas del experimento

1. **Una sola arquitectura o API a la vez**, con los mismos recursos asignados.
2. **Mismo dataset, misma semilla** (`--seed`) y mismos parámetros del simulador.
3. **Calentamiento** antes de medir (para que la JVM compile el código caliente y se llene el pool).
4. **De 3 a 5 repeticiones** por prueba, reportando la mediana.
5. Registrar las especificaciones de la máquina (CPU, RAM, disco, versión de Docker). Todo corre en un solo equipo (API, Postgres, Kafka y JMeter), así que las cifras absolutas no se comparan con otros sistemas; la comparación entre REST y gRPC sí es válida porque ambas corren en las mismas condiciones.
6. Reiniciar el estado entre pruebas (`reset-env.ps1`).

### 11.4 Escenarios para las APIs

| Escenario | Objetivo |
|---|---|
| Humo | Verificar que las tres operaciones responden y que REST y gRPC devuelven lo mismo |
| Carga escalonada | Subir los usuarios concurrentes por pasos y observar cómo escala la latencia |
| Estrés | Seguir subiendo hasta el punto de quiebre (latencia disparada o errores) |
| Resistencia (opcional) | Carga constante durante varios minutos para detectar fugas de memoria o degradación |
| Lectura bajo escritura (opcional) | Consultar la API mientras la ingesta en streaming está escribiendo |

### 11.5 Cuándo se considera buena una API

- p95 bajo y estable.
- Escala de forma predecible al subir la carga.
- Sin errores hasta llegar a su límite.
- Degrada de forma gradual al saturarse, sin caerse.
- Devuelve datos correctos (iguales a los de la base de datos).
- Se puede observar con métricas.

---

## 12. Estado del proyecto

| Pieza | Estado |
|---|---|
| Dataset y muestra (`sample.csv`) | Hecho |
| Limpieza (`CleaningRules`) | Hecho |
| Lambda: capa batch | Hecho (medido) |
| Lambda: capa speed y vistas `serving_*` | Hecho (prueba de humo) |
| Simulador C++ (generación y `--replay-csv`) | Hecho |
| Kappa (Kafka Streams + sink) | Hecho (prueba de humo) |
| Script de reinicio (`reset-env.ps1`) | Hecho |
| Validar Kappa con el histórico completo y compararlo con el batch de Lambda | **Pendiente** |
| `api-rest` y `api-grpc` | **Pendiente** (siguiente paso) |
| Vistas `serving_*` en Kappa (migración V2) | Pendiente |
| Prometheus y Grafana | Pendiente |
| Planes de JMeter | Pendiente |
| Mediciones oficiales (3–5 repeticiones, mediana) | Pendiente |
| Informe de 2 páginas con referencias (SCOPUS, ScienceDirect) | Pendiente |

### Entregables de la actividad

1. URL del repositorio de GitHub.
2. Informe del benchmark (2 páginas): 2 arquitecturas de ingesta y 2 arquitecturas de API.
3. Referencias bibliográficas (SCOPUS, ScienceDirect).

---

## 13. Decisiones abiertas

- [ ] ¿Implementar `product_catalog` para reconstruir `brand` y `category_code` por `product_id`?
- [ ] ¿Implementar las tablas planeadas (embudo, marcas, horas, sesiones, usuarios) o limitar el alcance a las tres actuales?
- [ ] Unificar cómo se mide la latencia en Lambda y Kappa antes del benchmark oficial.
- [ ] ¿Pasar Kappa a `exactly_once_v2` y medir el costo?
- [ ] ¿Agregar `orders`, `avg_ticket` y `active_users` a Kappa y a la capa speed (requieren conteos distintos)?
- [ ] Tipo del monto en Protobuf (cadena decimal o centavos como entero) para que sea equivalente al número decimal de JSON.
- [ ] Cómo se probará gRPC con JMeter (plugin de la comunidad o `ghz`).
- [ ] Tamaño del pool de Hikari y opciones de JVM comunes a ambas APIs.
- [x] Duplicados: se usa duplicado exacto (índice único de cinco columnas en el batch y la misma clave en el store de Kappa).
- [x] Alcance de la primera fase: `events`, `rejected_events`, `daily_sales`, `daily_product_stats` y `daily_category_sales`.

---

## Anexo A. Cómo ejecutar

```powershell
# Infraestructura (Postgres y Kafka) con Docker Compose, desde infra/
docker compose up -d

# Lambda: batch + speed (application.properties: ingestion.batch.enabled / ingestion.speed.enabled)
#   ejecutar IngestionLambdaApplication desde IntelliJ
cd simulator-cpp
.\build\Debug\simulator.exe --topic events --rate 5000 --total 200000

# Kappa
#   ejecutar IngestionKappaApplication y esperar "Kafka Streams iniciado"
.\build\Debug\simulator.exe --topic events-kappa --replay-csv ../data/sample.csv --rate 0   # histórico
.\build\Debug\simulator.exe --topic events-kappa --rate 5000 --total 200000                  # eventos nuevos

# Reiniciar (apps detenidas): lambda | kappa | all
.\reset-env.ps1 kappa
```