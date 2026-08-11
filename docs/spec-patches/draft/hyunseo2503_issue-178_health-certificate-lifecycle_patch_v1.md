---
patch_id: SPEC-178-02
status: draft
issue: 178
base_spec_version: 6.0.1
targets:
  - requirement: DOC-005
  - requirement: DOC-006
  - requirement: DOC-010
  - operation: POST /api/documents
  - operation: PATCH /api/documents/{documentId}
  - operation: DELETE /api/documents/{documentId}
  - decision: DEC-HEALTH-CERTIFICATE-LIFECYCLE
  - decision: DEC-DOCUMENT-STORAGE
  - decision: DEC-CONTRACT-FILE-COMMIT
---

# SPEC-178-02: 보건증 생명주기·파일 정책

## 추가 사항

보건증(`HEALTH_CERTIFICATE`)을 다시 등록하면 기존 문서에 Version을 추가하지 않고 새
`documents` 행을 만든다. 이 결정은 DOC-006의 “파일 Version 추가”를 보건증에 한해
대체한다. 이전 보건증은 자동 삭제하지 않고 SPEC-178-01의 만료 표시 규칙으로 문서함에
남긴다.

각 보건증 문서는 정확히 하나의 `document_versions` 행을 가진다.
`version_no=1`, `version_type=ORIGINAL`이며 `SIGNED`는 근로계약서에만 사용한다.

### 등록과 수정

- `POST /api/documents`는 인증 WORKER만 호출하며 `docType=HEALTH_CERTIFICATE`,
  `file`, `issuedDate`를 Multipart로 받는다.
- `PATCH /api/documents/{documentId}`는 문서 소유자인 WORKER만 호출하며
  `{issuedDate}`만 수정한다. 파일 교체나 Version 추가는 받지 않는다.
- `issuedDate`는 `Asia/Seoul`의 서버 수신 날짜보다 미래일 수 없다. 미래 값은
  `400 VALIDATION_ERROR`이며 `fieldErrors.field=issuedDate`를 포함한다.
- 서버는 `expiresDate = issuedDate.plusYears(1)`로 계산한다. Java `LocalDate` 규칙을
  그대로 적용하므로 2024-02-29의 만료일은 2025-02-28이다. 클라이언트가 보낸 만료일은
  받거나 저장하지 않는다.
- 이미 만료되는 `issuedDate`도 등록·수정할 수 있다. 성공 응답은 계산된
  `expiresDate`와 `status=EXPIRED`를 반환한다.
- PATCH로 보건증이 만료 상태가 되면 기존 공유 행의 저장 상태를 일괄 변경하지 않는다.
  SPEC-178-03의 매 요청 유효성 계산으로 공유 대상의 접근이 즉시 사라진다.

### 업로드 파일

- 파일 크기 상한은 정확히 10 MiB(10 × 1024 × 1024 byte)다. 초과하면
  `400 VALIDATION_ERROR`다.
- JPG, PNG, PDF만 허용하며 확장자, 선언 MIME, 파일 Signature를 모두 검증한다.
  셋이 일치하지 않으면 `400 VALIDATION_ERROR`다.
- 저장 확장자 `jpg`, `png`, `pdf`는 검증된 파일 Signature와 MIME에서 결정한다.
  사용자 파일명만으로 확장자를 결정하지 않는다.
- Checksum은 SHA-256 32byte를 사용한다.
- 최종 Key는
  `health-certificates/{ownerUserId}/{documentId}/v1.{ext}`, 임시 Key는
  `health-certificates/{ownerUserId}/{documentId}/.pending/v1.{ext}`다.
  비공개 저장, Commit 전 임시 Object 기록, Commit 뒤 멱등 승격, Rollback 뒤 최선 노력
  삭제는 근로계약서 파일 Commit 규칙과 같다.
- 최종 Key와 임시 Key는 API 응답과 일반 로그에 노출하지 않는다.

### 상태와 삭제

보건증의 `documents.status`는 `ACTIVE`와 `DELETED`만 사용한다. 외부
`ACTIVE/EXPIRED`는 `expiresDate`로 계산하며 PATCH는 DB 상태를 바꾸지 않는다.

`DELETE /api/documents/{documentId}`는 다음 순서로 처리한다.

1. 문서 행을 잠그고 인증 사용자가 소유한 보건증인지 확인한다.
2. 같은 DB 트랜잭션에서 `documents.status=DELETED`로 변경한다.
3. 해당 문서의 모든 `ACTIVE` 공유를 `REVOKED`로 바꾸고 같은 서버 시각을
   `revoked_at`에 기록한다.
4. 두 변경을 함께 Commit한 뒤 `204`를 반환한다. 하나라도 실패하면 전부 Rollback한다.

보건증 삭제는 논리 삭제다. 기존 `document_versions`, 비공개 파일, Checksum과
`document_access_logs`는 감사·복구 근거로 보존한다.

- 같은 소유자가 이미 삭제된 보건증을 다시 DELETE하면 `204`다.
- 문서가 없거나 다른 사용자의 문서면 `404 RESOURCE_NOT_FOUND`다.
- 근로계약서 DELETE는 `409 CONTRACT_RETENTION_REQUIRED`다.
- 삭제된 문서는 목록·상세·파일·공유 API에서 보이지 않으며 직접 접근도
  `404 RESOURCE_NOT_FOUND`다.

## 완료 조건

- [ ] 보건증 재등록마다 새 `documentId`와 ORIGINAL Version 1 한 행이 생성된다.
- [ ] `expiresDate`가 `issuedDate.plusYears(1)`로 계산되고 윤년 규칙이 고정된다.
- [ ] 미래 발급일은 거부하고 과거 발급일로 이미 만료된 등록·수정은 허용한다.
- [ ] 업로드가 10 MiB와 확장자·MIME·Signature 일치 여부를 모두 검증한다.
- [ ] 저장 확장자는 검증된 콘텐츠로 결정되고 Key·Checksum은 외부에 노출되지 않는다.
- [ ] DELETE가 문서 논리 삭제와 모든 활성 공유 철회를 같은 트랜잭션으로 처리한다.
- [ ] 반복 DELETE, 근로계약서 DELETE와 비소유자 접근의 응답이 계약대로 동작한다.
