---
patch_id: SPEC-307-01
status: accepted
issue: 307
base_spec_version: 6.0.0
targets:
  - requirement: MVP-P0-SCENARIOS
  - decision: DEC-MVP-SCOPE-PRIORITY
  - traceability: MVP-P0-TRACEABILITY
---

# SPEC-307-01: MVP_SCOPE 원본 추적과 에이전트 라우팅

## 추가 사항

- PM/Admin이 제공한 원본 `MVP_SCOPE.md`를 Git 줄바꿈 정규화 외의 내용 변경 없이
  `docs/specs/MVP_SCOPE.md`에 보존하고 보호 명세 Lock에 포함한다.
- 제품 범위, Priority, 시연 순서와 화면 성공 결과를 판단할 때 원본을 먼저 읽고,
  REQUIREMENTS·API_SPEC·DECISIONS·SPEC_TRACEABILITY에서 안정적인 ID와 공식 계약을
  확인하도록 공유 문서와 에이전트 진입점을 연결한다.
- #282가 공식화한 37개 P0, 목표 API, 구현 상태와 기능 이슈 매핑은 유지한다. 이 릴리스는
  P0/P1/P2를 재분류하거나 기능 구현 상태를 변경하지 않는다.
- 명세 릴리스를 `6.0.1`로 올리고 원본과 파생 규범 계약의 관계를 릴리스 기록과 추적표에
  남긴다.

## 완료 조건

- [ ] 저장소 checkout만으로 `docs/specs/MVP_SCOPE.md`를 읽을 수 있다.
- [ ] 제공 원본과 저장소 파일의 CRLF→LF 정규화 SHA-256이 같다.
- [ ] `docs/README.md`, `docs/specs/README.md`, `docs/agent/PROJECT_RULES.md`가 원본을
      제품 범위·Priority 판단의 첫 진입점으로 연결한다.
- [ ] 기존 37개 P0, P1/P2와 사용자 성공 결과가 바뀌지 않는다.
- [ ] `6.0.1` 릴리스 메타데이터, 상대 링크와 `SPEC_LOCK.json` 검증이 통과한다.
