/**
 * deploy/lambda/slack-alert/index.mjs 의 계약 테스트.
 *
 * 실행: npm run test:harness (저장소 루트)
 *
 * 로컬 Node 와 배포 Runtime(nodejs24.x) 이 다르므로, 함수는 양쪽에 모두 있는
 * API(전역 fetch, AbortSignal.timeout, Intl)만 쓴다. 이 테스트가 그 전제를 함께 지킨다.
 */

import { describe, it, beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";

import {
  LAMBDA_TIMEOUT_MS,
  SLACK_TIMEOUT_MS,
  buildMessage,
  handler,
  toKstText,
} from "./index.mjs";

const ALARM_PAYLOAD = {
  AlarmName: "gighub-ec2-cpu-high",
  NewStateValue: "ALARM",
  OldStateValue: "OK",
  NewStateReason:
    "Threshold Crossed: 3 out of the last 3 datapoints were greater than the threshold (70.0).",
  StateChangeTime: "2026-08-13T04:12:33.891+0000",
  Trigger: { MetricName: "CPUUtilization", ComparisonOperator: ">", Threshold: 70 },
};

function snsEvent(payload = ALARM_PAYLOAD, count = 1) {
  return {
    Records: Array.from({ length: count }, () => ({
      Sns: { Message: JSON.stringify(payload) },
    })),
  };
}

function slackResponse(status, body = "") {
  return { ok: status >= 200 && status < 300, status, text: async () => body };
}

/** globalThis.fetch 를 가로채고 호출 기록을 돌려준다. */
function stubFetch(response = slackResponse(200, "ok")) {
  const calls = [];
  globalThis.fetch = async (url, options) => {
    calls.push({ url, options });
    return response;
  };
  return calls;
}

const originalFetch = globalThis.fetch;
const originalWebhook = process.env.SLACK_WEBHOOK_URL;
const TEST_WEBHOOK = "https://hooks.slack.com/services/T0/B0/test";

beforeEach(() => {
  process.env.SLACK_WEBHOOK_URL = TEST_WEBHOOK;
});

afterEach(() => {
  globalThis.fetch = originalFetch;
  if (originalWebhook === undefined) {
    delete process.env.SLACK_WEBHOOK_URL;
  } else {
    process.env.SLACK_WEBHOOK_URL = originalWebhook;
  }
});

describe("toKstText", () => {
  it("CloudWatch 의 +0000 표기를 KST 로 9시간 앞당긴다", () => {
    assert.equal(toKstText("2026-08-13T04:12:33.891+0000"), "2026-08-13 13:12:33 KST");
  });

  it("Z 표기도 같은 결과를 준다", () => {
    assert.equal(toKstText("2026-08-13T04:12:33.891Z"), "2026-08-13 13:12:33 KST");
  });

  it("자정을 넘기는 변환에서 날짜가 함께 넘어간다", () => {
    assert.equal(toKstText("2026-08-13T16:30:00.000Z"), "2026-08-14 01:30:00 KST");
  });

  it("파싱할 수 없는 시각은 던지지 않고 원문을 남긴다", () => {
    assert.equal(toKstText("언젠가"), "언젠가 (시각 파싱 실패)");
  });
});

describe("buildMessage", () => {
  it("SNS 가 문자열로 준 payload 를 파싱해 AlarmName 을 제목에 넣는다", () => {
    const message = buildMessage(JSON.stringify(ALARM_PAYLOAD));
    assert.match(message.text, /gighub-ec2-cpu-high/);
  });

  // 색은 사람이 심각도를 먼저 읽는 신호다. 승인된 대응표 전체를 그대로 고정한다.
  it("상태값마다 승인된 색과 라벨만 쓴다", () => {
    const expected = {
      ALARM: { color: "#d9534f", label: "발생" },
      OK: { color: "#5cb85c", label: "해제" },
      INSUFFICIENT_DATA: { color: "#f0ad4e", label: "데이터 부족" },
    };

    for (const [state, { color, label }] of Object.entries(expected)) {
      const message = buildMessage(JSON.stringify({ ...ALARM_PAYLOAD, NewStateValue: state }));
      assert.equal(message.attachments[0].color, color, `${state} 색`);
      assert.match(message.text, new RegExp(`\\[${label}\\]`), `${state} 라벨`);
    }
  });

  it("모르는 상태값은 ALARM 이나 OK 로 접지 않고 중립 색에 원문을 남긴다", () => {
    const message = buildMessage(JSON.stringify({ ...ALARM_PAYLOAD, NewStateValue: "WEIRD" }));

    assert.equal(message.attachments[0].color, "#999999");
    assert.match(message.text, /알 수 없는 상태/);
    assert.match(
      message.attachments[0].fields.find((f) => f.title === "상태").value,
      /WEIRD/,
    );
  });

  it("임계치와 사유를 본문에 담는다", () => {
    const fields = buildMessage(JSON.stringify(ALARM_PAYLOAD)).attachments[0].fields;
    const byTitle = Object.fromEntries(fields.map((f) => [f.title, f.value]));

    assert.equal(byTitle["지표"], "CPUUtilization > 70");
    assert.match(byTitle["사유"], /Threshold Crossed/);
    assert.equal(byTitle["발생 시각"], "2026-08-13 13:12:33 KST");
  });

  // SNS 콘솔에서 평문을 직접 게시하면 실제로 일어난다. 던지는 것은 맞지만,
  // JSON.parse 의 기본 메시지로는 로그를 봐도 무엇이 들어왔는지 알 수 없다.
  it("CloudWatch Alarm JSON 이 아니면 원문을 붙여 던진다", () => {
    assert.throws(
      () => buildMessage("테스트 메시지"),
      /SNS Message 가 CloudWatch Alarm JSON 이 아니다: 테스트 메시지/,
    );
  });

  it("Trigger 가 없어도 던지지 않는다", () => {
    const withoutTrigger = { ...ALARM_PAYLOAD };
    delete withoutTrigger.Trigger;

    const fields = buildMessage(JSON.stringify(withoutTrigger)).attachments[0].fields;
    assert.equal(fields.find((f) => f.title === "지표").value, "-");
  });
});

describe("handler", () => {
  it("환경변수의 Webhook URL 로 POST 한다", async () => {
    const calls = stubFetch();

    await handler(snsEvent());

    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, TEST_WEBHOOK);
    assert.equal(calls[0].options.method, "POST");
    assert.equal(calls[0].options.headers["Content-Type"], "application/json");
    assert.match(JSON.parse(calls[0].options.body).text, /gighub-ec2-cpu-high/);
  });

  // 이 함수가 존재하는 이유 자체다. 알림이 안 갔는데 성공으로 보이면 안 된다.
  it("Slack 이 2xx 가 아니면 던진다", async () => {
    stubFetch(slackResponse(404, "no_service"));

    await assert.rejects(handler(snsEvent()), /Slack 이 404 로 거절했다: no_service/);
  });

  it("Slack 이 500 이어도 같은 이유로 던진다", async () => {
    stubFetch(slackResponse(500, "server_error"));

    await assert.rejects(handler(snsEvent()), /Slack 이 500 로 거절했다/);
  });

  it("SLACK_WEBHOOK_URL 이 없으면 Slack 을 부르기 전에 던진다", async () => {
    delete process.env.SLACK_WEBHOOK_URL;
    const calls = stubFetch();

    await assert.rejects(handler(snsEvent()), /SLACK_WEBHOOK_URL 환경변수가 없다/);
    assert.equal(calls.length, 0, "환경변수가 없으면 네트워크 호출을 시도하지 않는다");
  });

  it("Records 가 비어 있으면 던진다", async () => {
    stubFetch();

    await assert.rejects(handler({ Records: [] }), /SNS Records 가 비어 있다/);
  });

  it("Records 가 여러 건이면 건마다 보낸다", async () => {
    const calls = stubFetch();

    await handler(snsEvent(ALARM_PAYLOAD, 3));

    assert.equal(calls.length, 3);
  });

  it("제한시간 AbortSignal 을 붙여 호출한다", async () => {
    const calls = stubFetch();

    await handler(snsEvent());

    assert.ok(calls[0].options.signal, "AbortSignal 이 붙어야 Slack 지연에 물리지 않는다");
  });

  // 주석에만 적힌 전제는 지켜지지 않는다. 부등식을 코드로 고정해 둔다.
  // 이 값을 올릴 때 Lambda 콘솔의 Timeout 도 같이 올리지 않으면 Slack 지연이
  // 우리 예외가 아니라 Lambda 강제 종료로 끝나 원인이 로그에 남지 않는다.
  it("Slack 제한시간은 Lambda timeout 보다 짧다", () => {
    assert.ok(
      SLACK_TIMEOUT_MS < LAMBDA_TIMEOUT_MS,
      `SLACK_TIMEOUT_MS(${SLACK_TIMEOUT_MS}) 는 LAMBDA_TIMEOUT_MS(${LAMBDA_TIMEOUT_MS}) 보다 작아야 한다`,
    );
  });
});
