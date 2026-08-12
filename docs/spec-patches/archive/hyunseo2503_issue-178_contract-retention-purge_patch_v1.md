---
patch_id: SPEC-178-05
status: accepted
issue: 178
base_spec_version: 6.0.1
targets:
  - requirement: DOC-012
  - requirement: COMMON-003
  - decision: DEC-OPEN-DOCUMENT-RETENTION-SCOPE
  - decision: DEC-CONTRACT-RETENTION
---

# SPEC-178-05: 근로계약서 보존·폐기

> **선행 조건**: 폐기 문서의 비노출과 `404 RESOURCE_NOT_FOUND`는 SPEC-178-01을
> 따른다.

## 추가 사항

근로계약서(`EMPLOYMENT_CONTRACT`)의 보존 기준일은 연결된
`work_cases.ends_at`을 `Asia/Seoul`로 변환한 날짜다. 보존 만료 시각은 그 날짜에
3년을 더한 날의 서울 자정이다. 자정을 넘기는 야간 근무도 시작일이 아니라 종료일을
사용한다.

예를 들어 서울 시각 2026-08-20에 종료된 근무의 만료 시각은
2029-08-20T00:00:00+09:00이다. 서버 현재 시각이 만료 시각 이상이면 폐기 대상이다.

이 Patch에서 “자동 삭제”는 계약 이력 행의 물리 삭제가 아니라 문서 비노출과 비공개 저장소
Object의 물리 삭제를 뜻한다.

- `documents` 행은 `status=DELETED`로 전이하고 물리 삭제하지 않는다.
- `document_versions`의 Metadata·Checksum, `document_signatures`,
  `document_shares`, `document_access_logs`, `work_contracts`와 Work Case 관계는
  기간 제한 없이 보존한다.
- 각 Version의 최종 Storage Object와 결정적 `.pending` Object는 모두 물리 삭제한다.
- 폐기 전후에 사용자 DELETE Endpoint를 제공하지 않는다.

### 실행과 장애 복구

폐기 Job은 매일 02:00 `Asia/Seoul`에 실행하며 `documentId ASC` Keyset 방식으로 한 번에
100건씩 처리한다. 시스템 생성 근로계약서의 `work_case_id`가 없으면 데이터 손상으로
격리하고 `INTERNAL_ERROR` 운영 경보를 남긴다.

각 문서는 다음 두 단계로 처리한다.

1. 짧은 DB 트랜잭션에서 문서 행을 잠근다. 만료 대상이면
   `documents.status=DELETED`로 바꾸고 Commit하여 목록·상세·파일 접근을 먼저 막는다.
   이미 DELETED면 상태 변경 없이 다음 단계로 간다.
2. Commit 뒤 모든 `document_versions`를 조회해 각 `storage_key`의 최종 Object와 같은
   Version의 결정적 `.pending` Object를 멱등 삭제한다.

Object가 이미 없으면 성공으로 본다. 일부 삭제가 실패해도 문서를 ACTIVE로 되돌리지 않는다.
다음 Job은 만료된 DELETED 계약서도 다시 선택하므로 남은 최종·임시 Object를 재시도한다.
따라서 DB 상태 변경 뒤 프로세스가 종료되거나 저장소가 일시 실패해도 접근이 다시 열리지
않고 후속 실행으로 복구된다.

이력 Column이나 별도 purge 테이블을 추가하지 않으며 `updated_at`을 Object 삭제 성공
표시로 해석하지 않는다. 이 선택 때문에 완료된 DELETED 문서도 후속 Job에서 멱등 삭제를
다시 시도할 수 있다. Job은 모든 Keyset Page를 순회하므로 앞의 100건 때문에 뒤 대상이
굶지 않는다.

운영 로그에는 `traceId`, `documentId`, `versionId`, 단계, 성공·실패 Enum만 남기고 저장
Key·Checksum과 계약 당사자 정보는 남기지 않는다.

## 완료 조건

- [ ] 보존 만료가 `ends_at`의 서울 종료 날짜와 3년 기준으로 계산된다.
- [ ] 야간 근무도 종료 날짜를 사용한다.
- [ ] DB를 먼저 DELETED로 Commit하여 Object 삭제 실패 중에도 사용자 접근을 막는다.
- [ ] 모든 Version의 최종 Object와 결정적 임시 Object를 삭제한다.
- [ ] Object 미존재를 성공으로 보고 실패한 삭제는 다음 실행에서 재시도한다.
- [ ] 만료된 DELETED 문서도 재선택해 DB 선처리 뒤 실패를 복구한다.
- [ ] 문서·Version·Checksum·서명·공유·접근 감사와 계약 관계 행은 보존한다.
- [ ] 근로계약서 사용자 DELETE와 별도 감사 만료 Job을 추가하지 않는다.
