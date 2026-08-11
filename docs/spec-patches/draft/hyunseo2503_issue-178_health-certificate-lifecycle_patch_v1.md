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
  - decision: DEC-HEALTH-CERTIFICATE-LIFECYCLE
  - decision: DEC-DOCUMENT-STORAGE
  - decision: DEC-CONTRACT-FILE-COMMIT
---

# SPEC-178-02: 보건증 수명주기·파일 정책

## 추가 사항

보건증(`HEALTH_CERTIFICATE`)은 갱신마다 별도의 논리 문서(`documents`)로 만든다. 기존
문서에 새 Version을 덧붙이는 별도 갱신 Operation을 두지 않는다. `DOC-006`의 "파일
Version 추가"는 기존 `POST /api/documents`를 다시 호출해 새 문서를 만드는 것으로
충족하며, 이전 발급 문서는 삭제하지 않고 §1(`SPEC-178-01`)의 만료 표시 규칙대로
문서함에 남는다. `PATCH /api/documents/{documentId}`는 기존과 동일하게 `{issuedDate}`
수정 전용이며 파일을 받지 않는다.

이 모델에서 보건증 문서는 항상 `document_versions` 행을 하나만 가지며
`version_no=1`, `version_type=ORIGINAL`이다. `version_type=SIGNED`는 근로계약서
전용이며 보건증에는 쓰지 않는다.

`expiresOn`은 `issuedDate + 1년`으로 계산한다. `issuedDate`가 서버 수신 시각 기준
오늘보다 미래면 `400 VALIDATION_ERROR`다. 계산된 `expiresOn`이 이미 지난 값이어도
업로드 자체는 거부하지 않으며, 목록·상세 응답의 `status`는 §1 규칙대로 그 자리에서
`EXPIRED`로 계산된다.

`docType=HEALTH_CERTIFICATE` Multipart 업로드는 확장자·MIME·매직바이트 확인(`DOC-010`)
외에 파일 크기 상한 10MB를 추가로 적용하며, 초과 시 `400 VALIDATION_ERROR`다.
Checksum은 기존 계약서와 동일하게 SHA-256을 사용한다.

`documents.status`는 `HEALTH_CERTIFICATE`에서 `ACTIVE`(업로드 성공)와
`DELETED`(논리 삭제) 두 값만 사용한다. `DRAFT`, `AWAITING_SIGNATURE`, `SIGNED`,
`CANCELED`는 근로계약서 전자서명 흐름 전용이며 보건증에는 나타나지 않는다. 발급일
수정(`PATCH`)은 `status`를 바꾸지 않는다.

보건증 파일 저장은 `DEC-CONTRACT-FILE-COMMIT`의 임시 Key 선기록·Commit 후 멱등
승격·Rollback 시 최선 노력 정리 메커니즘을 그대로 재사용한다. 저장 Key만 계약서와
다른 패턴을 쓴다. 최종 Key는 `health-certificates/{ownerUserId}/{documentId}/v1.{ext}`,
임시 Key는 `health-certificates/{ownerUserId}/{documentId}/.pending/v1.{ext}`이며
`{ext}`는 `jpg`, `png`, `pdf` 중 하나다.

## 완료 조건

- [ ] 같은 사용자가 보건증을 다시 올리면 기존 문서를 바꾸지 않고 새 `documentId`가 생성된다.
- [ ] 새로 생성된 보건증 문서는 `document_versions` 행이 정확히 하나(`version_no=1`, `version_type=ORIGINAL`)다.
- [ ] `expiresOn`이 `issuedDate + 1년`으로 계산되어 응답에 반영된다.
- [ ] `issuedDate`가 미래인 업로드는 `400 VALIDATION_ERROR`로 거부된다.
- [ ] 이미 만료된 `issuedDate`의 업로드는 거부되지 않고 성공하며, 계산된 `status=EXPIRED`로 표시된다.
- [ ] 10MB를 초과하는 파일 업로드는 `400 VALIDATION_ERROR`다.
- [ ] 보건증 `documents.status`가 `ACTIVE`·`DELETED` 외의 값을 갖지 않는다.
- [ ] 보건증 파일이 계약서와 다른 저장 Key 패턴(`health-certificates/...`)으로 저장되고, 임시 Key 선기록·Commit 후 승격 흐름이 계약서와 동일하게 동작한다.
