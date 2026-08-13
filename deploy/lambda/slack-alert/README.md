# slack-alert Lambda

CloudWatch Alarm 이 발화하면 Slack 채널에 한국어 알림을 보내는 함수다.

```
EC2 지표 ─▶ CloudWatch Alarm ─▶ SNS ─▶ 이 함수 ─▶ Slack Incoming Webhook
```

AWS 콘솔에서 무엇을 만드는지, 임계치를 어떻게 정하는지, 어떻게 검증하는지는
`deploy/SETUP.md` 13절에 있다. 이 문서는 **코드를 AWS 에 올리는 방법**만 다룬다.

## 소유권 경계 — 코드는 두 벌이다

| 사본                    | 위치                | 역할                     |
| ----------------------- | ------------------- | ------------------------ |
| `index.mjs`             | 이 저장소           | 리뷰와 이력의 원본       |
| Lambda 함수 코드        | AWS Lambda 서비스   | **실제로 실행되는 것**   |

**저장소는 AWS 에 자동 배포하지 않는다.** 사람이 손으로 옮긴다. 따라서
`index.mjs` 를 고치면 아래 절차로 AWS 에도 반영해야 한다. 하지 않으면
**저장소는 새 코드, 운영은 옛 코드**가 되고 이 어긋남은 어떤 지표에도 나타나지 않는다.

배포 후에는 항상 `SETUP.md` 13.7절의 `set-alarm-state` 검증을 한 번 돌려 확인한다.

## 함수 설정

| 항목        | 값                                                             |
| ----------- | -------------------------------------------------------------- |
| 함수 이름   | `gighub-slack-notifier`                                        |
| Runtime     | `nodejs24.x`                                                   |
| 핸들러      | `index.handler`                                                |
| 아키텍처    | `arm64` (동일 성능에 더 저렴하다. `x86_64` 도 동작한다)        |
| 메모리      | 128 MB                                                         |
| Timeout     | 10 초                                                          |
| 실행 역할   | `AWSLambdaBasicExecutionRole` 만                               |
| VPC         | **연결하지 않는다**                                            |
| 환경 변수   | `SLACK_WEBHOOK_URL`                                            |

> **VPC 에 연결하지 않는 이유.** 이 함수는 RDS 를 포함해 어떤 사설 자원에도 접근하지
> 않고 Slack(외부 HTTPS)만 호출한다. VPC 에 붙이면 인터넷으로 나가기 위해 NAT Gateway
> 가 필요해지고 월 $35 이상이 고정으로 발생한다.

> **실행 역할에 추가 권한을 주지 않는다.** 함수가 AWS API 를 호출하지 않으므로
> `AWSLambdaBasicExecutionRole`(CloudWatch Logs 쓰기)이면 충분하다. 나중에 지표를
> 직접 조회하는 기능을 넣고 싶어지면 그때 권한을 추가하고 이 표를 함께 고친다.

## 의존성이 없다

`package.json` 이 없고 `node_modules` 도 없다. 외부 패키지를 쓰지 않기 때문이다.

- HTTP 호출은 Node 18+ 의 전역 `fetch`
- 제한시간은 `AbortSignal.timeout`
- 시간대 변환은 `Intl` (`toLocaleString` 의 `timeZone` 옵션)

**이 제약을 유지한다.** 패키지를 하나라도 추가하는 순간 배포가 "파일 하나 올리기"에서
"의존성 설치 후 번들링"으로 바뀌고, `docs/DEPENDENCY_SPECIFICATION.md` 7절의 직접
의존성 변경 절차 대상이 된다.

## 최초 배포

`SETUP.md` 13.5절에서 함수를 만들 때 아래 방법 중 하나로 코드를 넣는다.

### 방법 A — 콘솔 인라인 편집기 (권장)

파일이 하나이고 의존성이 없으므로 붙여넣기가 가장 단순하다.

1. `index.mjs` 전체를 복사한다
2. Lambda 콘솔 → 함수 → **코드** 탭 → 편집기의 `index.mjs` 내용을 전부 지우고 붙여넣는다
3. **Deploy** 버튼을 누른다 — 누르지 않으면 저장만 되고 반영되지 않는다

> 콘솔이 만드는 기본 파일명은 `index.mjs` 다. 런타임이 Node.js 면 그대로 맞는다.
> 파일명을 바꿨다면 핸들러 설정(`index.handler`)의 앞부분도 함께 바꿔야 한다.

### 방법 B — zip 업로드

```bash
cd deploy/lambda/slack-alert
zip function.zip index.mjs          # 테스트 파일은 넣지 않는다
```

Lambda 콘솔 → **코드** 탭 → **에서 업로드** → **.zip 파일** → `function.zip`

CLI 를 쓴다면:

```bash
aws lambda update-function-code \
  --function-name gighub-slack-notifier \
  --zip-file fileb://function.zip
```

`function.zip` 은 산출물이므로 커밋하지 않는다.

## 환경 변수

Lambda 콘솔 → 함수 → **구성** 탭 → **환경 변수** → **편집** → **환경 변수 추가**

| 키                  | 값                                             |
| ------------------- | ---------------------------------------------- |
| `SLACK_WEBHOOK_URL` | `https://hooks.slack.com/services/T.../B.../...` |

**이 URL 은 사실상 비밀번호다.** 아는 사람은 누구나 해당 채널에 글을 쓸 수 있다.
저장소, 이슈, PR, 스크린샷 어디에도 넣지 않는다. 입력하는 곳은 위 화면 한 곳뿐이고,
AWS 가 관리형 KMS 키로 저장 시점에 암호화한다.

유출되면 Slack 앱 설정에서 Webhook 을 폐기하고 새로 발급한 뒤 **이 환경 변수만**
교체한다. 코드도 커밋도 건드리지 않는다.

## 테스트

```bash
# 저장소 루트에서
npm run test:harness
```

`node --test` 로 도는 순수 단위 테스트다. `globalThis.fetch` 를 대체하므로 실제로
Slack 을 호출하지 않고 AWS 자격증명도 필요 없다.

로컬 Node 버전과 배포 Runtime(`nodejs24.x`)이 다르지만, 함수가 양쪽에 모두 존재하는
API 만 쓰기 때문에 이 테스트가 유효하다. 그 전제를 깨는 API 를 쓰면 로컬에서는 통과하고
운영에서 죽는 상황이 생기므로, 새 API 를 쓸 때는 Node 18 기준으로 존재하는지 확인한다.

배선 전체(Alarm → SNS → Lambda → Slack)의 검증은 단위 테스트가 아니라
`SETUP.md` 13.7절의 `set-alarm-state` 절차가 담당한다.

## 로그

```
/aws/lambda/gighub-slack-notifier
```

Slack 이 2xx 가 아닌 응답을 주면 이 함수는 **의도적으로 예외를 던진다.** 알림이 가지
않았는데 호출이 성공으로 보이는 것이 이 시스템에서 가장 나쁜 실패이기 때문이다.
따라서 알림이 안 왔을 때 가장 먼저 볼 곳이 이 로그 그룹이다.

던지면 CloudWatch Logs 에 흔적이 남고 SNS 비동기 호출이 자동으로 재시도한다.

로그 그룹의 기본 보존 기간은 **"만료되지 않음"** 이므로 30 일로 줄여둔다
(`SETUP.md` 13.5절).
