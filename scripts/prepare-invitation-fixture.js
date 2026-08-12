#!/usr/bin/env node

/**
 * 초대 수락 Browser E2E(#267)의 출발 상태를 만들고 초대 URL을 알려줍니다.
 *
 * 계정·사업장·지갑은 Fixture SQL이 넣고, DRAFT 근무와 초대는 실제 OWNER API로 만듭니다.
 * 초대 Token 원문은 발급 응답의 inviteUrl 안에서만 나오므로 SQL로는 유효한 E2E 입력을
 * 만들 수 없습니다. 그 원문은 이 명령의 표준출력에만 쓰고 어떤 파일에도 남기지 않습니다.
 */

const { spawnSync } = require("node:child_process");

const {
  CONFIRM_FLAG,
  DisposableDatabaseError,
  assertDisposableDatabase,
} = require("./assert-disposable-database");

const SEED_SERVICE = "seed-invitation-accept";
const DEFAULT_BASE_URL = "http://localhost:8080";
const OWNER_LOGIN_ID = "test_owner_267";
const WORKER_LOGIN_ID = "test_worker_267";
const TEST_PASSWORD = "Test1234!";

/** 초대 만료는 근무 시작 시각이므로 넉넉히 앞선 날짜를 씁니다. */
const WORK_DATE_OFFSET_DAYS = 7;
const WORK_CASE = {
  title: "[E2E-267] 초대 수락 확인용 근무",
  startTime: "09:00",
  endTime: "18:00",
  breakMinutes: 60,
  breakPaid: false,
  dailyWage: 300000,
};

class FixtureError extends Error {}

function log(message) {
  console.log(`[fixture] ${message}`);
}

/** Fixture SQL을 Compose Service로 실행하고 표준출력을 돌려줍니다. */
function runSeedService(rootDir) {
  const result = spawnSync(
    "docker",
    ["compose", "--profile", "tools", "run", "--rm", SEED_SERVICE],
    { cwd: rootDir, encoding: "utf8" },
  );

  if (result.error) {
    throw new FixtureError(
      `Docker를 실행하지 못했습니다: ${result.error.message}`,
    );
  }
  if (result.status !== 0) {
    throw new FixtureError(
      `Fixture SQL 실행이 실패했습니다.\n${result.stderr || result.stdout}`,
    );
  }

  return result.stdout;
}

/**
 * mysql client의 TSV 출력에서 Header 줄과 값 줄을 찾아 객체로 만듭니다.
 *
 * Compose가 섞어 넣는 진행 메시지를 건너뛰어야 하므로 Header 줄을 기준으로 찾습니다.
 */
function parseSeedSummary(stdout) {
  const lines = stdout.split(/\r?\n/);
  const headerIndex = lines.findIndex((line) =>
    line.startsWith("owner_login_id\t"),
  );

  if (headerIndex < 0 || !lines[headerIndex + 1]) {
    throw new FixtureError(
      "Fixture SQL 요약 출력을 찾지 못했습니다. SQL이 끝까지 실행됐는지 확인하세요.",
    );
  }

  const headers = lines[headerIndex].split("\t");
  const values = lines[headerIndex + 1].split("\t");

  return Object.fromEntries(headers.map((key, index) => [key, values[index]]));
}

/** Set-Cookie를 이름/값만 담는 최소 Jar에 모읍니다. */
function collectCookies(jar, response) {
  for (const cookie of response.headers.getSetCookie()) {
    const [pair] = cookie.split(";");
    const separator = pair.indexOf("=");
    if (separator > 0) {
      jar.set(pair.slice(0, separator).trim(), pair.slice(separator + 1).trim());
    }
  }
}

function cookieHeader(jar) {
  return [...jar].map(([name, value]) => `${name}=${value}`).join("; ");
}

/**
 * Session Cookie와 CSRF Header를 유지하며 API를 호출합니다.
 *
 * 실패 응답의 Body는 오류 Code 확인에 필요하지만, 요청 Header에는 Session과 CSRF Token이
 * 있으므로 어떤 경우에도 그대로 출력하지 않습니다.
 */
async function callApi(baseUrl, jar, path, { method = "GET", body } = {}) {
  const headers = { Accept: "application/json" };

  const cookies = cookieHeader(jar);
  if (cookies) headers.Cookie = cookies;

  const csrfToken = jar.get("XSRF-TOKEN");
  if (csrfToken) headers["X-XSRF-TOKEN"] = decodeURIComponent(csrfToken);

  if (body !== undefined) headers["Content-Type"] = "application/json";

  let response;
  try {
    response = await fetch(`${baseUrl}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch (cause) {
    throw new FixtureError(
      [
        `Backend에 연결하지 못했습니다: ${method} ${path}`,
        `주소 ${baseUrl}에서 Tomcat이 실행 중인지 확인하세요.`,
        "이 Fixture는 실제 OWNER API로 근무와 초대를 만들기 때문에 Backend 기동이 필요합니다.",
      ].join("\n"),
    );
  }

  collectCookies(jar, response);

  if (response.status === 204) return null;

  const text = await response.text();
  if (!response.ok) {
    throw new FixtureError(
      `${method} ${path} 응답이 ${response.status}입니다.\n${text}`,
    );
  }

  return text ? JSON.parse(text).data : null;
}

function workDate(offsetDays) {
  const date = new Date();
  date.setDate(date.getDate() + offsetDays);
  return date.toISOString().slice(0, 10);
}

async function prepare({ rootDir, baseUrl, argv }) {
  assertDisposableDatabase({ rootDir, argv });
  log("대상 DB가 폐기 가능한 로컬 DB임을 확인했습니다.");

  const summary = parseSeedSummary(runSeedService(rootDir));
  log(`사전 데이터를 준비했습니다. workplaceId=${summary.workplace_id}`);

  const jar = new Map();
  await callApi(baseUrl, jar, "/api/auth/csrf");
  await callApi(baseUrl, jar, "/api/auth/login", {
    method: "POST",
    body: {
      loginId: OWNER_LOGIN_ID,
      password: TEST_PASSWORD,
      expectedRole: "OWNER",
    },
  });
  // 인증 시점에 CSRF Token이 회전하므로 Frontend와 같이 로그인 직후 다시 받습니다.
  await callApi(baseUrl, jar, "/api/auth/csrf");
  log(`OWNER ${OWNER_LOGIN_ID}로 로그인했습니다.`);

  const created = await callApi(
    baseUrl,
    jar,
    `/api/workplaces/${summary.workplace_id}/work-cases`,
    {
      method: "POST",
      body: {
        title: WORK_CASE.title,
        workDate: workDate(WORK_DATE_OFFSET_DAYS),
        startTime: WORK_CASE.startTime,
        endTime: WORK_CASE.endTime,
        breakMinutes: WORK_CASE.breakMinutes,
        breakPaid: WORK_CASE.breakPaid,
        dailyWage: WORK_CASE.dailyWage,
      },
    },
  );
  log(`DRAFT 근무를 만들었습니다. workCaseId=${created.workCaseId}`);

  const invitation = await callApi(
    baseUrl,
    jar,
    `/api/work-cases/${created.workCaseId}/invitations`,
    { method: "POST" },
  );
  log("초대를 발급했습니다. 저장소에는 Token Hash만 남습니다.");

  await callApi(baseUrl, jar, "/api/auth/logout", { method: "POST" });

  return { summary, workCaseId: created.workCaseId, invitation };
}

function printResult({ summary, workCaseId, invitation }) {
  console.log(
    [
      "",
      "초대 수락 E2E 준비가 끝났습니다.",
      "",
      `  초대 URL      ${invitation.inviteUrl}`,
      `  만료          ${invitation.expiresAt} (근무 시작 시각)`,
      `  근무          ${WORK_CASE.title} / 일급 ${WORK_CASE.dailyWage}원`,
      `  workCaseId    ${workCaseId}`,
      `  workplaceId   ${summary.workplace_id}`,
      "",
      `  OWNER 로그인  ${OWNER_LOGIN_ID} / ${TEST_PASSWORD}`,
      `  WORKER 로그인 ${WORKER_LOGIN_ID} / ${TEST_PASSWORD}`,
      `  OWNER 지갑    가용 ${summary.owner_available_balance}원 / 잠금 ${summary.owner_locked_balance}원`,
      "",
      "초대 URL은 이 출력에만 있습니다. 파일이나 이슈에 붙여넣지 마세요.",
      "로그아웃 상태의 Browser로 위 URL을 열어 E2E를 시작하세요.",
      "",
    ].join("\n"),
  );
}

async function main(argv = process.argv.slice(2)) {
  try {
    printResult(
      await prepare({
        rootDir: process.cwd(),
        baseUrl: process.env.GIGHUB_API_BASE_URL || DEFAULT_BASE_URL,
        argv,
      }),
    );
    return 0;
  } catch (error) {
    if (error instanceof DisposableDatabaseError || error instanceof FixtureError) {
      console.error(`[fixture] ${error.message}`);
      return 1;
    }
    throw error;
  }
}

if (require.main === module) {
  main().then((code) => {
    process.exitCode = code;
  });
}

module.exports = {
  CONFIRM_FLAG,
  FixtureError,
  main,
  parseSeedSummary,
  prepare,
  workDate,
};
