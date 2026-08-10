# Shared Repository Agent Contract

## Context loading

1. Read this contract completely for the first repository task in a new agent conversation, after a context reset or agent handoff, after switching branches, or when this file changes.
2. Reuse the unchanged contract within the same conversation instead of reopening it on every turn.
3. When work is tied to a GitHub issue, read that issue before architecture or detailed repository documents. Confirm its Parent, directly blocking native dependencies, risk, scoped approvals, and declared target integration branch. Do not preload unrelated milestone issues.
4. Read `docs/agent/ARCHITECTURE_OVERVIEW.md` once in each agent conversation before material implementation for an issue or branch. Read it again after a context reset or agent handoff, when it changes, or when the work crosses an architecture boundary.
5. Use `docs/README.md` only when the relevant task-specific document is not already known or when the task moves to a different area.
6. Load the active protected product contract and only the current issue's related `draft` Patch before implementation. Load detailed domain, runbook, schema, API, and testing documents only when the current change touches that subject. Do not preload the documentation tree.
7. Read `docs/DEPENDENCY_SPECIFICATION.md` before changing a language, runtime, build tool, container image, or direct dependency. Do not load it for unrelated work.
8. For current-state facts, treat executable code, configuration, owner-controlled migrations, focused tests, and runtime Swagger as authoritative. Protected product specifications remain normative until an authorized human publishes a new administrative spec release.
9. General questions, status checks, and narrow read-only inspection do not require the architecture overview or unrelated task documents.

## Task startup

1. Inspect `git status`, the current branch, the declared target integration branch, and the files relevant to the request before editing.
2. Use `dev` as the default integration branch. When an issue or its approved parent program explicitly declares another integration branch, use that branch; stop and report a missing branch or conflicting declaration instead of guessing.
3. Preserve unrelated user changes.
4. Treat Vue.js, Spring Framework 5 non-Boot, MyBatis, MySQL, Java 17, and Tomcat 9 as fixed constraints.
5. Treat JavaScript as the default frontend language. TypeScript is conditional, not prohibited, and requires the decision process in `docs/DEPENDENCY_SPECIFICATION.md`.
6. Define the smallest reviewable task boundary and its verification before editing.

## Implementation rules

- Keep the monorepo split into `frontend/` and `backend/`; shared automation belongs at the repository root.
- Never add React, Spring Boot, JPA, embedded secrets, API keys, personal data, or environment-specific credentials.
- Use Spring MVC layering (`controller -> service -> mapper`) and explicit DTOs.
- Keep SQL in MyBatis mapper XML unless a tracked project document explicitly changes that convention.
- Update the executable manifest or build configuration, its lock or coupled configuration, and `docs/DEPENDENCY_SPECIFICATION.md` in the same pull request when adding, removing, replacing, or repurposing a direct dependency.
- Make scoped changes and follow the existing lint and formatting rules.
- Ask for direction only when a missing decision would materially change behavior, data, security, or architecture.
- Do not alter another contributor's unrelated work.

## Documentation ownership and maintenance

1. Treat every file under `docs/specs/` as a protected product contract. Ordinary implementation agents may read these files but must not create, modify, delete, rename, move, format, regenerate, stage, commit, restore, or revert them.
2. A protected-spec exception exists only when a human Product Manager or Repository Administrator explicitly assigns the current personal agent an administrative spec-release task. The request must identify the approved decision and the exact file or bounded subject area. The exception applies only to that administrative release, includes the corresponding `docs/specs/SPEC_LOCK.json` refresh, and does not carry into later implementation work.
3. Treat Flyway migrations under `backend/src/main/resources/db/migration/`, DDL artifacts under `docs/database/`, and schema-level DDL elsewhere as human Product Manager or Repository Administrator controlled. The current personal agent may change them only under an explicit, scoped administrative request that identifies the target tables, invariants, new Migration or DDL release, and verification boundary. A feature request, draft Patch, or inferred schema need is not such authorization. When that scoped approval is present, new immutable Flyway Migration, derived schema artifacts, compatible Mapper/Service code, and related tests may be reviewed atomically in the same issue and pull request; never edit an already applied Migration.
4. Outside a scoped administrative release, agents may inspect protected specifications, migrations, and DDL; identify exact contract or schema gaps; and report the required human decision, table, column, constraint, transition, or backfill. Do not hide a gap with an application workaround or present proposed protected content as an applied repository change.
5. Treat an existing protected-file modification as human-owned unless the current task contains the scoped administrative authorization described above. Do not format, stage, amend, restore, or otherwise alter that modification.
6. Agents may run existing owner-controlled migrations in a disposable verification database or an explicitly scoped local development database when the task requires it. Never apply schema changes to a shared, staging, production, or otherwise team-managed database on an agent's own initiative.
7. Treat `docs/spec-patches/` as the lightweight development-contract layer described in its `README.md`. For work on the approved integration branch, combine the canonical specification only with the `draft` Patch directly related to the current issue or target. Never combine unrelated drafts to infer a broader product contract.
8. Create one Patch per smallest independently reviewable functional change. A Patch requires only stable targets, the added or changed behavior, and observable completion conditions. Document API, data, security, frontend, backend, or test details only when that area is actually affected; never require blanket “no impact” sections.
9. Use only `draft` and `accepted` states. A complete `draft` in `docs/spec-patches/draft/` may be edited with its implementation and is the temporary contract for that feature on the approved integration branch. `accepted` means the Patch has already been integrated into canonical `docs/specs/**`; move it to `docs/spec-patches/archive/` and never rewrite or delete that record.
10. A feature pull request may include its own `draft` Patch and application code. The Patch does not grant authority to change Flyway migrations, DDL, integrated schema artifacts, or protected `docs/specs/**`. A separately scoped Migration/DDL approval under rule 3 may share the implementation pull request, but its approval evidence and `migration_scope` must be recorded independently and the protected canonical SPEC acceptance release remains separate. Remove or correct the draft together with its implementation when the feature is abandoned or materially changed.
11. In a Controller-owned acceptance release, start from the current remote-tracking ref for the issue's approved integration branch; recheck the Patch base specification version and overlapping draft targets; then update all affected canonical documents, release metadata, changelog, any required compatibility baseline, `SPEC_LOCK.json`, and the Patch's `accepted` archive state atomically. The acceptance transition changes only lifecycle status and location, not the reviewed Patch content. An approved or production release must not contain a feature whose Patch remains `draft`.
12. Reverse or change an accepted contract only through a new `draft` Patch and a new Controller-owned canonical spec release. Never restore an older protected file directly, rewrite an accepted record, or infer rollback authority from an implementation request.
13. Do not maintain current feature inventory, endpoint status, mock status, or implementation progress in `docs/specs/` or in another central status document. Determine current behavior from executable code, configuration, focused tests, verification results, and runtime Swagger. Use `IMPLEMENTATION_GUIDE.md` only for stable exploration order and entrypoints.
14. Update unprotected derived documentation only when its stable architecture, operating procedure, or schema explanation changes. Do not create or treat a generated route or endpoint inventory as canonical current behavior; inspect `frontend/src/router/index.js` and the affected code.
15. Remove a mock only after the corresponding endpoint or explicitly bounded feature unit is implemented and focused verification passes. Do not remove unrelated mocks in the same service merely because one endpoint is live. Production builds must not enable mock behavior.
16. Edit `PROJECT_RULES.md`, `ARCHITECTURE_OVERVIEW.md`, `IMPLEMENTATION_GUIDE.md`, and dependency policy only when the task explicitly changes shared policy, a top-level architecture boundary, stable exploration guidance, or dependency governance. Do not add feature inventory or transient implementation status to these files.
17. Treat `docs/archive/` as historical evidence, not a current document to rewrite. Do not edit other agents' local or personal files, including root `AGENTS.md`, `docs/memory/`, `docs/reports/`, and `NOTICE.md`.
18. For shared Markdown-only changes, verify formatting, relative links, and Git tracking. Keep documents unchanged when a change does not alter their contract, stable entrypoint, architecture, ownership, operating procedure, or verified schema explanation.
19. Never bypass protected-file ownership, Patch governance, or the spec-lock guardrail with `--no-verify`, environment overrides, alternate Git plumbing, generated output, another tool, or a delegated sub-agent.

## Verification

1. During incremental work, run the narrowest checks relevant to the changed area.
2. The pre-commit hook selects Frontend, Backend, both, or no application lint from staged paths. Unknown or shared automation paths fail closed to both areas.
3. Run the full root `npm run check` once before pull-request handoff when application code, dependencies, build or test configuration, shared verification automation, or multiple application areas changed.
4. For shared Markdown-only changes, verify formatting, local links, and Git tracking status without running the full application check.
5. Repeat a successful full check only when later changes can invalidate it. Record every skipped required check and the reason in the pull request.
6. Keep local-only agent state, reports, memories, plugins, permissions, models, and credentials out of shared files.
7. Treat stack, protected SPEC/Patch, Architecture boundary, and production Mock violations as hard gates. File count, implementation LOC, and new abstraction/type thresholds are review warnings; they do not fail a change or replace the issue's acceptance criteria.

## Language contract

- Agent-only instructions are written in English.
- Human-facing reports, notices, guides, and review explanations are written in Korean.
- Source-code identifiers follow the conventions of their language and framework.

## Shared versus personal configuration

- This file contains only rules that every repository agent must follow.
- `CLAUDE.md` is the tracked Claude Code adapter that imports this contract.
- Root `AGENTS.md`, `CLAUDE.local.md`, `.claude/settings.local.json`, `.codex/`, local reports, and agent memory are user-owned and are not team requirements.
- Do not commit or standardize personal plugin and permission choices without a separate team decision.
