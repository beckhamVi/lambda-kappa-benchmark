# Reporte técnico: dataset, limpieza y arquitectura Lambda

Este documento explica qué datos usamos, qué significa cada columna, cómo se limpian, cómo se construye la arquitectura Lambda y qué analíticos se calculan.

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

## 3. Arquitectura Lambda

### 3.1 Idea general

Lambda combina **dos caminos** que producen el mismo tipo de resultado:

- **Capa batch:** procesa el histórico completo de forma exacta.
- **Capa speed:** procesa eventos nuevos en tiempo real, de forma rápida e incremental.
- **Capa serving:** une ambos resultados para responder consultas.

```
                 ┌──────────────► CAPA BATCH (Spring Batch) ──┐
 sample.csv ─────┤                leer → limpiar → guardar     ├──► CAPA SERVING ──► API
                 │                → agregar con SQL            │    (une batch + speed)
 Eventos nuevos ─┴──► Kafka ──► CAPA SPEED (Kafka Streams) ────┘
 (simulador C++)                  limpiar → agregar incremental
```

### 3.2 Flujo de la capa batch (primera fase del proyecto)

```
sample.csv → [LEER] → [LIMPIAR] → [GUARDAR eventos limpios] → [AGREGAR con SQL] → [tablas analíticas]
                          │
                          └→ filas descartadas → rejected_events (con el motivo)
```

1. **Leer:** Spring Batch lee el CSV por bloques (chunks) para no cargar todo en memoria.
2. **Limpiar:** aplica las reglas de la sección 2.
3. **Guardar:** inserta los eventos limpios en `lambda.events` en lotes (batch inserts).
4. **Agregar:** consultas SQL `INSERT ... SELECT ... GROUP BY` calculan las tablas analíticas.

Se mide por separado el tiempo de **carga** y el de **agregación**.

### 3.3 Estructura del código

Paquete base: `com.bi.ingestionlambda`

```
ingestion-lambda/src/main/
├── java/com/bi/ingestionlambda/
│   ├── IngestionLambdaApplication.java
│   ├── config/
│   │   ├── BatchJobConfig.java        define el job y sus pasos
│   │   └── IngestionProperties.java   ruta del CSV, tamaño de chunk, hilos
│   ├── batch/
│   │   ├── EventCsvReader.java        lee el CSV
│   │   ├── EventProcessor.java        limpia y normaliza
│   │   ├── EventDbWriter.java         inserta en lambda.events por lotes
│   │   └── RejectedWriter.java        guarda filas rechazadas
│   ├── domain/
│   │   ├── RawEvent.java              la línea tal como viene
│   │   ├── CleanEvent.java            el evento ya limpio
│   │   └── RejectReason.java          motivos de rechazo
│   ├── cleaning/
│   │   └── CleaningRules.java         todas las reglas de limpieza
│   ├── aggregation/
│   │   └── AggregationService.java    SQL de agregación
│   ├── metrics/
│   │   └── RunMetrics.java            tiempos y contadores
│   └── runner/
│       └── BenchmarkRunner.java       lanza el job y mide el tiempo
└── resources/
    ├── application.properties
    └── db/migration/
        ├── V1__events.sql
        ├── V2__rejected_events.sql
        └── V3__analytics_tables.sql
```

`CleaningRules` se mantiene aislada porque **Kappa usará exactamente las mismas reglas**. Así la comparación entre arquitecturas es justa.

### 3.4 Dónde se agrega

| Arquitectura | Cómo agrega |
|---|---|
| **Lambda (batch)** | En SQL, dentro de PostgreSQL, tras cargar todo. Es exacto y muy rápido |
| **Kappa** | En Java con Kafka Streams, evento por evento |

---

## 4. Tablas del esquema `lambda`

### 4.1 Datos limpios

| Tabla | Contenido |
|---|---|
| `events` | Eventos ya limpios, con las mismas columnas del CSV más `event_date` |
| `rejected_events` | `line_number`, `raw_line`, `reason` |

### 4.2 Tablas analíticas

La base de todo es el **día**. Semana, mes y año se obtienen **sumando días** (un año son solo 365 filas, así que sumar es de 1 a 3 ms). Con esto no hay que guardar una tabla distinta por cada período y los datos **nunca quedan obsoletos**: cuando llegan eventos nuevos, el día actual se actualiza y los días nuevos se agregan como filas nuevas.

| Tabla | Una fila por | Columnas principales |
|---|---|---|
| `daily_sales` | día | `revenue`, `orders`, `units`, `views`, `carts`, `purchases`, `avg_ticket`, `active_users` |
| `daily_funnel` | día | `views`, `carts`, `purchases`, `view_to_cart`, `cart_to_purchase`, `view_to_purchase` |
| `daily_product_stats` | día + producto | `views`, `carts`, `purchases`, `revenue` |
| `daily_category_sales` | día + categoría | `revenue`, `units`, `views`, `carts`, `purchases` |
| `daily_brand_sales` | día + marca | `revenue`, `units`, `purchases` |
| `hourly_traffic` | día + hora | `events`, `purchases`, `revenue` |
| `daily_sessions` | día | `sessions`, `avg_events_per_session`, `avg_duration_sec` |
| `user_stats` | usuario | `views`, `carts`, `purchases`, `total_spent`, `first_seen`, `last_seen` |

**Primera fase (5 tablas):** `events`, `rejected_events`, `daily_sales`, `daily_product_stats` y `daily_category_sales`. Las demás se agregan cuando lo anterior funcione.

---

## 5. Analíticos útiles (qué se puede responder)

### 5.1 Los más valiosos

| Analítico | Qué responde | Tabla de origen | Cómo se obtiene por semana / mes |
|---|---|---|---|
| **Top productos más vendidos** | ¿Qué productos venden más (unidades o ingresos) en un día, semana o mes? | `daily_product_stats` | Suma los días del rango, ordena y toma los N primeros |
| **Top productos más vistos** | ¿Qué productos atraen más interés? | `daily_product_stats` | Igual, ordenando por `views` |
| **Ventas por período** | ¿Cuánto se vende por día, semana, mes o año y cómo crece? | `daily_sales` | Suma por `date_trunc` y calcula crecimiento contra el período anterior |
| **Embudo de conversión** | ¿Cuántas vistas terminan en carrito y en compra? | `daily_funnel` | Suma días y recalcula las tasas |
| **Ventas por categoría** | ¿Qué categorías generan más ingresos? | `daily_category_sales` | Suma por mes y categoría |

### 5.2 Complementarios

| Analítico | Qué responde |
|---|---|
| Ventas por marca | ¿Qué marcas lideran? (`daily_brand_sales`) |
| Horas pico | ¿A qué hora hay más tráfico y compras? (`hourly_traffic`) |
| Ticket promedio | ¿Cuánto gasta una orden en promedio? (`daily_sales`) |
| Productos con muchas vistas y pocas compras | Oportunidades de mejora de precio o ficha del producto |
| Productos más agregados y abandonados | `cart` alto con `purchase` bajo |
| Valor de cliente | ¿Quiénes gastan más? (`user_stats`) |

### 5.3 Ejemplo concreto: top 10 de la semana

La API no recorre millones de eventos. Consulta `daily_product_stats`, que ya tiene una fila por día y producto:

```sql
SELECT product_id, SUM(purchases) AS purchases, SUM(revenue) AS revenue
FROM lambda.daily_product_stats
WHERE day BETWEEN '2020-04-06' AND '2020-04-12'
GROUP BY product_id
ORDER BY revenue DESC
LIMIT 10;
```

Esta consulta recorre unas decenas de miles de filas pequeñas en lugar de millones de eventos. De ahí viene la velocidad en milisegundos.

### 5.4 Consideración sobre usuarios únicos

No se pueden sumar "usuarios únicos por día" para obtener los de la semana, porque el mismo usuario aparece en varios días. Esas cifras se calculan aparte (conteo directo o un contador aproximado tipo HyperLogLog). Es un detalle técnico que se documenta en el informe.

---

## 6. Qué se mide en el benchmark de Lambda

| Métrica | Descripción |
|---|---|
| Tiempo de carga | Leer, limpiar y guardar `sample.csv` |
| Eventos por segundo | Filas procesadas / tiempo |
| Tiempo de agregación | Construir las tablas analíticas |
| Filas rechazadas | Total y por motivo |
| Uso de CPU y RAM | Durante la ejecución |
| Exactitud | Que las tablas de Lambda coincidan con las de Kappa |

Cada arquitectura se mide **por separado** y con los mismos límites de recursos, repitiendo de 3 a 5 veces y reportando la mediana.

---

## 7. Orden de construcción

1. Migraciones `V2` y `V3` (tablas de rechazos y analíticas).
2. `domain` y `CleaningRules`.
3. `batch` (lector, procesador, escritores) y `BatchJobConfig`.
4. `RunMetrics` y `BenchmarkRunner`: primera medición de carga.
5. `AggregationService`: segunda medición.
6. Capa speed con Kafka Streams.
7. Repetir la lógica en Kappa y comparar resultados.

---

## 8. Decisiones abiertas

- [ ] ¿Implementar `product_catalog` para reconstruir `brand` y `category_code` por `product_id`?
- [ ] ¿Comenzar con 5 tablas o con las 8?
- [ ] ¿Duplicados: ventana de un segundo o duplicado exacto?