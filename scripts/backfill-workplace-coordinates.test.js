const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const {
  FAILURE_ADDRESS,
  FAILURE_TEMPORARY,
  geocode,
  parsePendingRows,
  quote,
  readGeocodingKey,
  readProperty,
  resolveDocuments,
  summarize,
  toStoredCoordinate,
} = require("./backfill-workplace-coordinates");

function jsonResponse(body, { ok = true, status = 200 } = {}) {
  return {
    ok,
    status,
    json: async () => body,
  };
}

test("Compose 진행 메시지를 건너뛰고 Header 아래 행만 읽는다", () => {
  const stdout = [
    " Container kb-pjt-24-2-db-1 Running",
    "workplace_id\troad_address",
    "107\t대전 동구 판교1길 3",
    "108\t서울 광진구 능동로 195-16",
    "",
  ].join("\n");

  assert.deepEqual(parsePendingRows(stdout), [
    { workplaceId: "107", roadAddress: "대전 동구 판교1길 3" },
    { workplaceId: "108", roadAddress: "서울 광진구 능동로 195-16" },
  ]);
});

test("대상이 없으면 Header가 없고 빈 목록이다", () => {
  assert.deepEqual(parsePendingRows(" Container db Running\n"), []);
});

/** 후보가 하나일 때만 좌표를 인정합니다. 첫 결과를 고르면 기준점이 조용히 틀어집니다. */
test("후보가 정확히 하나일 때만 좌표를 확정한다", () => {
  const single = resolveDocuments([{ y: "37.5481383507441", x: "127.073397180648" }]);
  assert.equal(single.latitude, "37.5481384");
  assert.equal(single.longitude, "127.0733972");

  assert.equal(resolveDocuments([]).failure, FAILURE_ADDRESS);
  assert.equal(
    resolveDocuments([
      { y: "37.1", x: "127.1" },
      { y: "37.2", x: "127.2" },
    ]).failure,
    FAILURE_ADDRESS,
  );
});

/** 응답 형태가 계약과 다르면 사용자 주소 문제가 아니므로 일시 실패로 나눕니다. */
test("해석할 수 없는 응답은 주소 오류와 구분한다", () => {
  assert.equal(resolveDocuments(undefined).failure, FAILURE_TEMPORARY);
  assert.equal(resolveDocuments([{ y: null, x: null }]).failure, FAILURE_TEMPORARY);
  assert.equal(resolveDocuments([{ y: "값아님", x: "값아님" }]).failure, FAILURE_TEMPORARY);
});

test("저장 정밀도(소수점 7자리)로 미리 반올림한다", () => {
  assert.equal(toStoredCoordinate("37.5481383507441"), "37.5481384");
  assert.equal(toStoredCoordinate("127.073397180648"), "127.0733972");
});

test("주소가 비어 있으면 외부를 호출하지 않고 주소 오류로 끝낸다", async () => {
  let called = false;
  const result = await geocode("   ", "key", {
    fetchImpl: async () => {
      called = true;
      return jsonResponse({ documents: [] });
    },
  });

  assert.equal(result.failure, FAILURE_ADDRESS);
  assert.equal(called, false, "빈 주소로 외부 Quota를 쓰지 않아야 합니다.");
});

test("외부 오류 응답은 상태 코드만 남기고 일시 실패로 분류한다", async () => {
  const result = await geocode("서울 광진구 능동로 195-16", "key", {
    fetchImpl: async () => jsonResponse({}, { ok: false, status: 403 }),
  });

  assert.equal(result.failure, FAILURE_TEMPORARY);
  assert.match(result.detail, /403/);
});

test("호출 자체가 실패하면 일시 실패로 분류한다", async () => {
  const result = await geocode("서울 광진구 능동로 195-16", "key", {
    fetchImpl: async () => {
      throw new Error("timeout");
    },
  });

  assert.equal(result.failure, FAILURE_TEMPORARY);
});

test("정상 응답은 저장 정밀도로 반올림한 좌표를 돌려준다", async () => {
  const result = await geocode("서울 광진구 능동로 195-16", "key", {
    fetchImpl: async () =>
      jsonResponse({ documents: [{ y: "37.5481383507441", x: "127.073397180648" }] }),
  });

  assert.deepEqual(result, { latitude: "37.5481384", longitude: "127.0733972" });
});

test("요약이 확정·주소 실패·일시 실패를 각각 센다", () => {
  const summary = summarize([
    { latitude: "37.5481384", longitude: "127.0733972" },
    { failure: FAILURE_ADDRESS, detail: "후보 0건" },
    { failure: FAILURE_TEMPORARY, detail: "외부 응답 503" },
    { failure: FAILURE_TEMPORARY, detail: "본문 해석 불가" },
  ]);

  assert.equal(summary.total, 4);
  assert.equal(summary.filled, 1);
  assert.equal(summary.addressFailures, 1);
  assert.equal(summary.temporaryFailures, 2);
  assert.equal(summary.unresolved.length, 3);
});

test("SQL 문자열 리터럴의 따옴표와 역슬래시를 막는다", () => {
  assert.equal(quote("37.5"), "'37.5'");
  assert.equal(quote("a'b"), "'a\\'b'");
  assert.equal(quote("a\\b"), "'a\\\\b'");
});

test("properties에서 값에 담긴 = 를 그대로 둔다", () => {
  const contents = ["# 주석", "kakao.local.rest-api-key=abc=def", "other=1"].join("\n");
  assert.equal(readProperty(contents, "kakao.local.rest-api-key"), "abc=def");
  assert.equal(readProperty(contents, "없는키"), "");
});

test("키가 없으면 조회 전에 중단한다", () => {
  const rootDir = fs.mkdtempSync(path.join(os.tmpdir(), "backfill-"));
  const configPath = path.join(rootDir, "empty.properties");
  fs.writeFileSync(configPath, "database.username=root\n", "utf8");

  assert.throws(
    () => readGeocodingKey(rootDir, configPath),
    /kakao\.local\.rest-api-key/,
  );
  assert.throws(() => readGeocodingKey(rootDir, "없는파일.properties"), /찾지 못했습니다/);
});
