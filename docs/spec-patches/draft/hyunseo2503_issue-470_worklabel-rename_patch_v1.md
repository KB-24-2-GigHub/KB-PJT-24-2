---
patch_id: SPEC-470-01
status: draft
issue: 470
base_spec_version: 8.1.0
targets:
  - requirement: WORK-007
---

# SPEC-470-01: 근무 상태 화면 표기 2종 개명

## 추가 사항

화면 표기 단일 소스(`frontend/src/constants/workCaseStatus.js`)의 근무 상태 라벨 중
`DRAFT`와 `CHECK_OUT_MISSING` 두 개를 아래와 같이 바꾼다. 상태 enum·전이·API 계약은
바꾸지 않고 화면에 보이는 한글 문구만 바꾼다.

- `DRAFT`: `수락 전` → `미배정`
- `CHECK_OUT_MISSING`: `퇴근 확인 필요` → `퇴근 미확인`

`REQUIREMENTS.md`(WORK-007)와 `MVP_SCOPE.md`(2-2·2-4·4-2 데모 시나리오 행)는 각각
`퇴근 확인 필요`·`수락 전` 문구를 그대로 인용하고 있어, 이 문서들의 서술과 실제 화면
표기가 이 Patch 이후 달라진다. 두 문서는 보호 SPEC이라 이 Patch가 직접 고치지 않으며,
문구 갱신은 정식 SPEC 반영(Controller 수락) 시 함께 처리한다.

## 완료 조건

- `DRAFT` 상태 근무가 화면에서 `미배정`으로 표시된다.
- `CHECK_OUT_MISSING` 상태 근무가 화면에서 `퇴근 미확인`으로 표시된다.
- 두 라벨 모두 `workCaseStatus.js`의 `WORK_CASE_STATUS` 단일 소스에서만 바뀌고, 다른
  컴포넌트에 문자열이 하드코딩되지 않는다.
