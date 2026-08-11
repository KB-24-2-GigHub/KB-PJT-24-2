#!/usr/bin/env node

/**
 * 합성 Fixture 명령이 폐기 가능한 로컬 DB에서만 실행되도록 막는 안전장치입니다.
 *
 * 판단에 필요한 값을 하나라도 읽지 못하면 통과시키지 않는 fail-closed 규칙을 씁니다.
 * 공유·Staging·Production DB를 목록으로 골라내는 대신, 로컬 Compose DB임이 증명된
 * 경우에만 통과시킵니다.
 */

const fs = require("node:fs");
const path = require("node:path");

const ENV_FILE = ".env";
const CONFIRM_FLAG = "--confirm-local";
const COMPOSE_FLAG = "--compose-internal";

/** Host에서 Compose가 노출한 Port로 붙을 때 허용하는 주소입니다. */
const LOOPBACK_HOSTS = new Set(["127.0.0.1", "localhost", "::1"]);
const DEFAULT_HOST = "127.0.0.1";

/** Compose 네트워크 안에서 접속할 때의 서비스 이름과 내부 Port입니다. */
const COMPOSE_SERVICE_HOST = "db";
const COMPOSE_SERVICE_PORT = 3306;

/** 자동화 환경에서는 로컬 개발 DB라는 근거가 될 수 없으므로 실행을 막습니다. */
const AUTOMATION_VARIABLES = ["CI", "GITHUB_ACTIONS", "BUILD_NUMBER"];

/** 로컬이 아님을 사용자가 직접 선언한 경우 통과시키지 않습니다. */
const ENVIRONMENT_VARIABLE = "GIGHUB_DB_ENVIRONMENT";
const LOCAL_ENVIRONMENT = "local";

class DisposableDatabaseError extends Error {
  constructor(reasons) {
    super(
      [
        "폐기 가능한 로컬 DB임을 확인하지 못해 실행을 중단합니다.",
        ...reasons.map((reason) => `- ${reason}`),
      ].join("\n"),
    );
    this.name = "DisposableDatabaseError";
    this.reasons = reasons;
  }
}

/**
 * `.env` 형식의 문자열을 key/value로 읽습니다.
 *
 * 값에 담긴 `=`는 그대로 두어야 하므로 첫 `=`에서만 자릅니다.
 */
function parseEnvFile(contents) {
  const values = {};

  for (const line of contents.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#")) continue;

    const separator = trimmed.indexOf("=");
    if (separator <= 0) continue;

    values[trimmed.slice(0, separator).trim()] = trimmed
      .slice(separator + 1)
      .trim();
  }

  return values;
}

/** 저장소 루트의 `.env`를 읽습니다. 파일이 없으면 판단 근거가 없으므로 실패합니다. */
function readEnvFile(rootDir) {
  const envPath = path.join(rootDir, ENV_FILE);

  if (!fs.existsSync(envPath)) {
    throw new DisposableDatabaseError([
      `${ENV_FILE} 파일이 없어 대상 DB를 확인할 수 없습니다.`,
    ]);
  }

  return parseEnvFile(fs.readFileSync(envPath, "utf8"));
}

/**
 * `.env` 값과 접속 방식으로 실제 접속 대상을 만듭니다.
 *
 * Compose 네트워크 안에서는 서비스 이름과 내부 Port를 쓰고, Host에서 직접 붙을 때는
 * Compose가 loopback에만 노출한 Port를 씁니다.
 */
function resolveTarget(
  env,
  { composeInternal = false, processEnv = process.env } = {},
) {
  const host = processEnv.MYSQL_HOST || env.MYSQL_HOST;

  if (composeInternal) {
    return {
      host: host || COMPOSE_SERVICE_HOST,
      port: COMPOSE_SERVICE_PORT,
      database: env.MYSQL_DATABASE,
      composeInternal: true,
    };
  }

  return {
    host: host || DEFAULT_HOST,
    port: Number.parseInt(env.MYSQL_PORT, 10),
    database: env.MYSQL_DATABASE,
    composeInternal: false,
  };
}

/**
 * 통과를 막아야 하는 이유를 모두 모읍니다.
 *
 * 첫 위반에서 멈추지 않아야 사용자가 한 번에 모든 문제를 고칠 수 있습니다.
 */
function collectViolations({ env, target, confirmed, processEnv }) {
  const reasons = [];

  if (!confirmed) {
    reasons.push(
      `로컬 DB 실행을 명시하는 ${CONFIRM_FLAG} 옵션이 없습니다.`,
    );
  }

  if (target.composeInternal) {
    if (target.host !== COMPOSE_SERVICE_HOST) {
      reasons.push(
        `Compose 내부 접속 Host가 ${COMPOSE_SERVICE_HOST}가 아닙니다.`,
      );
    }
    if (target.port !== COMPOSE_SERVICE_PORT) {
      reasons.push(
        `Compose 내부 접속 Port가 ${COMPOSE_SERVICE_PORT}가 아닙니다.`,
      );
    }
  } else {
    if (!LOOPBACK_HOSTS.has(target.host)) {
      reasons.push(`대상 Host ${target.host}가 loopback 주소가 아닙니다.`);
    }
    if (!env.MYSQL_PORT) {
      reasons.push(`${ENV_FILE}에 MYSQL_PORT가 없습니다.`);
    } else if (!Number.isInteger(target.port) || target.port <= 0) {
      reasons.push(`MYSQL_PORT 값 ${env.MYSQL_PORT}를 Port로 읽을 수 없습니다.`);
    }
  }

  if (!target.database) {
    reasons.push(`${ENV_FILE}에 MYSQL_DATABASE가 없습니다.`);
  }

  for (const name of AUTOMATION_VARIABLES) {
    if (processEnv[name]) {
      reasons.push(
        `자동화 환경 변수 ${name}가 설정되어 있어 로컬 실행으로 볼 수 없습니다.`,
      );
    }
  }

  const declaredEnvironment = processEnv[ENVIRONMENT_VARIABLE];
  if (declaredEnvironment && declaredEnvironment !== LOCAL_ENVIRONMENT) {
    reasons.push(
      `${ENVIRONMENT_VARIABLE}가 ${declaredEnvironment}로 선언되어 있습니다.`,
    );
  }

  return reasons;
}

/**
 * 대상이 폐기 가능한 로컬 DB임을 확인하고 접속 정보를 돌려줍니다.
 *
 * 확인하지 못하면 {@link DisposableDatabaseError}를 던집니다.
 */
function assertDisposableDatabase({
  rootDir = process.cwd(),
  argv = process.argv.slice(2),
  processEnv = process.env,
} = {}) {
  const env = readEnvFile(rootDir);
  const target = resolveTarget(env, {
    composeInternal: argv.includes(COMPOSE_FLAG),
    processEnv,
  });

  const reasons = collectViolations({
    env,
    target,
    confirmed: argv.includes(CONFIRM_FLAG),
    processEnv,
  });

  if (reasons.length > 0) {
    throw new DisposableDatabaseError(reasons);
  }

  return target;
}

function main(argv = process.argv.slice(2)) {
  try {
    const target = assertDisposableDatabase({ argv });
    console.log(
      `[fixture-guard] ok: ${target.host}:${target.port}/${target.database}`,
    );
    return 0;
  } catch (error) {
    if (!(error instanceof DisposableDatabaseError)) throw error;
    console.error(`[fixture-guard] ${error.message}`);
    return 1;
  }
}

if (require.main === module) {
  process.exitCode = main();
}

module.exports = {
  CONFIRM_FLAG,
  COMPOSE_FLAG,
  DisposableDatabaseError,
  assertDisposableDatabase,
  collectViolations,
  main,
  parseEnvFile,
  readEnvFile,
  resolveTarget,
};
