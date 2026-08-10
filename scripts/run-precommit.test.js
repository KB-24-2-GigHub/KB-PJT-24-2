const assert = require("node:assert/strict");
const { execFileSync } = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const {
  classifyStagedPaths,
  createPlan,
  executePlan,
  getStagedPaths,
  isDocumentationOnly,
  runPrecommit,
  splitNullSeparated,
} = require("./run-precommit");

test("splits NUL-delimited paths without breaking spaces, Korean, or newlines", () => {
  const output = "docs/한글 문서.md\0frontend/src/line\nbreak.js\0";

  assert.deepEqual(splitNullSeparated(output), [
    "docs/한글 문서.md",
    "frontend/src/line\nbreak.js",
  ]);
});

test("recognizes documentation and repository metadata", () => {
  const paths = [
    "README.md",
    "docs/GUIDE.yml",
    "frontend/README.md",
    ".github/ISSUE_TEMPLATE/task.yml",
    ".github/pull_request_template.md",
    ".gitignore",
  ];

  assert.equal(paths.every(isDocumentationOnly), true);
  assert.equal(classifyStagedPaths(paths), "none");
});

test("selects only the frontend for frontend changes", () => {
  assert.equal(
    classifyStagedPaths(["docs/README.md", "frontend/src/App.vue"]),
    "frontend",
  );
  assert.equal(
    classifyStagedPaths(["frontend/src/content/help.md"]),
    "frontend",
  );
});

test("selects only the backend for backend changes", () => {
  assert.equal(
    classifyStagedPaths([
      "backend/src/main/java/com/gighub/health/HealthController.java",
    ]),
    "backend",
  );
  assert.equal(
    classifyStagedPaths(["backend/src/main/resources/template.md"]),
    "backend",
  );
});

test("selects all checks for mixed frontend and backend changes", () => {
  assert.equal(
    classifyStagedPaths(["frontend/src/App.vue", "backend/build.gradle"]),
    "all",
  );
});

test("uses fail-closed all checks for shared or unknown paths", () => {
  for (const path of [
    "package.json",
    "package-lock.json",
    ".husky/pre-commit",
    "scripts/run-lint.js",
    ".github/workflows/check.yml",
    "docs/agent/MODULE_BOUNDARIES.json",
    "new-top-level/config.json",
  ]) {
    assert.equal(classifyStagedPaths([path]), "all", path);
  }
});

test("runs the harness when the architecture guard configuration changes", () => {
  const plan = createPlan(["docs/agent/MODULE_BOUNDARIES.json"], "linux");

  assert.equal(plan.lintTarget, "all");
  assert.deepEqual(
    plan.steps.map(({ label }) => label),
    ["guardrails:staged", "format:staged", "test:harness", "lint:all"],
  );
});

test("normalizes Windows paths and de-duplicates staged entries", () => {
  const plan = createPlan(
    ["frontend\\src\\App.vue", "frontend/src/App.vue"],
    "linux",
  );

  assert.deepEqual(plan.files, ["frontend/src/App.vue"]);
  assert.equal(plan.lintTarget, "frontend");
});

test("rename and deletion paths keep their original application scope", () => {
  assert.equal(
    classifyStagedPaths([
      "frontend/src/OldView.vue",
      "docs/archive/OldView.md",
    ]),
    "frontend",
  );
  assert.equal(
    classifyStagedPaths(["backend/src/main/java/DeletedService.java"]),
    "backend",
  );
});

test("reads both rename paths and deleted paths from a real staged Git diff", () => {
  const temporaryRepository = fs.mkdtempSync(
    path.join(os.tmpdir(), "gighub-precommit-"),
  );
  const oldFrontendFile = path.join(
    temporaryRepository,
    "frontend",
    "src",
    "OldView.vue",
  );
  const backendFile = path.join(
    temporaryRepository,
    "backend",
    "src",
    "DeletedService.java",
  );
  const archivedFile = path.join(
    temporaryRepository,
    "docs",
    "archive",
    "OldView.md",
  );

  try {
    execFileSync("git", ["init", "--quiet"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    execFileSync("git", ["config", "user.email", "test@example.com"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    execFileSync("git", ["config", "user.name", "Harness Test"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });

    fs.mkdirSync(path.dirname(oldFrontendFile), { recursive: true });
    fs.mkdirSync(path.dirname(backendFile), { recursive: true });
    fs.writeFileSync(oldFrontendFile, "<template />\n", "utf8");
    fs.writeFileSync(backendFile, "class DeletedService {}\n", "utf8");
    execFileSync("git", ["add", "."], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    execFileSync("git", ["commit", "--quiet", "-m", "test: baseline"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });

    fs.mkdirSync(path.dirname(archivedFile), { recursive: true });
    fs.renameSync(oldFrontendFile, archivedFile);
    fs.rmSync(backendFile);
    execFileSync("git", ["add", "-A"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });

    const paths = getStagedPaths(temporaryRepository);

    assert.deepEqual(paths, [
      "backend/src/DeletedService.java",
      "docs/archive/OldView.md",
      "frontend/src/OldView.vue",
    ]);
    assert.equal(classifyStagedPaths(paths), "all");
  } finally {
    fs.rmSync(temporaryRepository, { recursive: true, force: true });
  }
});

test("builds a lightweight plan for documentation-only changes", () => {
  const plan = createPlan(["docs/README.md"], "linux");

  assert.equal(plan.lintTarget, "none");
  assert.deepEqual(
    plan.steps.map(({ label }) => label),
    ["guardrails:staged", "format:staged"],
  );
});

test("builds only the selected application area's lint step", () => {
  const frontendPlan = createPlan(["frontend/src/App.vue"], "linux");
  const backendPlan = createPlan(["backend/build.gradle"], "linux");

  assert.deepEqual(
    frontendPlan.steps.map(({ label }) => label),
    ["guardrails:staged", "format:staged", "lint:frontend"],
  );
  assert.deepEqual(
    backendPlan.steps.map(({ label }) => label),
    ["guardrails:staged", "format:staged", "lint:backend"],
  );
});

test("uses the lightweight plan when there are no staged paths", () => {
  const plan = createPlan([], "linux");

  assert.equal(plan.lintTarget, "none");
  assert.deepEqual(
    plan.steps.map(({ label }) => label),
    ["guardrails:staged", "format:staged"],
  );
});

test("builds a fail-closed plan with harness tests for shared changes", () => {
  const plan = createPlan(["scripts/run-precommit.js"], "win32");

  assert.equal(plan.lintTarget, "all");
  assert.deepEqual(
    plan.steps.map(({ label }) => label),
    ["guardrails:staged", "format:staged", "test:harness", "lint:all"],
  );
  assert.equal(plan.steps[1].command, "npm.cmd");
  assert.equal(plan.steps[1].shell, true);
});

test("stops after the first failed step and preserves its exit code", () => {
  const plan = createPlan(["scripts/run-precommit.js"], "linux");
  const visited = [];

  const status = executePlan(plan, (step) => {
    visited.push(step.label);
    return step.label === "format:staged" ? 7 : 0;
  });

  assert.equal(status, 7);
  assert.deepEqual(visited, ["guardrails:staged", "format:staged"]);
});

test("dry-run prints a plan without executing subprocesses", () => {
  const messages = [];
  let executions = 0;

  const status = runPrecommit({
    files: ["frontend/src/App.vue"],
    dryRun: true,
    runner: () => {
      executions += 1;
      return 0;
    },
    logger: (message) => messages.push(message),
    platform: "linux",
  });

  assert.equal(status, 0);
  assert.equal(executions, 0);
  assert.equal(
    messages.some((message) => message.includes("lint:frontend")),
    true,
  );
});
