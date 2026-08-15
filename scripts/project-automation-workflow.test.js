const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");

// project-automation.yml 의 inline script 를 추출해 분기별로 실행한다.
//
// on: issues 는 브랜치가 없는 저장소 이벤트라 기본 브랜치의 워크플로 파일로만 돈다.
// 즉 PR 안에서는 이 워크플로를 실행해 볼 수 없고, 머지한 뒤에야 실제 동작을 본다.
// 그 공백을 메우려고 script 본문을 꺼내 github.graphql 을 대역으로 바꿔 실행한다.

const WORKFLOW_PATH = path.join(
  __dirname,
  "..",
  ".github",
  "workflows",
  "project-automation.yml",
);

const PROJECT_ID = "PVT_test";

/** `script: |` 블록 스칼라만 잘라내고 들여쓰기를 벗긴다. */
function extractScript(ymlPath) {
  const lines = fs.readFileSync(ymlPath, "utf8").split(/\r?\n/);
  const start = lines.findIndex((line) => /^\s*script:\s*\|\s*$/.test(line));
  assert.notStrictEqual(start, -1, "script: | 블록을 찾지 못했다");

  const indent = lines[start + 1].match(/^\s*/)[0].length;
  const body = [];
  for (let i = start + 1; i < lines.length; i += 1) {
    const line = lines[i];
    if (line.trim() !== "" && line.match(/^\s*/)[0].length < indent) break;
    body.push(line.slice(indent));
  }
  return body.join("\n");
}

function createGithubStub({ inProject, state, closedAt, dateField = true }) {
  const calls = [];
  const fields = dateField
    ? [{ id: "F_end", name: "End date", dataType: "DATE" }]
    : [{ id: "F_txt", name: "End date", dataType: "TEXT" }];

  const github = {
    async graphql(query, variables) {
      const name = query.match(/(?:query|mutation)\s+(\w+)/)[1];
      calls.push({ name, variables });
      if (name !== "GetIssueProjectItem") return {};
      return {
        node: {
          number: 349,
          state,
          closedAt,
          projectItems: {
            nodes: inProject
              ? [
                  {
                    id: "ITEM_1",
                    project: {
                      id: PROJECT_ID,
                      title: "GigHub",
                      fields: { nodes: fields },
                    },
                  },
                ]
              : [],
          },
        },
      };
    },
  };

  return { github, calls };
}

async function runScript(options) {
  const script = extractScript(WORKFLOW_PATH);
  const { github, calls } = createGithubStub(options);
  const logs = [];
  const warnings = [];
  const failures = [];

  const core = {
    info: (message) => logs.push(message),
    warning: (message) => warnings.push(message),
    setFailed: (message) => failures.push(message),
  };
  const context = { payload: { issue: { node_id: "I_349" } } };
  const processStub = {
    env: {
      PROJECT_ID,
      PROJECT_END_DATE_FIELD: "End date",
      PROJECT_TIME_ZONE: "Asia/Seoul",
    },
  };

  const run = new Function(
    "github",
    "context",
    "core",
    "process",
    `return (async () => { ${script} })();`,
  );
  await run(github, context, core, processStub);

  const mutations = calls.filter((call) => call.name !== "GetIssueProjectItem");
  return { calls, mutations, logs, warnings, failures };
}

test("보드 미등록 이슈를 닫아도 실패하지 않는다", async () => {
  const result = await runScript({
    inProject: false,
    state: "CLOSED",
    closedAt: "2026-08-14T01:45:00Z",
  });

  assert.deepStrictEqual(result.failures, []);
  assert.deepStrictEqual(result.mutations, [], "미등록인데 mutation 이 실행됐다");
});

test("보드 미등록 사실은 info 가 아니라 warning 으로 남는다", async () => {
  // info 로 내리면 실행은 초록이 되지만 "보드에 없다"가 실행 목록에서 사라진다.
  // 실패를 없애면서 신호까지 없애지는 않았는지 고정한다.
  const result = await runScript({
    inProject: false,
    state: "CLOSED",
    closedAt: "2026-08-14T01:45:00Z",
  });

  assert.ok(
    result.warnings.some((line) => /not in the configured Project/.test(line)),
    `건너뜀 사유가 warning 에 없다 (info: ${JSON.stringify(result.logs)})`,
  );
});

test("보드 등록 이슈를 닫으면 End date 를 KST 종료일로 설정한다", async () => {
  // 2026-08-13T20:00Z 는 KST 로 2026-08-14 다. UTC 기준으로 계산하면 하루 어긋난다.
  const result = await runScript({
    inProject: true,
    state: "CLOSED",
    closedAt: "2026-08-13T20:00:00Z",
  });

  assert.deepStrictEqual(result.failures, []);
  assert.strictEqual(result.mutations.length, 1);
  assert.strictEqual(result.mutations[0].name, "SetIssueEndDate");
  assert.strictEqual(result.mutations[0].variables.date, "2026-08-14");
  assert.strictEqual(result.mutations[0].variables.fieldId, "F_end");
});

test("보드 등록 이슈를 reopen 하면 End date 를 비운다", async () => {
  const result = await runScript({
    inProject: true,
    state: "OPEN",
    closedAt: null,
  });

  assert.deepStrictEqual(result.failures, []);
  assert.strictEqual(result.mutations.length, 1);
  assert.strictEqual(result.mutations[0].name, "ClearIssueEndDate");
});

test("DATE 타입 End date 필드가 없으면 계속 실패한다", async () => {
  // 보드 미등록과 달리 이쪽은 자동화가 조용히 죽는 설정 오류다. 실패로 남아야 한다.
  const result = await runScript({
    inProject: true,
    state: "CLOSED",
    closedAt: "2026-08-13T20:00:00Z",
    dateField: false,
  });

  assert.strictEqual(result.failures.length, 1);
  assert.match(result.failures[0], /no DATE field/);
  assert.deepStrictEqual(result.mutations, []);
});
