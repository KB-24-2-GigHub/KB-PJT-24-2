const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");
const { spawnSync } = require("node:child_process");

// deploy/apply-seed.sh 는 seed 컨테이너의 entrypoint 다. RDS 접속값을 .env 의
// FLYWAY_URL 에서 파싱하므로, 그 파싱이 틀리면 엉뚱한 DB 에 seed 가 들어간다.
// mysql 대역을 PATH 앞에 두고 실제 스크립트를 실행해 결정 결과를 확인한다.

const SCRIPT = path.join(__dirname, "..", "deploy", "apply-seed.sh");

const VALID_URL =
  "jdbc:mysql://gighub.abc123.ap-northeast-2.rds.amazonaws.com:3306/kb_pjt" +
  "?useSSL=true&requireSSL=true&serverTimezone=Asia%2FSeoul";

function createSandbox() {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "apply-seed-"));
  const seedDir = path.join(root, "seed");
  const binDir = path.join(root, "bin");
  fs.mkdirSync(seedDir);
  fs.mkdirSync(binDir);
  fs.writeFileSync(path.join(seedDir, "demo.sql"), "SELECT 1;\n");

  // 인자와 표준입력 길이를 남기고 끝나는 mysql 대역.
  const argsLog = path.join(root, "args.txt");
  fs.writeFileSync(
    path.join(binDir, "mysql"),
    `#!/bin/sh\nprintf '%s\\n' "$@" > "${argsLog.replace(/\\/g, "/")}"\ncat > /dev/null\n`,
    { mode: 0o755 },
  );

  return { root, seedDir, binDir, argsLog };
}

function run(env, sandbox) {
  return spawnSync(
    "sh",
    [SCRIPT],
    {
      encoding: "utf8",
      env: {
        PATH: `${sandbox.binDir}${path.delimiter}${process.env.PATH}`,
        SEED_DIR: sandbox.seedDir,
        FLYWAY_URL: VALID_URL,
        FLYWAY_USER: "gighub",
        FLYWAY_PASSWORD: "s3cret",
        SEED_FILE: "demo.sql",
        ...env,
      },
    },
  );
}

function readArgs(sandbox) {
  return fs.readFileSync(sandbox.argsLog, "utf8").trim().split(/\r?\n/);
}

test("FLYWAY_URL 에서 host, port, database 를 뽑아 mysql 에 넘긴다", () => {
  const sandbox = createSandbox();
  const result = run({}, sandbox);

  assert.strictEqual(result.status, 0, result.stderr);
  const args = readArgs(sandbox);
  assert.ok(
    args.includes("--host=gighub.abc123.ap-northeast-2.rds.amazonaws.com"),
    args.join(" "),
  );
  assert.ok(args.includes("--port=3306"), args.join(" "));
  assert.ok(args.includes("--user=gighub"), args.join(" "));
  // database 는 쿼리스트링을 떼고 마지막 위치 인자로 넘어간다.
  assert.strictEqual(args[args.length - 1], "kb_pjt");
});

test("포트가 없는 URL 은 3306 으로 채운다", () => {
  const sandbox = createSandbox();
  const result = run(
    { FLYWAY_URL: "jdbc:mysql://db.internal/kb_pjt?useSSL=true" },
    sandbox,
  );

  assert.strictEqual(result.status, 0, result.stderr);
  const args = readArgs(sandbox);
  assert.ok(args.includes("--host=db.internal"), args.join(" "));
  assert.ok(args.includes("--port=3306"), args.join(" "));
  assert.strictEqual(args[args.length - 1], "kb_pjt");
});

test("비밀번호는 argv 에 실리지 않는다", () => {
  const sandbox = createSandbox();
  run({}, sandbox);

  const args = readArgs(sandbox);
  assert.ok(
    !args.some((arg) => arg.includes("s3cret")),
    `비밀번호가 인자로 노출됐다: ${args.join(" ")}`,
  );
});

test("SEED_FILE 로 /seed 밖의 실존 .sql 을 가리킬 수 없다", () => {
  // 서버에서 /opt/gighub/seed 의 이웃은 /opt/gighub/migrations 다. 가드가 없으면
  // ../migrations/V1__x.sql 처럼 실존하면서 .sql 로 끝나는 경로가 통과해
  // Migration 파일이 seed 로 실행된다. 확장자 검사와 존재 확인만으로는 이 경우를
  // 막지 못하므로 경로 형태 자체를 따로 검증한다.
  const sandbox = createSandbox();
  const neighbour = path.join(sandbox.root, "migrations");
  fs.mkdirSync(neighbour);
  fs.writeFileSync(path.join(neighbour, "V1__schema.sql"), "DROP TABLE users;\n");

  const result = run({ SEED_FILE: "../migrations/V1__schema.sql" }, sandbox);

  assert.notStrictEqual(result.status, 0, "경로 탈출이 통과했다");
  assert.match(result.stderr, /must be a bare file name/);
  assert.ok(!fs.existsSync(sandbox.argsLog), "mysql 이 실행됐다");
});

test("하위 디렉터리 표기도 거부한다", () => {
  const sandbox = createSandbox();
  fs.mkdirSync(path.join(sandbox.seedDir, "sub"));
  fs.writeFileSync(path.join(sandbox.seedDir, "sub", "x.sql"), "SELECT 1;\n");

  const result = run({ SEED_FILE: "sub/x.sql" }, sandbox);

  assert.notStrictEqual(result.status, 0);
  assert.match(result.stderr, /must be a bare file name/);
  assert.ok(!fs.existsSync(sandbox.argsLog), "mysql 이 실행됐다");
});

test("sql 이 아닌 확장자는 거부한다", () => {
  const sandbox = createSandbox();
  const result = run({ SEED_FILE: "demo.txt" }, sandbox);

  assert.notStrictEqual(result.status, 0);
  assert.match(result.stderr, /must end with \.sql/);
});

test("jdbc:mysql 이 아닌 URL 은 거부한다", () => {
  const sandbox = createSandbox();
  const result = run({ FLYWAY_URL: "jdbc:postgresql://db/kb_pjt" }, sandbox);

  assert.notStrictEqual(result.status, 0);
  assert.match(result.stderr, /must start with jdbc:mysql/);
});

test("database 가 없는 URL 은 거부한다", () => {
  const sandbox = createSandbox();
  const result = run({ FLYWAY_URL: "jdbc:mysql://db.internal" }, sandbox);

  assert.notStrictEqual(result.status, 0);
  assert.match(result.stderr, /could not parse/);
});

test("없는 seed 파일은 mysql 을 부르지 않고 실패한다", () => {
  const sandbox = createSandbox();
  const result = run({ SEED_FILE: "missing.sql" }, sandbox);

  assert.notStrictEqual(result.status, 0);
  assert.match(result.stderr, /seed file not found/);
  assert.ok(!fs.existsSync(sandbox.argsLog), "mysql 이 실행됐다");
});

test("필수 환경변수가 없으면 실패한다", () => {
  for (const missing of ["FLYWAY_URL", "FLYWAY_USER", "FLYWAY_PASSWORD", "SEED_FILE"]) {
    const sandbox = createSandbox();
    const result = run({ [missing]: "" }, sandbox);

    assert.notStrictEqual(result.status, 0, `${missing} 없이 통과했다`);
    assert.match(result.stderr, new RegExp(missing));
  }
});
