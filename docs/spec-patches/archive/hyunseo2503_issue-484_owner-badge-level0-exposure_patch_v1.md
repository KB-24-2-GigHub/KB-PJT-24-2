---
patch_id: SPEC-484-01
status: accepted
issue: 484
base_spec_version: 8.1.0
targets:
  - operation: GET /api/invitations/{token}
  - decision: DEC-TRUST-BADGE-CRITERIA
---

# SPEC-484-01: 초대 확인 화면 OWNER 배지 0단계 노출

## 추가 사항

`GET /api/invitations/{token}` 응답의 `ownerBadge`는 더 이상 0단계(아직 이력 없음)를
`null`로 감추지 않는다. `SPEC-178-06`이 정한 "0단계는 `ownerBadge: null`" 관례를 뒤집는
것이며, 산정 기준(누적 10/20/30건, 정상 비율 80/90/100%) 자체는 그대로 둔다.

```json
"ownerBadge": {
  "badgeType": "TRUST_OWNER",
  "level": 0
}
```

`worker.badge`(`WorkCaseDetailResponse`, `SPEC-472-01`)가 먼저 이 관례로 설계됐다 — OWNER가
매칭된 WORKER를 볼 때 0단계도 "이력 쌓는 중" 배지 그림으로 보여준다는 화면 결정이었다.
이 Patch는 WORKER가 초대를 확인하는 시점에도 같은 방식으로 통일한다: 신뢰 정보를 보여주는
두 화면(초대 확인·근무 상세)이 서로 다른 두 관례(하나는 0단계 숨김, 하나는 노출)를 쓰는
비일관성을 없앤다.

뱃지 산정은 새 산식을 만들지 않고 기존 `BadgeApplicationService.recalculate(employerId)`를
그대로 재사용한다(`SPEC-178`이 확정한 계약). Schema·Migration 변경은 없다.

## 완료 조건

- `GET /api/invitations/{token}` 응답에서 `ownerBadge`는 항상 `badgeType`+`level`(0~3)
  객체다 — 0단계도 `null`로 감추지 않는다.
- 뱃지 산정 기준(누적 건수·정상 비율 문턱)과 `GET /api/users/me/badge`의 값·의미는
  변경되지 않는다.
- `worker.badge`(`SPEC-472-01`)의 값과 의미는 변경되지 않는다.
