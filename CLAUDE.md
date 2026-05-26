# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Development Commands

### Backend (Java 17, Maven, Spring Boot 3.2.4)

```bash
# Build all modules (skip tests)
mvn -DskipTests package

# Build a single module + its dependencies
mvn clean install -pl common -am -DskipTests
mvn clean install -pl user-service -am -DskipTests

# Run a single service locally (after building common + the service)
cd user-service && mvn spring-boot:run

# Run tests for a single module
mvn test -pl goal-service
```

### Frontend (Vue 3 + Vite, in `web-front/`)

```bash
cd web-front
npm install
npm run dev        # Dev server on port 5175, proxies /api -> localhost:8088
npm run build      # Production build
```

### Docker Compose (full stack)

```bash
docker compose up -d              # Start all 13 containers
docker compose up -d --build      # Rebuild images before starting
```

### Prerequisites for local dev

- MySQL, Redis (RedisStack), RabbitMQ, Nacos, Elasticsearch must be running
- Set `AI_DASHSCOPE_API_KEY` and `MODEL` env vars (or keep the `.env` values)

## Architecture Overview

**SmartPlanner (智慧学习助手)** — microservice-based personal learning platform: Goals → Task Breakdown → Schedule → Punch-in → Profile/Suggestions.

### Service Topology

All external requests enter through **gateway-service** (:8088) which routes `/api/**` → `lb://user-service`. `user-service` (:8080) is the **BFF/aggregation facade** — the only service exposed to the gateway. All other services are internal, called via OpenFeign from `user-service`.

| Module | Port | Database | Responsibility |
|--------|------|----------|----------------|
| `common` | — | — | Shared DTOs, `Result<T>`, AI client, RabbitMQ config, OpenFeign clients |
| `gateway-service` | 8088 | — | CORS, JWT parsing, Redis rate limiting, routing |
| `user-service` | 8080 | `vibe_user` | Auth (JWT), SSE notifications, Agent chat, aggregation facade |
| `goal-service` | 8081 | `vibe_goal` | Goals, tasks, journals, AI task breakdown |
| `schedule-engine` | 8082 | `vibe_schedule` | Class import, free-time calc, daily plan scheduling |
| `resource-search` | 8083 | `vibe_resource` | ES search, RedisStack vector search, RAG, web crawling |
| `punch-service` | 8084 | `vibe_punch` | Punch records, habit tracking |

### Communication Patterns

- **Synchronous**: OpenFeign clients defined in `common` — `user-service` calls downstream services
- **Asynchronous**: 3 RabbitMQ exchanges in `common`:
  - `goal.exchange` → `goal.ai.queue` (AI task breakdown jobs)
  - `notification.exchange` → `user.notification.queue` (notifications → SSE to frontend)
  - `resource.exchange` → `resource.advice.queue` (resource recommendation jobs)
- **SSE**: `user-service` pushes real-time notifications to the frontend via `SseEmitter`

### Data Contracts

- All API responses use a unified `Result<T>` wrapper (`com.chao.common.model.Result`): `code` (200=ok), `message`, `data`
- All services register with Nacos for service discovery
- JWT auth: `Authorization: Bearer <token>` header, parsed at gateway, user context forwarded via headers to downstream services
- Each service owns its own MySQL database (5 total)

### AI / LLM Integration

- All LLM calls go through `OpenAiCompatClient` in `common` (wraps Spring AI Alibaba + DashScope/Qwen)
- Model configured via `MODEL` env var (default: `qwen-max`)
- Timeout at 3 layers: HTTP read-timeout (180s), Spring AI retry disabled (max-attempts=1), business-level `orTimeout`
- **Agent**: Spring AI Alibaba ReactAgent with RedisSaver, 11 `@Tool` methods across query/write/hybrid-search categories, streams via SSE
- **RAG**: RedisStack vector store with DashScope embeddings, hybrid search (vector + keyword fallback)

### Async Job Pattern

Long-running operations (AI breakdown, scheduling, resource advice) use a job pattern:
1. POST returns a `jobId` immediately
2. Processing happens asynchronously (thread or RabbitMQ consumer)
3. On completion, a notification is pushed via RabbitMQ → SSE → frontend

### Frontend Key Paths

- `web-front/src/plugins/api.js` — Axios instance with JWT interceptor + automatic token refresh
- `web-front/src/stores/assistant.js` — Agent streaming chat using `fetch` + `ReadableStream`
- `web-front/src/router/index.js` — Route guard redirects unauthenticated users to `/login`, and users without schedule to `/plan`
- Nginx production config (`web-front/nginx.conf`) has special `proxy_buffering off` for the agent streaming endpoint `/api/user/agent/chat/stream`

### Each Backend Module Layout

```
{service}/
  src/main/java/com/chao/{service}/
    {Service}Application.java
    controller/
    service/
    entity/
    mapper/          # MyBatis-Plus mappers
    config/
    handler/         # Global exception handlers
    dto/
  src/main/resources/application.yml
```

MyBatis-Plus maps underscore table columns to camelCase entity fields. All date fields use `LocalDateTime` with Jackson `JavaTimeModule` in `common`.
