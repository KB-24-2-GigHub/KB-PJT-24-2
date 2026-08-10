#!/usr/bin/env node

const { execFileSync, spawnSync } = require("node:child_process");

const DOC_ONLY_FILES = new Set([
  ".gitattributes",
  ".gitignore",
  ".gitmessage.txt",
  "LICENSE",
  "LICENSE.txt",
  ".github/CODEOWNERS",
]);
const SHARED_GUARDRAIL_CONFIGURATION = new Set([
  "docs/agent/MODULE_BOUNDARIES.json",
]);

function normalizePath(file) {
  return file.replace(/\\/g, "/");
}

function splitNullSeparated(output) {
  return output.split("\0").map(normalizePath).filter(Boolean);
}

function getStagedPaths(cwd = process.cwd()) {
  const output = execFileSync(
    "git",
    ["diff", "--cached", "--name-only", "-z", "--no-renames"],
    {
      cwd,
      encoding: "utf8",
      stdio: ["ignore", "pipe", "inherit"],
    },
  );

  return [...new Set(splitNullSeparated(output))].sort();
}

function isDocumentationOnly(file) {
  const normalized = normalizePath(file);
  if (SHARED_GUARDRAIL_CONFIGURATION.has(normalized)) return false;
  const rootMarkdown = !normalized.includes("/") && normalized.endsWith(".md");
  const areaReadme = /(^|\/)README\.md$/.test(normalized);

  return (
    normalized.startsWith("docs/") ||
    rootMarkdown ||
    areaReadme ||
    normalized.startsWith(".github/ISSUE_TEMPLATE/") ||
    normalized === ".github/pull_request_template.md" ||
    DOC_ONLY_FILES.has(normalized)
  );
}

function classifyStagedPaths(files) {
  let frontend = false;
  let backend = false;
  let shared = false;

  for (const file of new Set(files.map(normalizePath))) {
    if (isDocumentationOnly(file)) {
      continue;
    }

    if (file.startsWith("frontend/")) {
      frontend = true;
      continue;
    }

    if (file.startsWith("backend/")) {
      backend = true;
      continue;
    }

    shared = true;
  }

  if (shared || (frontend && backend)) {
    return "all";
  }

  if (frontend) {
    return "frontend";
  }

  if (backend) {
    return "backend";
  }

  return "none";
}

function npmStep(label, script, platform = process.platform) {
  return {
    label,
    command: platform === "win32" ? "npm.cmd" : "npm",
    args: ["run", script],
    shell: platform === "win32",
  };
}

function nodeStep(label, script, args = []) {
  return {
    label,
    command: process.execPath,
    args: [script, ...args],
    shell: false,
  };
}

function createPlan(files, platform = process.platform) {
  const lintTarget = classifyStagedPaths(files);
  const steps = [
    nodeStep("guardrails:staged", "scripts/check-project-guardrails.js", [
      "--staged",
    ]),
    npmStep("format:staged", "format:staged", platform),
  ];

  if (lintTarget === "all") {
    steps.push(npmStep("test:harness", "test:harness", platform));
  }

  if (lintTarget !== "none") {
    steps.push(
      nodeStep(`lint:${lintTarget}`, "scripts/run-lint.js", [lintTarget]),
    );
  }

  return {
    files: [...new Set(files.map(normalizePath))].sort(),
    lintTarget,
    steps,
  };
}

function formatStep(step) {
  return `${step.command} ${step.args.join(" ")}`;
}

function runStep(step) {
  console.log(`[pre-commit] run ${step.label}: ${formatStep(step)}`);

  const result = spawnSync(step.command, step.args, {
    cwd: process.cwd(),
    stdio: "inherit",
    shell: step.shell,
  });

  if (result.error) {
    console.error(`[pre-commit] failed to start ${step.label}`);
    console.error(result.error.message);
    return 1;
  }

  return Number.isInteger(result.status) ? result.status : 1;
}

function executePlan(plan, runner = runStep) {
  for (const step of plan.steps) {
    const status = runner(step);
    if (status !== 0) {
      return status;
    }
  }

  return 0;
}

function printPlan(plan, logger = console.log) {
  logger(
    `[pre-commit] staged paths: ${plan.files.length}; application lint: ${plan.lintTarget}`,
  );

  if (plan.files.length === 0) {
    logger("[pre-commit] no staged paths were detected.");
  }

  for (const step of plan.steps) {
    logger(`[pre-commit] plan ${step.label}: ${formatStep(step)}`);
  }

  if (plan.lintTarget === "none") {
    logger(
      "[pre-commit] application lint skipped: only documentation or repository metadata changed.",
    );
  }
}

function runPrecommit({
  files = getStagedPaths(),
  dryRun = false,
  runner = runStep,
  logger = console.log,
  platform = process.platform,
} = {}) {
  const plan = createPlan(files, platform);
  printPlan(plan, logger);

  if (dryRun) {
    return 0;
  }

  return executePlan(plan, runner);
}

function main() {
  const args = process.argv.slice(2);
  const allowed = new Set(["--dry-run"]);
  const unknown = args.filter((arg) => !allowed.has(arg));

  if (unknown.length > 0) {
    console.error(`Unknown option: ${unknown.join(", ")}`);
    console.error("Use: node scripts/run-precommit.js [--dry-run]");
    return 1;
  }

  return runPrecommit({ dryRun: args.includes("--dry-run") });
}

if (require.main === module) {
  process.exitCode = main();
}

module.exports = {
  classifyStagedPaths,
  createPlan,
  executePlan,
  getStagedPaths,
  isDocumentationOnly,
  normalizePath,
  runPrecommit,
  splitNullSeparated,
};
