const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");
const { spawnSync } = require("node:child_process");

// seed-db.yml 은 워크플로 입력을 env 로 넘기고 이 스크립트로 검증한다. 검증이
// 뚫리면 그 값이 원격 SSH 명령 문자열에 들어가 서버 셸이 파싱한다.

const SCRIPT = path.join(__dirname, "resolve-seed-file.sh");

function createSource(names = ["test-contract-escrow.sql", "test-invitation-accept.sql"]) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "seed-src-"));
  for (const name of names) {
    fs.writeFileSync(path.join(dir, name), "SELECT 1;\n");
  }
  return dir;
}

function run(env) {
  return spawnSync("sh", [SCRIPT], {
    encoding: "utf8",
    env: { PATH: process.env.PATH, ...env },
  });
}

test("허용 목록에 있는 이름은 그대로 돌려준다", () => {
  const result = run({
    SEED_SOURCE: createSource(),
    SEED_FILE_INPUT: "test-contract-escrow.sql",
  });

  assert.strictEqual(result.status, 0, result.stderr);
  assert.strictEqual(result.stdout.trim(), "test-contract-escrow.sql");
});

test("목록에 없는 이름은 거부하고 사용 가능한 목록을 알려준다", () => {
  const result = run({
    SEED_SOURCE: createSource(),
    SEED_FILE_INPUT: "nope.sql",
  });

  assert.notStrictEqual(result.status, 0);
  assert.match(result.stderr, /not an allowed seed file/);
  assert.match(result.stderr, /test-contract-escrow\.sql/);
  assert.strictEqual(result.stdout.trim(), "");
});

test("셸 구문을 섞은 입력은 통과하지 못한다", () => {
  // 워크플로가 이 값을 run 스크립트에 직접 박으면 셸이 실행해 버리던 형태다.
  const injections = [
    "test-contract-escrow.sql'; whoami; '",
    "'; rm -rf /tmp/x; '",
    "$(whoami).sql",
    "`whoami`.sql",
    "a.sql\nwhoami",
    "a.sql; whoami",
    "a.sql && whoami",
    "a.sql | whoami",
  ];

  for (const input of injections) {
    const source = createSource();
    const result = run({ SEED_SOURCE: source, SEED_FILE_INPUT: input });

    assert.notStrictEqual(result.status, 0, `통과하면 안 되는 입력: ${input}`);
    assert.strictEqual(result.stdout.trim(), "", `이름을 돌려줬다: ${input}`);
  }
});

test("경로가 붙은 이름은 실존하더라도 거부한다", () => {
  const source = createSource();
  const sibling = path.join(path.dirname(source), "migrations-" + path.basename(source));
  fs.mkdirSync(sibling);
  fs.writeFileSync(path.join(sibling, "V1__schema.sql"), "DROP TABLE users;\n");

  for (const input of [
    `../${path.basename(sibling)}/V1__schema.sql`,
    "./test-contract-escrow.sql",
  ]) {
    const result = run({ SEED_SOURCE: source, SEED_FILE_INPUT: input });

    assert.notStrictEqual(result.status, 0, `통과하면 안 되는 입력: ${input}`);
    assert.strictEqual(result.stdout.trim(), "");
  }
});

test("같은 디렉터리에 있어도 .sql 이 아니면 거부한다", () => {
  // 허용 목록이 *.sql 열거라는 사실을 고정한다. "존재하면 통과" 로 바뀌면
  // 이름에 메타문자가 없는 notes.txt 같은 파일이 그대로 통과한다. 경로 검사나
  // 메타문자 검사로는 이 경우를 잡지 못한다.
  const source = createSource();
  fs.writeFileSync(path.join(source, "notes.txt"), "not sql\n");

  const result = run({ SEED_SOURCE: source, SEED_FILE_INPUT: "notes.txt" });

  assert.notStrictEqual(result.status, 0, ".sql 이 아닌 파일이 통과했다");
  assert.match(result.stderr, /not an allowed seed file/);
  assert.strictEqual(result.stdout.trim(), "");
});

test("목록에 있어도 셸 메타문자가 든 이름은 거부한다", () => {
  // 저장소에 이상한 이름의 파일이 들어오는 경우다. 존재 검사만으로는 못 막는다.
  const source = createSource(["ok.sql", "bad name.sql"]);
  const result = run({ SEED_SOURCE: source, SEED_FILE_INPUT: "bad name.sql" });

  assert.notStrictEqual(result.status, 0);
  assert.match(result.stderr, /characters outside/);
  assert.strictEqual(result.stdout.trim(), "");
});

test("빈 입력은 거부한다", () => {
  const result = run({ SEED_SOURCE: createSource(), SEED_FILE_INPUT: "" });

  assert.notStrictEqual(result.status, 0);
  assert.match(result.stderr, /SEED_FILE_INPUT/);
});

test("SEED_SOURCE 가 없거나 디렉터리가 아니면 거부한다", () => {
  const missing = run({
    SEED_SOURCE: path.join(os.tmpdir(), "definitely-not-here-" + Date.now()),
    SEED_FILE_INPUT: "test-contract-escrow.sql",
  });
  assert.notStrictEqual(missing.status, 0);
  assert.match(missing.stderr, /seed source directory not found/);

  const unset = run({ SEED_FILE_INPUT: "test-contract-escrow.sql" });
  assert.notStrictEqual(unset.status, 0);
  assert.match(unset.stderr, /SEED_SOURCE/);
});

test("빈 디렉터리에서는 어떤 이름도 통과하지 못한다", () => {
  const result = run({
    SEED_SOURCE: createSource([]),
    SEED_FILE_INPUT: "test-contract-escrow.sql",
  });

  assert.notStrictEqual(result.status, 0);
  assert.strictEqual(result.stdout.trim(), "");
});
