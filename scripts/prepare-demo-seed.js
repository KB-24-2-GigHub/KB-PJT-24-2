#!/usr/bin/env node

/**
 * 통합 시연 SEED를 전체 초기화 뒤 적용하고, 기능 시나리오는 실제 API 흐름까지 완성합니다.
 *
 * 로컬 실행은 폐기 가능한 Compose DB인지 확인하고, 운영 실행은 GitHub Actions에서 SQL 적용이
 * 끝난 뒤에만 API 단계를 수행합니다. 두 경로 모두 별도의 전체 삭제 확인값을 요구합니다.
 */

const fs = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");

const {
  CONFIRM_FLAG,
  DisposableDatabaseError,
  assertDisposableDatabase,
  parseEnvFile,
} = require("./assert-disposable-database");

const DEFAULT_BASE_URL = "http://localhost:8080";
const RESET_CONFIRM_FLAG = "--confirm-reset-all-data";
const LOCAL_CONFIRM_ARGUMENT = "confirm-local";
const PRODUCTION_CONFIRM_FLAG = "--confirm-production-reset";
const SKIP_SQL_FLAG = "--skip-sql";
const RESET_CONFIRM_VALUE = "reset-all-data";
const SEED_SERVICE = "seed-demo";
const PASSWORD = "Demo1234!";
const OWNER_LOGIN_ID = "gigsajang";
const PRIMARY_WORKPLACE_NAME = "냠냠과자점 1호점";
const HEALTH_CERTIFICATE_PATH = path.join(
  "output",
  "pdf",
  "gig-hub-demo-health-certificate.pdf",
);

const SCENARIOS = Object.freeze({
  functional: {
    file: "demo-functional.sql",
    description: "기능 통합 점검",
    apiSetup: true,
  },
  "video-01-onboarding": {
    file: "demo-video-01-onboarding.sql",
    description: "영상 01 · 첫 로그인과 사업장 등록",
  },
  "video-02-check-in": {
    file: "demo-video-02-check-in.sql",
    description: "영상 02 · 계약 완료와 출근/노쇼 대기",
  },
  "video-03-check-out": {
    file: "demo-video-03-check-out.sql",
    description: "영상 03 · 정상/지각 퇴근과 노쇼 비교",
  },
  "video-04-three-years": {
    file: "demo-video-04-three-years.sql",
    description: "영상 04 · 3년 후 3개 사업장",
  },
});

class DemoSeedError extends Error {}

function log(message) {
  console.log(`[demo-seed] ${message}`);
}

function parseOptions(argv = process.argv.slice(2)) {
  const scenarioKey = argv[0];
  if (!Object.hasOwn(SCENARIOS, scenarioKey)) {
    throw new DemoSeedError(
      `시나리오를 골라 주세요: ${Object.keys(SCENARIOS).join(", ")}`,
    );
  }
  const scenario = SCENARIOS[scenarioKey];

  const flags = argv.slice(1);
  const allowed = new Set([
    CONFIRM_FLAG,
    RESET_CONFIRM_FLAG,
    LOCAL_CONFIRM_ARGUMENT,
    RESET_CONFIRM_VALUE,
    PRODUCTION_CONFIRM_FLAG,
    SKIP_SQL_FLAG,
  ]);
  const unknown = flags.find((flag) => !allowed.has(flag));
  if (unknown) throw new DemoSeedError(`알 수 없는 옵션입니다: ${unknown}`);

  return {
    scenarioKey,
    scenario,
    skipSql: flags.includes(SKIP_SQL_FLAG),
    argv,
  };
}

/** npm 위치 인자와 node 직접 실행 Flag를 같은 로컬 안전 확인값으로 정규화합니다. */
function resolveLocalConfirmation(argv) {
  const localConfirmed =
    argv.includes(CONFIRM_FLAG) || argv.includes(LOCAL_CONFIRM_ARGUMENT);
  const resetConfirmed =
    argv.includes(RESET_CONFIRM_FLAG) || argv.includes(RESET_CONFIRM_VALUE);

  return {
    localConfirmed,
    resetConfirmed,
    guardArgv: localConfirmed ? [...argv, CONFIRM_FLAG] : argv,
  };
}

function assertLocalResetConfirmation(argv) {
  const confirmation = resolveLocalConfirmation(argv);
  if (!confirmation.resetConfirmed) {
    throw new DemoSeedError(
      `${RESET_CONFIRM_FLAG} 또는 ${RESET_CONFIRM_VALUE} 확인값이 없습니다.`,
    );
  }
  return confirmation;
}

/** mysql TSV 출력에서 Compose 진행 문구를 건너뛰고 SEED 요약만 읽습니다. */
function parseSeedSummary(stdout) {
  const lines = stdout.split(/\r?\n/);
  const headerIndex = lines.findIndex((line) =>
    line.startsWith("scenario_key\t"),
  );
  if (headerIndex < 0 || !lines[headerIndex + 1]) {
    throw new DemoSeedError(
      "SEED 요약 출력을 찾지 못했습니다. SQL이 끝까지 실행됐는지 확인하세요.",
    );
  }

  const headers = lines[headerIndex].split("\t");
  const values = lines[headerIndex + 1].split("\t");
  return Object.fromEntries(headers.map((key, index) => [key, values[index]]));
}

function runSeedService(rootDir, seedFile) {
  const result = spawnSync(
    "docker",
    [
      "compose",
      "--profile",
      "tools",
      "run",
      "--rm",
      "-e",
      `SEED_FILE=${seedFile}`,
      "-e",
      `DEMO_RESET_CONFIRM=${RESET_CONFIRM_VALUE}`,
      SEED_SERVICE,
    ],
    { cwd: rootDir, encoding: "utf8" },
  );

  if (result.error) {
    throw new DemoSeedError(`Docker를 실행하지 못했습니다: ${result.error.message}`);
  }
  if (result.status !== 0) {
    throw new DemoSeedError(
      `SEED SQL 실행이 실패했습니다.\n${result.stderr || result.stdout}`,
    );
  }
  return result.stdout;
}

/**
 * DB 초기화와 파일 저장소 상태가 갈라지지 않도록 로컬 문서도 비웁니다.
 * 설정값이 저장소의 정확한 local-data/documents 경로가 아니면 삭제하지 않습니다.
 */
function clearLocalDocumentStorage({ rootDir, configPath } = {}) {
  const expected = path.resolve(rootDir, "local-data", "documents");
  const propertiesPath =
    configPath || path.join(rootDir, "backend", "config", "database-local.properties");
  if (!fs.existsSync(propertiesPath)) {
    throw new DemoSeedError(`문서 저장소 설정 파일이 없습니다: ${propertiesPath}`);
  }

  const configuredValue = parseEnvFile(
    fs.readFileSync(propertiesPath, "utf8"),
  )["document.storage.base-path"];
  if (!configuredValue) {
    throw new DemoSeedError("document.storage.base-path 설정이 없습니다.");
  }

  const configured = path.resolve(rootDir, configuredValue);
  if (configured !== expected) {
    throw new DemoSeedError(
      `문서 삭제 경로가 안전한 로컬 경로와 다릅니다.\n설정: ${configured}\n허용: ${expected}`,
    );
  }

  fs.mkdirSync(expected, { recursive: true });
  for (const entry of fs.readdirSync(expected)) {
    fs.rmSync(path.join(expected, entry), { recursive: true, force: true });
  }
  return expected;
}

function assertProductionReset({ argv, processEnv = process.env }) {
  const reasons = [];
  if (!argv.includes(SKIP_SQL_FLAG)) reasons.push(`${SKIP_SQL_FLAG} 옵션이 없습니다.`);
  if (!argv.includes(PRODUCTION_CONFIRM_FLAG)) {
    reasons.push(`${PRODUCTION_CONFIRM_FLAG} 옵션이 없습니다.`);
  }
  if (processEnv.CI !== "true" || processEnv.GITHUB_ACTIONS !== "true") {
    reasons.push("GitHub Actions 환경이 아닙니다.");
  }
  if (processEnv.DEMO_SEED_CONFIRM !== RESET_CONFIRM_VALUE) {
    reasons.push(`DEMO_SEED_CONFIRM=${RESET_CONFIRM_VALUE} 확인값이 없습니다.`);
  }
  if (reasons.length) {
    throw new DemoSeedError(
      ["운영 전체 초기화 후속 실행임을 확인하지 못했습니다.", ...reasons.map((r) => `- ${r}`)].join("\n"),
    );
  }
}

function collectCookies(jar, response) {
  const cookies = response.headers.getSetCookie
    ? response.headers.getSetCookie()
    : response.headers.get("set-cookie")
      ? [response.headers.get("set-cookie")]
      : [];
  for (const cookie of cookies) {
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

async function callApi(
  baseUrl,
  jar,
  apiPath,
  { method = "GET", body, rawBody, headers: customHeaders = {} } = {},
) {
  const headers = { Accept: "application/json", ...customHeaders };
  const cookies = cookieHeader(jar);
  if (cookies) headers.Cookie = cookies;
  const csrfToken = jar.get("XSRF-TOKEN");
  if (csrfToken) headers["X-XSRF-TOKEN"] = decodeURIComponent(csrfToken);
  if (body !== undefined) headers["Content-Type"] = "application/json";

  let response;
  try {
    response = await fetch(`${baseUrl}${apiPath}`, {
      method,
      headers,
      body:
        rawBody !== undefined
          ? rawBody
          : body === undefined
            ? undefined
            : JSON.stringify(body),
    });
  } catch (cause) {
    throw new DemoSeedError(
      `${baseUrl}에 연결하지 못했습니다: ${method} ${apiPath}\n${cause.message}`,
    );
  }

  collectCookies(jar, response);
  if (!response.ok) {
    const text = await response.text();
    throw new DemoSeedError(`${method} ${apiPath} 응답이 ${response.status}입니다.\n${text}`);
  }
  if (response.status === 204) return null;
  const text = await response.text();
  return text ? JSON.parse(text).data : null;
}

async function login(baseUrl, jar, loginId, expectedRole) {
  await callApi(baseUrl, jar, "/api/auth/csrf");
  await callApi(baseUrl, jar, "/api/auth/login", {
    method: "POST",
    body: { loginId, password: PASSWORD, expectedRole },
  });
  // 로그인 때 CSRF Token이 회전하므로 실제 Frontend와 같이 다시 받습니다.
  await callApi(baseUrl, jar, "/api/auth/csrf");
}

async function logout(baseUrl, jar) {
  await callApi(baseUrl, jar, "/api/auth/logout", { method: "POST" });
  jar.clear();
}

function seoulDate(offsetDays = 0, now = new Date()) {
  const adjusted = new Date(now.getTime() + offsetDays * 24 * 60 * 60 * 1000);
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(adjusted);
  const value = Object.fromEntries(parts.map(({ type, value: part }) => [type, part]));
  return `${value.year}-${value.month}-${value.day}`;
}

function invitationToken(inviteUrl) {
  let parsed;
  try {
    parsed = new URL(inviteUrl);
  } catch {
    throw new DemoSeedError("발급된 초대 URL 형식이 올바르지 않습니다.");
  }
  const token = parsed.pathname.split("/").filter(Boolean).at(-1);
  if (!token) throw new DemoSeedError("초대 URL에서 Token을 찾지 못했습니다.");
  return token;
}

async function createInvitation(baseUrl, jar, workplaceId, workCase) {
  const created = await callApi(
    baseUrl,
    jar,
    `/api/workplaces/${workplaceId}/work-cases`,
    { method: "POST", body: workCase },
  );
  const invitation = await callApi(
    baseUrl,
    jar,
    `/api/work-cases/${created.workCaseId}/invitations`,
    { method: "POST" },
  );
  return { workCaseId: created.workCaseId, invitation };
}

function pageContent(page) {
  return page && Array.isArray(page.content) ? page.content : [];
}

async function completeFunctionalApiSetup({ rootDir, baseUrl }) {
  const jar = new Map();
  await login(baseUrl, jar, OWNER_LOGIN_ID, "OWNER");
  const initialWallet = await callApi(baseUrl, jar, "/api/wallet");

  const workplaces = await callApi(baseUrl, jar, "/api/workplaces?page=0&size=100");
  const workplace = pageContent(workplaces).find(
    ({ name }) => name === PRIMARY_WORKPLACE_NAME,
  );
  if (!workplace) throw new DemoSeedError(`${PRIMARY_WORKPLACE_NAME}을 찾지 못했습니다.`);

  const acceptedWage = 100000;
  const accepted = await createInvitation(baseUrl, jar, workplace.workplaceId, {
    title: "[FUNCTION-API] 김성실 계약·문서 확인",
    workDate: seoulDate(1),
    startTime: "09:00",
    endTime: "13:00",
    breakMinutes: 30,
    breakPaid: false,
    dailyWage: acceptedWage,
  });
  const pending = await createInvitation(baseUrl, jar, workplace.workplaceId, {
    title: "[FUNCTION-API] 이수면 초대 수락 확인",
    workDate: seoulDate(2),
    startTime: "10:00",
    endTime: "14:00",
    breakMinutes: 30,
    breakPaid: false,
    dailyWage: 100000,
  });
  await logout(baseUrl, jar);

  await login(baseUrl, jar, "hardworker", "WORKER");
  const token = invitationToken(accepted.invitation.inviteUrl);
  await callApi(baseUrl, jar, `/api/invitations/${token}`);
  const acceptance = await callApi(baseUrl, jar, `/api/invitations/${token}/accept`, {
    method: "POST",
    headers: { "Idempotency-Key": `demo-accept-${accepted.workCaseId}` },
  });
  if (acceptance.escrowStatus !== "HELD") {
    throw new DemoSeedError("김성실 초대 수락 뒤 임금이 예치되지 않았습니다.");
  }

  const certificatePath = path.join(rootDir, HEALTH_CERTIFICATE_PATH);
  if (!fs.existsSync(certificatePath)) {
    throw new DemoSeedError(`시연용 보건증 PDF가 없습니다: ${certificatePath}`);
  }
  const form = new FormData();
  form.append(
    "file",
    new Blob([fs.readFileSync(certificatePath)], { type: "application/pdf" }),
    path.basename(certificatePath),
  );
  const registered = await callApi(
    baseUrl,
    jar,
    `/api/documents?docType=HEALTH_CERTIFICATE&issuedDate=${seoulDate()}`,
    { method: "POST", rawBody: form },
  );
  await callApi(baseUrl, jar, `/api/documents/${registered.documentId}/shares`, {
    method: "POST",
    body: { workplaceId: workplace.workplaceId },
  });
  await logout(baseUrl, jar);

  await login(baseUrl, jar, OWNER_LOGIN_ID, "OWNER");
  // 인증 응답이 Cookie를 회전해도 Jar 갱신 순서가 결정적이도록 순차 호출합니다.
  const notifications = await callApi(
    baseUrl,
    jar,
    "/api/notifications?page=0&size=100",
  );
  const documents = await callApi(baseUrl, jar, "/api/documents?page=0&size=100");
  const wallet = await callApi(baseUrl, jar, "/api/wallet");
  const notificationTypes = new Set(pageContent(notifications).map(({ notiType }) => notiType));
  for (const required of ["WORK_CASE_CONFIRMED", "ESCROW_HELD", "DOC_SHARED"]) {
    if (!notificationTypes.has(required)) {
      throw new DemoSeedError(`OWNER 알림에서 ${required}를 확인하지 못했습니다.`);
    }
  }
  const documentTypes = new Set(pageContent(documents).map(({ docType }) => docType));
  for (const required of ["EMPLOYMENT_CONTRACT", "HEALTH_CERTIFICATE"]) {
    if (!documentTypes.has(required)) {
      throw new DemoSeedError(`OWNER 문서함에서 ${required}를 확인하지 못했습니다.`);
    }
  }
  const expectedAvailable = initialWallet.availableBalance - acceptedWage;
  const expectedLocked = initialWallet.lockedBalance + acceptedWage;
  if (
    wallet.availableBalance !== expectedAvailable ||
    wallet.lockedBalance !== expectedLocked
  ) {
    throw new DemoSeedError(
      `API 준비 후 OWNER 지갑이 예상과 다릅니다: 가용 ${wallet.availableBalance}, 예치 ${wallet.lockedBalance}`,
    );
  }
  await logout(baseUrl, jar);

  return {
    workplaceId: workplace.workplaceId,
    acceptedWorkCaseId: accepted.workCaseId,
    pendingWorkCaseId: pending.workCaseId,
    pendingInvitation: pending.invitation,
    healthCertificateDocumentId: registered.documentId,
  };
}

async function prepare({ rootDir, baseUrl, argv, processEnv = process.env }) {
  const options = parseOptions(argv);
  let summary = null;

  if (options.skipSql) {
    assertProductionReset({ argv, processEnv });
    log("GitHub Actions의 운영 전체 초기화 후속 실행임을 확인했습니다.");
  } else {
    // npm 11은 `npm run ... -- --confirm-local`도 알 수 없는 npm config로 가로챕니다.
    // npm 경로에서는 위치 인자를 받고, 직접 node 실행의 기존 Flag도 계속 지원합니다.
    const confirmation = assertLocalResetConfirmation(argv);

    assertDisposableDatabase({
      rootDir,
      argv: confirmation.guardArgv,
      processEnv,
    });
    summary = parseSeedSummary(runSeedService(rootDir, options.scenario.file));
    if (summary.scenario_key !== options.scenarioKey) {
      throw new DemoSeedError("요청한 시나리오와 SQL 결과가 일치하지 않습니다.");
    }
    const cleared = clearLocalDocumentStorage({ rootDir });
    log(`로컬 문서 저장소를 비웠습니다: ${cleared}`);
  }

  const apiResult = options.scenario.apiSetup
    ? await completeFunctionalApiSetup({ rootDir, baseUrl })
    : null;
  return { options, summary, apiResult };
}

function shouldPrintPendingInvitation(processEnv = process.env) {
  return processEnv.GITHUB_ACTIONS !== "true";
}

function printResult({ options, summary, apiResult }, processEnv = process.env) {
  console.log(`\n${options.scenario.description} SEED 준비가 끝났습니다.`);
  if (summary) console.log(`  기준 시각       ${summary.seed_now}`);
  console.log(`  OWNER 긱사장     ${OWNER_LOGIN_ID} / ${PASSWORD}`);
  console.log(`  A 김성실         hardworker / ${PASSWORD}`);
  console.log(`  B 이수면         ilovesleep / ${PASSWORD}`);
  console.log(`  C 박잠수         submarine / ${PASSWORD}`);
  if (apiResult) {
    console.log(`  사업장 ID        ${apiResult.workplaceId}`);
    console.log(`  김성실 근무 ID   ${apiResult.acceptedWorkCaseId}`);
    console.log(`  보건증 문서 ID   ${apiResult.healthCertificateDocumentId}`);
    if (shouldPrintPendingInvitation(processEnv)) {
      console.log(`  이수면 초대 URL  ${apiResult.pendingInvitation.inviteUrl}`);
      console.log("  초대 URL은 이 출력 외 파일이나 이슈에 저장하지 마세요.");
    } else {
      console.log("  이수면 초대 URL은 Actions 로그에 출력하지 않았습니다.");
    }
  }
  if (options.scenarioKey === "video-02-check-in" || options.scenarioKey === "functional") {
    console.log("  박잠수 근무는 앱 실행 후 다음 60초 Scheduler 주기에서 NO_SHOW가 됩니다.");
    if (summary?.late_no_show_at) {
      console.log(`  이수면 출근 마감 ${summary.late_no_show_at} (KST, 이후에는 SEED 재실행)`);
    }
  }
  if (options.scenarioKey === "video-03-check-out") {
    console.log("  이수면의 30분 지각 차감 지급은 #424 구현 후 검증합니다.");
  }
  console.log();
}

async function main(argv = process.argv.slice(2)) {
  try {
    const result = await prepare({
      rootDir: process.cwd(),
      baseUrl: process.env.GIGHUB_API_BASE_URL || DEFAULT_BASE_URL,
      argv,
    });
    printResult(result);
    return 0;
  } catch (error) {
    if (error instanceof DisposableDatabaseError || error instanceof DemoSeedError) {
      console.error(`[demo-seed] ${error.message}`);
      return 1;
    }
    throw error;
  }
}

if (require.main === module) {
  main().then(
    (code) => {
      process.exitCode = code;
    },
    (error) => {
      const message = error instanceof Error ? error.message : String(error);
      console.error(`[demo-seed] 예상하지 못한 오류입니다: ${message}`);
      process.exitCode = 1;
    },
  );
}

module.exports = {
  DemoSeedError,
  LOCAL_CONFIRM_ARGUMENT,
  PRODUCTION_CONFIRM_FLAG,
  RESET_CONFIRM_FLAG,
  RESET_CONFIRM_VALUE,
  SCENARIOS,
  SKIP_SQL_FLAG,
  assertLocalResetConfirmation,
  assertProductionReset,
  clearLocalDocumentStorage,
  invitationToken,
  main,
  parseOptions,
  parseSeedSummary,
  resolveLocalConfirmation,
  seoulDate,
  shouldPrintPendingInvitation,
};
