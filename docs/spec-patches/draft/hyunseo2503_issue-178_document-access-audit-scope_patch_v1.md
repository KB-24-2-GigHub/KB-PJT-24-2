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

> **선행 조건**: 이 Patch는 `GET /api/documents/{documentId}`를 정의한
> SPEC-178-01과 함께 승인한다.

## 추가 사항

기존 근로계약서 파일 감사 규칙을 보건증 파일과 문서 상세 조회에 확장한다. 목록 조회와
공유 생성·철회는 `document_access_logs` 대상이 아니다.

### 감사 대상

| 요청                     | `action`                    |
| ------------------------ | --------------------------- |
| 보건증 파일 view         | `HEALTH_CERT_FILE_VIEW`     |
| 보건증 파일 download     | `HEALTH_CERT_FILE_DOWNLOAD` |
| 근로계약서 파일 view     | `CONTRACT_FILE_VIEW`        |
| 근로계약서 파일 download | `CONTRACT_FILE_DOWNLOAD`    |
| 문서 상세 조회           | `DOCUMENT_DETAIL_VIEW`      |

- 인증 뒤 기존 문서 행을 식별한 요청은 허용·거부 결과마다 정확히 한 행을 Commit한다.
- `ALLOWED`는 실제 응답에 사용한 최신 허용 `document_version_id`가 필수이고
  `denial_reason=null`이다.
- `DENIED`는 허용 Version까지 식별했으면 그 `document_version_id`를 기록하고, Version을
  식별하기 전에 거부됐거나 허용 Version이 없을 때만 `null`이다.
- 보건증 파일 거부 사유는 `PARTY_ACCESS_DENIED`, `DOCUMENT_UNAVAILABLE`,
  `FILE_UNAVAILABLE`, `CHECKSUM_MISMATCH` 중 하나다.
- 근로계약서 파일은 기존 다섯 사유에 `SIGNED_VERSION_UNAVAILABLE`를 포함한다.
- 상세 조회 거부 사유는 파일을 읽지 않으므로 `PARTY_ACCESS_DENIED` 또는
  `DOCUMENT_UNAVAILABLE`만 사용한다.
- 공유 만료·철회, 보건증 만료·삭제와 근무 관계 종료로 접근이 사라진 경우는
  `DOCUMENT_UNAVAILABLE`로 기록하고 외부에는 SPEC-178-01의
  `404 RESOURCE_NOT_FOUND`를 반환한다.

### Commit과 실패 처리

- 파일 요청은 권한·Version·Checksum을 검증한 뒤 감사 행을 먼저 Commit하고 Header나
  파일 Bytes를 전송한다.
- 상세 요청도 감사 행 Commit이 끝난 뒤 Metadata Body를 전송한다.
- 거부 응답도 문서 식별 뒤라면 감사 행 Commit을 완료한 뒤 반환한다.
- 감사 Commit에 실패하면 성공 Body·Header·파일 Bytes를 보내지 않고
  `500 INTERNAL_ERROR`를 반환한다. 같은 `traceId`로 최소 보안 로그를 남긴다.
- 존재하지 않는 `documentId`는 FK 대상이 없으므로 감사 행을 만들지 않고
  `traceId` 보안 로그만 남긴다.
- 미인증 요청은 공통 인증 단계에서 `401 AUTH_REQUIRED`로 막으며
  `actor_user_id=null`인 감사 행을 만들지 않는다.
- `document_access_logs`는 일반 기능에서 수정·삭제하지 않는다.

### 애플리케이션 로그

일반 애플리케이션 로그에는 `traceId`, `action`, `result`, `denialReason`처럼 닫힌
Enum과 상관관계 정보만 기록한다. `actorUserId`, 이름, 문서 파일명, 저장 Key, Checksum
원문, 파일 내용, 보건증·근로계약서의 개인 정보는 어떤 로그 수준에도 기록하지 않는다.
사용자와 문서의 감사 식별값은 접근 통제된 `document_access_logs`에만 저장한다.

## 완료 조건

- [ ] 보건증 파일 view·download의 허용·거부가 전용 action으로 정확히 한 번 감사된다.
- [ ] 문서 상세 조회의 허용·거부가 `DOCUMENT_DETAIL_VIEW`로 감사된다.
- [ ] ALLOWED의 Version ID와 DENIED의 Version Null 규칙이 지켜진다.
- [ ] 상세와 파일 모두 감사 Commit 실패 시 데이터 전송 없이 `500`을 반환한다.
- [ ] 목록·공유 변경, 미인증 요청과 존재하지 않는 문서는 DB 감사 행을 만들지 않는다.
- [ ] 일반 로그에 사용자 식별자, 저장 정보와 문서 개인 정보가 노출되지 않는다.
