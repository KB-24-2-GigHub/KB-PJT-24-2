---
patch_id: SPEC-178-05
status: draft
issue: 178
base_spec_version: 6.0.1
targets:
  - requirement: DOC-012
  - requirement: COMMON-003
  - decision: DEC-OPEN-DOCUMENT-RETENTION-SCOPE
  - decision: DEC-CONTRACT-RETENTION
---

# SPEC-178-05: 근로계약서 보존·파기

## 추가 사항

근로계약서(`EMPLOYMENT_CONTRACT`)의 보존 기준일은 그 문서가 속한 `work_cases.ends_at`의
`Asia/Seoul` 달력 날짜다. 야간 근무처럼 `starts_at`과 `ends_at`이 다른 날짜에 걸쳐도
동일하게 `ends_at` 날짜를 기준으로 삼는다. 보존 기간은 그 날짜 `Asia/Seoul` 자정
(00:00)부터 3년이며, 3년이 지난 자정 이후 파기 대상이 된다.

`document_versions`, `document_shares`, `document_access_logs`는 모두 `documents`에
대한 `ON DELETE RESTRICT` 외래키로 보호되어 있어 `documents` 행을 물리적으로 지우면
연결된 감사·공유·버전 기록을 먼저 지워야 한다. 이미 승인된 `COMMON-003`("일반 도메인
행은 상태 또는 삭제 시각으로 비활성화한다")을 그대로 따라, 파기는 행 삭제가 아니라
다음과 같은 상태 전환으로 수행한다.

- 비공개 저장소의 파일 실체(Storage Object)만 물리적으로 삭제한다.
- `documents.status`를 `DELETED`로 바꾼다. 행 자체는 지우지 않는다.
- `document_versions`(Metadata·Checksum), `document_shares`, `document_access_logs`는
  전부 보존하며 파기 대상이 아니다. 별도 만료·파기 배치를 두지 않고 무기한 보존한다.
- `documents.status=DELETED`가 되면 §1(`SPEC-178-01`)의 문서함 노출 규칙(`status IN
  (SIGNED, ACTIVE)`)에 따라 목록·상세·파일 응답 모두에서 자연히 제외되고 직접 접근은
  `404 RESOURCE_NOT_FOUND`다. 파기 전용 오류 Code나 별도 분기를 추가하지 않는다.

파기 배치는 매일 1회 실행하고 작은 Batch 크기로 대상을 처리한다. 파일 삭제 시도는
멱등하며, 이미 삭제된 Object에 대한 재시도는 성공으로 간주한다. 실패한 건은 다음 배치
주기에 다시 시도하며, Dry-run 모드·정교한 실패 격리·재시작 복구 절차는 두지 않는다.
파기 시도 전용 이력 Column이나 테이블을 추가하지 않고 `documents.status`와
`updated_at`만으로 상태를 판별한다.

시스템이 자동 생성하는 `EMPLOYMENT_CONTRACT` 문서는 항상 `work_case_id`가 있다는 것을
애플리케이션 불변식으로 취급한다. `documents.work_case_id` 컬럼 자체는 보건증과 공유하는
Nullable 컬럼이라 스키마를 바꾸지 않으며, DB 제약 대신 계약서 생성 경로에서만 이 불변식을
지킨다.

## 완료 조건

- [ ] 근로계약서 보존 만료일이 `work_cases.ends_at`의 `Asia/Seoul` 달력 날짜 기준 3년 뒤로 계산된다.
- [ ] 야간 근무(자정을 넘는 근무)도 `ends_at` 날짜를 기준으로 동일하게 계산된다.
- [ ] 파기 시 Storage Object만 삭제되고 `documents`, `document_versions`, `document_shares`, `document_access_logs` 행은 삭제되지 않는다.
- [ ] 파기 뒤 `documents.status`가 `DELETED`이고, 해당 문서는 목록·상세·파일 응답에서 제외되며 직접 접근 시 `404 RESOURCE_NOT_FOUND`다.
- [ ] `document_access_logs`에 대한 별도 파기·만료 배치가 없다.
- [ ] 파일 삭제 재시도가 멱등하게 동작하고(이미 없는 Object 재시도가 실패로 처리되지 않음), 새 이력 Column·테이블이 추가되지 않는다.
- [ ] 시스템 생성 근로계약서는 항상 `work_case_id`가 채워져 있다.
