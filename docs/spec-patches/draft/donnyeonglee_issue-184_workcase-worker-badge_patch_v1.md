---
patch_id: SPEC-184-01
status: draft
issue: 184
base_spec_version: 8.1.0
targets:
  - operation: GET /api/work-cases/{workCaseId}
---

# SPEC-184-01: 근무 상세 응답에 알바생 신뢰 뱃지 추가

## 추가 사항

`GET /api/work-cases/{workCaseId}` 응답의 `worker`에 `badge`(`{badgeType, level}` 또는
`null`)를 추가한다. 산정·응답 규칙은 초대 상세(`GET /api/invitations/{token}`)의
`ownerBadge`와 동일하게 맞춘다 — 요청 시점에 해당 알바생의 뱃지를 재산정하고, 활성 등급이
없으면(`level <= 0`) 객체 대신 명시적 `null`을 반환한다.

이 Endpoint는 이미 `requireParty`로 해당 근무 Case의 사장·알바생 당사자만 조회할 수 있어,
타인 ID를 받아 임의로 Badge를 조회하는 새 Endpoint(#184 제외 범위)를 열지 않는다.

## 완료 조건

- `GET /api/work-cases/{workCaseId}` 응답의 `worker.badge`가 알바생의 활성 등급이 있으면
  `{badgeType, level}`을, 없으면 `null`을 반환한다.
- 요청자가 해당 근무 Case의 당사자(사장 또는 매칭된 알바생)가 아니면 기존과 동일하게
  거부되고, `worker.badge` 추가로 새 권한 경로가 생기지 않는다.
- `worker.workerId`·`worker.name`을 포함한 기존 응답 필드의 값과 의미는 바뀌지 않는다.
