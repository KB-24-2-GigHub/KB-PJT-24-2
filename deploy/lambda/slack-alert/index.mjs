/**
 * CloudWatch Alarm -> SNS -> (이 함수) -> Slack Incoming Webhook
 *
 * 책임은 하나다. SNS 로 들어온 CloudWatch Alarm 상태 변화를 Slack 메시지로 바꿔 보낸다.
 * AWS API 를 호출하지 않고 상태를 갖지 않으므로 실행 역할은
 * AWSLambdaBasicExecutionRole(CloudWatch Logs 쓰기) 하나면 충분하다.
 *
 * Runtime  nodejs24.x (외부 패키지 없음. 전역 fetch 만 사용한다)
 * 배포 방법 deploy/lambda/slack-alert/README.md
 * 운영 절차 deploy/SETUP.md 13절
 */

/**
 * Lambda 함수 자체의 timeout. 실제 값은 AWS 콘솔에 있고(SETUP.md 13.5절) 코드가 바꾸지
 * 못하므로, 여기 적은 값은 아래 관계를 고정하기 위한 사본이다. 둘이 어긋나면
 * SETUP.md 13.5절의 표와 이 상수를 함께 고친다.
 */
export const LAMBDA_TIMEOUT_MS = 10_000;

/**
 * Slack 호출 제한시간. LAMBDA_TIMEOUT_MS 보다 짧아야 Slack 지연이 Lambda 강제 종료가
 * 아니라 우리 예외로 잡히고, 로그에 원인이 남는다. 테스트가 이 부등식을 지킨다.
 */
export const SLACK_TIMEOUT_MS = 5000;

/** Alarm 상태별 표시. 키에 없는 상태는 UNKNOWN_STYLE 로 떨어진다. */
const STATE_STYLE = {
  ALARM: { color: "#d9534f", icon: "🔴", label: "발생" },
  OK: { color: "#5cb85c", icon: "🟢", label: "해제" },
  INSUFFICIENT_DATA: { color: "#f0ad4e", icon: "🟡", label: "데이터 부족" },
};

/**
 * 모르는 상태값을 임의로 ALARM 이나 OK 로 접지 않는다. 색만 중립으로 두고
 * 원문 상태값을 그대로 보여줘야 사람이 무엇을 못 읽었는지 알 수 있다.
 */
const UNKNOWN_STYLE = { color: "#999999", icon: "⚪", label: "알 수 없는 상태" };

/**
 * ISO 시각 문자열을 KST 표기로 바꾼다. CloudWatch 는 UTC 로 준다.
 *
 * 파싱에 실패하면 던지지 않고 원문을 남긴다. 시각 표기 하나 때문에 알림 전체를
 * 못 보내는 것이 알림이 늦는 것보다 나쁘다.
 */
export function toKstText(isoText) {
  const at = new Date(isoText);
  if (Number.isNaN(at.getTime())) {
    return `${isoText} (시각 파싱 실패)`;
  }
  // sv-SE 로케일이 "YYYY-MM-DD HH:mm:ss" 형태를 준다. 실제 시간대 변환은 timeZone 이 한다.
  return `${at.toLocaleString("sv-SE", { timeZone: "Asia/Seoul" })} KST`;
}

/**
 * SNS Message(문자열로 들어온 CloudWatch Alarm payload)를 Slack 요청 본문으로 만든다.
 * 순수 함수이므로 네트워크 없이 단독 테스트할 수 있다.
 */
export function buildMessage(rawSnsMessage) {
  let alarm;
  try {
    alarm = JSON.parse(rawSnsMessage);
  } catch (cause) {
    // SNS 콘솔의 "메시지 게시"로 평문을 직접 보내면 여기로 온다. 원문 앞부분을 함께
    // 남겨야 CloudWatch Logs 만 보고 무엇이 들어왔는지 판단할 수 있다.
    throw new Error(
      `SNS Message 가 CloudWatch Alarm JSON 이 아니다: ${String(rawSnsMessage).slice(0, 120)}`,
      { cause },
    );
  }

  const style = STATE_STYLE[alarm.NewStateValue] ?? UNKNOWN_STYLE;
  const trigger = alarm.Trigger ?? {};

  return {
    text: `${style.icon} *[${style.label}]* ${alarm.AlarmName}`,
    attachments: [
      {
        color: style.color,
        fallback: `${alarm.AlarmName} ${alarm.NewStateValue}`,
        fields: [
          {
            title: "상태",
            value: `${alarm.NewStateValue} (이전: ${alarm.OldStateValue ?? "-"})`,
            short: true,
          },
          {
            title: "지표",
            value: `${trigger.MetricName ?? "-"} ${trigger.ComparisonOperator ?? ""} ${
              trigger.Threshold ?? ""
            }`.trim(),
            short: true,
          },
          { title: "발생 시각", value: toKstText(alarm.StateChangeTime), short: false },
          { title: "사유", value: alarm.NewStateReason ?? "-", short: false },
        ],
        footer: "대응 절차: deploy/SETUP.md 13절과 문제 해결 표",
      },
    ],
  };
}

/**
 * Slack 이 2xx 를 주지 않으면 반드시 던진다.
 *
 * 알림이 가지 않았는데 호출이 성공으로 보이는 것이 이 시스템에서 가장 나쁜 실패다.
 * 여기서 던져야 CloudWatch Logs 에 흔적이 남고 SNS 비동기 호출이 재시도한다.
 */
async function postToSlack(webhookUrl, payload) {
  const response = await fetch(webhookUrl, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
    signal: AbortSignal.timeout(SLACK_TIMEOUT_MS),
  });

  if (!response.ok) {
    const body = await response.text().catch(() => "<본문 읽기 실패>");
    throw new Error(`Slack 이 ${response.status} 로 거절했다: ${body}`);
  }
}

export const handler = async (event) => {
  const webhookUrl = process.env.SLACK_WEBHOOK_URL;
  if (!webhookUrl) {
    throw new Error(
      "SLACK_WEBHOOK_URL 환경변수가 없다. Lambda 구성 탭의 환경 변수를 확인한다.",
    );
  }

  const records = event?.Records ?? [];
  if (records.length === 0) {
    throw new Error("SNS Records 가 비어 있다. Lambda 트리거 구성을 확인한다.");
  }

  for (const record of records) {
    await postToSlack(webhookUrl, buildMessage(record.Sns.Message));
  }
};
