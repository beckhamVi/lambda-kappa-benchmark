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

if ($Target -in 'lambda', 'all') {
    Write-Host '== Reset LAMBDA =='
    # run_log no se borra: guarda el historial de mediciones del batch
    Run-Sql 'TRUNCATE lambda.events, lambda.rejected_events, lambda.daily_sales, lambda.daily_product_stats, lambda.daily_category_sales, lambda.speed_daily_sales, lambda.speed_product_stats, lambda.speed_category_sales;'
    Remove-Topics '^events$'
}

if ($Target -in 'kappa', 'all') {
    Write-Host '== Reset KAPPA =='
    Run-Sql 'TRUNCATE kappa.daily_sales, kappa.daily_product_stats, kappa.daily_category_sales, kappa.rejected_events;'
    # topic de entrada + topics internos de Kafka Streams (repartition y changelog)
    Remove-Topics '^events-kappa$|^kappa-ingestion-'
    $state = Join-Path $PSScriptRoot 'ingestion-kappa\kappa-state'
    if (Test-Path $state) { Remove-Item -Recurse -Force $state; Write-Host 'Borrada carpeta kappa-state' }
}

Write-Host 'Listo. Ahora arranca la app y vuelve a correr el simulador.'