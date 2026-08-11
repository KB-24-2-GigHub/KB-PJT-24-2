---
patch_id: SPEC-178-01
status: draft
issue: 178
base_spec_version: 6.0.1
targets:
  - requirement: DOC-001
  - requirement: DOC-003
  - requirement: DOC-004
  - requirement: DOC-009
  - operation: GET /api/documents
  - operation: GET /api/documents/{documentId}
  - operation: GET /api/documents/{documentId}/file
  - decision: DEC-OPEN-DOCUMENT-RESPONSE-SHAPES
  - decision: DEC-OPEN-ERROR-CATALOG
---

# SPEC-178-01: 문서 목록·상세·파일 응답 계약

## 추가 사항

`GET /api/documents/{documentId}`를 신규 승인한다. 목록 Item과 상세 Item은 같은 필드를
쓰고 상세만 `versions[]`를 더한다.

```json
{
  "documentId": 5,
  "docType": "HEALTH_CERTIFICATE",
  "status": "ACTIVE",
  "fileName": "보건증_강남점_20260601",
  "mimeType": "image/jpeg",
  "issuedDate": "2026-06-01",
  "expiryDate": "2027-06-01",
  "latestVersion": 1,
  "source": "SHARED",
  "sharedByName": "김알바",
  "workplaceId": 1,
  "workplaceName": "강남점",
  "workCaseId": 201,
  "capabilities": {
    "canView": true,
    "canDownload": true,
    "canShare": true,
    "canDelete": false
  },
  "createdAt": "2026-06-01T01:00:00Z"
}
```

```json
"versions": [
  { "versionNo": 2, "versionType": "SIGNED", "mimeType": "application/pdf",
    "sizeBytes": 128400, "createdAt": "2026-06-05T01:00:00Z" }
]
```

- `GET /api/documents`는 `workplaceId?`, `docType?`, `page?`, `size?` Query만 사용한다.
  `source`는 요청 Query로 받지 않고 서버가 `OWN`/`SHARED`로 판정한다.
- 최신 Version은 `SIGNED`가 있으면 그중 `version_no` 최대값, 없으면 `ORIGINAL` 중
  `version_no` 최대값이다. `mimeType`·`latestVersion`은 그 Version의 값이다.
- 문서함에는 `status`가 `SIGNED` 또는 `ACTIVE`인 문서만 노출한다. `DRAFT`,
  `AWAITING_SIGNATURE`, `CANCELED`, `DELETED`는 제외한다.
- 응답의 `status`는 DB 저장값이 아니라 서버가 `expiryDate`로 계산하는 `ACTIVE`/`EXPIRED`다.
- 유효기간이 지난 보건증은 소유자 문서함에 `EXPIRED`로 남아 열람·다운로드는 가능하고
  공유만 불가하다. 공유받은 쪽 목록에서는 제외한다.
- `capabilities`는 `canView`, `canDownload`, `canShare`, `canDelete` 네 개이며 화면
  힌트다. 서버는 실제 요청에서 다시 검증한다. `canView`·`canDownload`는 항상 `true`,
  `canShare`는 보건증이면서 본인 소유이고 `status=ACTIVE`일 때만, `canDelete`는
  보건증이면서 본인 소유일 때만 `true`가 될 수 있다. 근로계약서는
  `DEC-CONTRACT-RETENTION`에 따라 둘 다 항상 `false`다.
- 삭제·철회된 항목과 만료된 공유는 기본 목록에서 제외하며 직접 접근해도
  `404 RESOURCE_NOT_FOUND`다. 만료된 보건증 본인 문서는 제외 대상이 아니다.
- 정렬은 `createdAt` 내림차순, 동일 시각은 `documentId` 내림차순이다.
- `storage_key`, Checksum과 내부 사용자 ID는 응답에 포함하지 않는다.

`GET /api/documents/{documentId}/file`의 `mode`는 `view` 또는 `download`이며 생략하면
`view`다. 그 외 값은 `400 VALIDATION_ERROR`다.

- `Content-Type`은 최신 Version의 저장 값을 쓰되 `image/jpeg`, `image/png`,
  `application/pdf`가 아니면 `application/octet-stream`으로 내려보내고 `mode`와 무관하게
  `attachment`로 처리한다. 응답에 `X-Content-Type-Options: nosniff`를 포함한다.
- `Content-Disposition`은 `mode=view`면 `inline`, `mode=download`면 `attachment`이며
  파일명은 RFC 5987 `filename*=UTF-8''` 형식과 ASCII 대체 `filename`을 함께 제공한다.
  제어문자, 개행, 경로 구분자는 제거한다.
- `Cache-Control: private, no-store`를 쓰고 Range 요청은 지원하지 않으며
  `Accept-Ranges: none`을 반환한다.
- 파일 실체가 저장소에 없으면 `500 INTERNAL_ERROR`이며 `404`로 대체하지 않는다.

도메인별 오류 Code를 새로 만들지 않는다. 문서 API는 `VALIDATION_ERROR`, `AUTH_REQUIRED`,
`FORBIDDEN`, `ROLE_MISMATCH`, `RESOURCE_NOT_FOUND`, `CONFLICT`,
`CONTRACT_RETENTION_REQUIRED`, `INTERNAL_ERROR`만 사용한다. 미존재·권한없음·삭제·
공유철회·공유만료는 모두 `404 RESOURCE_NOT_FOUND`로 통일하고 `410`을 쓰지 않는다. 이는
공통 규칙("역할 또는 리소스 소유권 위반은 403")에 대한 문서 API 한정 예외이며, 목적은
요청자가 알 수 없는 타인의 문서 존재 여부를 403/404 구분으로 노출하지 않는 것이다.
저장소 파일 실체 누락만 예외로 `500 INTERNAL_ERROR`다.

## 완료 조건

- [ ] `GET /api/documents/{documentId}`가 목록과 같은 Item 필드에 `versions[]`를 더해 반환한다.
- [ ] `source`는 요청 Query와 무관하게 서버가 `OWN`/`SHARED`로 판정한다.
- [ ] 문서함 목록에 `SIGNED`/`ACTIVE` 상태 문서만 나타나고 `status`는 계산된 `ACTIVE`/`EXPIRED`다.
- [ ] 만료된 보건증이 소유자 목록엔 `EXPIRED`로 남아 열람·다운로드는 되고 공유는 막히며, 공유받은 목록에서는 사라진다.
- [ ] `capabilities.canDelete`가 근로계약서에서 항상 `false`이고, 보건증에서 본인 소유일 때만 조건부 `true`다.
- [ ] 존재하지 않거나 권한 없거나 삭제·철회·만료된 문서 접근이 모두 `404 RESOURCE_NOT_FOUND`로 통일되고 `403`·`410`이 새지 않는다.
- [ ] `mode` 생략 시 `view`로 동작하고 허용되지 않는 `mode` 값은 `400 VALIDATION_ERROR`다.
- [ ] 허용되지 않은 MIME은 `application/octet-stream` + `attachment` + `nosniff`로 응답한다.
- [ ] 저장소에 파일 실체가 없으면 `404`가 아니라 `500 INTERNAL_ERROR`다.
- [ ] 응답 어디에도 `storage_key`, Checksum, 내부 사용자 ID가 노출되지 않는다.
