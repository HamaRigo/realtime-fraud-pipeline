# Realtime Fraud Detection Pipeline

> An event-driven banking platform that ingests financial documents (cheques, bills, IDs) via multi-language OCR, scores them for fraud in real time, and propagates the results through a Kafka-powered microservices architecture.

Built as an end-of-studies engineering project (PFE) for a banking context: bank operators upload a client's document, the pipeline extracts its content with OCR, a fraud-analysis service returns a risk score, and the verdict is persisted and broadcast in near real time — with SSO, distributed tracing and a full observability stack behind it.

![Architecture](Backend/doc/Architecture-microservices-avec-Spring-Cloud.jpg)

---

## ✨ Highlights for recruiters

- **Microservices at scale** — 9 Spring Boot 3 / Spring Cloud modules (discovery, gateway, config, 5 business services, shared library) orchestrated as one Maven multi-module build.
- **Realtime async pipeline** — document upload → preprocessing → Tesseract OCR (French + Arabic + English) → ML fraud scoring → DB persistence → Kafka event publication, executed with `@Async` / `CompletableFuture`.
- **Event-driven core** — Kafka (Zookeeper-backed) topic `ocr-results` decouples detection from downstream consumers.
- **Security by design** — Keycloak OIDC realm (JWT) on the API gateway and every resource server, role-based route protection, reactive Spring Security.
- **Observability** — centralized logging (Fluentd → Elasticsearch), metrics & alerting (Prometheus + Grafana), distributed tracing (Micrometer Tracing + Zipkin), Spring Boot Actuator.
- **Containerized infra** — 11 services in a single `docker compose up`: MySQL 8, Keycloak, Tesseract OCR Flask service, Python signature-matching/fraud service, Kafka, Zookeeper, NGINX, Fluentd, Elasticsearch, Prometheus, Grafana.
- **Typed React frontend** — TypeScript + Keycloak-js SSO login, protected routes, apexcharts operator dashboard.

---

## 🎯 The problem it solves

Banks process huge volumes of paper documents (cheques, *effets*, IDs) whose manual verification is slow and fraud-prone. This platform automates the chain:

1. An operator uploads a document image/PDF tied to a bank account.
2. The image is preprocessed (grayscale, contrast, resize, skew correction) and OCR'd by Tesseract with multilingual models (`fra + ara + eng`).
3. The document is sent to a fraud-analysis service that returns a **risk score** (0–1); a score above the **0.7 threshold** flags the document as fraudulent.
4. The result (extracted text, verdict, score, preview image) is stored against the client's account and published to Kafka for any downstream consumer (alerting, audit, analytics).

The pipeline steps are implemented exactly as designed in [OcrService.java](Backend/ocr-module/src/main/java/dev/atb/ocr/Service/OcrService.java):

```
upload → validate & preprocess → Tesseract OCR → fraud-score API → persist (MySQL) → publish (Kafka)
```

---

## 🧱 Tech stack

| Layer | Technologies |
|---|---|
| Backend | Java 17, Spring Boot 3.3, Spring Cloud 2023.0 (Gateway, Eureka, Config), Spring WebFlux, Spring Kafka, Spring Data JPA, Lombok, Flyway, springdoc-openapi |
| AI / OCR | Tesseract 4 (multilingual `fra+ara+eng`) behind a Flask HTTP service, OpenCV-based signature matching & fraud-check service (Python), TensorFlow-based scoring roadmap |
| Security | Keycloak 24 (OIDC / JWT realm), Spring Security OAuth2 Resource Server, role-based access |
| Event streaming | Apache Kafka + Zookeeper |
| Data | MySQL 8.0 (shared database, Flyway migrations) |
| Observability | Prometheus, Grafana, Fluentd, Elasticsearch (ELK-style log pipeline), Zipkin / Micrometer Tracing, Actuator |
| Frontend | React 18, TypeScript, Bootstrap 5, Formik + Yup, apexcharts, keycloak-js, axios |
| DevOps | Docker, Docker Compose (11-service stack), NGINX reverse proxy, layered Dockerfiles |
| Testing | JUnit, Testcontainers |

---

## 🏗 Architecture

**Full details:** [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)

```
                        ┌──────────────────────────────┐
   React SPA (TS) ───►  │  api-gateway :8187           │  ◄── Keycloak :8282 (OIDC/JWT)
                        │  Spring Cloud Gateway        │
                        │  Path routes + LB + tracing  │
                        └───────┬──────────────────────┘
        /clients /comptes /ocrs /credits /users (lb:// via Eureka :8761)
                                │
   ┌────────────┬───────────────┼────────────────┬─────────────┐
   ▼            ▼               ▼                ▼             ▼
client :4444  compte :5555   ocr :3333       credit :2222   user :7777
   │            │               │                │             │
   └────────────┴── shared-module (models · DTOs · repositories) ─┘
                                   │
                     MySQL 8 :3306 ▼            Flask services (Docker)
                                  │        Tesseract OCR :5006 ──► /ocr
                                  │        fraud & signature :5005 ──► /fraud-check
                                  ▼
                     Kafka :9092 ── topic "ocr-results" ──► downstream consumers
```

- **Discovery**: Eureka (`discovery-server :8761`) — every service registers itself.
- **Central config**: `config-server :8888`.
- **Single entry point**: the gateway exposes one path per domain (`/ocrs/**` → `lb://OCR-MODULE`) with load balancing, CORS handling and trace propagation.
- **shared-module**: single source of truth for JPA entities, DTOs and Spring Data repositories, avoiding duplication and circular dependencies between services.

---

## 📦 Backend modules

| Module | Port | Responsibility |
|---|---|---|
| `discovery-server` | 8761 | Netflix Eureka service registry |
| `config-server` | 8888 | Spring Cloud externalized configuration |
| `api-gateway` | 8187 | Reactive gateway: routing, load balancing, Keycloak JWT validation, Zipkin tracing |
| `client-module` | 4444 | Bank client (customer) CRUD |
| `compte-module` | 5555 | Accounts (checking / savings) |
| `ocr-module` | 3333 | ⭐ Core pipeline: upload, OCR, fraud scoring, persistence, Kafka publishing |
| `credit-module` | 2222 | Credit/loan management with status workflow |
| `user-module` | 7777 | Users & roles (RBAC) |
| `shared-module` | — | Library: entities, DTOs, repositories, OpenAPI config |

---

## 🚀 Quick start

### Prerequisites
Docker & Docker Compose · Java 17 · Maven · Node.js 18+

### 1. Boot the infrastructure (MySQL, Keycloak, Kafka, OCR & fraud services, observability)

```bash
cd Backend
docker compose up -d
```

### 2. Build and run the microservices

```bash
cd Backend
mvn clean install -DskipTests
# then start each Spring app (order matters):
#   discovery-server → config-server → business modules → api-gateway
```

### 3. Run the frontend

```bash
cd Frontend
npm install
npm start          # http://localhost:3000
```

### Service endpoints

| What | Where |
|---|---|
| API Gateway | http://localhost:8187 |
| Eureka dashboard | http://localhost:8761 |
| Keycloak admin | http://localhost:8282 (`admin`/`admin`, realm `spring-microservices-realm`) |
| Grafana dashboards | http://localhost:3001 |
| Prometheus | http://localhost:9091 |
| Elasticsearch | http://localhost:9200 |

---

## 🔌 API surface (excerpt)

**Full reference:** [docs/API.md](docs/API.md)

| Method | Route (via gateway) | Description |
|---|---|---|
| `POST` | `/ocrs/upload` | Async full pipeline: OCR + fraud score + persist + Kafka event |
| `POST` | `/ocrs/fraud-detection` | Standalone fraud check → verdict with score (threshold 0.7) |
| `GET` | `/ocrs` · `/ocrs/{id}` | Fraud/OCR history |
| `CRUD` | `/clients/**` · `/comptes/**` · `/credits/**` · `/users/**` | Domain APIs (JWT-protected) |

---

## 🖥 Frontend

React 18 + TypeScript SPA (Bootstrap 5 admin template):
- **SSO** — `keycloak-js` + `@react-keycloak/web`; session-boundary token refresh; `ProtectedRoute` guards every page with role checks (`UnauthorizedPage` on failure).
- **Operator screens** — client, account, credit and OCR document views (`views/ui/`), OCR upload & result preview components, apexcharts dashboard.
- **Forms** — Formik + Yup validation; axios service layer (`services/*.ts`) hitting the gateway.

---

## 🧪 Testing & quality

- JUnit integration tests per module; **Testcontainers** managed in the parent POM for real-dependency testing.
- Bean validation (`@Valid`, `@Validated`) and typed domain exceptions (`OcrProcessingException`) with standardized error responses.
- Flyway-managed DB schema (`ocr-module/src/main/resources/db/migration`).
- Centralized logging pattern with `traceId`/`spanId` MDC injection across all services.

---

## 🗺 Roadmap

- [ ] Replace heuristic scoring with a trained TensorFlow model (InceptionV3 transfer learning — design done, see [Backend/README.md](Backend/README.md))
- [ ] Signature verification service integration (ORB feature matching + homography alignment)
- [ ] Swagger/OpenAPI aggregation at gateway level
- [ ] Kubernetes manifests + Helm charts
- [ ] CI/CD pipeline (build → test → layered Docker images → deploy)

## 👤 Author

**Hamza Rigo** — Full-stack / Java Spring & AI-oriented engineer
GitHub: [@HamaRigo](https://github.com/HamaRigo)

---

<details>
<summary>📎 Where to look in the code (for interview prep)</summary>

- Async event-driven pipeline: `Backend/ocr-module/.../Service/OcrService.java`
- Gateway routes & JWT security: `Backend/api-gateway/src/main/resources/application.properties`
- Keycloak realm definition: `Backend/api-gateway/docker/keycloak/realms/`
- Shared domain model: `Backend/shared-module/src/main/java/dev/atb/`
- Full infra topology: `Backend/docker-compose.yml`
- Frontend SSO & route guards: `Frontend/src/keycloak.js`, `Frontend/src/routes/ProtectedRoute.tsx`

</details>
