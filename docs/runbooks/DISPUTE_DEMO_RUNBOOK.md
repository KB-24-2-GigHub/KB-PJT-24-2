# LLM 외부 분쟁조정 DEMO 실행 Runbook

이 DEMO는 실제 법률 판단이나 고용노동청·법원 연동이 아닙니다. GigHub는 분쟁 중 예치금
흐름을 보류하고, 외부 조정 시스템을 흉내 내는 Provider의 제한된 결과를 기존 정산 규칙에
연결합니다. Provider는 지급액·환불액을 계산하거나 바꾸지 않습니다.

## 1. 준비

1. 폐기 가능한 로컬 MySQL을 사용하고 Flyway Head를 확인합니다.

   ```powershell
   node scripts/assert-disposable-database.js --confirm-local
   .\backend\gradlew.bat -p backend prepareFlywayDriver
   npm.cmd run db:migrate
   docker compose --profile tools run --rm flyway validate
   ```

2. `backend/config/database.example.properties`를 복사한 로컬 외부 설정 파일에서 실행 모드를
   고릅니다. 기본값 `DISABLED`는 검토 작업을 만들지 않습니다.

3. Tomcat과 Frontend를 다시 시작합니다. Provider와 실행 모드는 기동 시 한 번만 읽습니다.

## 2. 결정적인 Fake 시연

외부 네트워크 없이 한 결정을 반복하려면 로컬 설정을 다음처럼 둡니다.

```properties
dispute.review.mode=FAKE
dispute.review.demo-confirmed=true
dispute.review.fake-decision=RESOLVE
```

`fake-decision`은 `RESOLVE`, `REJECT`, `NEEDS_MORE_INFO` 중 하나입니다. 결정을 바꾸면 Tomcat을
재시작하고 새 Work Case로 시연합니다.

1. `COMPLETED / SCHEDULED`, `NO_SHOW / WAITING` 또는 `CHECK_OUT_MISSING / WAITING`인 근무를 준비합니다.
2. WORKER 근무 상세에서 **임금분쟁 신고·조회**로 들어가 제목과 경위를 제출합니다.
3. 정상 정산은 `ON_HOLD`가 되고, NO_SHOW·CHECK_OUT_MISSING 정산은 `WAITING`을 유지하지만
   환불 승인이 거절되는지 확인합니다.
4. 화면의 **새로고침**으로 결과를 다시 조회합니다.
5. OWNER 근무 상세에서도 같은 상태·요약·사유 코드·처리 시각이 보이는지 확인합니다.
6. `RESOLVE / REJECT`는 상태에 맞는 기존 지급·노쇼/퇴근 누락 환불 절차를 다시 실행할 수 있고,
   `NEEDS_MORE_INFO`는 `UNDER_REVIEW`와 보류를 유지해야 합니다.

모든 결과 화면에 `DEMO 시뮬레이션이며 법적 판단이 아님`이라는 안내가 계속 보여야 합니다.

## 3. 실제 Responses API DEMO

실제 자금·공유 환경에서는 사용하지 않습니다. 로컬 비밀 설정 파일에만 다음 값을 넣습니다.

```properties
dispute.review.mode=DEMO_LLM
dispute.review.demo-confirmed=true
dispute.review.openai.model=gpt-5.6-luna
dispute.review.openai.api-key=<local-secret>
dispute.review.prompt-version=dispute-review-v1
```

API Key는 Frontend, Git, 이슈, 로그에 남기지 않습니다. 서버는 Responses API에
`store=false`와 Strict JSON Schema를 보내고, 요청 UUID는 `X-Client-Request-Id`로 전달합니다.
이름·전화번호·계좌 형태와 내부 ID는 Provider 입력에서 제외합니다.

Worker Lease 만료, 늦은 응답, Timeout, 호출 중단, 전송 오류, 429·5xx·기타 HTTP 오류,
미완료·거부 응답, 잘못된 JSON·값 검증 실패와 예상 밖 Provider 오류는 새 요청 키로 최대
`dispute.review.max-attempts`회(기본 3회)까지 다시 시도합니다. 최초 시도 뒤 재시도 후보는 시도
순서에 따라 2초, 10초, 30초, 60초, 5분, 10분 간격으로 늦춰집니다. 각 실패 행은 감사 이력으로
남습니다. 재시도 소진과 선점 전 입력 불일치는 분쟁을 `UNDER_REVIEW`로 유지하고 양측 화면에
검토 지연과 보류 유지를 표시합니다. 이 경우 보류는 자동으로 풀리지 않으며 수동 재시도
Endpoint도 없습니다.

## 4. 감사 확인

아래 조회에는 API Key나 신고 원문이 나타나면 안 됩니다.

```sql
SELECT request_key,
       status,
       source,
       provider,
       model,
       prompt_version,
       input_hash,
       decision,
       reason_codes,
       provider_response_id,
       failure_code,
       started_at,
       completed_at
FROM dispute_ai_reviews
ORDER BY id DESC
LIMIT 10;
```

`PROCESSING` Lease가 만료되면 다음 Worker 주기 또는 늦은 응답 처리 시점에 해당 실행을
`FAILED`로 확정하고 허용 횟수 안에서 새 검토를 예약합니다. 늦게 도착한 응답 자체는 무시하며,
자금 보류를 해제하지 않습니다.

## 5. 자동 검증

```powershell
.\backend\gradlew.bat -p backend test --tests "com.gighub.settlement.*"

.\backend\gradlew.bat -p backend `
  "-Dgighub.database.config=C:/absolute/path/to/database-local.properties" `
  databaseTest --tests "com.gighub.settlement.DisputeAiReviewSchemaDatabaseIntegrationTest"

.\backend\gradlew.bat -p backend `
  "-Dgighub.database.config=C:/absolute/path/to/database-local.properties" `
  databaseTest --tests "com.gighub.settlement.service.DisputeFlowDatabaseIntegrationTest"

npm.cmd --prefix frontend run test:run -- `
  src/views/worker/workCase/WorkerReportView.spec.js `
  src/views/owner/workCase/OwnerWorkCaseDetailView.spec.js
```

CI와 자동 테스트는 실제 LLM을 호출하지 않고 Fake 또는 HTTP Stub만 사용합니다.
