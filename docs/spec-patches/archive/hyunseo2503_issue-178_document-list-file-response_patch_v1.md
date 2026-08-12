---
patch_id: SPEC-178-01
status: accepted
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

`GET /api/documents`와 `GET /api/documents/{documentId}`는 같은 문서 Item을 사용하고,
상세 응답에만 `versions[]`를 추가한다. 외부 날짜 필드는 `issuedDate`와
`expiresDate`로 고정하며 `expiryDate`·`expiresOn`을 사용하지 않는다.

```json
{
  "documentId": 5,
  "docType": "HEALTH_CERTIFICATE",
  "status": "ACTIVE",
  "fileName": "보건증_20260601_김알바.jpg",
  "mimeType": "image/jpeg",
  "issuedDate": "2026-06-01",
  "expiresDate": "2027-06-01",
  "latestVersion": 1,
  "source": "SHARED",
  "sharedByName": "김알바",
  "workplaceId": 1,
  "workplaceName": "강남점",
  "workCaseId": 201,
  "capabilities": {
    "canView": true,
    "canDownload": true,
    "canShare": false,
    "canDelete": false
  },
  "createdAt": "2026-06-01T01:00:00Z"
}
```

```json
{
  "versions": [
    {
      "versionNo": 1,
      "versionType": "ORIGINAL",
      "mimeType": "image/jpeg",
      "sizeBytes": 128400,
      "createdAt": "2026-06-01T01:00:00Z"
    }
  ]
}
```

### 조회와 행 식별

- `GET /api/documents`는 `workplaceId?`, `docType?`, `page?`, `size?`만 Query로
  받는다. `source`는 요청값이 아니라 서버가 현재 사용자 기준으로 계산한다.
- `source=OWN`은 `documents.owner_user_id`가 현재 사용자인 경우다.
  `source=SHARED`는 유효한 보건증 공유를 받은 OWNER 또는 근로계약 당사자인 WORKER다.
- OWN 문서는 `documentId`당 한 행이다. SHARED 보건증은
  `(documentId, workCaseId)`당 한 행이며, Page의 `totalElements`도 이 가시 행 수를
  뜻한다.
- 기본 정렬은 `createdAt DESC, documentId DESC, workCaseId DESC NULLS LAST`다.
- DB 상태가 `ACTIVE`인 문서만 후보로 삼는다. 근로계약서 생성 트랜잭션의 중간 상태인
  `DRAFT`, `AWAITING_SIGNATURE`, `SIGNED`와 `CANCELED`, `DELETED`는
  목록·상세·파일에서 제외한다.
- 보건증의 외부 `status`는 서울 날짜 기준 `expiresDate < today`이면 `EXPIRED`,
  아니면 `ACTIVE`다. DB의 `documents.status`를 `EXPIRED`로 변경하지 않는다.
- 근로계약서의 외부 `status`는 항상 `ACTIVE`다.
- 만료 보건증은 소유자에게만 `EXPIRED`로 계속 보이고 열람·다운로드·삭제할 수 있다.
  공유받은 쪽에서는 즉시 보이지 않는다.

### 필드와 Null 규칙

| 경우              | `sharedByName`   | `workplaceId`·`workplaceName`·`workCaseId` | `expiresDate` |
| ----------------- | ---------------- | ------------------------------------------ | ------------- |
| 보건증 OWN        | `null`           | 모두 `null`                                | 필수          |
| 보건증 SHARED     | 문서 소유자 이름 | 모두 필수                                  | 필수          |
| 근로계약서 OWN    | `null`           | 모두 필수                                  | `null`        |
| 근로계약서 SHARED | 문서 소유자 이름 | 모두 필수                                  | `null`        |

- `issuedDate`는 두 문서 유형 모두 필수다. 시각은 UTC ISO-8601, 날짜는
  `YYYY-MM-DD`로 응답한다.
- `fileName`은 서버가 조립한다. 근로계약서는
  `근로계약서_{사업장명}_{발급일}_{근로자이름}.pdf`, 보건증은
  `보건증_{발급일}_{소유자이름}.{ext}`다. 제어문자와 경로 구분자는 제거한다.
- `versions[]`에는 현재 사용자가 받을 수 있는 Version만 최신순으로 넣는다.
  보건증은 ORIGINAL Version 1을, 근로계약서는 최신 SIGNED Version만 포함한다.
  근로계약서 ORIGINAL Version과 전자동의 증거는 Metadata에도 노출하지 않는다.
- `latestVersion`, `mimeType`, `fileName`과 파일 Stream은 모두 같은 최신 허용
  Version을 기준으로 한다.
- `storage_key`, 임시 Key, Checksum, 내부 사용자 ID와 서명 증거는 어느 응답에도 넣지 않는다.

### Capability

- 목록에 포함된 행의 `canView`와 `canDownload`는 `true`다. 실제 요청에서도 권한과
  유효성을 다시 검사하므로 이 값은 권한 Token이 아니다.
- `canShare`는 OWN 보건증이 `ACTIVE`이고 SPEC-178-03의 공유 가능한 근무가 하나 이상
  남은 경우만 `true`다. SHARED 문서와 근로계약서는 항상 `false`다.
- `canDelete`는 OWN 보건증만 `true`다. 근로계약서는 보존 정책 때문에 항상 `false`다.

### 파일 응답과 오류

`GET /api/documents/{documentId}/file`의 `mode`는 `view` 또는 `download`이고, 생략하면
`view`다. 그 밖의 값은 `400 VALIDATION_ERROR`다.

- 허용 MIME은 `image/jpeg`, `image/png`, `application/pdf`다. 저장 Metadata가 이
  목록 밖이면 `application/octet-stream`, `attachment`, `X-Content-Type-Options:
nosniff`로 내려보낸다.
- `Content-Disposition`은 view일 때 `inline`, download일 때 `attachment`다.
  정제한 ASCII `filename`과 RFC 5987 `filename*=UTF-8''...`를 함께 제공한다.
- `Cache-Control: private, no-store`와 `Accept-Ranges: none`을 사용하고 Range 요청을
  지원하지 않는다.
- 미인증은 `401 AUTH_REQUIRED`다. 인증 후 문서가 없거나, 삭제·철회·만료 등으로
  보이지 않거나, 소유자·당사자·유효 공유자가 아니면 모두
  `404 RESOURCE_NOT_FOUND`다. 문서 존재 여부를 드러내는 `403`·`410` 분기는 두지 않는다.
- 허용 Version의 최종·임시 Object를 모두 복구하지 못하거나 Checksum이 맞지 않으면
  `500 INTERNAL_ERROR`다. 저장소 장애를 `404`로 위장하지 않는다.
- 문서 API는 `VALIDATION_ERROR`, `AUTH_REQUIRED`, `FORBIDDEN`, `ROLE_MISMATCH`,
  `RESOURCE_NOT_FOUND`, `CONFLICT`, `CONTRACT_RETENTION_REQUIRED`,
  `INTERNAL_ERROR`만 사용한다.

## 완료 조건

- [ ] 목록과 상세가 같은 Item Shape를 쓰고 상세만 `versions[]`를 추가한다.
- [ ] 외부 만료 날짜 이름이 `expiresDate`로 통일되고 Null 규칙이 지켜진다.
- [ ] 보건증 OWN은 공유 수와 무관하게 한 행, SHARED는 근무 건별 한 행으로 Page를 계산한다.
- [ ] 근로계약서 ORIGINAL Version과 저장 Key·Checksum·내부 ID가 노출되지 않는다.
- [ ] SHARED 예시와 실제 응답의 `canShare`가 `false`다.
- [ ] 만료 보건증은 소유자에게만 `EXPIRED`로 보이고 공유 대상에게는 보이지 않는다.
- [ ] 보이지 않는 문서 접근은 `404 RESOURCE_NOT_FOUND`로 통일한다.
- [ ] 파일 응답이 MIME, Disposition, nosniff, no-store와 무결성 실패 규칙을 지킨다.
