# OCR & Fraud-Detection Module — Feature Spotlight

> `ocr-module` (Spring Boot, port **3333**) is the heart of the platform: a reactive, async, event-driven pipeline that turns an uploaded bank document into an OCR transcript + a real-time fraud verdict, persists it, and broadcasts it to Kafka.

This document zooms into the module's technologies and features for interview/reviewer deep-dives. Related: [Architecture §3](ARCHITECTURE.md) · [API reference](API.md).

---

## 🚀 High-level capability map

| Capability | Technology | Source |
|---|---|---|
| Non-blocking document processing | Spring WebFlux + `@Async` / `CompletableFuture` | [`OcrService`](../Backend/ocr-module/src/main/java/dev/atb/ocr/Service/OcrService.java) |
| Multi-format ingestion (PNG/JPEG/PDF/HEIC) | Apache PDFBox 2.0.29, Cloudinary 1.37.0, `javax.imageio` | [`ImageConverter`](../Backend/ocr-module/src/main/java/dev/atb/ocr/Service/ImageConverter.java) |
| Multilingual text extraction (fra + ara + eng) | Tess4J 5.4.0 + Tesseract 4 Flask service | [`application.properties`](../Backend/ocr-module/src/main/resources/application.properties), [`ocr.xml`](../Backend/ocr-module/src/main/resources/ocr.xml) |
| OCR engine tuning | char whitelist, page-segmentation mode, space preservation | [`ocr.xml`](../Backend/ocr-module/src/main/resources/ocr.xml) |
| Real-time fraud scoring | REST call → `fraud_score` (0–1) + 0.7 decision threshold | `OcrService.detectFraudScore` |
| Event-driven downstream fan-out | Spring Kafka producer → topic `ocr-results` | `OcrService.notifyKafka` |
| Persistent audit trail | Spring Data JPA + MySQL 8 + Flyway, base64 image LOB, audit timestamps | [`Ocr`](../Backend/shared-module/src/main/java/dev/atb/models/Ocr.java) entity |
| Config-driven behaviour | `@ConfigurationProperties("ocr.tesseract")`, externalized API URLs | [`OcrConfig`](../Backend/ocr-module/src/main/java/dev/atb/ocr/config/OcrConfig.java) |
| Hardened input validation | Content-type allow-list, 10 MB size cap, Bean Validation | [`FileValidationService`](../Backend/ocr-module/src/main/java/dev/atb/ocr/Service/FileValidationService.java), controller |
| Typed error handling | `OcrProcessingException`, unified `ApiResponse`/`ResponseWrapper`, `@ExceptionHandler` | `exceptions/`, `config/` |
| OIDC-secured endpoints | Keycloak JWT resource server | `application.properties` |
| Observability | Actuator health/metrics/prometheus, `traceId`/`spanId` logs | `application.properties` |
| Service composition pattern | Builder with mandatory-dependency validation | [`OcrServiceBuilder`](../Backend/ocr-module/src/main/java/dev/atb/ocr/Service/OcrServiceBuilder.java) |

---

## 🧬 The pipeline, feature by feature

### 1. Reactive + async by default
The module runs in **reactive web mode** (`spring.main.web-application-type=reactive`) and offloads the heavy OCR/fraud work off the request thread with `@Async` returning `CompletableFuture<OcrDTO>`, so a slow Tesseract round-trip never blocks the HTTP connector. Upload endpoints respond as soon as the future completes, and failures map cleanly to a 500 `OcrDTO` via `.exceptionally(...)`.

### 2. Multi-format document ingestion
`ImageConverter` normalizes heterogeneous operator uploads into OCR-friendly PNG:
- **PDF → PNG** via Apache **PDFBox** rendered at **300 DPI** for high-accuracy recognition.
- **JPG → PNG** via `javax.imageio`.
- **HEIC → PNG** pathway scaffolded for iPhone-shot receipts (Cloudinary `cloudinary-http44` dependency wired for server-side conversion).

### 3. Multilingual, tuned OCR
Text extraction targets a **Tesseract 4** engine (Dockerised Flask service, `POST /ocr`) with language stack **`fra+ara+eng`** — covering French, Arabic and English bank documents. Engine behaviour is tuned through `ocr.xml`:
- `tessedit_char_whitelist` (alphanumerics) to cut noise on amounts/refs,
- `tessedit_pageseg_mode=3` for sparse-text handling,
- `preserve_interword_spaces` to keep cheque/fee layouts readable.

### 4. Real-time fraud scoring
`detectFraudScore` ships the document to a dedicated **fraud-analysis API** (`POST /fraud-check` → `{ "fraud_score": … }`) and applies a **0.7 decision threshold** to flag the document. Crucially, the integration **degrades gracefully**: any fraud-API outage is logged and returns `0.0` rather than aborting the whole pipeline — an availability-first design worth calling out in review.

### 5. Event-driven fan-out
Every completed analysis is serialized (`ObjectMapper`) and published to the Kafka topic **`ocr-results`**. Alerting, audit and analytics consumers can subscribe without the detection path knowing they exist — textbook event-driven decoupling.

### 6. Persistent, auditable results
The `Ocr` JPA entity stores document type, OCR transcript, fraud flag, account link (`@ManyToOne` **LAZY** to `Compte`), the **base64 image as `@Lob`** for instant UI preview, and automatic `createdAt`/`updatedAt` audit columns (`@PrePersist`/`@PreUpdate`). Schema is versioned with **Flyway** (`V1__Initial_setup.sql`).

### 7. Defensive validation & typed errors
Three layers guard bad input: content-type allow-list + 10 MB size cap (`FileValidationService`), Bean Validation on the controller (`@Valid/@NotNull/@NotEmpty`), and typed exceptions (`OcrProcessingException`) surfaced through a unified `ApiResponse`/`ResponseWrapper` envelope with a `MultipartException` handler.

---

## 🏛 Design patterns showcased

- **Builder pattern** — `OcrServiceBuilder` assembles `OcrService` and fails fast (`IllegalStateException`) if any mandatory collaborator is missing.
- **Constructor injection / immutability** — Lombok `@RequiredArgsConstructor` on the service.
- **Separation of concerns** — validation (`FileValidationService`), format conversion (`ImageConverter`), orchestration (`OcrService`), configuration (`OcrConfig`) each isolated.
- **Externalized configuration** — API URLs, OCR languages, thresholds and Kafka/DB settings all in `application.properties`, no magic constants in code.

---

## 🐳 Deployment topology

Two Docker Compose profiles exist for the module:
- **Standalone** ([`ocr-module/docker-compose.yml`](../Backend/ocr-module/docker-compose.yml)): `tesseract` + `mysql` + `zookeeper` + `kafka` on an `ocr-network`, all health-checked — for isolated development.
- **Platform stack** ([`Backend/docker-compose.yml`](../Backend/docker-compose.yml)): shares the central Tesseract `:5006`, fraud/signature `:5005`, and Kafka `:9092` services with the rest of the microservices.

---

## 🗺 Roadmap / in-progress (honesty for interviews)

These are designed or partially referenced but **not yet fully implemented** in the codebase — be ready to discuss them as next steps rather than shipped features:

- **Signature verification** — `SignatureService` is referenced by `OcrService`/`OcrServiceBuilder` but the class is not present. The intended algorithm (OpenCV ORB keypoints + homography alignment + similarity threshold) is specified in [`Backend/README.md`](../Backend/README.md).
- **Notifications** — `LoggerService` / `NotificationService` are wired into the service constructor but not yet implemented.
- **ML classifier** — the current fraud score comes from an external API; the planned replacement is a fine-tuned **InceptionV3 / TensorFlow** transfer-learning model with quantization-aware export (design in `Backend/README.md`).

> ⚠️ Until the referenced classes above are added, the module will not compile. This is the single highest-value "finishing" task before demoing.

---

## 🖥 Frontend counterpart

The SPA exposes the pipeline through `components/ocr/*` (upload + result/preview screens) and `services/ocrService.ts`, posting to `POST /ocrs/upload` and rendering the returned `OcrDTO` (transcript, base64 preview, fraud verdict + score).
