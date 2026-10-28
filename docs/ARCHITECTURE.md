# Architecture

This document describes how the Realtime Fraud Detection Pipeline is put together: the microservice topology, the fraud-detection data flow, the security model and the observability stack.

---

## 1. System overview

The platform is a **Maven multi-module Spring Boot 3.3 / Spring Cloud 2023.0 application** (parent POM: [`Backend/pom.xml`](../Backend/pom.xml)) composed of nine modules plus a React/TypeScript SPA in front of an API gateway.

```
                        ┌──────────────────────────────┐
   React SPA (TS) ───►  │  api-gateway :8187           │  ◄── Keycloak :8282 (OIDC/JWT)
                        └───────┬──────────────────────┘
                                │  Path routing + lb:// (Eureka :8761)
        ┌────────────┬──────────┼───────────┬────────────┐
        ▼            ▼          ▼           ▼            ▼
   client :4444  compte :5555  ocr :3333  credit :2222  user :7777
        │            │          │           │            │
        └────────────┴── shared-module (entities·DTOs·repos) ┴────┘
                                   │
                     MySQL 8 :3306 ◄┘        Flyway migrations (ocr-module)
                                   │
        External Python services (Docker network "shared-network"):
            • Tesseract OCR Flask API  :5006  → POST /ocr
            • Fraud / signature API    :5005  → POST /fraud-check
                                   │
                     Kafka :9092 (Zookeeper :2181) — topic "ocr-results"
```

## 2. Module responsibilities

| Module | Port | Key dependencies | Notes |
|---|---|---|---|
| `discovery-server` | 8761 | Eureka Server | All services register here; gateway resolves `lb://` URIs through it |
| `config-server` | 8888 | Spring Cloud Config | Centralized external configuration |
| `api-gateway` | 8187 | Spring Cloud Gateway (reactive), OAuth2 Resource Server, Zipkin | 8 path routes: `/comptes/**`, `/clients/**`, `/credits/**`, `/ocrs/**`, `/users/**`, `/roles/**`, `/eureka/**`, `/fallback/**` |
| `client-module` | 4444 | JPA, Eureka client, tracing | Bank customer domain |
| `compte-module` | 5555 | JPA, Eureka client, tracing | Checking (`CompteCourant`) & savings (`CompteEpargne`) accounts, joined to OCR results |
| `ocr-module` | 3333 | Kafka, Tess4J/PDFBox/Cloudinary, Flyway, WebFlux, OAuth2 | ⭐ The fraud-detection pipeline (see §3) |
| `credit-module` | 2222 | JPA, Eureka client | Loans with `CreditStatus` / `CreditType` workflow |
| `user-module` | 7777 | JPA, OAuth2 | Users & roles for RBAC |
| `shared-module` | — | JPA entities, DTOs, Spring Data repositories | Library jar: **single source of truth** for the domain model, preventing duplication and circular dependencies between services |

Every business service exports Actuator metrics (`health,info,metrics,prometheus`) and logs with `traceId`/`spanId` MDC pattern.

## 3. The realtime fraud-detection pipeline (ocr-module)

Entry point: `POST /ocrs/upload` in [`OcrController`](../Backend/ocr-module/src/main/java/dev/atb/ocr/controller/OcrController.java) → `@Async CompletableFuture` in [`OcrService.uploadAndAnalyze`](../Backend/ocr-module/src/main/java/dev/atb/ocr/Service/OcrService.java).

Stages (design steps 1–7 as implemented):

1. **Upload & validation** — multipart file (PNG/JPEG/PDF, ≤10 MB) + `typeDocument` + account `id`; content-type allow-list and bean validation reject anything else.
2. **Temporary materialization** — file written to a temp path with `deleteOnExit` cleanup in a `finally` block.
3. **OCR** — `RestTemplate POST → http://…:5006/ocr` (Tesseract 4 Flask container, languages `fra+ara+eng`; input normalized to PNG by `ImageConverter` — PDFBox renders PDFs at 300 DPI, JPG/HEIC converted via `javax.imageio`/Cloudinary; engine tuned in `ocr.xml` — char whitelist, page-segmentation mode — tessdata shared through a Docker volume).
4. **Fraud scoring** — `POST → http://…:5005/fraud-check` returns JSON `{ "fraud_score": 0.0–1.0 }`. Failures **degrade gracefully** (logged, score 0.0) instead of breaking the pipeline.
5. **Decision** — `fraud = fraudScore > 0.7` (configurable threshold constant).
6. **Persistence** — `Ocr` entity saved against the client's `Compte` (account number link) in MySQL; Flyway manages schema.
7. **Event publication** — the DTO is serialized and published to Kafka topic **`ocr-results`**, decoupling detection from consumers (alerts, audit, analytics).

The image itself is round-tripped as base64 so the operator UI can preview the flagged document without a second fetch. Auxiliary endpoints: `/ocrs/fraud-detection` (standalone check), `/perform-and-preview` (OCR preview, currently disabled), and full CRUD on stored results.

## 4. Security model

- **Identity provider**: Keycloak 24 (`start-dev --import-realm`), realm `spring-microservices-realm` provisioned from [`spring-microservices-realm.json`](../Backend/api-gateway/docker/keycloak/realms/spring-microservices-realm.json); MySQL-backed, admin `admin/admin` on port 8282.
- **Token flow**: React SPA logs in with `keycloak-js` (OIDC authorization code + PKCE implicit config in [`keycloak.js`](../Frontend/src/keycloak.js)), refreshes at session boundaries, attaches the JWT to every axios call.
- **Enforcement**: the reactive gateway and each resource server validate JWTs against the Keycloak issuer (`spring.security.oauth2.resourceserver.jwt.issuer-uri`); client registration per module (`ocr-module` service client with secret).
- **Authorization**: `ProtectedRoute` + role checks in the SPA, `Role`/`User` entities (RBAC) server-side; permissive paths limited to Keycloak/health endpoints.
- **Transport**: NGINX reverse proxy exposes the Python AI services on :87/:743 inside the Docker network, keeping them off public ports.

## 5. Event streaming

Kafka (Confluent image, Zookeeper coordinator — both health-checked) on `kafka:9092` inside `shared-network`. The `ocr-module` is the producer (`spring-kafka`, group `ocr-group`) publishing `OcrDTO` JSON to `ocr-results`. Consumers can be added without touching the detection path — the classic event-driven decoupling argument that also motivated the shared-model library.

## 6. Observability & infrastructure (`Backend/docker-compose.yml`)

| Concern | Stack |
|---|---|
| Logs | service stdout → **Fluentd** (`:24224`) → **Elasticsearch 7.17** (`:9200`) |
| Metrics | Actuator `/prometheus` → **Prometheus** (`:9091`) → **Grafana** (`:3001`) dashboards |
| Traces | Micrometer Tracing + Zipkin reporter, `traceId/spanId` in every log line |
| Traffic | **NGINX** reverse proxy (`:87`/`:743`) for AI micro-services |
| Data | **MySQL 8.0.41** (`:3306`, shared database, health-checked, volume-backed) |

All 11 containers attach to a single bridge network with health checks and explicit dependencies (e.g., Fluentd waits for a healthy Elasticsearch).

## 7. Frontend architecture

CRA-based React 18 + TypeScript:
- `routes/Router.tsx` + `ProtectedRoute.tsx` — declarative route table, auth/role gating, `UnauthorizedPage` fallback; `backend-config.ts` centralizes gateway base URLs.
- `services/*.ts` — typed axios modules per domain (client, compte, credit, ocr).
- `views/ui/*` — operator screens; `components/ocr/*` — upload & OCR result preview; `components/dashboard` — apexcharts visualizations.
- `layouts/` — Bootstrap admin shell (FullLayout, Header, Sidebar) with loader states.

## 8. Testing strategy

JUnit per-module tests (`src/test`), **Testcontainers 1.17 BOM** managed centrally for integration tests against real MySQL/Kafka images, bean validation on controllers, and a typed exception path (`OcrProcessingException` → standardized `ApiResponse`).

## 9. Known trade-offs & next steps

- **Shared database** (`shared_database`) — pragmatic for a PFE; the target is database-per-service with the gateway as the seam.
- **Zookeeper-based Kafka** — migration path to KRaft is a config change.
- **Heuristic fraud scoring endpoint** — designed replacement with a fine-tuned InceptionV3/TensorFlow classifier (transfer learning, signature alignment via ORB + homography, quantization-aware deployment) is fully specified in [`Backend/README.md`](../Backend/README.md).
- **Kubernetes** — layered Dockerfiles (`Dockerfile.layered`) already prepare small incremental images for a Helm-based deployment.
