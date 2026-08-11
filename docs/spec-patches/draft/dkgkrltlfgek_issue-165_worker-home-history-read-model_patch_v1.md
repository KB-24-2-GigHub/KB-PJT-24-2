---
patch_id: SPEC-165-01
status: draft
issue: 165
base_spec_version: 6.0.1
targets:
  - requirement: DASH-001
  - requirement: DASH-003
  - requirement: WORK-007
  - operation: GET /api/worker/home
  - operation: GET /api/worker/work-cases
---

# SPEC-165-01: WORKER 홈·근무 이력 조회에서 시급 기반 금액을 분리한다

## 추가 사항

정식 API_SPEC 6.0.1의 `GET /api/worker/home` 응답 `todayWorkCase`는 `hourlyWage`,
`expectedDeductionAmount`, `expectedPaymentAmount`를 포함한다. 세 값은 모두 저장된 시급을
입력으로 요구하지만 `work_cases`에는 시급 컬럼이 없고, 현재 근무 등록은 `dailyWage`를 직접
입력받는다. `SPEC_TRACEABILITY.md`도 이 지점을 5B-3·5B-4 **Blocked**("지각 공제식·필드·담당
기능 이슈 없음")로 이미 기록하고 있다.

이 Patch는 시급 도입 전까지 두 조회 Operation이 반환하는 필드 집합을 한정한다.

- `GET /api/worker/home`의 `todayWorkCase`는 `hourlyWage`, `expectedDeductionAmount`,
  `expectedPaymentAmount`를 반환하지 않는다. 나머지 필드는 정식 명세를 그대로 따른다.
- `expectedNetAmount`는 `dailyWage`만 입력으로 쓰므로 `DASH-003`의 계산 계약을 그대로
  구현한다.
- `attendance`의 `isLate`, `lateMinutes`는 성공 CHECK_IN에서 파생하며 저장 상태로 만들지
  않는다. 시급이 없어도 파생 가능하므로 정식 명세대로 반환한다.
- 두 Operation은 저장 상태를 화면 별칭으로 바꾸지 않고 `CHECK_OUT_MISSING`과 `NO_SHOW`를
  구분한다. 정밀 좌표, QR Token, OWNER 잔액과 계약 Storage 정보는 반환하지 않는다.

`GET /api/worker/home`의 오늘 근무 후보는 시작일이 오늘인 배정 근무와 전날부터 남은
`IN_PROGRESS`·`CHECK_OUT_MISSING`으로 한정한다. `DRAFT`와 `CANCELED`는 후보가 아니다.

`GET /api/worker/work-cases`는 배정이 확정된 이후 근무만 반환한다. `DRAFT`는 OWNER가 아직
작성 중인 초안이고 `CANCELED`는 배정이 성립하지 않은 근무이므로, 목록과 Page 총계 어디에도
포함하지 않는다. 두 값은 같은 조건을 보므로 `totalElements`와 반환된 `content`가 어긋나지
않는다.

저장된 근무 상태와 성공 근태 기록이 서로 모순되면 두 Operation은 저장된 값을 그대로
반환하되 서버 로그에 무결성 신호를 남긴다. 조회를 실패로 바꾸지 않는다. 이미 저장된 모순
한 건 때문에 WORKER가 본인 근무를 전혀 조회하지 못하는 편이 더 나쁘기 때문이다.

시급 컬럼 도입과 `WORK-008`·`SETTLE-006` 재구현은 이 Patch의 범위가 아니며, Flyway
Migration을 포함하는 별도 승인 작업으로 다룬다. 그 작업이 완료되면 이 Patch가 제외한 세
필드를 정식 명세대로 복원한다.

## 완료 조건

- [ ] 인증 WORKER가 `GET /api/worker/home`으로 오늘 근무 한 건 또는 `todayWorkCase: null`을
      받는다.
- [ ] 오늘 근무가 여러 건이면 `IN_PROGRESS`, `CHECK_OUT_MISSING`, `READY`, `ACCEPTED`,
      `COMPLETED`, `NO_SHOW` 순서로, 같은 상태에서는 `startsAt ASC, workCaseId ASC`로 한
      건만 고른다.
- [ ] 전날보다 이전에 시작한 `IN_PROGRESS`는 오늘 근무로 올라오지 않는다.
- [ ] `expectedNetAmount`가 `DASH-003`의 절사·면제 경계에서 명세와 같은 값을 돌려준다.
- [ ] 지각 근무의 `attendance.isLate`가 `true`이고 `lateMinutes`가 올림된 분수를 돌려준다.
- [ ] `GET /api/worker/work-cases`가 인증 WORKER 본인 근무만 공통 Page Envelope로 돌려주고
      `CHECK_OUT_MISSING`과 `NO_SHOW`를 서로 다른 값으로 구분한다.
- [ ] `GET /api/worker/work-cases`의 `content`와 `totalElements` 어디에도 `DRAFT`·`CANCELED`
      근무가 포함되지 않는다.
- [ ] WORKER가 아닌 인증 사용자는 두 Operation에서 `403 ROLE_MISMATCH`를, 비인증 요청은
      `401 AUTH_REQUIRED`를 받는다.
- [ ] 두 응답 어디에도 `hourlyWage`, `expectedDeductionAmount`, `expectedPaymentAmount`,
      정밀 좌표, QR Token, OWNER 잔액, 계약 Storage 정보가 없다.
