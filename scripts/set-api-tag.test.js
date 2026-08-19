const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");
const { spawnSync } = require("node:child_process");

// deploy/set-api-tag.sh 는 배포가 끝난 뒤 /opt/gighub/.env 의 API_TAG 를 방금 띄운
// 태그로 맞춘다. 이 갱신이 없으면 .env 가 옛 태그를 가리킨 채 남고, 뒤에 누가
// API_TAG 없이 `docker compose up -d app` 을 하면 구버전이 조용히 뜬다 (#450, #452).
//
// 같은 파일에 FLYWAY_* 접속값이 함께 있으므로, "API_TAG 만 바뀌고 나머지는 그대로"
// 가 이 스크립트의 계약이다. 실제 .env 모양으로 실행해 그 계약을 고정한다.

const SCRIPT = path.join(__dirname, "..", "deploy", "set-api-tag.sh");

// 운영 .env 를 그대로 본뜬다. FLYWAY_URL 은 값 안에 =, &, % 를 모두 갖고 있어서
// 줄 단위로 다루지 않으면 가장 먼저 깨지는 줄이다.
const ENV_CONTENT = [
  "API_TAG=dev",
  "FLYWAY_URL=jdbc:mysql://gighub.abc123.ap-northeast-2.rds.amazonaws.com:3306/kb_pjt" +
    "?useSSL=true&requireSSL=true&serverTimezone=Asia%2FSeoul",
  "FLYWAY_USER=gighub",
  "FLYWAY_PASSWORD=s3cret=with=equals",
  "",
].join("\n");

const SHA = "056201e9f0a1b2c3d4e5f60718293a4b5c6d7e8f";

function createSandbox(content = ENV_CONTENT) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "set-api-tag-"));
  const envFile = path.join(root, ".env");
  if (content !== null) {
    fs.writeFileSync(envFile, content, { mode: 0o600 });
  }
  return { root, envFile };
}

function run(sandbox, args) {
  return spawnSync("sh", [SCRIPT, ...args], {
    encoding: "utf8",
    env: { ...process.env, ENV_FILE: sandbox.envFile },
  });
}

function readLines(sandbox) {
  return fs.readFileSync(sandbox.envFile, "utf8").split("\n");
}

/** 임시 파일을 남기지 않는지 본다. .env 옆에 비밀번호 사본이 남으면 안 된다. */
function leftovers(sandbox) {
  return fs.readdirSync(sandbox.root).filter((name) => name !== ".env");
}

test("API_TAG 줄을 새 태그로 바꾸고 나머지 줄은 건드리지 않는다", () => {
  const sandbox = createSandbox();
  const before = readLines(sandbox);

  const result = run(sandbox, [SHA]);
  assert.strictEqual(result.status, 0, result.stderr);

  const after = readLines(sandbox);
  assert.strictEqual(after[0], `API_TAG=${SHA}`);
  // 값에 =, &, % 가 든 줄들이 그대로인지 문자 단위로 본다.
  assert.deepStrictEqual(after.slice(1), before.slice(1));
  assert.deepStrictEqual(leftovers(sandbox), []);
});

test("API_TAG 키가 없으면 끝에 추가하고 기존 키는 보존한다", () => {
  const sandbox = createSandbox(
    ["FLYWAY_USER=gighub", "FLYWAY_PASSWORD=s3cret", ""].join("\n"),
  );

  const result = run(sandbox, [SHA]);
  assert.strictEqual(result.status, 0, result.stderr);

  assert.deepStrictEqual(readLines(sandbox), [
    "FLYWAY_USER=gighub",
    "FLYWAY_PASSWORD=s3cret",
    `API_TAG=${SHA}`,
    "",
  ]);
});

test("중복 API_TAG 줄을 하나로 정리한다", () => {
  // Compose 는 마지막 값을 쓴다. 첫 줄만 고치고 둘째 줄을 남기면 고친 값이
  // 조용히 무시되므로, 남은 줄이 정확히 하나여야 한다.
  const sandbox = createSandbox(
    ["API_TAG=dev", "FLYWAY_USER=gighub", "API_TAG=stale", ""].join("\n"),
  );

  const result = run(sandbox, [SHA]);
  assert.strictEqual(result.status, 0, result.stderr);

  assert.deepStrictEqual(readLines(sandbox), [
    `API_TAG=${SHA}`,
    "FLYWAY_USER=gighub",
    "",
  ]);
});

test("같은 태그로 두 번 실행해도 결과가 같다", () => {
  const sandbox = createSandbox();

  assert.strictEqual(run(sandbox, [SHA]).status, 0);
  const once = fs.readFileSync(sandbox.envFile, "utf8");
  assert.strictEqual(run(sandbox, [SHA]).status, 0);

  assert.strictEqual(fs.readFileSync(sandbox.envFile, "utf8"), once);
});

test("파일을 제자리에서 고쳐 권한과 소유를 보존한다", () => {
  const sandbox = createSandbox();
  const before = fs.statSync(sandbox.envFile);

  assert.strictEqual(run(sandbox, [SHA]).status, 0);

  const after = fs.statSync(sandbox.envFile);
  // mv 로 갈아끼우면 inode 가 바뀌고 임시 파일의 속성이 .env 의 속성이 된다.
  if (before.ino !== 0) {
    assert.strictEqual(after.ino, before.ino, "inode 가 바뀌었다");
  }
  // Windows 는 POSIX 모드 비트를 그대로 두지 않으므로 여기서만 확인한다.
  // 실제 판정은 리눅스 CI 의 npm run test:harness 가 한다.
  if (process.platform !== "win32") {
    assert.strictEqual(after.mode & 0o777, 0o600);
  }
});

test("태그를 주지 않으면 실패하고 파일을 그대로 둔다", () => {
  const sandbox = createSandbox();

  const result = run(sandbox, []);
  assert.strictEqual(result.status, 1);
  assert.match(result.stderr, /usage/);
  assert.strictEqual(fs.readFileSync(sandbox.envFile, "utf8"), ENV_CONTENT);
});

test("Docker 태그 문법을 벗어난 값을 거부하고 파일을 그대로 둔다", () => {
  // 허용 목록으로 판정한다. 금지 문자를 열거하면 빠뜨린 문자가 그대로 .env 에
  // 적히고, 깨지는 시점은 여기가 아니라 다음 기동이다.
  const rejected = [
    "ghcr.io/kb-24-2-gighub/api:dev", // 슬래시와 콜론
    "-dev", // 하이픈으로 시작
    ".dev", // 점으로 시작
    "dev tag", // 공백
    "dev$(id)", // 명령 치환 모양
    "a".repeat(129), // 길이 초과
  ];

  // 줄바꿈은 .env 에 새 키를 주입할 수 있는 유일한 모양이라 반드시 막아야 하는데,
  // Windows 에서는 확인할 수 없다. spawnSync 가 만든 명령줄을 Git Bash 가 다시
  // 파싱하면서 "dev\ntag" 가 인자 두 개로 갈라져, 스크립트의 $1 에는 "dev" 만
  // 도착한다(argc=2 로 실측). 가드가 아니라 전달 경로의 문제다. 리눅스에서는
  // "tag may contain only [A-Za-z0-9._-]" 로 거부되는 것을 컨테이너로 확인했고,
  // 이 단언의 실제 판정은 리눅스 CI 의 npm run test:harness 가 한다.
  if (process.platform !== "win32") {
    rejected.push("dev\ntag");
  }

  for (const tag of rejected) {
    const sandbox = createSandbox();
    const result = run(sandbox, [tag]);

    assert.strictEqual(result.status, 1, `허용되면 안 된다: ${tag}`);
    assert.strictEqual(
      fs.readFileSync(sandbox.envFile, "utf8"),
      ENV_CONTENT,
      `거부했는데 파일이 바뀌었다: ${tag}`,
    );
    assert.deepStrictEqual(leftovers(sandbox), []);
  }
});

test("배포가 실제로 넘기는 형태의 태그를 받아들인다", () => {
  // deploy-api.yml 은 github.sha 를, 롤백 절차는 SHA 나 브랜치 태그를 넘긴다.
  for (const tag of [SHA, "dev", "v1.2.3", "feat_x-1"]) {
    const sandbox = createSandbox();
    const result = run(sandbox, [tag]);

    assert.strictEqual(result.status, 0, `거부되면 안 된다: ${tag}`);
    assert.strictEqual(readLines(sandbox)[0], `API_TAG=${tag}`);
  }
});

// 스크립트가 옳아도 배포가 부르지 않으면 아무 일도 일어나지 않는다. 2026-08-19 의
// 사고가 정확히 "한 줄이 없어서" 난 것이므로, 연결 자체를 고정한다.
test("deploy-api.yml 이 스크립트를 올리고 up -d 뒤에 실행한다", () => {
  // Windows 체크아웃이면 CRLF 다. 줄 단위 패턴이 조용히 빗나가지 않게 먼저 맞춘다.
  const workflow = fs
    .readFileSync(
      path.join(__dirname, "..", ".github", "workflows", "deploy-api.yml"),
      "utf8",
    )
    .replace(/\r\n/g, "\n");

  assert.match(
    workflow,
    /scp[^\n]*\n?[^\n]*deploy\/set-api-tag\.sh/,
    "deploy-api.yml 이 set-api-tag.sh 를 서버로 올리지 않는다",
  );

  // ssh 로 넘기는 원격 스크립트 본문만 떼어 순서를 본다.
  const remote = workflow.match(/<<'REMOTE'\n([\s\S]*?)\n\s*REMOTE\b/);
  assert.notStrictEqual(remote, null, "REMOTE heredoc 을 찾지 못했다");

  const body = remote[1];
  const upAt = body.indexOf("up -d app");
  const setAt = body.indexOf("sh set-api-tag.sh");

  assert.notStrictEqual(upAt, -1, "원격 스크립트에 up -d app 이 없다");
  assert.notStrictEqual(setAt, -1, "원격 스크립트가 set-api-tag.sh 를 부르지 않는다");
  // 순서가 뒤집히면 기동에 실패한 배포가 .env 에 "뜨지 않은 버전" 을 남긴다.
  assert.ok(setAt > upAt, "set-api-tag.sh 가 up -d app 보다 먼저 실행된다");
});

test(".env 가 없으면 새로 만들지 않고 멈춘다", () => {
  // 접속값이 빠진 한 줄짜리 .env 를 만들어 주면 Migration 과 seed 가 그럴듯하게
  // 진행되다 엉뚱한 곳에서 실패한다.
  const sandbox = createSandbox(null);

  const result = run(sandbox, [SHA]);
  assert.strictEqual(result.status, 1);
  assert.match(result.stderr, /env file not found/);
  assert.strictEqual(fs.existsSync(sandbox.envFile), false);
});
