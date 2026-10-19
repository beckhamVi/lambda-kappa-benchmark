# reset-env.ps1  (ponlo en la raiz de bi-project)
# Uso:  .\reset-env.ps1 lambda | kappa | all
# IMPORTANTE: deten las apps Spring (Lambda y Kappa) ANTES de ejecutarlo.
# Borra datos de las tablas y los topics de Kafka. NO borra la base de datos ni el schema.
param([ValidateSet('lambda', 'kappa', 'all')][string]$Target = 'all')

$kafka = 'my_kafka'
$pg = docker ps --filter "publish=5433" --format "{{.Names}}" | Select-Object -First 1
if (-not $pg) { Write-Error 'No encuentro el contenedor de Postgres (puerto 5433). Revisa docker ps.'; exit 1 }

function Run-Sql($q) {
    docker exec $pg psql -U ram -d bidb -c $q
}

function Remove-Topics($pattern) {
    $topics = docker exec $kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list |
        Where-Object { $_ -match $pattern }
    foreach ($t in $topics) {
        Write-Host "Borrando topic $t"
        docker exec $kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --delete --topic $t
    }
}

function Remove-ConsumerGroup($group) {
    # Borra el grupo si existe (si no existe, kafka-consumer-groups devuelve error y lo ignoramos)
    $exists = docker exec $kafka /opt/kafka/bin/kafka-consumer-groups.sh `
        --bootstrap-server localhost:9092 --list 2>$null | Where-Object { $_ -eq $group }
    if ($exists) {
        Write-Host "Borrando consumer group $group"
        docker exec $kafka /opt/kafka/bin/kafka-consumer-groups.sh `
            --bootstrap-server localhost:9092 --delete --group $group
    } else {
        Write-Host "Consumer group $group no existe, nada que borrar"
    }
}

if ($Target -in 'lambda', 'all') {
    Write-Host '== Reset LAMBDA =='
    # run_log no se borra: guarda el historial de mediciones del batch
    Run-Sql 'TRUNCATE lambda.events, lambda.rejected_events, lambda.daily_sales, lambda.daily_product_stats, lambda.daily_category_sales, lambda.speed_daily_sales, lambda.speed_product_stats, lambda.speed_category_sales;'
    Remove-Topics '^events$'
}

if ($Target -in 'kappa', 'all') {
    Write-Host '== Reset KAPPA =='
    Run-Sql 'TRUNCATE kappa.daily_sales, kappa.daily_product_stats, kappa.daily_category_sales, kappa.rejected_events;'

    # 1. Borrar el consumer group ANTES que los topics
    #    (si no, el grupo queda con offsets huerfanos y a veces no reinicia desde earliest)
    Remove-ConsumerGroup 'kappa-ingestion'

    # 2. Topics: fuente (+ variantes del simulador) e internos de Kafka Streams
    #    - ^events-kappa$         -> topic fuente que consume la app
    #    - ^events-kappa-bench$   -> topic que uso el simulador por error
    #    - ^kappa-ingestion-      -> repartition + changelog de los state stores
    Remove-Topics '^events-kappa$|^events-kappa-bench$|^kappa-ingestion-'

    # 3. State store local (RocksDB)
    $state = Join-Path $PSScriptRoot 'ingestion-kappa\kappa-state'
    if (Test-Path $state) { Remove-Item -Recurse -Force $state; Write-Host 'Borrada carpeta kappa-state' }
}

Write-Host ''
Write-Host 'Listo. Ahora:'
Write-Host '  1) Arranca la app Spring (Kafka Streams recreara events-kappa con 3 particiones).'
Write-Host '  2) Espera a ver en el log: "Kappa: Kafka Streams iniciado, escuchando topic events-kappa"'
Write-Host '  3) Recien entonces lanza el simulador apuntando al MISMO topic:'
Write-Host '     simulator.exe --brokers localhost:9092 --topic events-kappa ...'