---
patch_id: SPEC-472-01
status: draft
issue: 472
base_spec_version: 8.1.0
targets:
  - operation: GET /api/work-cases/{workCaseId}
---

# SPEC-472-01: 근무 상세 응답에 매칭 WORKER의 신뢰 뱃지 추가

## 추가 사항

`GET /api/work-cases/{workCaseId}` 응답의 `worker` 객체에 `badge` 필드를 추가한다.
`worker`가 있으면(매칭됨) `badge`는 항상 `badgeType`+`level` 객체다 — 초대 조회 응답
(`InvitationDetailResponse.ownerBadge`, SPEC-178 계열)은 0단계를 `null`로 감추지만, 이
필드는 그러지 않는다. OWNER가 매칭된 WORKER를 볼 때는 "아직 이력 쌓는 중(0단계)"도 뱃지
그림으로 보여준다는 화면 결정이라, `level`은 0~3 그대로 노출한다. `worker`가 아직 매칭
전이라 `null`이면 `badge` 필드 자체도 당연히 없다(`worker` 객체 전체가 없으므로).

```json
"worker": {
  "workerId": 42,
  "name": "이알바",
  "badge": {
    "badgeType": "TRUST_WORKER",
    "level": 0
  }
}
```

`badge`는 `badgeType`·`level` 두 필드만 담는다(`ownerBadge`와 동일한 얕은 구조). 산정
기준·정의문·다음 등급까지 남은 건수 같은 부가 설명은 포함하지 않는다 — 그런 상세는 이미
`GET /api/users/me/badge`가 본인에게만 제공하며, 이번 필드는 타인(매칭된 WORKER)의 등급을
근무 당사자인 OWNER가 확인하는 좁은 용도다.

뱃지 산정은 새 산식을 만들지 않고 기존 `BadgeApplicationService.recalculate(workerId)`를
그대로 재사용한다(SPEC-178이 확정한 계약). Schema·Migration 변경은 없다.

## 완료 조건

- `GET /api/work-cases/{workCaseId}` 응답에서 `worker`가 있으면(매칭됨) `worker.badge`가
  `badgeType`+`level`(0~3) 객체다 — 0단계도 `null`로 감추지 않는다.
- `worker`가 `null`이면(미매칭) 응답에 `badge` 필드가 별도로 존재하지 않는다.
- 뱃지 산정 기준·다른 응답(`ownerBadge`, `GET /api/users/me/badge`)의 값과 의미는 변경되지
  않는다.
