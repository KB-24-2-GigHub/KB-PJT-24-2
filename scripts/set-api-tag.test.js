const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");
const { spawnSync } = require("node:child_process");

// deploy/set-api-tag.sh 는 배포가 끝난 뒤 /opt/gighub/.env 의 API_TAG 를 방금 띄운
// 태그로 맞춘다. 배경은 deploy/SETUP.md 7절에 있다.
//
// 같은 파일에 FLYWAY_* 접속값이 함께 있으므로 계약은 둘이다.
//   1) API_TAG 만 바뀌고 나머지는 그대로다.
//   2) 어떤 실패에서도 .env 가 손상되지 않는다. 저장소에 사본이 없는 파일이다.

const REPO = path.join(__dirname, "..");
const SCRIPT = path.join(REPO, "deploy", "set-api-tag.sh");

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

/** 테스트가 끝나면 지운다. 남기면 FLYWAY_PASSWORD 가 든 .env 사본이 쌓인다. */
function createSandbox(t, content = ENV_CONTENT, mode = 0o600) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "set-api-tag-"));
  t.after(() => {
    fs.chmodSync(root, 0o700);
    fs.rmSync(root, { recursive: true, force: true });
  });

  const envFile = path.join(root, ".env");
  if (content !== null) {
    fs.writeFileSync(envFile, content, { mode });
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

test("API_TAG 줄을 새 태그로 바꾸고 나머지 줄은 건드리지 않는다", (t) => {
  const sandbox = createSandbox(t);
  const before = readLines(sandbox);

  const result = run(sandbox, [SHA]);
  assert.strictEqual(result.status, 0, result.stderr);

  const after = readLines(sandbox);
  assert.strictEqual(after[0], `API_TAG=${SHA}`);
  // 값에 =, &, % 가 든 줄들이 그대로인지 문자 단위로 본다.
  assert.deepStrictEqual(after.slice(1), before.slice(1));
  assert.deepStrictEqual(leftovers(sandbox), []);
});

test("API_TAG 키가 없으면 끝에 추가하고 기존 키는 보존한다", (t) => {
  const sandbox = createSandbox(
    t,
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

test("중복 API_TAG 줄을 하나로 정리한다", (t) => {
  // Compose 는 마지막 값을 쓴다. 첫 줄만 고치고 둘째 줄을 남기면 고친 값이
  // 조용히 무시되므로, 남은 줄이 정확히 하나여야 한다.
  const sandbox = createSandbox(
    t,
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

test("export 접두와 선행 공백이 붙은 API_TAG 줄도 정리한다", (t) => {
  // Compose 는 두 형태를 모두 읽는다. `^API_TAG=` 만 앵커로 삼으면 이 줄들이 뒤에
  // 남아 우리가 쓴 값을 덮는다 — 고쳤는데 조용히 옛 태그가 뜨는 경우다.
  const sandbox = createSandbox(
    t,
    [
      "API_TAG=old",
      "FLYWAY_USER=gighub",
      "export API_TAG=other",
      "  API_TAG=third",
      "",
    ].join("\n"),
  );

  const result = run(sandbox, [SHA]);
  assert.strictEqual(result.status, 0, result.stderr);

  assert.deepStrictEqual(readLines(sandbox), [
    `API_TAG=${SHA}`,
    "FLYWAY_USER=gighub",
    "",
  ]);
});

test("같은 태그로 두 번 실행해도 결과가 같다", (t) => {
  const sandbox = createSandbox(t);

  assert.strictEqual(run(sandbox, [SHA]).status, 0);
  const once = fs.readFileSync(sandbox.envFile, "utf8");
  assert.strictEqual(run(sandbox, [SHA]).status, 0);

  assert.strictEqual(fs.readFileSync(sandbox.envFile, "utf8"), once);
});

test("원본 권한을 보존한다", (t) => {
  // 일부러 0600 이 아닌 값으로 만든다. 0600 으로 두면 umask 077 이 만든 새 파일과
  // 우연히 같아져, 권한을 보존하지 않는 구현도 이 단언을 통과한다.
  const sandbox = createSandbox(t, ENV_CONTENT, 0o640);

  assert.strictEqual(run(sandbox, [SHA]).status, 0);

  // Windows 는 POSIX 모드 비트를 그대로 두지 않으므로 여기서만 확인한다.
  // 실제 판정은 리눅스 CI 의 npm run test:harness 가 한다.
  if (process.platform !== "win32") {
    assert.strictEqual(fs.statSync(sandbox.envFile).mode & 0o777, 0o640);
  }
});

test("임시본을 만들 수 없으면 .env 를 건드리지 않고 실패한다", (t) => {
  // .env 는 접속값이 든 유일한 사본이다. 쓰기 경로의 어느 단계가 실패하든 옛 내용이
  // 온전히 남아야 한다. 디렉터리를 읽기전용으로 만들어 임시본 생성을 막는다.
  if (process.platform === "win32") {
    t.skip("Windows 는 디렉터리 모드로 파일 생성을 막지 못한다");
    return;
  }
  // root 는 디렉터리 권한 검사를 건너뛰므로 이 트리거가 통하지 않는다. GitHub
  // Actions 러너는 비root(uid 1001) 라 CI 에서는 실제로 판정된다.
  if (process.getuid && process.getuid() === 0) {
    t.skip("root 로 실행 중 — 디렉터리 모드가 파일 생성을 막지 못한다");
    return;
  }

  const sandbox = createSandbox(t);
  fs.chmodSync(sandbox.root, 0o500);

  const result = run(sandbox, [SHA]);

  assert.notStrictEqual(result.status, 0, "실패해야 한다");
  assert.strictEqual(fs.readFileSync(sandbox.envFile, "utf8"), ENV_CONTENT);
});

test("쓰기 경로가 .env 를 제자리에서 비우지 않는다", (t) => {
  // 이 둘은 구조 단언이다. 시그널이나 디스크 가득 참을 이 하네스에서 결정적으로
  // 재현할 수 없는데, 둘 다 결과가 "0바이트 또는 잘린 .env" 라 값이 크다.
  //
  // - `> "$ENV_FILE"` 는 한 바이트를 쓰기 전에 .env 를 먼저 truncate 한다. 그 뒤
  //   쓰기가 실패하면 접속값이 잘린 채로 남는다. 같은 디렉터리 안의 mv 는
  //   rename(2) 이라 원자적이다.
  // - POSIX sh 에서 EXIT 이 아닌 시그널 트랩은 핸들러가 끝나면 중단된 명령 다음
  //   줄부터 실행을 재개한다. exit 가 없으면 임시본만 지운 채 남은 줄이 계속 돈다.
  const script = fs.readFileSync(SCRIPT, "utf8");

  assert.doesNotMatch(
    script,
    />\s*"\$ENV_FILE"/,
    ".env 로의 truncate 리다이렉션이 있다",
  );
  assert.match(script, /\bmv "\$tmp" "\$ENV_FILE"/, "원자적 교체가 아니다");

  for (const signal of ["INT", "TERM"]) {
    const trap = script.match(new RegExp(`^trap '([^']*)' ${signal}$`, "m"));
    assert.notStrictEqual(trap, null, `${signal} 트랩이 없다`);
    assert.match(trap[1], /\bexit\b/, `${signal} 트랩이 exit 하지 않는다`);
  }
});

test("태그를 주지 않으면 실패하고 파일을 그대로 둔다", (t) => {
  const sandbox = createSandbox(t);

  const result = run(sandbox, []);
  assert.strictEqual(result.status, 1);
  assert.match(result.stderr, /usage/);
  assert.strictEqual(fs.readFileSync(sandbox.envFile, "utf8"), ENV_CONTENT);
});

test("Docker 태그 문법을 벗어난 값을 거부하고 파일을 그대로 둔다", (t) => {
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
    const sandbox = createSandbox(t);
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

test("배포가 실제로 넘기는 형태의 태그를 받아들인다", (t) => {
  // deploy-api.yml 은 github.sha 를, 롤백 절차는 SHA 나 브랜치 태그를 넘긴다.
  for (const tag of [SHA, "dev", "v1.2.3", "feat_x-1"]) {
    const sandbox = createSandbox(t);
    const result = run(sandbox, [tag]);

    assert.strictEqual(result.status, 0, `거부되면 안 된다: ${tag}`);
    assert.strictEqual(readLines(sandbox)[0], `API_TAG=${tag}`);
  }
});

test("호출자가 로케일을 넘겨도 비ASCII 태그를 거부한다", (t) => {
  // sshd 는 보통 AcceptEnv 로 클라이언트의 LANG/LC_* 를 받으므로 호출자가 로케일을
  // 정할 수 있다. 이 테스트가 고정하는 것은 "그 상태에서도 거부된다" 까지다.
  //
  // 스크립트의 LC_ALL=C 를 지워도 이 단언은 통과한다. glibc(en_US.UTF-8)와 musl 에서
  // 악센트 문자가 [A-Za-z] 범위에 접히는지 실측했더니 둘 다 거부됐기 때문이다.
  // LC_ALL=C 는 POSIX 가 미정의로 둔 범위 해석에 기대지 않으려는 것이고, 그 값은
  // 이 하네스로 증명되지 않는다.
  const sandbox = createSandbox(t);
  const result = spawnSync("sh", [SCRIPT, "dévtag"], {
    encoding: "utf8",
    env: {
      ...process.env,
      ENV_FILE: sandbox.envFile,
      LC_ALL: "en_US.UTF-8",
      LANG: "en_US.UTF-8",
      LC_COLLATE: "en_US.UTF-8",
    },
  });

  assert.strictEqual(result.status, 1, "로케일에 따라 통과하면 안 된다");
  assert.strictEqual(fs.readFileSync(sandbox.envFile, "utf8"), ENV_CONTENT);
});

test(".env 가 없으면 새로 만들지 않고 멈춘다", (t) => {
  // 접속값이 빠진 한 줄짜리 .env 를 만들어 주면 Migration 과 seed 가 그럴듯하게
  // 진행되다 엉뚱한 곳에서 실패한다.
  const sandbox = createSandbox(t, null);

  const result = run(sandbox, [SHA]);
  assert.strictEqual(result.status, 1);
  assert.match(result.stderr, /env file not found/);
  assert.strictEqual(fs.existsSync(sandbox.envFile), false);
});

// 스크립트가 옳아도 배포가 부르지 않으면 아무 일도 일어나지 않는다. 2026-08-19 의
// 사고가 정확히 "한 줄이 없어서" 난 것이므로, 연결 자체를 고정한다.
test("deploy-api.yml 이 산출물을 올리고 up -d 뒤에 절대 경로로 실행한다", () => {
  // Windows 체크아웃이면 CRLF 다. 줄 단위 패턴이 조용히 빗나가지 않게 먼저 맞춘다.
  const workflow = fs
    .readFileSync(path.join(REPO, ".github", "workflows", "deploy-api.yml"), "utf8")
    .replace(/\r\n/g, "\n");

  // 목적지까지 함께 본다. 원본 경로만 보면 목적지를 /tmp 로 바꿔도 통과하는데,
  // 원격 호출은 /opt/gighub/set-api-tag.sh 를 부르므로 매 배포가 깨진다.
  for (const file of ["set-api-tag.sh", "compose.prod.yaml"]) {
    assert.match(
      workflow,
      new RegExp(`deploy/${file.replace(".", "\\.")}\\s*\\\\\\n[^\\n]*:/opt/gighub/${file.replace(".", "\\.")}"`),
      `deploy/${file} 를 /opt/gighub/${file} 로 올리지 않는다`,
    );
  }

  // 업로드가 배포보다 앞서야 한다. 뒤집으면 파일이 없는 서버의 첫 배포가 깨진다.
  const uploadAt = workflow.indexOf("- name: Upload deploy artifacts");
  const deployAt = workflow.indexOf("- name: Deploy over SSH");
  assert.notStrictEqual(uploadAt, -1, "Upload deploy artifacts Step 이 없다");
  assert.notStrictEqual(deployAt, -1, "Deploy over SSH Step 이 없다");
  assert.ok(uploadAt < deployAt, "업로드가 배포보다 뒤에 있다");

  // ssh 로 넘기는 원격 스크립트 본문만 떼어 순서를 본다.
  const remote = workflow.match(/<<'REMOTE'\n([\s\S]*?)\n\s*REMOTE\b/);
  assert.notStrictEqual(remote, null, "REMOTE heredoc 을 찾지 못했다");

  const body = remote[1];
  const upAt = body.indexOf("up -d app");
  // 절대 경로여야 한다. 위쪽 cd 에 기대면 REMOTE 본문에 cd 가 하나만 더 끼어도
  // up -d 가 컨테이너를 교체한 뒤에 배포가 깨진다.
  const setAt = body.indexOf("sh /opt/gighub/set-api-tag.sh");

  assert.notStrictEqual(upAt, -1, "원격 스크립트에 up -d app 이 없다");
  assert.notStrictEqual(setAt, -1, "set-api-tag.sh 를 절대 경로로 부르지 않는다");
  // 순서가 뒤집히면 기동에 실패한 배포가 .env 에 "뜨지 않은 버전" 을 남긴다.
  assert.ok(setAt > upAt, "set-api-tag.sh 가 up -d app 보다 먼저 실행된다");
});

test("compose.prod.yaml 의 app 이미지 태그에 기본값이 없다", () => {
  // 이 수정의 나머지 절반이다. 기본값을 되살리는 변경은 #450 회귀를 그대로
  // 재장전하는데, .env 갱신 쪽 테스트로는 잡히지 않는다. 파일 내용을 고정하는 데는
  // Docker 가 필요 없다.
  const compose = fs.readFileSync(path.join(REPO, "deploy", "compose.prod.yaml"), "utf8");

  assert.match(compose, /kb-pjt-24-2-api:\$\{API_TAG\}/);
  assert.doesNotMatch(
    compose,
    /kb-pjt-24-2-api:\$\{API_TAG[:\-?]/,
    "app 이미지 태그에 기본값이나 대체값이 붙었다",
  );
});
