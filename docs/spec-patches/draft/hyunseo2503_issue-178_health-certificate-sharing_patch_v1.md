---
patch_id: SPEC-178-03
status: draft
issue: 178
base_spec_version: 6.0.1
targets:
  - requirement: DOC-007
  - requirement: DOC-008
  - operation: POST /api/documents/{documentId}/shares
  - operation: DELETE /api/documents/{documentId}/shares/{workplaceId}
  - operation: GET /api/worker/workplaces
  - decision: DEC-DOCUMENT-SHARE-UNIT
---

# SPEC-178-03: 보건증 공유 단위·수명주기 계약

## 추가 사항

보건증 공유는 API에서 사업장(`workplaceId`) 단위로 주고받고 DB에는 근무 건
(`work_case_id`) 단위로 저장한다. 서버가 `workplaceId`를 요청자 본인의 확정 근무 건으로
변환하며 요청자가 근무 건을 직접 지정하지 않는다.

- 변환 대상 근무 건은 `status`가 `ACCEPTED` 또는 `READY`인 건만 해당한다. DOC-007의
  "확정됐고 시작 전인 근무"라는 표현대로, 이미 출근해 `IN_PROGRESS`가 된 근무나
  `DRAFT`, `COMPLETED`, `NO_SHOW`, `CHECK_OUT_MISSING`, `CANCELED`는 신규 공유 생성
  대상이 아니다. 이미 만들어진 공유의 열람 가능 기간은 이 조건과 별개로 §1
  (`SPEC-178-01`)의 `ends_at > NOW()` 기준을 그대로 따른다.
- 해당 사업장에 조건을 만족하는 근무 건이 하나도 없으면 `400 VALIDATION_ERROR`이며
  `fieldErrors`의 `field`는 `workplaceId`다.
- 공유 대상 사장님(`shared_with_user_id`)은 그 근무 건의 근무 계약 소유자로 서버가
  도출하며 요청 Body로 받지 않는다.
- 본인 소유가 아닌 문서, 근로계약서, 유효기간이 지난 보건증, 확정 근무가 없는
  사업장은 모두 같은 `400 VALIDATION_ERROR`로 응답하며 사유를 세분화하지 않는다.
- 공유 생성 시 `purpose`는 `HEALTH_CERTIFICATE`로 고정하고 `status`는 `ACTIVE`로
  만든다. 같은 문서·근무 건 조합에 이미 활성 공유가 있으면 `409 CONFLICT`다.
- 공유 철회는 기존 행의 상태를 `REVOKED`로 바꾸는 것이며 행을 삭제하지 않는다.
  철회 뒤 같은 대상에 다시 공유하면 기존 행을 되살리지 않고 새 행을 만든다. 같은
  문서·근무 건 조합의 공유 이력은 여러 행으로 누적될 수 있다.
- 공유의 유효성은 별도 만료 상태나 저장된 만료 시각 없이, 근무 건 종료 시각과
  보건증 유효기간으로 매 요청 계산한다. 상태를 바꾸는 배치나 스케줄 작업을 두지
  않는다.
- `DELETE /api/documents/{documentId}/shares/{workplaceId}`는 그 사업장에 대한 해당
  문서의 활성 공유를 전부 철회한다. 철회할 활성 공유가 없어도 `204`를 반환하며 존재
  여부를 오류로 구분하지 않는다.

`GET /api/worker/workplaces`는 요청자 본인이 위와 같은 조건(`ACCEPTED` 또는 `READY`
근무 건 보유)을 만족하는 사업장을 `workplaceId`, `workplaceName`, `ownerName`,
`startsAt`, `endsAt`로 반환한다. 사업장별로 한 건만 반환하고 `startsAt` 오름차순으로
정렬하며 근무 건 식별자는 포함하지 않는다. 이 목록의 선정 조건은 공유 생성 시
`workplaceId` 변환 조건과 동일하다.

## 완료 조건

- [ ] `ACCEPTED`·`READY` 상태의 확정 근무가 있는 사업장에만 새 보건증 공유를 만들 수 있다.
- [ ] `IN_PROGRESS`를 포함해 출근 이후 상태이거나 미확정·종료된 근무의 사업장은 신규 공유 생성 대상에서 `400 VALIDATION_ERROR`로 거부된다.
- [ ] 대상 근무 건이 없거나 본인 소유가 아닌 문서·근로계약서·만료 보건증 요청이 모두 같은 `400 VALIDATION_ERROR`로 응답되고 사유가 세분화되지 않는다.
- [ ] 공유 대상 사장님은 요청 Body와 무관하게 서버가 근무 건 기준으로 도출한다.
- [ ] 같은 문서·근무 건 조합에 활성 공유가 이미 있으면 `409 CONFLICT`다.
- [ ] 공유 철회는 행을 지우지 않고 `REVOKED`로 남기며, 재공유는 새 행을 만들어 이력이 누적된다.
- [ ] 공유 유효성은 저장된 만료 시각 없이 매 요청 계산되고, 별도 만료 처리 배치가 없다.
- [ ] `DELETE .../shares/{workplaceId}`는 철회 대상이 없어도 항상 `204`를 반환한다.
- [ ] `GET /api/worker/workplaces`에 나오는 사업장은 예외 없이 공유 생성이 성공한다(선정 조건 일치).
