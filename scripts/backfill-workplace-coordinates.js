#!/usr/bin/env node

/**
 * 좌표가 비어 있는 사업장을 각자의 도로명주소로 채웁니다(#351).
 *
 * SPEC-343-01 이후 신규 등록은 서버가 주소를 변환해 좌표를 채우지만, 그 이전에 등록된
 * 사업장은 좌표가 NULL로 남아 QR 출퇴근의 100m 반경 검증이 성립하지 않습니다. 이 명령은
 * 남은 행을 같은 규칙으로 메웁니다.
 *
 * 모든 사업장을 한 좌표로 맞추지 않습니다. 사업장마다 자기 주소에서 나온 좌표를 써야
 * 반경 판정 기준점이 실제 위치와 맞습니다.
 *
 * 기본 동작은 조회와 변환까지만 하는 예행입니다. 실제 UPDATE는 --apply를 줄 때만 하며,
 * 그때는 폐기 가능한 로컬 DB임을 확인합니다. 공유·운영 DB 반영은 이 명령의 범위가 아니라
 * 관리자 승인 절차에서 다룹니다.
 */

const fs = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");

const {
  DisposableDatabaseError,
  assertDisposableDatabase,
  readEnvFile,
} = require("./assert-disposable-database");

const APPLY_FLAG = "--apply";
const CONFIG_FLAG = "--config";
const DEFAULT_CONFIG_PATH = path.join("backend", "config", "database-local.properties");
const KEY_PROPERTY = "kakao.local.rest-api-key";

const SEARCH_URL = "https://dapi.kakao.com/v2/local/search/address.json";
const REQUEST_TIMEOUT_MS = 5000;

/** workplaces.latitude/longitude는 decimal(10,7)이라 저장 시 반올림됩니다. */
const COORDINATE_SCALE = 7;

/** 연속 호출이 외부 서비스 제한에 걸리지 않도록 각 요청 사이에 두는 간격입니다. */
const REQUEST_INTERVAL_MS = 200;

const SELECT_HEADER = "workplace_id\troad_address";

/** 좌표를 채우지 못한 이유입니다. 확정 실패와 일시 실패를 섞지 않습니다. */
const FAILURE_ADDRESS = "ADDRESS_NOT_RESOLVABLE";
const FAILURE_TEMPORARY = "TEMPORARILY_UNAVAILABLE";

class BackfillError extends Error {}

function log(message) {
  console.log(`[backfill] ${message}`);
}

/** `key=value` properties에서 값 하나를 읽습니다. 값에 담긴 `=`는 그대로 둡니다. */
function readProperty(contents, key) {
  for (const line of contents.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#")) continue;

    const separator = trimmed.indexOf("=");
    if (separator <= 0) continue;
    if (trimmed.slice(0, separator).trim() !== key) continue;

    return trimmed.slice(separator + 1).trim();
  }

  return "";
}

/**
 * 외부 properties에서 주소 변환 키를 읽습니다.
 *
 * 키를 저장소에 두지 않는 기존 경계를 그대로 씁니다. 값은 어떤 출력에도 남기지 않습니다.
 */
function readGeocodingKey(rootDir, configPath) {
  const resolved = path.isAbsolute(configPath)
    ? configPath
    : path.join(rootDir, configPath);

  if (!fs.existsSync(resolved)) {
    throw new BackfillError(
      `주소 변환 키 설정 파일을 찾지 못했습니다: ${resolved}`,
    );
  }

  const key = readProperty(fs.readFileSync(resolved, "utf8"), KEY_PROPERTY);
  if (!key) {
    throw new BackfillError(`${resolved}에 ${KEY_PROPERTY}가 없습니다.`);
  }

  return key;
}

/** Compose의 db Service에서 SQL 한 문장을 실행하고 표준출력을 돌려줍니다. */
function runSql(rootDir, env, sql) {
  const result = spawnSync(
    "docker",
    [
      "compose",
      "exec",
      "-T",
      "-e",
      `MYSQL_PWD=${env.MYSQL_PASSWORD}`,
      "db",
      "mysql",
      "-u",
      env.MYSQL_USER,
      "-D",
      env.MYSQL_DATABASE,
      // 기본 Client 문자셋은 한글을 '?'로 바꿔 내보냅니다. 주소가 깨진 채 변환되면
      // 멀쩡한 주소가 전부 확정 실패로 보고되므로 연결 문자셋을 명시합니다.
      "--default-character-set=utf8mb4",
      "--batch",
      "--raw",
      "-e",
      sql,
    ],
    { cwd: rootDir, encoding: "utf8" },
  );

  if (result.error) {
    throw new BackfillError(
      `Docker를 실행하지 못했습니다: ${result.error.message}`,
    );
  }
  if (result.status !== 0) {
    throw new BackfillError(
      `SQL 실행이 실패했습니다.\n${result.stderr || result.stdout}`,
    );
  }

  return result.stdout;
}

/**
 * mysql `--batch` TSV 출력에서 Header 줄을 찾아 행 객체로 만듭니다.
 *
 * Compose가 섞어 넣는 진행 메시지를 건너뛰어야 하므로 Header 줄을 기준으로 찾습니다.
 * Header가 없으면 대상이 0건인 정상 상태이므로 빈 목록입니다.
 */
function parsePendingRows(stdout) {
  const lines = stdout.split(/\r?\n/);
  const headerIndex = lines.indexOf(SELECT_HEADER);
  if (headerIndex < 0) return [];

  const rows = [];
  for (const line of lines.slice(headerIndex + 1)) {
    if (!line.trim()) continue;

    const [workplaceId, ...rest] = line.split("\t");
    if (!/^\d+$/.test(workplaceId)) continue;

    rows.push({ workplaceId, roadAddress: rest.join("\t") });
  }

  return rows;
}

/** 좌표가 비어 있는 사업장을 식별자 순으로 읽습니다. */
function selectPendingWorkplaces(rootDir, env) {
  const stdout = runSql(
    rootDir,
    env,
    "SELECT id AS workplace_id, road_address FROM workplaces"
      + " WHERE latitude IS NULL ORDER BY id",
  );

  return parsePendingRows(stdout);
}

/** 저장 정밀도로 미리 반올림해, 예행 출력이 실제 저장될 값과 같게 합니다. */
function toStoredCoordinate(value) {
  return Number.parseFloat(value).toFixed(COORDINATE_SCALE);
}

/**
 * Kakao 응답에서 좌표를 확정합니다.
 *
 * 후보가 정확히 하나일 때만 인정합니다. 0건은 주소를 확인할 수 없는 경우이고 복수는 어느
 * 위치인지 정할 수 없는 경우입니다. 첫 결과를 고르면 잘못된 기준점이 조용히 저장돼
 * 출퇴근 반경 판정을 계속 어긋나게 합니다. 백엔드
 * KakaoLocalAddressGeocoder와 같은 규칙입니다.
 */
function resolveDocuments(documents) {
  if (!Array.isArray(documents)) {
    return { failure: FAILURE_TEMPORARY, detail: "documents 없음" };
  }
  if (documents.length !== 1) {
    return { failure: FAILURE_ADDRESS, detail: `후보 ${documents.length}건` };
  }

  const [document] = documents;
  const latitude = document?.y;
  const longitude = document?.x;
  if (!latitude || !longitude || Number.isNaN(Number(latitude)) || Number.isNaN(Number(longitude))) {
    return { failure: FAILURE_TEMPORARY, detail: "좌표 해석 불가" };
  }

  return {
    latitude: toStoredCoordinate(latitude),
    longitude: toStoredCoordinate(longitude),
  };
}

/** 도로명주소 한 건을 좌표로 바꿉니다. 실패는 원인을 구분해 돌려줍니다. */
async function geocode(roadAddress, key, { fetchImpl = fetch } = {}) {
  if (!roadAddress || !roadAddress.trim()) {
    return { failure: FAILURE_ADDRESS, detail: "주소 없음" };
  }

  const url = `${SEARCH_URL}?query=${encodeURIComponent(roadAddress)}`;

  let response;
  try {
    response = await fetchImpl(url, {
      headers: { Authorization: `KakaoAK ${key}` },
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
  } catch (error) {
    return { failure: FAILURE_TEMPORARY, detail: `호출 실패: ${error.message}` };
  }

  if (!response.ok) {
    // 상태 코드만 남깁니다. 본문에는 연동 구성이 드러날 수 있습니다.
    return { failure: FAILURE_TEMPORARY, detail: `외부 응답 ${response.status}` };
  }

  let body;
  try {
    body = await response.json();
  } catch (error) {
    return { failure: FAILURE_TEMPORARY, detail: "본문 해석 불가" };
  }

  return resolveDocuments(body?.documents);
}

/** SQL 문자열 리터럴에 값을 넣기 전에 따옴표와 역슬래시를 막습니다. */
function quote(value) {
  return `'${String(value).replace(/\\/g, "\\\\").replace(/'/g, "\\'")}'`;
}

/**
 * 좌표가 아직 비어 있는 행만 채웁니다.
 *
 * `latitude IS NULL` 조건을 UPDATE에 그대로 두어, 이 명령이 도는 사이 다른 경로가 먼저
 * 확정했더라도 그 값을 덮어쓰지 않습니다.
 */
function applyCoordinates(rootDir, env, { workplaceId, latitude, longitude }) {
  runSql(
    rootDir,
    env,
    `UPDATE workplaces SET latitude = ${quote(latitude)},`
      + ` longitude = ${quote(longitude)}`
      + ` WHERE id = ${Number.parseInt(workplaceId, 10)} AND latitude IS NULL`,
  );
}

/** 사람이 읽을 요약을 만듭니다. 실패는 원인별로 나눠 보여줍니다. */
function summarize(results) {
  const filled = results.filter((result) => result.latitude);
  const addressFailures = results.filter(
    (result) => result.failure === FAILURE_ADDRESS,
  );
  const temporaryFailures = results.filter(
    (result) => result.failure === FAILURE_TEMPORARY,
  );

  return {
    total: results.length,
    filled: filled.length,
    addressFailures: addressFailures.length,
    temporaryFailures: temporaryFailures.length,
    unresolved: [...addressFailures, ...temporaryFailures],
  };
}

function sleep(ms) {
  return new Promise((resolve) => {
    setTimeout(resolve, ms);
  });
}

async function run({
  rootDir = process.cwd(),
  argv = process.argv.slice(2),
} = {}) {
  const apply = argv.includes(APPLY_FLAG);
  const configIndex = argv.indexOf(CONFIG_FLAG);
  const configPath =
    configIndex >= 0 && argv[configIndex + 1]
      ? argv[configIndex + 1]
      : DEFAULT_CONFIG_PATH;

  // 쓰기가 있을 때만 대상 DB를 확인합니다. 예행은 읽기뿐이라 막을 이유가 없습니다.
  if (apply) {
    assertDisposableDatabase({ rootDir, argv });
  }

  const env = readEnvFile(rootDir);
  const key = readGeocodingKey(rootDir, configPath);

  const pending = selectPendingWorkplaces(rootDir, env);
  if (pending.length === 0) {
    log("좌표가 비어 있는 사업장이 없습니다.");
    return 0;
  }

  log(
    `${apply ? "반영" : "예행"} 대상 ${pending.length}건`
      + `${apply ? "" : " (--apply 없이는 저장하지 않습니다)"}`,
  );

  const results = [];
  for (const [index, row] of pending.entries()) {
    if (index > 0) await sleep(REQUEST_INTERVAL_MS);

    const resolved = await geocode(row.roadAddress, key);
    const result = { ...row, ...resolved };
    results.push(result);

    if (result.failure) {
      log(
        `- ${row.workplaceId} ${row.roadAddress} -> 실패(${result.failure}: ${result.detail})`,
      );
      continue;
    }

    if (apply) {
      applyCoordinates(rootDir, env, result);
    }
    log(
      `- ${row.workplaceId} ${row.roadAddress} -> ${result.latitude}, ${result.longitude}`,
    );
  }

  const summary = summarize(results);
  log(
    `요약: 대상 ${summary.total}건, 좌표 확정 ${summary.filled}건,`
      + ` 주소 확정 실패 ${summary.addressFailures}건,`
      + ` 일시 실패 ${summary.temporaryFailures}건`,
  );

  if (summary.unresolved.length > 0) {
    log(
      "좌표를 채우지 못한 사업장은 그대로 두었습니다."
        + " 주소를 고치거나 PUT /api/workplaces/{id}/coordinates로 확정해야 합니다.",
    );
  }
  if (!apply && summary.filled > 0) {
    log(`실제로 저장하려면 ${APPLY_FLAG}와 로컬 DB 확인 옵션을 함께 주세요.`);
  }

  // 일시 실패는 다시 시도하면 풀릴 수 있으므로 종료 코드로 구분합니다.
  return summary.temporaryFailures > 0 ? 1 : 0;
}

async function main(argv = process.argv.slice(2)) {
  try {
    return await run({ argv });
  } catch (error) {
    if (error instanceof DisposableDatabaseError || error instanceof BackfillError) {
      console.error(`[backfill] ${error.message}`);
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
  APPLY_FLAG,
  BackfillError,
  FAILURE_ADDRESS,
  FAILURE_TEMPORARY,
  geocode,
  main,
  parsePendingRows,
  quote,
  readGeocodingKey,
  readProperty,
  resolveDocuments,
  run,
  summarize,
  toStoredCoordinate,
};
