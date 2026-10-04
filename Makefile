.PHONY: build up down logs reset deps-up deps-down

# Build application image
build:
	docker compose build

# Start all services (PostgreSQL, Redis, App) in detached mode
up:
	docker compose up -d

# Stop and remove containers and networks
down:
	docker compose down

# Stream logs from the application container
logs:
	docker compose logs -f app

# Reset environment: stop containers and delete database/cache volumes
reset:
	docker compose down -v

# Start only dependencies (Postgres + Redis) for running the app locally on host
deps-up:
	docker compose up -d postgres redis

# Stop only dependency containers
deps-down:
	docker compose stop postgres redis
