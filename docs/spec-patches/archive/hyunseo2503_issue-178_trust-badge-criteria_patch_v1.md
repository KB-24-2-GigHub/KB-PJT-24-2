---
patch_id: SPEC-178-06
status: accepted
issue: 178
base_spec_version: 6.0.1
targets:
  - requirement: BADGE-001
  - requirement: BADGE-002
  - requirement: BADGE-003
  - operation: GET /api/users/me/badge
  - operation: GET /api/invitations/{token}
  - decision: DEC-TRUST-BADGE-CRITERIA
---

# SPEC-178-06: 신뢰 뱃지 산정 기준

## 추가 사항

BADGE-001의 “최근 5·10·15건” 기준을 역할 공통 **누적 10·20·30건**과 정상 비율
**80·90·100%** 기준으로 대체한다. OWNER와 WORKER 모두 같은 문턱을 사용하며 누적 건수와
정상 건수 비율을 AND로 판정한다.

| 등급  | 누적 건수 | 정상 비율 |
| ----- | --------: | --------: |
| 0단계 |    기본값 |         - |
| 1단계 | 10건 이상 |  80% 이상 |
| 2단계 | 20건 이상 |  90% 이상 |
| 3단계 | 30건 이상 |      100% |

3단계부터 내림차순으로 검사해 처음 만족한 등급을 적용한다. 비율은 반올림하지 않고
`normalCount * 100 >= totalCount * thresholdPercent`로 비교한다. `totalCount=0`은
항상 0단계다.

### OWNER (`TRUST_OWNER`)

- 누적 건수는 OWNER가 지급자인 `settlements` 중 `status=COMPLETED`인 건수다.
  `WAITING`, `SCHEDULED`, `PROCESSING`, `FAILED`, `ON_HOLD`는 포함하지 않는다.
- 정상 건수는 그중 같은 `work_case_id`에 상태가 `CANCELED` 또는 `REJECTED`가 아닌
  분쟁 행이 없는 건수다.
- 분쟁 기능이 Deferred인 동안은 완료 정산을 정상으로 센다. 분쟁 기능 도입 뒤에도 이
  분모·분자 정의를 바꾸지 않고 실제 분쟁 행만 반영한다.

### WORKER (`TRUST_WORKER`)

- 누적 건수는 본인이 WORKER인 Work Case 중 `COMPLETED`, `NO_SHOW`,
  `CHECK_OUT_MISSING` 상태의 건수다. `CANCELED`와 진행 전·진행 중 상태는 포함하지 않는다.
- 정상 건수는 그중 `status=COMPLETED`이고 지각하지 않은 건수다.
- 지각 여부는 별도 저장 Flag가 아니라 성공한 CHECK_IN 기록의 `attempted_at`이
  `work_cases.starts_at`보다 늦은지로 재계산한다. `NO_SHOW`,
  `CHECK_OUT_MISSING`과 지각한 COMPLETED는 비정상이다.

### 응답 계약

`GET /api/users/me/badge`는 기존 필드 집합을 유지한다.

```json
{
  "badgeType": "TRUST_WORKER",
  "level": 1,
  "recentCount": 12,
  "remainingToNextLevel": 8,
  "criterionLabel": "성실근로",
  "criterionDesc": "누적 12건과 정상 비율을 기준으로 산정했습니다."
}
```

- 하위 호환을 위해 필드명 `recentCount`는 유지하지만 값은 최근 구간이 아니라 이 Patch의
  누적 `totalCount`다.
- 이력이 없는 사용자도 `null` 대신 역할에 맞는 `badgeType`과 `level=0`,
  `recentCount=0`을 반환한다.
- `remainingToNextLevel`은 다음 등급의 건수 문턱까지 남은 수
  `max(0, thresholdCount - totalCount)`다. 건수는 충족했지만 비율이 부족하면 0이고,
  `criterionDesc`가 부족한 정상 비율을 안내한다. 3단계도 0이다.
- `criterionLabel`은 OWNER가 `안심거래`, WORKER가 `성실근로`다.
  `criterionDesc`는 누적 건수, 정상 건수와 다음 등급의 건수·비율 조건을 함께 설명한다.
- 인증 후 `GET /api/invitations/{token}`은 OWNER의 같은 기준 결과가 1~3단계일 때만
  `ownerBadge={badgeType:"TRUST_OWNER",level}`을 반환한다. 0단계는 기존 계약대로
  `ownerBadge=null`이다.

### 재계산·동시성·기존 사용자

뱃지는 원천 이력에서 계산되는 파생 Projection이다. Work·Attendance·Settlement 모듈이
`user_badges`를 직접 쓰지 않고 Member/Auth의 Badge Application Service만 쓴다.

`GET /api/users/me/badge`와 인증된 초대 조회의 `ownerBadge` 계산은 다음 순서를 따른다.

1. 산정 대상 `users` 행을 `SELECT ... FOR UPDATE`로 잠근다. 뱃지 행이 아직 없어도 잠금
   기준이 항상 존재하도록 `user_badges`가 아니라 사용자 행을 잠근다.
2. 잠금을 얻은 뒤 현재 Commit된 정산·분쟁·Work Case·CHECK_IN 원천 이력을 다시 조회해
   건수와 등급을 계산한다.
3. 같은 트랜잭션에서 `UNIQUE(user_id, badge_type)` 기준으로 `user_badges`를 Upsert하고
   Commit한 뒤 응답한다.

따라서 같은 사용자의 동시 조회 계산은 직렬화되고, 먼저 읽은 오래된 결과가 나중 결과를
덮어쓰지 않는다. 원천 이력 변경과 동시에 시작된 조회는 그 트랜잭션 시작 시점에 Commit된
이력을 반영하며 다음 조회가 새 Commit을 반드시 다시 계산한다.

초대 조회를 담당하는 Work 모듈은 Member/Auth가 공개한 Badge Projection 갱신·조회
Application 경계를 호출한다. `user_badges` Mapper나 원천 모듈 Mapper를 직접 호출하지
않는다. 구현 변경에서는 이 공개 호출 방향을 Module Boundary Manifest에도 함께 등록한다.

배포 시 별도 일괄 소급 계산(Backfill) Batch는 실행하지 않는다. 대신 기존 사용자도 위 두
조회 경로의 첫 요청에서 배포 전 누적 이력 전체를 즉시 계산한다. 기존 이력이 있는데 다음
이벤트가 생길 때까지 0단계로 남겨 두지 않는다.

`user_badges.evidence`에는 다음 닫힌 JSON 필드를 저장한다. 새 Column은 추가하지 않는다.

- `ruleVersion="trust-badge-cumulative-10-20-30-v1"`
- `badgeType`, `level`
- `totalCount`, `normalCount`
- 적용된 `thresholdCount`, `thresholdPercent`
- `calculatedAt`

0단계의 `thresholdCount`와 `thresholdPercent`는 0이고, 1~3단계는 현재 등급의 문턱을
저장한다.

비율은 `totalCount`와 `normalCount`로 재검증하며 개인 이름, 원천 행 ID 목록과 그 밖의
개인 정보는 evidence에 저장하지 않는다.

## 완료 조건

- [ ] OWNER·WORKER 모두 누적 10/20/30건과 80/90/100% AND 기준을 사용한다.
- [ ] 비율 비교에 반올림을 사용하지 않는다.
- [ ] 이력 없는 내 뱃지는 역할별 0단계 객체이고 초대의 OWNER 0단계는 `null`이다.
- [ ] `recentCount`가 누적 건수라는 호환 의미와 다음 단계 안내 규칙이 고정된다.
- [ ] 첫 조회가 배포 전 기존 이력까지 계산하므로 별도 일괄 소급 계산이 필요 없다.
- [ ] 사용자 행 잠금 뒤 재조회·Upsert하여 동시 계산의 오래된 덮어쓰기를 막는다.
- [ ] 원천 모듈이 뱃지 행을 직접 쓰지 않고 Member/Auth Service가 쓰기 경계를 소유한다.
- [ ] 초대 조회는 공개 Badge Application 경계를 사용하고 호출 방향을 Manifest에 등록한다.
- [ ] evidence로 규칙 Version, 건수, 적용 문턱과 계산 시각을 재검증할 수 있다.
