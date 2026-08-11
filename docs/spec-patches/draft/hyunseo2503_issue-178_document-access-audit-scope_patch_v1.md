---
patch_id: SPEC-178-04
status: draft
issue: 178
base_spec_version: 6.0.1
targets:
  - requirement: DOC-009
  - requirement: DOC-011
  - operation: GET /api/documents/{documentId}
  - operation: GET /api/documents/{documentId}/file
  - decision: DEC-DOCUMENT-ACCESS-AUDIT
  - decision: DEC-CONTRACT-FILE-COMMIT
---

# SPEC-178-04: 문서 접근 감사 범위

> **선행 조건**: `GET /api/documents/{documentId}`는 정식 명세에 없고
> `SPEC-178-01`이 draft로 처음 추가한 대상이다. 이 Patch는 `SPEC-178-01`이 먼저
> 또는 같은 릴리스로 승인되는 것을 전제로 하며, 단독으로 정식 SPEC에 반영할 수
> 없다.

## 추가 사항

계약서(`EMPLOYMENT_CONTRACT`) 파일 접근에만 적용되던 `document_access_logs` 감사
계약(`DEC-CONTRACT-FILE-COMMIT`)을 보건증(`HEALTH_CERTIFICATE`)과 §1(`SPEC-178-01`)이
추가한 문서 상세 조회로 넓힌다. 새 규칙을 만들지 않고 계약서에 이미 승인된 형식을
그대로 재사용한다.

- 보건증 `GET /api/documents/{documentId}/file` 접근은 `action`을
  `HEALTH_CERT_FILE_VIEW` 또는 `HEALTH_CERT_FILE_DOWNLOAD`로 같은 행을 Commit한다.
  `result`, `document_version_id` 채움 규칙, FK 위반 요청의 처리(DB에 안 남기고
  `traceId` 보안 로그만)는 계약서와 동일하다.
- 보건증 `denial_reason`은 계약서 5종 중 전자서명 개념이 없는
  `SIGNED_VERSION_UNAVAILABLE`을 제외한 `PARTY_ACCESS_DENIED`, `DOCUMENT_UNAVAILABLE`,
  `FILE_UNAVAILABLE`, `CHECKSUM_MISMATCH` 4종만 사용한다. 새 사유 Code를 추가하지
  않는다.
- `GET /api/documents/{documentId}`(문서 상세) 접근은 문서 유형과 무관하게 `action`을
  `DOCUMENT_DETAIL_VIEW`로 기록한다. 파일 바이트를 다루지 않으므로 `denial_reason`은
  `PARTY_ACCESS_DENIED`, `DOCUMENT_UNAVAILABLE` 중 하나만 쓰고
  `FILE_UNAVAILABLE`·`CHECKSUM_MISMATCH`는 쓰지 않는다.
- `GET /api/documents`(목록 조회)와 `POST`/`DELETE .../shares`(공유 생성·철회)는
  `document_access_logs`에 행을 만들지 않는다. 공유 이력은 `document_shares`의
  생성·철회 시각으로 이미 확인할 수 있다.
- 문서 API는 모두 인증된 접근자만 호출 가능하므로 미인증 요청은 공통 Session
  검증에서 `401 AUTH_REQUIRED`로 차단되며 문서 감사 로직에 도달하지 않는다.
  `actor_user_id`가 `NULL`인 감사 행은 발생하지 않는다.
- 애플리케이션 로그(`log.info`/`log.warn`/`log.error`)에는 `documentId`,
  `actorUserId`, `action`, `result`, `denialReason`, `traceId` 같은 식별자·Enum만
  남긴다. `storage_key`, Checksum 원문, 파일 내용, 보건증·계약서의 개인정보 필드는
  어떤 로그 레벨에도 남기지 않는다.

## 완료 조건

- [ ] 보건증 파일 `VIEW`·`DOWNLOAD` 성공·거부가 `HEALTH_CERT_FILE_VIEW`/`HEALTH_CERT_FILE_DOWNLOAD`로 `document_access_logs`에 남는다.
- [ ] 보건증 `DENIED` 행의 `denial_reason`이 `PARTY_ACCESS_DENIED`/`DOCUMENT_UNAVAILABLE`/`FILE_UNAVAILABLE`/`CHECKSUM_MISMATCH` 중 하나이며 `SIGNED_VERSION_UNAVAILABLE`은 쓰이지 않는다.
- [ ] `GET /api/documents/{documentId}` 상세 조회 성공·거부가 `DOCUMENT_DETAIL_VIEW`로 감사되고, 그 `denial_reason`은 `PARTY_ACCESS_DENIED`·`DOCUMENT_UNAVAILABLE`로만 제한된다.
- [ ] `GET /api/documents` 목록 조회와 공유 생성·철회는 `document_access_logs`에 행을 만들지 않는다.
- [ ] 존재하지 않는 `documentId` 요청은 DB에 감사 행을 만들지 않고 `traceId` 보안 로그만 남긴다.
- [ ] 미인증 요청은 `401 AUTH_REQUIRED`로 차단되어 `actor_user_id=NULL`인 감사 행이 만들어지지 않는다.
- [ ] 애플리케이션 로그 어디에도 `storage_key`, Checksum 원문, 파일 내용, 개인정보가 노출되지 않는다.
