# API Reference

All endpoints are exposed through the **API Gateway** at `http://localhost:8187`.
Every route except Keycloak/health endpoints requires a **Bearer JWT** issued by the Keycloak realm `spring-microservices-realm` (`Authorization: Bearer <token>`).

Base URLs used in this doc: `GW = http://localhost:8187`

---

## 🔐 Authentication

```
Keycloak realm : http://localhost:8282/realms/spring-microservices-realm
SPA login      : keycloak-js (OIDC)  →  access token attached to every request
Gateway        : spring-security oauth2-resource-server validates JWTs
```

## 🧾 OCR & Fraud Detection — `/ocrs` (ocr-module, async pipeline)

| Method | Endpoint | Request | Response |
|---|---|---|---|
| `POST` | `GW/ocrs/upload` | multipart: `file` (PNG/JPEG/PDF ≤ 10 MB), query: `typeDocument`, `id` (account id) | `200 OcrDTO` — async pipeline: OCR text, base64 preview, `fraud` boolean, `fraudScore`; result persisted and published to Kafka topic `ocr-results` |
| `POST` | `GW/ocrs/fraud-detection` | multipart: `file`, query: `id` | `200` — `"⚠️ Fraud Detected! (Score: x)"` if score > 0.7, else `"✅ No Fraud Detected. (Score: x)"` |
| `POST` | `GW/ocrs/perform-and-preview` | `file`, `typeDocument` | OCR preview without persistence *(currently disabled in controller)* |
| `GET` | `GW/ocrs` | — | `200 List<OcrDTO>` — analysis history |
| `GET` | `GW/ocrs/{id}` | — | `200 OcrDTO` / `404` |
| `DELETE` | `GW/ocrs/{id}` | — | `204` / `404` |

**OcrDTO shape**

```json
{
  "id": "…",
  "typeDocument": "CHEQUE",
  "resultatsReconnaissance": "…OCR extracted text…",
  "fraud": true,
  "image": "<base64>",
  "observation": "…",
  "fraudScore": 0.84
}
```

Error handling: `400` on invalid file type / missing fields (`MultipartException` handler), `500` with typed message on pipeline failure (`OcrProcessingException`).

## 👥 Clients — `/clients` (client-module)

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `GW/clients` · `GW/clients/{id}` | List / fetch customers |
| `GET` | `GW/clients/count` | Dashboard metric: customer count |
| `GET` | `GW/clients/exists/{numeroDocument}` | Duplicate-check by ID-document number |
| `POST` / `PUT` / `DELETE` | `GW/clients` · `GW/clients/{id}` | Customer CRUD |
| `POST` | `GW/clients/{id}/signature` | Upload reference signature (used by the signature-matching service) |
| `GET` | `GW/clients/{id}/signature` | Fetch reference signature |

## 💰 Accounts — `/comptes` (compte-module)

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `GW/comptes/current` · `GW/comptes/saving` | Checking / savings account lists |
| `GET` | `GW/comptes/{id}` · `GW/comptes/exists/{numeroCompte}` | Fetch / duplicate-check |
| `GET` | `GW/comptes/countByType` · `GW/comptes/countByStatus` | Dashboard metrics |
| `POST` / `PUT` | `GW/comptes/current` · `GW/comptes/saving` | Create / update per account type |
| `DELETE` | `GW/comptes/{id}` | Close account |

## 🏦 Credits — `/credits` (credit-module)

| Method | Endpoint | Description |
|---|---|---|
| `GET` / `POST` / `PUT` / `DELETE` | `GW/credits` · `GW/credits/{id}` | Loan CRUD with `CreditStatus` workflow (applied → approved/rejected) and `CreditType` |

## 👤 Users — `/users` (user-module)

| Method | Endpoint | Description |
|---|---|---|
| `GET` / `POST` / `PUT` / `DELETE` | `GW/users` · `GW/users/{id}` | User management backed by `Role` entities (RBAC); gateway also routes `/roles/**` |

## 🛠 Ops endpoints (direct on each service)

| Endpoint | Purpose |
|---|---|
| `GET :{port}/actuator/health` | Liveness (shown with details) |
| `GET :{port}/actuator/metrics` · `/actuator/prometheus` | Micrometer metrics scraped by Prometheus |
| `http://localhost:8761` | Eureka registry dashboard |
| `http://localhost:9091` | Prometheus |
| `http://localhost:3001` | Grafana (`admin`/`admin`) |
| `http://localhost:9200` | Elasticsearch (log store) |

## 🐳 Internal Python AI APIs (Docker network only)

| Service | Endpoint | Consumed by |
|---|---|---|
| Tesseract OCR (Flask, `:5006`) | `POST /ocr` (multipart `file`) | `ocr-module` → `ocr.api.url` |
| Fraud / signature service (Flask, `:5005`) | `POST /fraud-check` → `{ "fraud_score": 0.0–1.0 }` | `ocr-module` → `fraud.api.url` |
