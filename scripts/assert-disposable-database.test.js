const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const {
  CONFIRM_FLAG,
  COMPOSE_FLAG,
  DisposableDatabaseError,
  assertDisposableDatabase,
  parseEnvFile,
  resolveTarget,
} = require("./assert-disposable-database");

const LOCAL_ENV = ["MYSQL_PORT=3307", "MYSQL_DATABASE=kb_pjt"].join("\n");

function createRoot(envContents) {
  const rootDir = fs.mkdtempSync(path.join(os.tmpdir(), "fixture-guard-"));
  if (envContents !== null) {
    fs.writeFileSync(path.join(rootDir, ".env"), envContents, "utf8");
  }

  return rootDir;
}

function reasonsFrom(options) {
  try {
    assertDisposableDatabase(options);
  } catch (error) {
    assert.ok(error instanceof DisposableDatabaseError);
    return error.reasons;
  }

  return null;
}

test("reads .env values and keeps '=' inside the value", () => {
  const parsed = parseEnvFile(
    ["# comment", "", "MYSQL_PORT=3307", "MYSQL_PASSWORD=a=b=c"].join("\n"),
  );

  assert.deepEqual(parsed, { MYSQL_PORT: "3307", MYSQL_PASSWORD: "a=b=c" });
});

test("accepts the confirmed local Compose database", () => {
  const rootDir = createRoot(LOCAL_ENV);

  assert.deepEqual(
    assertDisposableDatabase({
      rootDir,
      argv: [CONFIRM_FLAG],
      processEnv: {},
    }),
    {
      host: "127.0.0.1",
      port: 3307,
      database: "kb_pjt",
      composeInternal: false,
    },
  );
});

test("accepts the Compose service host on the internal port", () => {
  const rootDir = createRoot(LOCAL_ENV);

  assert.deepEqual(
    assertDisposableDatabase({
      rootDir,
      argv: [CONFIRM_FLAG, COMPOSE_FLAG],
      processEnv: { MYSQL_HOST: "db" },
    }),
    { host: "db", port: 3306, database: "kb_pjt", composeInternal: true },
  );
});

test("rejects a run without the explicit local confirmation", () => {
  const rootDir = createRoot(LOCAL_ENV);

  assert.deepEqual(reasonsFrom({ rootDir, argv: [], processEnv: {} }), [
    `로컬 DB 실행을 명시하는 ${CONFIRM_FLAG} 옵션이 없습니다.`,
  ]);
});

test("rejects a host that is not loopback", () => {
  const rootDir = createRoot(LOCAL_ENV);

  const reasons = reasonsFrom({
    rootDir,
    argv: [CONFIRM_FLAG],
    processEnv: { MYSQL_HOST: "staging-db.internal" },
  });

  assert.deepEqual(reasons, [
    "대상 Host staging-db.internal가 loopback 주소가 아닙니다.",
  ]);
});

test("rejects a shared host smuggled into the Compose internal mode", () => {
  const rootDir = createRoot(LOCAL_ENV);

  const reasons = reasonsFrom({
    rootDir,
    argv: [CONFIRM_FLAG, COMPOSE_FLAG],
    processEnv: { MYSQL_HOST: "staging-db.internal" },
  });

  assert.deepEqual(reasons, ["Compose 내부 접속 Host가 db가 아닙니다."]);
});

test("rejects a missing .env instead of assuming defaults", () => {
  const rootDir = createRoot(null);

  assert.deepEqual(reasonsFrom({ rootDir, argv: [CONFIRM_FLAG] }), [
    ".env 파일이 없어 대상 DB를 확인할 수 없습니다.",
  ]);
});

test("rejects an .env that cannot answer the port and database", () => {
  const rootDir = createRoot("MYSQL_USER=kb_pjt_app");

  assert.deepEqual(
    reasonsFrom({ rootDir, argv: [CONFIRM_FLAG], processEnv: {} }),
    [".env에 MYSQL_PORT가 없습니다.", ".env에 MYSQL_DATABASE가 없습니다."],
  );
});

test("rejects a port that cannot be read as a number", () => {
  const rootDir = createRoot(["MYSQL_PORT=not-a-port", "MYSQL_DATABASE=kb_pjt"].join("\n"));

  assert.deepEqual(
    reasonsFrom({ rootDir, argv: [CONFIRM_FLAG], processEnv: {} }),
    ["MYSQL_PORT 값 not-a-port를 Port로 읽을 수 없습니다."],
  );
});

test("rejects automation environments even when everything else looks local", () => {
  const rootDir = createRoot(LOCAL_ENV);

  assert.deepEqual(
    reasonsFrom({
      rootDir,
      argv: [CONFIRM_FLAG],
      processEnv: { CI: "true" },
    }),
    [
      "자동화 환경 변수 CI가 설정되어 있어 로컬 실행으로 볼 수 없습니다.",
    ],
  );
});

test("rejects a database the user declared as non-local", () => {
  const rootDir = createRoot(LOCAL_ENV);

  assert.deepEqual(
    reasonsFrom({
      rootDir,
      argv: [CONFIRM_FLAG],
      processEnv: { GIGHUB_DB_ENVIRONMENT: "staging" },
    }),
    ["GIGHUB_DB_ENVIRONMENT가 staging로 선언되어 있습니다."],
  );
});

test("collects every reason so one run shows all problems", () => {
  const rootDir = createRoot("MYSQL_DATABASE=kb_pjt");

  assert.deepEqual(
    reasonsFrom({
      rootDir,
      argv: [],
      processEnv: { MYSQL_HOST: "staging-db.internal", CI: "1" },
    }),
    [
      `로컬 DB 실행을 명시하는 ${CONFIRM_FLAG} 옵션이 없습니다.`,
      "대상 Host staging-db.internal가 loopback 주소가 아닙니다.",
      ".env에 MYSQL_PORT가 없습니다.",
      "자동화 환경 변수 CI가 설정되어 있어 로컬 실행으로 볼 수 없습니다.",
    ],
  );
});

test("resolves the loopback default when no host is configured", () => {
  assert.equal(
    resolveTarget({ MYSQL_PORT: "3307", MYSQL_DATABASE: "kb_pjt" }, {
      processEnv: {},
    }).host,
    "127.0.0.1",
  );
});
