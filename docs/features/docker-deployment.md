# Feature: Docker deployment

> Last updated: 2026-05-18

## Context

Picsou deploys as two Docker images orchestrated by `docker/docker-compose.yml`:
- **`picsou:latest`** — main app: frontend (Nginx) + backend (Spring Boot), no Python. Published to GHCR as `ghcr.io/zoeille/picsou-finance`.
- **`docker-tr-auth`** — Trade Republic auth sidecar: headless Chromium + Python/uvicorn. Published to GHCR as `ghcr.io/zoeille/picsou-finance/tr-auth`.

A third container is PostgreSQL 16 (official image, not built).

## How it works

### Main image — `docker/Dockerfile` (3-stage build, context: project root)

1. **`frontend-build`** — `oven/bun:alpine`. `bun install --frozen-lockfile`, `bun run build`. Output: `dist/`.
2. **`backend-build`** — `maven:3.9-eclipse-temurin-21-alpine`. `mvn dependency:go-offline` (layer cache), then `mvn package -DskipTests`. Output: fat JAR.
3. **Runtime** — `eclipse-temurin:21-jre-jammy`. Installs Nginx + supervisor + openssl. Copies `dist/` and JAR from build stages plus `docker/{nginx.conf,supervisord.conf,entrypoint.sh}`.

`supervisord` manages **two** processes in the main container:

| Process | Port | Role |
|---------|------|------|
| Nginx | 8080 (public) | Serves SPA, proxies `/api/*` and `/actuator` to backend |
| Java (Spring Boot) | 9090 (internal) | Backend API |

### tr-auth sidecar — `services/tr-auth/Dockerfile`

Based on `python:3.12-slim`. Installs only Chromium (not Firefox/WebKit) via `playwright install chromium`, with system deps pre-installed via apt. The sidecar exposes port 8001 and is reached by the backend via `TR_AUTH_URL=http://tr-auth:8001`.

### Entrypoint (`docker/entrypoint.sh`)

On first boot, auto-generates three secrets into `/data/.secrets/` (mounted named volume):
- `JWT_SECRET` — 48-byte base64
- `CRYPTO_ENCRYPTION_KEY` — 32-byte base64
- `POSTGRES_PASSWORD` — 24-byte base64

On subsequent boots, re-reads from the files. If the env var is already set by the operator, it is respected and written to the file for consistency. Secrets are **never** regenerated once created — doing so would invalidate JWTs and corrupt all encrypted data in the DB.

### Key files

- `docker/Dockerfile` — main image, 3-stage build
- `docker/docker-compose.yml` — orchestration (app + tr-auth + PostgreSQL + volumes)
- `services/tr-auth/Dockerfile` — tr-auth sidecar image
- `docker/nginx.conf` — Nginx reverse proxy config
- `docker/supervisord.conf` — supervisor (nginx + backend)
- `docker/entrypoint.sh` — secret bootstrap + exec supervisord

### Flow

```
docker compose -f docker/docker-compose.yml up
  → picsou:latest  (nginx:8080 → backend:9090)
  → docker-tr-auth (uvicorn:8001)
  → postgres:16-alpine (:5432)
```

### Building a release archive

```bash
docker compose -f docker/docker-compose.yml build
docker save picsou:latest docker-tr-auth:latest | gzip > picsou-release.tar.gz
# On target machine:
docker load < picsou-release.tar.gz
```

### Pulling from GHCR

Both images are published by `.github/workflows/docker.yml` on every push (matrix build, one entry per image). To deploy from the registry instead of building or loading a tar.gz:

```bash
# Replace 1.0.0 with the desired tag (nightly, branch name, or semver).
docker pull ghcr.io/zoeille/picsou-finance:1.0.0
docker pull ghcr.io/zoeille/picsou-finance/tr-auth:1.0.0

# Re-tag to the local names referenced by docker-compose.yml:
docker tag ghcr.io/zoeille/picsou-finance:1.0.0       picsou:latest
docker tag ghcr.io/zoeille/picsou-finance/tr-auth:1.0.0 docker-tr-auth:latest
```

Tag scheme:
- `main` push → `nightly`
- other branch push → branch name (e.g. `1.0.0`, `feature-foo`)
- version tag (`1.0.0` or `v1.0.0`) → `latest` + semver (`1.0.0`, `1.0`, `1`)

### Build version shown in the app

The published Docker workflow computes `APP_VERSION` from the Git ref and passes
it to the main image build. Version-tag builds display the normalized tag value
in Settings → About (for example both `1.0.13` and `v1.0.13` tags display
`1.0.13`), `main` builds display `nightly-<short-sha>`, and branch builds display
`<branch-name>-<short-sha>`.

Local source builds fall back to `frontend/package.json` for the frontend About
screen. Backend runtime metadata (`/actuator/info` and the embedded MCP server
version) uses `APP_VERSION` when it is set and otherwise falls back to `dev`.
A single `APP_VERSION` build arg feeds both the frontend and backend. For a
local release-style build, pass it explicitly:

```bash
docker build -f docker/Dockerfile --build-arg APP_VERSION=1.0.13 .
```

## Technical choices

| Choice | Why | Rejected alternative |
|--------|-----|----------------------|
| Bun for frontend build | Project uses bun exclusively (`bun.lock`) | npm (would need a separate lock file) |
| tr-auth as a separate container | Keeps the main image slim (JRE only, no Python/Playwright) | Embed tr-auth in the main image via supervisord — was done previously, bloated the main image to 1.5GB+ |
| `python:3.12-slim` + Chromium only | Only Chromium is used (`p.chromium.launch()`); base image is ~5× smaller than `mcr.microsoft.com/playwright/python` which pre-installs all three browsers | `mcr.microsoft.com/playwright/python:v1.44.0-jammy` — included Firefox + WebKit unnecessarily (+~1.5GB uncompressed) |
| Auto-generated secrets on first boot | Zero-config install: user runs `docker compose up` with no pre-configuration | Require operator to set secrets manually before first boot |
| `.dockerignore` excludes `docker/Dockerfile` | Prevents the Dockerfile from being part of its own build context | No exclusion (harmless but unnecessary) |

## Gotchas / Pitfalls

- **`.dockerignore` must NOT exclude `docker/` entirely.** The runtime stage copies `docker/nginx.conf`, `docker/supervisord.conf`, and `docker/entrypoint.sh` from the build context.
- **Frontend lock file is `bun.lock`.** The Dockerfile must use `oven/bun` and `bun install --frozen-lockfile`. npm will fail.
- **`VITE_DEMO_MODE` build arg** defaults to `false`. Pass `--build-arg VITE_DEMO_MODE=true` for a demo build.
- **Nginx listens on 8080**, backend on 9090. The backend port is set via `SERVER_PORT` in `entrypoint.sh`, not `application.yml`.
- **`TR_AUTH_URL` default in entrypoint is `http://127.0.0.1:8001`** (legacy single-container fallback). In docker-compose it is overridden to `http://tr-auth:8001` via the `environment:` block.
- **Secrets are never regenerated.** If `/data/.secrets/jwt_secret` exists, it is reused. Deleting it will log out all users and invalidate all encrypted secrets in the DB.
- **Spring Boot env var naming:** Properties under `app.*` require the `APP_` prefix. `app.finary.email` → `APP_FINARY_EMAIL`. Variables like `JWT_SECRET` work because `application.yml` maps them explicitly.
- **Stale env vars removed (2026-04-19):** `TR_PHONE_NUMBER`, `TR_PIN`, `FINARY_TOTP`, `POWENS_*`, `FINARY_EMAIL`, `FINARY_PASSWORD`. Do not re-add them.

## Tests

- No dedicated Docker integration tests. Build validation is manual: `docker build -f docker/Dockerfile .`.
- Backend unit tests run separately via `./mvnw test` (not in Docker build — skipped with `-DskipTests`).

## Links

- Related: [Trade Republic feature](./trade-republic.md) (tr-auth microservice)
