---
patch_id: SPEC-178-06
status: draft
issue: 178
base_spec_version: 6.0.1
targets:
  - requirement: BADGE-001
  - operation: GET /api/users/me/badge
  - decision: DEC-TRUST-BADGE-CRITERIA
---

# SPEC-178-06: 신뢰 배지 산정 기준

## 추가 사항

배지 등급은 역할과 무관하게 같은 두 값으로 계산한다: **누적 건수**와 그 중
**정상 비율**. 두 조건을 모두 만족해야 해당 등급이며, 건수만 많고 비율이 낮은
사용자가 등급을 얻지 못하도록 두 조건을 AND로 검사한다.

| 등급 | 누적 건수 | 정상 비율 |
| --- | --- | --- |
| 0단계 | (기본값) | - |
| 1단계 | 10건 이상 | 80% 이상 |
| 2단계 | 20건 이상 | 90% 이상 |
| 3단계 | 30건 이상 | 100% |

등급은 3단계부터 역순으로 검사해 처음 조건을 만족하는 단계로 정한다(예: 50건에
85%면 1단계 조건은 만족하지만 2단계 조건은 못 만족하므로 1단계). 두 조건 중
하나라도 충족하지 못하면 0단계다.

### OWNER (`TRUST_OWNER`)

- 분모: 본인이 당사자인 `settlements` 중 `status='COMPLETED'`인 누적 건수.
  `WAITING`/`SCHEDULED`/`PROCESSING`/`FAILED`/`ON_HOLD`는 아직 결과가 나지 않은
  것으로 보고 집계하지 않는다.
- 분자("정상 정산"): 그 중 `work_case_id`에 `CANCELED`·`REJECTED`가 아닌 `disputes`
  행이 없는 건수. 분쟁 신고 기능(`DISPUTE-001~003`)이 Deferred라 지금은 항상 전부
  정상으로 집계되며, 그 기능이 나오면 이 계산식을 바꾸지 않고 그대로 반영된다.

### WORKER (`TRUST_WORKER`)

- 분모: 본인이 당사자인 `work_cases` 중 `status IN ('COMPLETED', 'NO_SHOW',
  'CHECK_OUT_MISSING')`인 누적 건수. `CANCELED`와 아직 진행 중인 상태는 집계하지
  않는다.
- 분자("정상 근무"): 그 중 `status='COMPLETED'`이고 `attendance_records.isLate`가
  `true`가 아닌 건수. `NO_SHOW`, `CHECK_OUT_MISSING`, 지각 `COMPLETED`는 비정상으로
  센다.

### 공통 응답·재산정

- 이력이 없는 신규 사용자도 `null` 없이 `badgeType`, `level=0`을 반환한다.
- `recentCount`는 위에서 정의한 누적 분모 건수를 그대로 반환한다.
- `remainingToNextLevel`은 다음 단계 건수 임계값까지 남은 건수다(`max(0, 다음 단계
  건수 임계값 - 현재 누적 건수)`). 건수는 이미 충분한데 비율만 부족한 경우
  `remainingToNextLevel=0`이며, 그 사정은 `criterionDesc`로 안내한다. 3단계는 항상
  `remainingToNextLevel=0`이다.
- 재산정은 기준 이벤트(정산 완료·분쟁 상태 변경, 근무 종료·노쇼 확정)가 발생하는
  트랜잭션에서 즉시 이루어진다. 별도 배치·스케줄 작업을 두지 않는다.
- 같은 사용자에 대한 재산정이 동시에 여러 트랜잭션에서 발생해도 `user_badges`의
  `UNIQUE(user_id, badge_type)`을 이용한 Upsert로 마지막에 Commit된 계산 결과만
  반영한다. 별도 분산 Lock이나 순서 보장 장치를 두지 않는다.
- 이 계약을 배포하는 시점에 이미 존재하는 이력에 대한 별도 Backfill 배치를 두지
  않는다. 기존 사용자는 배포 뒤 다음 기준 이벤트가 발생할 때 그 시점까지의 누적
  이력으로 처음 계산되며, 그전까지는 이력이 없는 사용자와 동일하게 `level=0`으로
  응답한다.
- `user_badges.evidence`에는 누적 분모 건수, 정상 건수, 비율, 계산 시각을 저장해
  재검증 가능하게 한다. 새 Column은 추가하지 않는다.

## 완료 조건

- [ ] 총 건수가 아무리 많아도 정상 비율이 80% 미만이면 1단계에 도달하지 못한다.
- [ ] 건수 10/20/30, 비율 80/90/100% 두 조건을 모두 만족해야 각각 1/2/3단계다.
- [ ] 이력이 없는 신규 사용자가 `level=0`으로 응답하며 `null`이 아니다.
- [ ] 건수 조건은 충족했지만 비율 조건을 못 채운 경우 `remainingToNextLevel=0`이고 `criterionDesc`가 비율 부족을 설명한다.
- [ ] 3단계 사용자의 `remainingToNextLevel`이 `0`이다.
- [ ] 근무 종료·노쇼 확정·정산 완료 시점에 배지가 배치 없이 즉시 재계산된다.
- [ ] `user_badges.evidence`에서 등급 산정 근거(누적 건수·정상 건수·비율)를 재확인할 수 있다.
- [ ] 같은 사용자의 재산정이 동시에 발생해도 `user_badges` 행이 `UNIQUE(user_id, badge_type)` 위반 없이 마지막 계산값으로 정상 갱신된다.
- [ ] 배포 시점에 별도 Backfill 없이도, 기존 이력이 있는 사용자가 다음 기준 이벤트 발생 시 그 누적 이력 그대로 정상 계산된다.
