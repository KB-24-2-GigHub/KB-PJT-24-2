# Implementation Guide

Use this guide to locate implementation evidence in a stable order. It intentionally contains no route inventory, endpoint inventory, mock inventory, feature status, or delivery progress. Those facts change too quickly and must be derived from executable sources at task time.

## Start every implementation task

1. Read `PROJECT_RULES.md`.
2. Read the current issue, its Parent, directly blocking native dependencies, scoped approvals, and declared target integration branch.
3. Read the relevant protected requirement or API contract under `docs/specs/` and only the current issue's related `draft` Patch.
4. Define one reviewable feature or endpoint boundary and its focused verification.
5. Inspect `git status`, confirm the branch derives from the approved integration branch, and preserve unrelated or human-owned changes.
6. Follow the exploration order for the affected area below.
7. Treat code, configuration, focused tests, current verification, and runtime Swagger as implementation evidence. Never infer completion from a filename, package, route overview, or specification status label.

## Frontend page or navigation work

1. Start at `frontend/src/router/index.js` to find the actual path, route name, guard, redirect, and lazy-loaded view.
2. Open the routed file under `frontend/src/views/`.
3. Follow imported components, composables, Pinia stores, and utilities only as the flow requires.
4. Follow calls into `frontend/src/services/` and then the shared client in `frontend/src/services/http.js`.
5. Inspect the matching backend mapping and focused frontend tests before changing request or response assumptions.
6. Verify the affected route, guard, view state, and service behavior. Do not rely on an archived or generated route inventory.

## Frontend API integration or mock removal

1. Open the affected file under `frontend/src/services/` and identify the exact mock branch and HTTP branch.
2. Trace every caller in views, components, stores, and tests.
3. Confirm the real backend mapping, request DTO, response DTO, error behavior, authorization, and session requirements.
4. Compare runtime Swagger with the controller and focused tests when the application can be run.
5. Switch only the verified endpoint or explicitly bounded feature unit.
6. Remove its mock data and switching code only after focused integration verification passes. Leave unrelated mocks intact.
7. Ensure production configuration cannot enable mock behavior.

## Backend HTTP or business-flow work

1. Find the concrete mapping in `backend/src/main/java/**/controller/`.
2. Follow request and response DTOs rather than inferring a contract from a `Map`.
3. Follow `controller -> service -> mapper` and inspect transaction boundaries, authorization, idempotency, and error conversion.
4. Open the corresponding MyBatis mapper interface and XML under `backend/src/main/resources/mappers/`.
5. Read only the migrations needed to understand the touched tables and constraints.
6. Inspect focused controller, service, and database tests.
7. Verify runtime Swagger and the narrowest relevant automated checks.

## Authentication, session, CORS, or CSRF work

1. Inspect `frontend/src/services/http.js` and the affected router guards or authentication store.
2. Inspect `AppInitializer`, `WebMvcConfig`, root or servlet configuration, security filters, and authentication controllers.
3. Trace cookie, session, role, ownership, CORS, and CSRF behavior across both applications.
4. Verify unauthorized, forbidden, expired-session, and state-changing request cases with focused tests.

## Persistence or schema-gap work

1. Trace the current controller, service transaction, mapper interface, and mapper XML.
2. Read the ordered Flyway migrations that define the affected tables, keys, checks, and data transitions.
3. Determine whether the requirement is enforceable in application code and the current schema without weakening integrity.
4. If a protected schema change is required, report the exact gap and required human migration. Do not edit protected SQL during ordinary implementation work.
5. After an authorized administrative schema release exists, update affected application code and unprotected schema explanations, then run focused migration and database verification.

## Shared configuration or dependency work

1. Read `docs/DEPENDENCY_SPECIFICATION.md` before changing a language, runtime, build tool, container image, or direct dependency.
2. Inspect the executable manifest, lock or coupled configuration, and the narrowest relevant runtime entrypoint.
3. Update all coupled configuration and verification in the same reviewable change.

## Verification boundary

- Use the narrowest relevant checks while iterating.
- Verify request and response contracts at both caller and controller boundaries.
- For database behavior, include the affected constraint, transaction, and replay or concurrency case when applicable.
- Run the full root check once at the issue or branch completion boundary when required by `PROJECT_RULES.md`.
- Report unresolved protected-spec or schema gaps instead of marking the implementation complete.
