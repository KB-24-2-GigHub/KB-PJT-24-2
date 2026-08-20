const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const {
  DemoSeedError,
  LOCAL_CONFIRM_ARGUMENT,
  PRODUCTION_CONFIRM_FLAG,
  RESET_CONFIRM_FLAG,
  RESET_CONFIRM_VALUE,
  SKIP_SQL_FLAG,
  assertLocalResetConfirmation,
  assertProductionReset,
  clearLocalDocumentStorage,
  invitationToken,
  parseOptions,
  parseSeedSummary,
  resolveLocalConfirmation,
  seoulDate,
  shouldPrintPendingInvitation,
} = require("./prepare-demo-seed");

test("다섯 시나리오를 고정된 SQL 파일에 연결한다", () => {
  assert.equal(parseOptions(["functional"]).scenario.file, "demo-functional.sql");
  assert.equal(
    parseOptions(["video-04-three-years"]).scenario.file,
    "demo-video-04-three-years.sql",
  );
  assert.throws(() => parseOptions(["unknown"]), DemoSeedError);
  assert.throws(
    () => parseOptions(["functional", "--surprise"]),
    /알 수 없는 옵션/,
  );
  for (const inheritedKey of ["constructor", "toString", "valueOf"]) {
    assert.throws(() => parseOptions([inheritedKey]), DemoSeedError);
  }
});

test("Compose 출력 사이에서 demo SEED 요약을 읽는다", () => {
  const parsed = parseSeedSummary(
    "Container starting\nscenario_key\tseed_now\tworkplace_id\nfunctional\t2026-08-19 10:00:00\t17\n",
  );
  assert.deepEqual(parsed, {
    scenario_key: "functional",
    seed_now: "2026-08-19 10:00:00",
    workplace_id: "17",
  });
});

test("서울 날짜는 자정 경계를 Asia/Seoul 기준으로 계산한다", () => {
  const now = new Date("2026-08-18T15:30:00.000Z");
  assert.equal(seoulDate(0, now), "2026-08-19");
  assert.equal(seoulDate(1, now), "2026-08-20");
});

test("초대 URL 마지막 경로에서 원문 Token을 뽑는다", () => {
  assert.equal(
    invitationToken("https://gighub.store/invitations/safe-token-123"),
    "safe-token-123",
  );
  assert.throws(() => invitationToken("not a url"), DemoSeedError);
});

test("운영 후속 API 실행은 Actions와 3중 확인값이 모두 있어야 한다", () => {
  const argv = ["functional", SKIP_SQL_FLAG, PRODUCTION_CONFIRM_FLAG];
  assert.doesNotThrow(() =>
    assertProductionReset({
      argv,
      processEnv: {
        CI: "true",
        GITHUB_ACTIONS: "true",
        DEMO_SEED_CONFIRM: RESET_CONFIRM_VALUE,
      },
    }),
  );
  assert.throws(
    () => assertProductionReset({ argv, processEnv: {} }),
    /운영 전체 초기화 후속 실행임을 확인하지 못했습니다/,
  );
});

test("로컬 문서 저장소는 정확한 안전 경로의 자식만 삭제한다", () => {
  const rootDir = fs.mkdtempSync(path.join(os.tmpdir(), "demo-seed-storage-"));
  const storage = path.join(rootDir, "local-data", "documents");
  const configPath = path.join(rootDir, "database-local.properties");
  fs.mkdirSync(path.join(storage, "nested"), { recursive: true });
  fs.writeFileSync(path.join(storage, "nested", "old.pdf"), "old");
  fs.writeFileSync(
    configPath,
    `document.storage.base-path=${storage.replace(/\\/g, "/")}\n`,
  );

  assert.equal(clearLocalDocumentStorage({ rootDir, configPath }), storage);
  assert.deepEqual(fs.readdirSync(storage), []);
});

test("설정 문서 경로가 local-data/documents 밖이면 삭제하지 않는다", () => {
  const rootDir = fs.mkdtempSync(path.join(os.tmpdir(), "demo-seed-storage-"));
  const outside = fs.mkdtempSync(path.join(os.tmpdir(), "demo-seed-outside-"));
  const sentinel = path.join(outside, "keep.txt");
  const configPath = path.join(rootDir, "database-local.properties");
  fs.writeFileSync(sentinel, "keep");
  fs.writeFileSync(
    configPath,
    `document.storage.base-path=${outside.replace(/\\/g, "/")}\n`,
  );

  assert.throws(
    () => clearLocalDocumentStorage({ rootDir, configPath }),
    /안전한 로컬 경로와 다릅니다/,
  );
  assert.equal(fs.readFileSync(sentinel, "utf8"), "keep");
});

test("상대 문서 경로는 저장소 루트를 기준으로 해석한다", () => {
  const rootDir = fs.mkdtempSync(path.join(os.tmpdir(), "demo-seed-storage-"));
  const storage = path.join(rootDir, "local-data", "documents");
  const configPath = path.join(rootDir, "database-local.properties");
  fs.mkdirSync(storage, { recursive: true });
  fs.writeFileSync(path.join(storage, "old.pdf"), "old");
  fs.writeFileSync(configPath, "document.storage.base-path=local-data/documents\n");

  assert.equal(clearLocalDocumentStorage({ rootDir, configPath }), storage);
  assert.deepEqual(fs.readdirSync(storage), []);
});

test("로컬 전체 초기화 확인 옵션 이름을 고정한다", () => {
  assert.equal(RESET_CONFIRM_FLAG, "--confirm-reset-all-data");
  assert.equal(LOCAL_CONFIRM_ARGUMENT, "confirm-local");
  assert.equal(RESET_CONFIRM_VALUE, "reset-all-data");
});

test("모든 demo 시나리오가 npm 11 위치 확인 인자를 안전 가드까지 전달한다", () => {
  for (const scenarioKey of [
    "functional",
    "video-01-onboarding",
    "video-02-check-in",
    "video-03-check-out",
    "video-04-three-years",
  ]) {
    const argv = [scenarioKey, LOCAL_CONFIRM_ARGUMENT, RESET_CONFIRM_VALUE];
    const parsed = parseOptions(argv);
    const confirmation = resolveLocalConfirmation(argv);

    assert.equal(parsed.scenarioKey, scenarioKey);
    assert.equal(confirmation.localConfirmed, true);
    assert.equal(confirmation.resetConfirmed, true);
    assert.ok(confirmation.guardArgv.includes("--confirm-local"));
  }
});

test("node 직접 실행의 기존 확인 Flag도 계속 지원한다", () => {
  const confirmation = resolveLocalConfirmation([
    "functional",
    "--confirm-local",
    "--confirm-reset-all-data",
  ]);

  assert.equal(confirmation.localConfirmed, true);
  assert.equal(confirmation.resetConfirmed, true);
});

test("로컬 실행은 전체 초기화 확인값이 없으면 SQL 전에 중단한다", () => {
  assert.throws(
    () => assertLocalResetConfirmation(["functional", LOCAL_CONFIRM_ARGUMENT]),
    /reset-all-data 확인값이 없습니다/,
  );
  assert.doesNotThrow(() =>
    assertLocalResetConfirmation([
      "functional",
      LOCAL_CONFIRM_ARGUMENT,
      RESET_CONFIRM_VALUE,
    ]),
  );
});

test("GitHub Actions 로그에는 미수락 초대 Token을 출력하지 않는다", () => {
  assert.equal(shouldPrintPendingInvitation({ GITHUB_ACTIONS: "true" }), false);
  assert.equal(shouldPrintPendingInvitation({}), true);
});

test("전체 초기화 목록은 현재 Flyway 애플리케이션 Table과 정확히 일치한다", () => {
  const rootDir = path.join(__dirname, "..");
  const migrationDir = path.join(
    rootDir,
    "backend",
    "src",
    "main",
    "resources",
    "db",
    "migration",
  );
  const effectiveTables = new Set();
  for (const file of fs.readdirSync(migrationDir).filter((name) => name.endsWith(".sql")).sort()) {
    const sql = fs.readFileSync(path.join(migrationDir, file), "utf8");
    for (const match of sql.matchAll(/^CREATE TABLE(?: IF NOT EXISTS)?\s+`?([a-z0-9_]+)`?/gim)) {
      effectiveTables.add(match[1]);
    }
    for (const match of sql.matchAll(/^DROP TABLE(?: IF EXISTS)?\s+`?([a-z0-9_]+)`?/gim)) {
      effectiveTables.delete(match[1]);
    }
  }

  const reset = fs.readFileSync(
    path.join(rootDir, "backend", "src", "test", "resources", "db", "seed", "demo-reset.inc"),
    "utf8",
  );
  const resetTables = new Set(
    [...reset.matchAll(/^DELETE FROM\s+`?([a-z0-9_]+)`?;/gim)].map((match) => match[1]),
  );
  assert.deepEqual([...resetTables].sort(), [...effectiveTables].sort());
});

test("Compose와 운영 Workflow가 전체 초기화 확인값을 함께 요구한다", () => {
  const rootDir = path.join(__dirname, "..");
  const compose = fs.readFileSync(path.join(rootDir, "compose.yaml"), "utf8");
  const workflow = fs.readFileSync(
    path.join(rootDir, ".github", "workflows", "seed-db.yml"),
    "utf8",
  );

  assert.match(compose, /DEMO_RESET_CONFIRM/);
  assert.match(compose, /reset-all-data/);
  assert.match(workflow, /Stop application for full demo reset/);
  assert.match(workflow, /Clear production document storage for full demo reset/);
  assert.match(workflow, /Restart application after full demo reset/);
  assert.match(workflow, /DEMO_SEED_CONFIRM: \$\{\{ inputs\.confirm \}\}/);
  assert.match(workflow, /DEMO_RESET_CONFIRM=/);
  assert.match(workflow, /"\$\{SEED_SOURCE\}"\/\*\.inc/);

  const runner = fs.readFileSync(
    path.join(rootDir, "scripts", "prepare-demo-seed.js"),
    "utf8",
  );
  assert.ok(
    runner.indexOf("summary = parseSeedSummary(runSeedService") <
      runner.indexOf("const cleared = clearLocalDocumentStorage"),
    "로컬 문서 삭제는 SQL 성공 뒤여야 합니다.",
  );
  assert.ok(
    workflow.indexOf("- name: Apply seed") <
      workflow.indexOf("- name: Clear production document storage"),
    "운영 문서 삭제는 SQL 성공 뒤여야 합니다.",
  );
});

/** SOURCE /seed/*.inc 를 펼쳐 시나리오 하나를 한 덩어리 SQL 로 만든다. */
function inlineSeedIncludes(seedDir, file) {
  const sql = fs.readFileSync(path.join(seedDir, file), "utf8");
  return sql.replace(/^SOURCE\s+\/seed\/([\w.-]+)\s*$/gim, (whole, include) =>
    inlineSeedIncludes(seedDir, include),
  );
}

/**
 * VALUES 목록을 최상위 괄호 단위로 자른다. JSON_OBJECT(...)·DATE(...)·UNHEX(...) 처럼
 * 중첩 괄호가 들어 있어 정규식만으로는 Tuple 경계를 찾을 수 없다.
 */
function splitSqlTuples(valuesBody) {
  const tuples = [];
  let depth = 0;
  let start = 0;
  let inString = false;

  for (let index = 0; index < valuesBody.length; index += 1) {
    const character = valuesBody[index];
    if (inString) {
      // MySQL 의 '' Escape 는 닫고 곧바로 다시 열리므로 따로 다루지 않아도 경계가 맞는다.
      if (character === "'") inString = false;
      continue;
    }
    if (character === "'") inString = true;
    else if (character === "(") {
      if (depth === 0) start = index + 1;
      depth += 1;
    } else if (character === ")") {
      depth -= 1;
      if (depth === 0) tuples.push(valuesBody.slice(start, index));
    }
  }
  return tuples;
}

/** Tuple 을 최상위 쉼표로 나눈다. 괄호 안과 문자열 안의 쉼표는 열 경계가 아니다. */
function splitSqlColumns(tuple) {
  const columns = [];
  let depth = 0;
  let inString = false;
  let current = "";

  for (const character of tuple) {
    if (inString) {
      current += character;
      if (character === "'") inString = false;
      continue;
    }
    if (character === "'") inString = true;
    else if (character === "(") depth += 1;
    else if (character === ")") depth -= 1;
    else if (character === "," && depth === 0) {
      columns.push(current.trim());
      current = "";
      continue;
    }
    current += character;
  }
  columns.push(current.trim());
  return columns;
}

/** Table 별 INSERT ... VALUES 문 전체를 잡는다. */
const SEED_INSERT_STATEMENTS = {
  work_contracts: /INSERT\s+INTO\s+work_contracts\s*\(([^)]*)\)\s*VALUES([\s\S]*?);/gi,
  documents: /INSERT\s+INTO\s+documents\s*\(([^)]*)\)\s*VALUES([\s\S]*?);/gi,
};

/** 주어진 Table 의 INSERT 들에서 work_case_id 자리의 변수 이름을 모은다. */
function seededWorkCaseIds(sql, table, accept = () => true) {
  const found = new Set();
  const statements = SEED_INSERT_STATEMENTS[table];

  for (const [, columnList, valuesBody] of sql.matchAll(statements)) {
    const columns = columnList.split(",").map((name) => name.trim());
    const workCaseColumn = columns.indexOf("work_case_id");
    if (workCaseColumn < 0) continue;

    for (const tuple of splitSqlTuples(valuesBody)) {
      const values = splitSqlColumns(tuple);
      if (accept(columns, values)) found.add(values[workCaseColumn]);
    }
  }
  return found;
}

test("계약을 만드는 SEED 는 같은 근무의 EMPLOYMENT_CONTRACT 문서도 함께 만든다", () => {
  // 계약은 있는데 연결 문서가 없으면 서버가 계약서 생성이 끊긴 손상 상태로 보고 근무 상세를
  // 500 으로 막는다(WorkCaseServiceImpl.requireContractIntegrity). SEED 가 그 조합을 만들면
  // 시연 중 화면을 눌러 봐야 드러나므로 파일 단계에서 잡는다.
  const seedDir = path.join(
    __dirname,
    "..",
    "backend",
    "src",
    "test",
    "resources",
    "db",
    "seed",
  );
  // demo 시나리오와 기존 고정 Fixture 를 함께 본다. .inc 는 SOURCE 로 펼쳐져 들어온다.
  const scenarios = fs
    .readdirSync(seedDir)
    .filter((name) => name.endsWith(".sql"))
    .sort();
  assert.ok(scenarios.length > 0, "SEED 시나리오를 찾지 못했습니다.");

  for (const scenario of scenarios) {
    const sql = inlineSeedIncludes(seedDir, scenario);
    const contracted = seededWorkCaseIds(sql, "work_contracts");
    const documented = seededWorkCaseIds(
      sql,
      "documents",
      (columns, values) =>
        values[columns.indexOf("document_type")] === "'EMPLOYMENT_CONTRACT'",
    );

    // 정규식이 조용히 아무것도 못 잡으면 모든 시나리오가 통과해 버린다.
    if (/INSERT\s+INTO\s+work_contracts/i.test(sql)) {
      assert.ok(
        contracted.size > 0,
        `${scenario}: work_contracts INSERT 를 해석하지 못했습니다.`,
      );
    }

    for (const workCase of contracted) {
      assert.ok(
        documented.has(workCase),
        `${scenario}: ${workCase} 근무에 계약만 있고 EMPLOYMENT_CONTRACT 문서가 없습니다.`,
      );
    }
  }
});
