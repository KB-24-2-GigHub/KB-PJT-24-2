# Architecture Overview

Read this short overview after the current issue, its Parent, direct native dependencies, and target integration branch are confirmed. Use `docs/README.md` only when deeper task-specific guidance is needed.

## Runtime shape

- The repository is a monorepo: `frontend/` contains the browser application, `backend/` contains the server application, and root scripts coordinate shared checks and local infrastructure.
- The frontend is currently a JavaScript-based Vue 3 and Vite application. Vue Router owns navigation, Pinia owns client state, and `src/services/` owns HTTP access through Axios. TypeScript adoption is conditional under `docs/DEPENDENCY_SPECIFICATION.md`.
- The backend is a Java 17 WAR for Tomcat 9 using Spring Framework 5 without Spring Boot. Annotation-based configuration separates the Root Context from the Spring MVC Servlet Context.
- Backend domain and business flows follow `controller -> service -> mapper`; MyBatis mapper interfaces call SQL in `backend/src/main/resources/mappers/`.
- Backend 업무 경계는 현재 package 수가 아니라 [`MODULE_BOUNDARIES.md`](MODULE_BOUNDARIES.md)의 9개 논리 모듈과 25개 table write owner로 판단한다. `work`/`invitation`/`contract`는 하나의 Work 모듈이고 `auth`/`member`/`badge`는 하나의 Member/Auth 모듈이다.
- MySQL 8.4 runs through Docker Compose. Flyway SQL under `backend/src/main/resources/db/migration/` is the schema source of truth.
- Docker Compose provides MySQL and opt-in Flyway or seed tools only; run the Vue application and Tomcat WAR separately.

## Change boundaries

- Frontend pages live in `src/views/`, reusable UI in `src/components/`, state in `src/stores/`, routes in `src/router/`, and backend calls in `src/services/`.
- Backend code is grouped by domain under `com.gighub`. Keep web, business, and persistence responsibilities in their existing layers and use explicit DTOs at API boundaries.
- `AppInitializer`, `RootConfig`, `WebMvcConfig`, and `DatabaseConfig` are the current backend wiring entrypoints.
- The frontend HTTP client defaults to `/api` and can override its base URL with `VITE_API_BASE_URL`. During local Vite development, `DEV_PROXY_TARGET` changes only the target of the `/api` proxy.
- Local development fixes Vite to `http://localhost:5173`. Tomcat permits credentialed `/api/**` CORS only from that exact origin, and `JSESSIONID` is host-only, HttpOnly, `SameSite=Lax`, `Secure=false`; deployed HTTPS settings must be separated.
- API moment fields are UTC `Instant` strings. Database `DATETIME(6)` values remain `Asia/Seoul` wall-clock values and are converted at the server boundary; date-only values use `LocalDate`.
- A frontend service or domain package name does not prove end-to-end implementation. Inspect the affected service's mock branch, matching backend mapping, focused tests, and runtime Swagger before changing a flow.
- Protected specifications, Flyway migrations, and schema DDL follow the administrative-release boundary in `PROJECT_RULES.md`. Ordinary implementation work is read-only for those paths.

## Authoritative sources

- Technology selection and allowed, conditional, or prohibited dependencies: `docs/DEPENDENCY_SPECIFICATION.md`.
- Application dependency and runtime versions: `frontend/package.json` and `backend/build.gradle`.
- Container image versions and local infrastructure: `compose.yaml`.
- Stable task exploration order and code entrypoints: [`IMPLEMENTATION_GUIDE.md`](IMPLEMENTATION_GUIDE.md).
- Frontend routes and request behavior: `frontend/src/router/index.js`, `frontend/src/services/http.js`, and the affected `frontend/src/services/*.js`.
- Backend wiring and layer boundaries: `backend/src/main/java/com/gighub/config/` and current domain packages.
- Logical module ownership, public application calls, query exceptions, and transaction orchestrators: [`MODULE_BOUNDARIES.md`](MODULE_BOUNDARIES.md) and its machine-readable manifest.
- Java Lombok, type, interface, Exception, and Service complexity reviews: [`JAVA_MODELING_GUIDE.md`](JAVA_MODELING_GUIDE.md).
- Protected product requirements and target API contracts: `docs/specs/`. Read them for intent and acceptance criteria, but derive current implementation only from code, configuration, focused tests, verification results, and runtime Swagger.
- Database structure: owner-controlled Flyway migrations first, then `docs/agent/SCHEMA_OVERVIEW.md` for compact context and `docs/DATABASE_SCHEMA_ERD.md` for the detailed relationship map. Migration and DDL ownership follows the scoped administrative-release rule.
- Task-specific guides and runbooks: `docs/README.md`.

## Active documentation responsibilities

- `docs/README.md` is the single human and agent router. It links only tracked, active shared documents.
- `PROJECT_RULES.md` owns repository-wide agent hard rules; `IMPLEMENTATION_GUIDE.md` owns stable exploration order, not current feature status.
- `docs/specs/` owns protected product behavior, `docs/spec-patches/` owns the temporary development-contract lifecycle, and `docs/runbooks/` owns executable operating and recovery procedures.
- `docs/archive/` and accepted Patch records are historical evidence, not active contracts.
- `MODULE_BOUNDARIES.md`, `VERIFICATION_GUIDE.md`, and `JAVA_MODELING_GUIDE.md` are active shared contracts for module ownership, risk-based verification, and Java modeling respectively. Do not replace them with issue-local copies or empty placeholders.
- Ignored local references, personal agent files, memories, and reports are not canonical sources and must not be linked from the shared router.

Follow the documentation ownership and maintenance rules in [`PROJECT_RULES.md`](PROJECT_RULES.md). Update this overview only when a top-level runtime, default language, directory responsibility, request path, persistence boundary, or authoritative source changes. Keep feature details in task-specific documents.
