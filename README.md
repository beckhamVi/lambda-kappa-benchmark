# Lambda vs Kappa Benchmark

Benchmark of two data ingestion architectures (**Lambda** and **Kappa**) and two
API styles (**REST** and **gRPC**), using a large e-commerce events dataset.

## Goals

- Compare Lambda (batch + speed + serving layers) against Kappa (stream-only) in
  throughput, end-to-end latency, resource usage and result accuracy.
- Compare REST and gRPC over the same analytical tables and service layer.
- Provide a reproducible environment so anyone can run the benchmarks.

## Tech stack

| Layer | Technology |
|---|---|
| Language / framework | Java 21, Spring Boot |
| Database | PostgreSQL 18 |
| Batch layer | Spring Batch |
| Streaming layer | Apache Kafka, Kafka Streams |
| API | Spring MVC (REST) and gRPC |
| Migrations | Flyway |
| Event simulator | C++ |
| Monitoring | Micrometer, Prometheus, Grafana |
| Load testing | k6, ghz |
| Infrastructure | Docker, Docker Compose |

## Project structure

| Folder | Description |
|---|---|
| `infra/` | Docker Compose and environment files |
| `api/` | REST and gRPC API over analytical tables |
| `ingestion-lambda/` | Lambda architecture: batch, speed and serving layers |
| `ingestion-kappa/` | Kappa architecture: stream-only processing |
| `simulator-cpp/` | Synthetic event generator |
| `data/` | Dataset files (not tracked by git) |
| `docs/` | Report and charts |
| `benchmarks/` | Load test scripts and raw results |

## Dataset

[eCommerce behavior data from multi-category store](https://www.kaggle.com/mkechinov/ecommerce-behavior-data-from-multi-category-store)
provided by the [REES46 Marketing Platform](https://rees46.com).

Each row is an event (`view`, `cart`, `remove_from_cart`, `purchase`) with:
`event_time`, `event_type`, `product_id`, `category_id`, `category_code`,
`brand`, `price`, `user_id`, `user_session`.

Download the CSV files and place them in the `data/` folder.

## Getting started

### Requirements

- Docker Desktop
- Java 21
- Git

### 1. Start PostgreSQL

```bash
cd infra
cp .env.example .env
docker compose up -d
```

On Windows PowerShell use `copy .env.example .env`.

PostgreSQL is exposed on `localhost:5433`.

### 2. Run the API

```bash
cd api
./gradlew bootRun
```

On startup, Flyway creates the database schema automatically.

## Database

| Setting | Value |
|---|---|
| Host | `localhost` |
| Port | `5433` |
| Database | `bidb` |
| User | `ram` |

Credentials are defined in `infra/.env`.

## Roadmap

- [x] PostgreSQL in Docker
- [x] API skeleton with Flyway
- [ ] Load dataset into `raw_events`
- [ ] Analytical tables (daily sales, top products, funnel, sessions)
- [ ] REST endpoints
- [ ] gRPC endpoints
- [ ] C++ event simulator
- [ ] Kappa ingestion
- [ ] Lambda ingestion
- [ ] Benchmarks and report
- [ ] Redis cache layer (phase 2)

## Branching strategy

- `main`: stable, deliverable version
- `develop`: integration branch
- `feature/*`: one branch per feature, merged into `develop` through pull requests

Commit messages follow the convention `feat:`, `fix:`, `docs:`, `chore:`.

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE).

## Acknowledgements

Dataset by [REES46 Marketing Platform](https://rees46.com), available on Kaggle.