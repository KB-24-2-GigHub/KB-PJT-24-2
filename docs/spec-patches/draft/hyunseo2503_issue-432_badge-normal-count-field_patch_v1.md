---
patch_id: SPEC-432-01
status: draft
issue: 432
base_spec_version: 8.1.0
targets:
  - operation: GET /api/users/me/badge
---

# SPEC-432-01: 최신 뱃지 응답에 정상 건수 필드 추가

## 추가 사항

`GET /api/users/me/badge`는 기존 필드에 더해 정수 `normalCount`(누적 건수 중 정상으로 판정된
건수)를 `data`에 추가로 반환한다. 이 값은 서버가 이미 계산해 `criterionDesc` 문장을 조립하는
데 쓰던 값을 그대로 노출하는 것이며, 등급 판정 로직이나 기존 필드(`recentCount`,
`remainingToNextLevel`, `criterionLabel`, `criterionDesc`)의 의미는 바꾸지 않는다.

마이페이지 화면은 이 필드로 "누적 N건 중 정상 M건 (P%)" 형태를 구성한다. 비율(P%)은 표시
전용 값으로 Frontend가 `normalCount`와 `recentCount`로 반올림해 계산하며, 등급 판정에는
쓰지 않는다(판정은 여전히 서버가 반올림 없이 `normalCount * 100 >= recentCount * threshold`로
수행한 뒤 `level`로만 전달한다).

## 완료 조건

- `GET /api/users/me/badge` 응답에 정수 `normalCount`가 포함되고, 이력이 없으면 0을 반환한다.
- `normalCount`는 항상 `recentCount`(누적 건수) 이하다.
- 기존 `criterionDesc`·`criterionLabel`·`level`·`remainingToNextLevel`의 값과 의미는
  변경되지 않는다.
