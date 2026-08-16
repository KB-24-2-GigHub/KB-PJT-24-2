# 근로계약서 보존 만료 파기 Runbook

## 목적

`ContractRetentionPurgeScheduler`(DOC-012, `DEC-CONTRACT-RETENTION`)는 매일 02:00(Asia/Seoul)
`work_cases.ends_at`의 서울 종료 날짜 + 3년이 지난 근로계약서를 `documents.status=DELETED`로
전이하고 Storage Object를 삭제한다. 이 문서는 결과 확인·재처리·데모 시연 방법만 다룬다.
정책 자체는 `docs/specs/DECISIONS.md`의 `DEC-CONTRACT-RETENTION`이 단일 원본이다.

이 기능은 `docs/specs/REQUIREMENTS.md` DOC-012 기준 `Deferred` 우선순위이고 데모 시나리오
6-5는 `P2`다(구두 설명으로 대체 가능). Runbook도 그 비중에 맞춰 최소한으로만 유지한다.

## 실행 모드

- 기본은 **Dry-run**이다(`contract.retention.purge-enabled` 미설정 또는 `false`). 이때는
  후보 문서 수와 `documentId` 목록만 로그로 남기고 아무것도 바꾸지 않는다.
- 실제 파기를 켜려면 `contract.retention.purge-enabled=true`를 외부 설정 파일에 추가한다.

## 로그로 결과 읽기

Application 로그에서 `ContractRetentionPurgeScheduler` Logger를 찾는다.

- Dry-run: `[Dry-run] 근로계약서 보존 만료 파기 후보. executionId=..., candidates=N, documentIds=[...]`
- 실행 완료: `근로계약서 보존 만료 파기 실행을 완료했습니다. ... candidates=N, purged=N, failed=N`
- 데이터 손상(근무 참조 없음): `근로계약서의 근무 참조가 없어 보존 만료 판정에서 격리했습니다. ... documentId=...`
  — 이 로그가 보이면 `document_type='EMPLOYMENT_CONTRACT'`인데 `work_case_id`가 비었거나
  참조 `work_cases` 행이 없는 문서다. 자동 생성 정책상 정상적으로는 발생할 수 없으므로
  DB를 직접 조회해 원인을 확인한다(이 프로젝트 규모에서 별도 조회 화면·API는 두지 않는다).

## 수동 재처리

별도 재처리 절차가 없다. 실패한 문서는 상태·Storage Object가 그대로 남아 있고, 다음 날
02:00 실행이 같은 조건으로 다시 후보로 잡아 재시도한다(멱등). 즉시 재시도가 필요하면
애플리케이션을 재기동하지 않고 `ContractRetentionPurgeScheduler.runOnce()`를 수동으로
호출할 수 있는 별도 관리 Endpoint는 두지 않았다 — 필요해지면 그때 추가한다.

## 데모 시연 방법

3년을 기다릴 필요는 없다. `work_cases.ends_at`이 3년 이상 과거인 Fixture 근무를 만들고
그 근무의 근로계약서가 있으면 다음 배치 실행 때 바로 후보로 잡힌다. 코드 변경은 필요 없다.
