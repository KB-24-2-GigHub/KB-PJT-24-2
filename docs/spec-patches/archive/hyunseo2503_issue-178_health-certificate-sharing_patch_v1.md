---
patch_id: SPEC-178-03
status: accepted
issue: 178
base_spec_version: 6.0.1
targets:
  - requirement: DOC-007
  - requirement: DOC-008
  - operation: GET /api/worker/workplaces
  - operation: GET /api/documents/{documentId}/shares
  - operation: POST /api/documents/{documentId}/shares
  - operation: DELETE /api/documents/{documentId}/shares/{workplaceId}
  - decision: DEC-DOCUMENT-SHARE-UNIT
  - decision: DEC-WORKPLACE-LIST
---

# SPEC-178-03: 보건증 공유 단위·생명주기 계약

## 추가 사항

보건증 공유 API는 외부에서 `workplaceId`를 받고 DB에는 서버가 결정한
`work_case_id`를 저장한다. 요청 사용자는 Work Case ID나 공유 대상 OWNER ID를 보내지
않는다.

### 공유 대상 결정

신규 공유 대상은 다음 조건을 모두 만족해야 한다.

- 요청 문서는 인증 WORKER가 소유한 `HEALTH_CERTIFICATE`이고 DB 상태가 `ACTIVE`다.
- 서울 날짜 기준 문서의 `expiresDate >= today`다.
- 사업장은 `ACTIVE`다.
- 그 사업장과 인증 WORKER의 Work Case가 정확히 하나 있고 상태가 `ACCEPTED` 또는
  `READY`다. `DRAFT`, `IN_PROGRESS`, `COMPLETED`, `NO_SHOW`,
  `CHECK_OUT_MISSING`, `CANCELED`에는 신규 공유를 만들지 않는다.
- 공유 대상 `shared_with_user_id`는 선택한 Work Case의 계약서 소유자인 OWNER를 서버가
  결정한다.

동일 사업장의 복수 근무는 시연 범위 밖이며 시연 데이터에는 공유 후보가 최대 한 건이라는
가정을 둔다. 데이터가 이 가정을 위반하면 서버는 임의의 Work Case를 고르지 않고
`409 CONFLICT`를 반환한다.

문서가 없거나, 삭제됐거나, 근로계약서거나, 요청자 소유가 아니면
`404 RESOURCE_NOT_FOUND`다. 사업장이 없거나 비활성이고, 위 조건의 Work Case가 없거나,
보건증이 만료됐으면 `400 VALIDATION_ERROR`이며 `fieldErrors.field=workplaceId`를
사용한다. 문서의 존재나 다른 소유자를 세부 메시지로 구분하지 않는다.

### 공유 생성·철회

- `POST /api/documents/{documentId}/shares` Body는 `{workplaceId}`다.
- 성공하면 `purpose=HEALTH_CERTIFICATE`, `status=ACTIVE`인 새 행을 만들고
  `201 {data:{shareId}}`를 반환한다.
- 같은 `document_id`·`work_case_id`·`shared_with_user_id`·`purpose`의 ACTIVE 공유가
  이미 있으면 `409 CONFLICT`다.
- 철회한 공유 행을 되살리지 않는다. 다시 공유하면 새 행을 만들며 이력을 누적한다.
- `DELETE /api/documents/{documentId}/shares/{workplaceId}`는 해당 사업장과 연결된
  현재 문서의 모든 ACTIVE 공유를 `REVOKED`로 바꾸고 `revoked_at`을 기록한다.
  대상이 이미 없거나 철회됐어도 소유자 요청이면 `204`다.
- 철회 API에서도 문서가 없거나 삭제됐거나 다른 사용자 소유면
  `404 RESOURCE_NOT_FOUND`다.
- 원본 보건증 DELETE는 SPEC-178-02에 따라 문서 삭제와 모든 ACTIVE 공유 철회를 같은
  트랜잭션에서 수행한다.

### 매 요청 유효성

공유 접근은 저장한 `document_shares.status`만으로 허용하지 않는다. 상세·파일·목록을
조회할 때 다음 조건을 모두 다시 검사한다.

- 공유 행이 `ACTIVE`다.
- 보건증 문서가 `ACTIVE`이고 `expiresDate >= today`다.
- 사업장이 `ACTIVE`다.
- Work Case 상태가 `ACCEPTED`, `READY` 또는 `IN_PROGRESS`다.
- 서버 현재 시각이 `work_cases.ends_at`보다 이르다.

하나라도 어기면 공유는 즉시 효력을 잃는다. 별도 만료 Batch나 저장 상태 변경은 필요하지
않고 공유 대상의 문서 목록에서 제외하며 직접 접근은 `404 RESOURCE_NOT_FOUND`다.

### 조회 응답

`GET /api/worker/workplaces`는 인증 WORKER에게 신규 공유 후보가 될 수 있는 관계를
`startsAt ASC, workplaceId ASC`로 반환한다.

```json
{
  "workplaceId": 1,
  "workplaceName": "강남점",
  "ownerName": "박사장",
  "startsAt": "2026-08-20T01:00:00Z",
  "endsAt": "2026-08-20T09:00:00Z"
}
```

이 Endpoint는 `documentId`를 받지 않으므로 특정 문서의 중복 공유 여부는 판정하지 않는다.
목록에 있어도 POST 시점에 문서 만료와 중복을 다시 확인한다.

`GET /api/documents/{documentId}/shares`는 문서 소유자에게 공유 이력을 최신 생성순으로
반환한다.

```json
{
  "shareId": 91,
  "workplaceId": 1,
  "workplaceName": "강남점",
  "workCaseId": 201,
  "status": "ACTIVE",
  "sharedAt": "2026-08-11T03:00:00Z",
  "revokedAt": null,
  "effectiveUntil": "2026-08-20T09:00:00Z"
}
```

응답 `status`는 저장 상태가 REVOKED면 `REVOKED`, 저장 상태는 ACTIVE지만 매 요청
유효성 조건을 잃었으면 `EXPIRED`, 모두 유효하면 `ACTIVE`다. `effectiveUntil`은 Work
Case 종료 시각과 보건증 만료일 다음 날 서울 자정 중 빠른 시각이다.

## 완료 조건

- [ ] 신규 공유가 ACTIVE 사업장의 ACCEPTED·READY Work Case에만 생성된다.
- [ ] 동일 사업장 복수 후보를 임의 선택하지 않고 시연 범위 밖의 충돌로 처리한다.
- [ ] 비소유·삭제·잘못된 유형 문서는 `404`, 잘못된 대상 관계는 `400`으로 일관된다.
- [ ] 공유 대상 OWNER와 `work_case_id`를 서버가 결정한다.
- [ ] 중복 ACTIVE 공유는 `409`이고 철회 후 재공유는 새 이력으로 남는다.
- [ ] 공유 효력은 문서·사업장·Work Case·종료 시각을 매 요청에서 함께 검증한다.
- [ ] 공유 철회는 멱등 `204`이며 원본 삭제 시 모든 ACTIVE 공유가 함께 철회된다.
- [ ] 두 조회 Endpoint의 필드와 계산 `status`가 고정된다.
