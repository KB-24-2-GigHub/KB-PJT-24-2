---
patch_id: SPEC-180-01
status: draft
issue: 180
base_spec_version: 8.0.0
targets:
  - requirement: DOC-005
  - decision: DEC-HEALTH-CERTIFICATE-LIFECYCLE
  - operation: POST /api/documents
---

# SPEC-180-01: 보건증 등록 응답 — HTTP Status와 Body Shape

## 추가 사항

`API_SPEC.md`의 "보건증 등록·수정·삭제" 절은 정확한 HTTP Status와 성공 응답의 그 밖의 Body
필드를 미결로 남겨 두었다. 이 Patch는 `#180` 구현이 실제로 반환하는 값을 고정한다.

- `POST /api/documents`가 보건증 등록에 성공하면 `201 Created`를 반환한다. `issuedDate`가
  이미 만료되는 경우(등록 즉시 `expiresDate < 오늘`)도 같은 `201 Created`를 사용하며 별도
  Status로 구분하지 않는다.
- 성공 응답 Body는 문서 목록·상세가 이미 쓰는 `DocumentListItem` 하나와 같은 Shape이다
  (`documentId`, `docType`, `status`, `fileName`, `mimeType`, `issuedDate`, `expiresDate`,
  `latestVersion`, `source`, `sharedByName`, `workplaceId`, `workplaceName`, `workCaseId`,
  `capabilities`, `createdAt`). 새 응답 DTO를 따로 만들지 않는다.
- `status`는 `documents.status`(`ACTIVE`)가 아니라 목록·상세와 같은 계산 규칙으로 만든
  외부 값이다: 서울 오늘 날짜가 `expiresDate` 이상이면 `EXPIRED`, 아니면 `ACTIVE`.
- `latestVersion`은 항상 `1`(ORIGINAL)이고, `source`는 `OWN`이다.
- `capabilities.canShare`는 등록 응답에서 항상 `false`다. 공유 후보(ACTIVE 사업장의 유일한
  `ACCEPTED`·`READY` Work Case) 계산은 공유 조회 경로(`#181`)의 책임이며, 등록 API는 이
  계산을 새로 하지 않는다. 정확한 `canShare` 값은 뒤이은 목록·상세 조회(`#132`)로 받는다.
- `capabilities.canDelete`는 `DocumentListItem`의 기존 계산 규칙(`source=="OWN" &&
  docType=="HEALTH_CERTIFICATE"`)을 그대로 따르므로 등록 직후 항상 `true`다.

## 완료 조건

- [ ] `POST /api/documents` 보건증 등록 성공이 `201 Created`와 `DocumentListItem` Shape의
      Body를 반환한다.
- [ ] 등록 즉시 만료되는 `issuedDate`도 같은 `201 Created`이고 `status=EXPIRED`,
      계산된 `expiresDate`를 포함한다.
- [ ] 응답의 `capabilities.canShare`는 항상 `false`이고, `capabilities.canDelete`는 항상
      `true`다.
- [ ] 이 Patch는 `docs/specs/API_SPEC.md`의 기존 미결 문구를 대체하지 않고, 정식 릴리스
      전까지 구현·테스트가 참조하는 임시 계약으로만 쓰인다.
