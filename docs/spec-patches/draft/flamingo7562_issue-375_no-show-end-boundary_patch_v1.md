---
patch_id: SPEC-375-01
status: draft
issue: 375
base_spec_version: 8.0.0
targets:
  - requirement: ATT-002
  - requirement: ATT-005
  - decision: DEC-ATTENDANCE-LIFECYCLE
  - decision: DEC-NO-SHOW-OWNER-APPROVAL
  - api: POST /api/attendance/scans
---

# SPEC-375-01: 짧은 근무 노쇼 경계 정합화

## 추가 사항

- 성공한 `CHECK_IN`이 없는 근무의 체크인 종료 및 `NO_SHOW` 판정 경계는
  `min(starts_at + 1시간, ends_at)`으로 계산한다.
- 경계 직전까지 체크인을 허용하고, 경계 시각부터 체크인을 허용하지 않으며 `NO_SHOW`
  자동 전이 대상에 포함한다.
- 1시간 이상인 근무는 기존처럼 `starts_at + 1시간`에 판정하고, 1시간보다 짧은 근무는
  `ends_at`에 판정한다.
- 후보 조회와 근무 행 잠금 후 재검증은 동일한 경계를 사용한다.
- `NO_SHOW` 전이는 근태·근무 상태만 변경하며 지갑, 에스크로, 정산 금액은 변경하지 않는다.

## 완료 조건

- [ ] 13:53~13:55 근무는 성공한 체크인이 없을 때 13:55부터 `NO_SHOW` 대상이다.
- [ ] 09:00~18:00 근무는 성공한 체크인이 없을 때 10:00부터 `NO_SHOW` 대상이다.
- [ ] 경계 직전 체크인은 허용되고 경계 시각의 체크인은 거절된다.
- [ ] 성공한 체크인이 있으면 `NO_SHOW`로 전이하지 않는다.
- [ ] 소유자와 근로자 조회에서 전이된 근무가 `NO_SHOW`로 표시된다.
- [ ] 단위 테스트와 MySQL 통합 테스트가 짧은 근무 경계 및 기존 1시간 경계를 검증한다.

## 범위 제외

- 체크인 전 별도 `LATE` 상태 또는 API 표시 추가
- 지각 임금 차감이나 자동 환급
- QR 외 수기 근태 보정 기능
- 스키마 및 DDL 변경
