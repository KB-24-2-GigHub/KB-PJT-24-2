const assert = require("node:assert/strict");
const test = require("node:test");

const {
  FixtureError,
  parseSeedSummary,
  workDate,
} = require("./prepare-invitation-fixture");

const SUMMARY_HEADER = [
  "owner_login_id",
  "worker_login_id",
  "test_password",
  "workplace_id",
  "owner_available_balance",
  "owner_locked_balance",
  "worker_available_balance",
  "remaining_work_cases",
].join("\t");

const SUMMARY_VALUES = [
  "test_owner_267",
  "test_worker_267",
  "Test1234!",
  "487",
  "1000000",
  "0",
  "0",
  "0",
].join("\t");

test("reads the summary row that follows the header", () => {
  const summary = parseSeedSummary(`${SUMMARY_HEADER}\n${SUMMARY_VALUES}\n`);

  assert.equal(summary.workplace_id, "487");
  assert.equal(summary.owner_login_id, "test_owner_267");
  assert.equal(summary.owner_available_balance, "1000000");
});

test("skips the Compose progress lines that surround the summary", () => {
  const stdout = [
    " Container kb-pjt-24-2-db-1 Running ",
    " Container kb-pjt-24-2-db-1 Healthy ",
    SUMMARY_HEADER,
    SUMMARY_VALUES,
    "",
  ].join("\n");

  assert.equal(parseSeedSummary(stdout).workplace_id, "487");
});

test("reads output that kept Windows line endings", () => {
  const stdout = `${SUMMARY_HEADER}\r\n${SUMMARY_VALUES}\r\n`;

  assert.equal(parseSeedSummary(stdout).workplace_id, "487");
});

test("fails when the SQL never reached its summary select", () => {
  assert.throws(
    () => parseSeedSummary("ERROR 1045 (28000): Access denied\n"),
    FixtureError,
  );
});

test("fails when the header has no value row", () => {
  assert.throws(() => parseSeedSummary(`${SUMMARY_HEADER}\n`), FixtureError);
});

test("offsets the work date so the invitation is not born expired", () => {
  const today = new Date();
  const expected = new Date(today);
  expected.setDate(expected.getDate() + 7);

  assert.equal(workDate(7), expected.toISOString().slice(0, 10));
  assert.match(workDate(7), /^\d{4}-\d{2}-\d{2}$/);
});
