const assert = require("node:assert/strict");
const { execFileSync, spawnSync } = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const {
  buildReviewScopeWarnings,
  compareArchitectureViolations,
  extractReadmeReleaseRows,
  extractSpecReleaseVersion,
  extractSwaggerSpecReleaseVersion,
  findArchitectureViolations,
  findViolations,
  hashNormalizedSpecContent,
  normalizeSpecContent,
  parseArchitectureManifest,
  parseNumstat,
  parsePatchDocument,
  parseMode,
  parseSpecManifest,
  selectBlockingArchitectureViolations,
  selectIntegrationBaseBranch,
  splitNullSeparated,
  verifyPatchSnapshot,
  verifyArchitectureManifestEvolution,
  verifyGovernanceTemplateSnapshot,
  verifyMigrationImmutability,
  verifyRuntimeSwaggerSpecVersion,
  verifySpecReleaseMetadata,
} = require("./check-project-guardrails");

const ARCHITECTURE_MANIFEST = {
  schemaVersion: 1,
  logicalModules: [
    {
      id: "work",
      packageRoots: ["work", "invitation", "contract"],
      ownedTables: ["work_cases"],
    },
    {
      id: "attendance",
      packageRoots: ["attendance"],
      ownedTables: ["attendance_records"],
    },
    {
      id: "document",
      packageRoots: ["document"],
      ownedTables: ["documents"],
    },
    {
      id: "idempotency-common",
      packageRoots: ["common"],
      ownedTables: [],
    },
    {
      id: "test-support",
      packageRoots: ["support"],
      ownedTables: [],
    },
  ],
  tableOwnership: [
    {
      table: "work_cases",
      owner: "work",
      allowedWriterType: "com.gighub.work.mapper.WorkCaseMapper",
    },
    {
      table: "attendance_records",
      owner: "attendance",
      allowedWriterType: "com.gighub.attendance.mapper.AttendanceRecordMapper",
    },
    {
      table: "documents",
      owner: "document",
      allowedWriterType: "com.gighub.document.mapper.DocumentWriteMapper",
    },
  ],
  guardRules: {
    controllerMayImportMapper: false,
    crossModuleMapperImport: false,
    queryExceptionMayWrite: false,
    domainForbiddenImportPrefixes: ["org.springframework", "org.apache.ibatis"],
    domainForbiddenImportRegexes: [
      "^com\\.gighub\\..*\\.controller\\.",
      "^com\\.gighub\\..*\\.dto\\.",
      "^com\\.gighub\\..*\\.mapper\\.",
    ],
  },
};

function createIssueFormFixture() {
  return [
    "name: Fixture",
    "description: Fixture",
    "body:",
    "  - type: textarea",
    "    id: goal",
    "  - type: textarea",
    "    id: acceptance",
    "    attributes:",
    "      description: 3~7개 완료 조건",
    "  - type: textarea",
    "    id: non_goals",
    "  - type: dropdown",
    "    id: risk",
    "    attributes:",
    "      options:",
    "        - R0",
    "        - R1",
    "        - R2",
    "        - R3",
    "  - type: input",
    "    id: primary_module",
    "  - type: textarea",
    "    id: affected_modules",
    "  - type: textarea",
    "    id: required_operations",
    "  - type: textarea",
    "    id: migration_scope",
    "  - type: textarea",
    "    id: verification",
    "  - type: textarea",
    "    id: depends_on",
    "",
  ].join("\n");
}

function createGovernanceTemplateFixture() {
  const pullRequestHeadings = [
    "관련 이슈와 통합",
    "실제 Diff",
    "계약 대비 차이",
    "검증 결과",
    "잔여 위험",
    "리뷰와 Migration",
    "종료 상태",
  ]
    .map((heading) => `## ${heading}\n`)
    .join("\n");
  return new Map([
    [".github/ISSUE_TEMPLATE/feature_request.yml", createIssueFormFixture()],
    [".github/ISSUE_TEMPLATE/bug_report.yml", createIssueFormFixture()],
    [".github/ISSUE_TEMPLATE/task.yml", createIssueFormFixture()],
    [".github/pull_request_template.md", pullRequestHeadings],
    [
      ".github/CODEOWNERS",
      "/.github/ISSUE_TEMPLATE/ @owner\n/docs/GITHUB_PROJECTS_PANEL_GUIDE.md @owner\n",
    ],
  ]);
}

const PATCH_SCAFFOLD = {
  "docs/spec-patches/README.md": "# Specification Patch governance\n",
  "docs/spec-patches/TEMPLATE.md": "# Specification Patch template\n",
  "docs/spec-patches/draft/.gitkeep": "",
  "docs/spec-patches/archive/.gitkeep": "",
};

function writeRepositoryFile(repository, relativePath, content) {
  const absolutePath = path.join(repository, ...relativePath.split("/"));
  fs.mkdirSync(path.dirname(absolutePath), { recursive: true });
  fs.writeFileSync(absolutePath, content, "utf8");
}

function createSpecManifest(fileContents) {
  return `${JSON.stringify(
    {
      version: 1,
      algorithm: "sha256",
      normalization: "crlf-to-lf",
      files: Object.entries(fileContents)
        .sort(([left], [right]) => left.localeCompare(right))
        .map(([file, content]) => ({
          path: file,
          sha256: hashNormalizedSpecContent(content),
        })),
    },
    null,
    2,
  )}\n`;
}

function writeSpecFixture(
  repository,
  fileContents = {
    "docs/specs/README.md": [
      "# Product specification",
      "",
      "| Item | Value |",
      "| --- | --- |",
      "| Release | `3.0.0` |",
      "",
      "## Release history",
      "",
      "| Version | Date |",
      "| --- | --- |",
      "| `3.0.0` | 2026-08-05 |",
      "",
    ].join("\n"),
    "docs/specs/API_SPEC.md":
      "# API contract\r\n\r\n| Release | `3.0.0` |\r\n\r\nProtected.\r\n",
    "docs/specs/DECISIONS.md":
      "# Decisions\n\n| Release | `3.0.0` |\n\nProtected.\n",
    "docs/specs/REQUIREMENTS.md":
      "# Requirements\n\n| Release | `3.0.0` |\n\nProtected acceptance criteria.\n",
    "docs/specs/SPEC_TRACEABILITY.md":
      "# Traceability\n\n| Release | `3.0.0` |\n\nProtected.\n",
  },
) {
  for (const [file, content] of Object.entries(fileContents)) {
    writeRepositoryFile(repository, file, content);
  }
  writeRepositoryFile(
    repository,
    "docs/specs/SPEC_LOCK.json",
    createSpecManifest(fileContents),
  );
  return fileContents;
}

function formatPatchScalar(value) {
  if (value === null) return "null";
  if (typeof value === "number") return String(value);
  return JSON.stringify(value);
}

function createPatchDocument(overrides = {}, options = {}) {
  const metadata = {
    patch_id: "SPEC-205-01",
    status: "draft",
    issue: 205,
    base_spec_version: "3.0.0",
    targets: [{ requirement: "WALLET-003" }],
    ...overrides,
  };
  const omitted = new Set(options.omit ?? []);
  const lines = ["---"];

  for (const [key, value] of Object.entries(metadata)) {
    if (omitted.has(key)) continue;
    if (key === "targets") {
      lines.push("targets:");
      for (const target of value) {
        const [type, targetValue] = Object.entries(target)[0];
        lines.push(`  - ${type}: ${formatPatchScalar(targetValue)}`);
      }
    } else {
      lines.push(`${key}: ${formatPatchScalar(value)}`);
    }
  }

  lines.push(
    "---",
    "",
    "## 추가 사항",
    "",
    "지갑 계약의 변경은 관련 요구사항과 검증 조건을 함께 추적한다.",
    "",
    "## 완료 조건",
    "",
    "- 지갑 계약과 검증 조건의 연결을 확인할 수 있다.",
    "",
  );

  if (options.bodySuffix) lines.push(options.bodySuffix);
  return lines.join("\n");
}

function createPatchSnapshot(documents = {}) {
  return new Map(Object.entries({ ...PATCH_SCAFFOLD, ...documents }));
}

function patchPath(summary, directory = "draft", revision = 1) {
  return `docs/spec-patches/${directory}/flamingo7562_issue-205_${summary}_patch_v${revision}.md`;
}

function guardrailEnvironment(overrides = {}) {
  const environment = { ...process.env };
  delete environment.GITHUB_BASE_REF;
  delete environment.GIGHUB_GUARDRAIL_BASE_REF;
  return { ...environment, ...overrides };
}

function specFixtureForVersion(version) {
  return {
    "docs/specs/README.md": [
      "# Product specification",
      "",
      "| Item | Value |",
      "| --- | --- |",
      `| Release | \`${version}\` |`,
      "",
      "## Release history",
      "",
      "| Version | Date |",
      "| --- | --- |",
      `| \`${version}\` | 2026-08-12 |`,
      "",
    ].join("\n"),
    "docs/specs/API_SPEC.md": `# API contract\n\n| Release | \`${version}\` |\n\nProtected.\n`,
    "docs/specs/DECISIONS.md": `# Decisions\n\n| Release | \`${version}\` |\n\nProtected.\n`,
    "docs/specs/REQUIREMENTS.md": `# Requirements\n\n| Release | \`${version}\` |\n\nProtected acceptance criteria.\n`,
    "docs/specs/SPEC_TRACEABILITY.md": `# Traceability\n\n| Release | \`${version}\` |\n\nProtected.\n`,
  };
}

function initializePatchHistoryRepository(prefix, { draft = false } = {}) {
  const repository = fs.mkdtempSync(path.join(os.tmpdir(), prefix));
  execFileSync("git", ["init", "--quiet"], { cwd: repository });
  execFileSync("git", ["config", "user.name", "Guardrail Test"], {
    cwd: repository,
  });
  execFileSync("git", ["config", "user.email", "guardrail@example.com"], {
    cwd: repository,
  });
  writeSpecFixture(repository, specFixtureForVersion("3.0.0"));
  for (const [file, content] of Object.entries(PATCH_SCAFFOLD)) {
    writeRepositoryFile(repository, file, content);
  }
  if (draft) {
    writeRepositoryFile(
      repository,
      patchPath("wallet-contract"),
      createPatchDocument(),
    );
  }
  execFileSync("git", ["add", "."], { cwd: repository });
  execFileSync("git", ["commit", "--quiet", "-m", "baseline"], {
    cwd: repository,
  });
  const base = execFileSync("git", ["rev-parse", "HEAD"], {
    cwd: repository,
    encoding: "utf8",
  }).trim();
  execFileSync("git", ["update-ref", "refs/remotes/origin/dev", base], {
    cwd: repository,
  });
  return repository;
}

test("parses explicit staged and all modes", () => {
  assert.equal(parseMode(["--staged"]), "staged");
  assert.equal(parseMode(["--all"]), "all");
  assert.equal(parseMode(["--release"]), "release");
  assert.throws(() => parseMode([]), /Use one mode/);
});

test("selects only approved integration base branch names", () => {
  assert.equal(
    selectIntegrationBaseBranch({
      githubBaseRef: "",
      localBaseRef: "",
      hasOriginRemote: true,
    }),
    "dev",
  );
  assert.equal(
    selectIntegrationBaseBranch({
      githubBaseRef: "dev2",
      localBaseRef: "",
      hasOriginRemote: true,
    }),
    "dev2",
  );
  assert.equal(
    selectIntegrationBaseBranch({
      githubBaseRef: "",
      localBaseRef: "main",
      hasOriginRemote: true,
    }),
    "main",
  );
  assert.equal(
    selectIntegrationBaseBranch({
      githubBaseRef: "dev2",
      localBaseRef: "dev2",
      hasOriginRemote: true,
    }),
    "dev2",
  );
  assert.equal(
    selectIntegrationBaseBranch({
      githubBaseRef: "dev2",
      localBaseRef: "dev2",
      hasOriginRemote: false,
    }),
    "dev",
  );

  assert.throws(
    () =>
      selectIntegrationBaseBranch({
        githubBaseRef: "dev2",
        localBaseRef: "dev",
        hasOriginRemote: true,
      }),
    /comparison base mismatch/,
  );
  for (const invalid of ["feature/example", "refs/heads/dev2", "HEAD"]) {
    assert.throws(
      () =>
        selectIntegrationBaseBranch({
          githubBaseRef: "",
          localBaseRef: invalid,
          hasOriginRemote: true,
        }),
      /must be one of main, dev, dev2/,
    );
  }
});

test("all mode compares against the selected dev or dev2 remote base", () => {
  const temporaryRepository = fs.mkdtempSync(
    path.join(os.tmpdir(), "gighub-integration-base-"),
  );
  const script = path.resolve(__dirname, "check-project-guardrails.js");

  try {
    execFileSync("git", ["init", "--quiet"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    execFileSync("git", ["config", "user.name", "Guardrail Test"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    execFileSync("git", ["config", "user.email", "guardrail@example.com"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    execFileSync(
      "git",
      ["remote", "add", "origin", "https://example.invalid/repository.git"],
      {
        cwd: temporaryRepository,
        stdio: "ignore",
      },
    );
    writeSpecFixture(temporaryRepository);
    for (const [file, content] of Object.entries(PATCH_SCAFFOLD)) {
      writeRepositoryFile(temporaryRepository, file, content);
    }
    execFileSync("git", ["add", "."], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    execFileSync("git", ["commit", "--quiet", "-m", "dev baseline"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    const devCommit = execFileSync("git", ["rev-parse", "HEAD"], {
      cwd: temporaryRepository,
      encoding: "utf8",
    }).trim();
    execFileSync("git", ["update-ref", "refs/remotes/origin/dev", devCommit], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });

    const missingDev2 = spawnSync(process.execPath, [script, "--all"], {
      cwd: temporaryRepository,
      encoding: "utf8",
      env: guardrailEnvironment({ GIGHUB_GUARDRAIL_BASE_REF: "dev2" }),
    });
    assert.equal(missingDev2.status, 1);
    assert.match(missingDev2.stderr, /refs\/remotes\/origin\/dev2/);

    writeRepositoryFile(
      temporaryRepository,
      patchPath("dev2-baseline", "archive"),
      createPatchDocument({ status: "accepted" }),
    );
    execFileSync("git", ["add", "."], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    execFileSync("git", ["commit", "--quiet", "-m", "dev2 baseline"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    const dev2Commit = execFileSync("git", ["rev-parse", "HEAD"], {
      cwd: temporaryRepository,
      encoding: "utf8",
    }).trim();
    execFileSync(
      "git",
      ["update-ref", "refs/remotes/origin/dev2", dev2Commit],
      {
        cwd: temporaryRepository,
        stdio: "ignore",
      },
    );

    writeRepositoryFile(
      temporaryRepository,
      "docs/change.md",
      "# Current change\n",
    );
    execFileSync("git", ["add", "."], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    execFileSync("git", ["commit", "--quiet", "-m", "current change"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });

    const defaultDev = spawnSync(process.execPath, [script, "--all"], {
      cwd: temporaryRepository,
      encoding: "utf8",
      env: guardrailEnvironment(),
    });
    assert.equal(defaultDev.status, 1);
    assert.match(defaultDev.stderr, /new Patch must start in draft/);

    const localDev2 = spawnSync(process.execPath, [script, "--all"], {
      cwd: temporaryRepository,
      encoding: "utf8",
      env: guardrailEnvironment({ GIGHUB_GUARDRAIL_BASE_REF: "dev2" }),
    });
    assert.equal(localDev2.status, 0, localDev2.stderr);

    const githubDev2 = spawnSync(process.execPath, [script, "--all"], {
      cwd: temporaryRepository,
      encoding: "utf8",
      env: guardrailEnvironment({ GITHUB_BASE_REF: "dev2" }),
    });
    assert.equal(githubDev2.status, 0, githubDev2.stderr);

    const mismatch = spawnSync(process.execPath, [script, "--all"], {
      cwd: temporaryRepository,
      encoding: "utf8",
      env: guardrailEnvironment({
        GITHUB_BASE_REF: "dev2",
        GIGHUB_GUARDRAIL_BASE_REF: "dev",
      }),
    });
    assert.equal(mismatch.status, 1);
    assert.match(mismatch.stderr, /comparison base mismatch/);

    const arbitraryBase = spawnSync(process.execPath, [script, "--all"], {
      cwd: temporaryRepository,
      encoding: "utf8",
      env: guardrailEnvironment({
        GIGHUB_GUARDRAIL_BASE_REF: "feature/example",
      }),
    });
    assert.equal(arbitraryBase.status, 1);
    assert.match(arbitraryBase.stderr, /must be one of main, dev, dev2/);
  } finally {
    fs.rmSync(temporaryRepository, { recursive: true, force: true });
  }
});

test("splits NUL-delimited Git output without losing unusual paths", () => {
  assert.deepEqual(
    splitNullSeparated("frontend/src/한글 파일.js\0backend/line\nbreak.java\0"),
    ["frontend/src/한글 파일.js", "backend/line\nbreak.java"],
  );
});

test("validates the RF-02 module manifest used by architecture guardrails", () => {
  assert.deepEqual(
    parseArchitectureManifest(JSON.stringify(ARCHITECTURE_MANIFEST)).errors,
    [],
  );

  const invalid = structuredClone(ARCHITECTURE_MANIFEST);
  invalid.logicalModules.push({
    id: "duplicate",
    packageRoots: ["attendance"],
  });
  invalid.guardRules.controllerMayImportMapper = true;
  invalid.guardRules.queryExceptionMayWrite = true;
  invalid.guardRules.domainForbiddenImportRegexes.push("[");
  invalid.tableOwnership[0].allowedWriterType =
    "com.gighub.attendance.mapper.AttendanceWorkCaseMapper";
  const errors = parseArchitectureManifest(JSON.stringify(invalid)).errors.join(
    "\n",
  );
  assert.match(errors, /maps package root attendance more than once/);
  assert.match(errors, /controllerMayImportMapper must be false/);
  assert.match(errors, /queryExceptionMayWrite must be false/);
  assert.match(errors, /invalid domain import regex/);
  assert.match(errors, /allowed writer .* must belong to owner work/);

  const weakened = structuredClone(ARCHITECTURE_MANIFEST);
  weakened.logicalModules[0].packageRoots = ["work", "contract"];
  weakened.tableOwnership[0].owner = "attendance";
  weakened.tableOwnership[1].allowedWriterType =
    "com.gighub.attendance.mapper.LegacyAttendanceMapper";
  weakened.guardRules.domainForbiddenImportPrefixes = ["org.springframework"];
  const evolutionErrors = verifyArchitectureManifestEvolution(
    ARCHITECTURE_MANIFEST,
    weakened,
  ).join("\n");
  assert.match(
    evolutionErrors,
    /must not remove governed package root invitation/,
  );
  assert.match(
    evolutionErrors,
    /domainForbiddenImportPrefixes must not remove baseline rule org.apache.ibatis/,
  );
  assert.match(evolutionErrors, /must not reassign table work_cases/);
  assert.match(
    evolutionErrors,
    /must not replace the allowed writer for attendance_records/,
  );
});

test("keeps issue forms and PR metadata aligned with the RF-03 workflow", () => {
  const valid = createGovernanceTemplateFixture();
  assert.deepEqual(verifyGovernanceTemplateSnapshot(valid), []);

  const invalid = createGovernanceTemplateFixture();
  invalid.set(
    ".github/ISSUE_TEMPLATE/task.yml",
    invalid
      .get(".github/ISSUE_TEMPLATE/task.yml")
      .replace("    id: migration_scope\n", "    id: goal\n"),
  );
  invalid.set(
    ".github/pull_request_template.md",
    invalid
      .get(".github/pull_request_template.md")
      .replace("## 잔여 위험\n", ""),
  );
  const errors = verifyGovernanceTemplateSnapshot(invalid).join("\n");
  assert.match(errors, /duplicates field ids: goal/);
  assert.match(errors, /missing required workflow field id: migration_scope/);
  assert.match(errors, /missing heading: 잔여 위험/);
});

test("allows new forward-only migrations but never rewrites applied files", () => {
  const baselineFiles = new Map([
    [
      "backend/src/main/resources/db/migration/V1__baseline.sql",
      "CREATE TABLE sample;\n",
    ],
  ]);
  const forwardOnly = verifyMigrationImmutability({
    baselineFiles,
    candidateFiles: new Map([
      ...baselineFiles,
      [
        "backend/src/main/resources/db/migration/V2__add_index.sql",
        "CREATE INDEX ix;\n",
      ],
    ]),
  });
  assert.deepEqual(forwardOnly, []);

  assert.match(
    verifyMigrationImmutability({
      baselineFiles,
      candidateFiles: new Map([
        [
          "backend/src/main/resources/db/migration/V1__baseline.sql",
          "ALTER TABLE sample;\n",
        ],
      ]),
    }).join("\n"),
    /must remain immutable/,
  );
  assert.match(
    verifyMigrationImmutability({
      baselineFiles,
      candidateFiles: new Map(),
    }).join("\n"),
    /must not be deleted/,
  );
});

test("blocks only architecture violations added beyond the frozen baseline", () => {
  const baselineFiles = new Map([
    [
      "backend/src/main/java/com/gighub/document/controller/DocumentController.java",
      "package com.gighub.document.controller;\nimport com.gighub.document.mapper.DocumentQueryMapper;\n",
    ],
    ["frontend/src/services/documents.js", "const USE_MOCK = true;\n"],
    [
      "backend/src/main/resources/mappers/AttendanceMapper.xml",
      '<mapper namespace="attendance"><select id="find" resultType="com.gighub.attendance.mapper.result.AttendanceRow" /></mapper>\n',
    ],
  ]);
  const candidateFiles = new Map([
    ...baselineFiles,
    [
      "backend/src/main/java/com/gighub/work/service/WorkService.java",
      "package com.gighub.work.service;\nimport com.gighub.invitation.mapper.InvitationMapper;\n",
    ],
    [
      "backend/src/main/java/com/gighub/attendance/service/AttendanceService.java",
      "package com.gighub.attendance.service;\nimport com.gighub.work.mapper.WorkCaseMapper;\n",
    ],
    [
      "backend/src/main/java/com/gighub/attendance/domain/AttendancePolicy.java",
      "package com.gighub.attendance.domain;\nimport org.springframework.stereotype.Component;\n",
    ],
    [
      "backend/src/main/java/com/gighub/attendance/controller/AttendanceController.java",
      "package com.gighub.attendance.controller;\nimport com.gighub.attendance.mapper.QrTokenMapper;\n",
    ],
    ["frontend/src/services/worker.js", "const FORCE_MOCK = true;\n"],
    [
      "backend/src/main/resources/mappers/AttendanceMapper.xml",
      '<mapper namespace="attendance"><resultMap id="response" type="com.gighub.attendance.dto.AttendanceView" /></mapper>\n',
    ],
    [
      "backend/src/main/java/com/gighub/attendance/controller/AttendanceController.java",
      "package com.gighub.attendance.controller;\n" +
        "import com.gighub.attendance.dto.AttendanceView;\n" +
        "import com.gighub.attendance.mapper.QrTokenMapper;\n" +
        "public class AttendanceController {}\n",
    ],
  ]);

  const baseline = findArchitectureViolations(
    baselineFiles,
    ARCHITECTURE_MANIFEST,
  );
  const candidate = findArchitectureViolations(
    candidateFiles,
    ARCHITECTURE_MANIFEST,
  );
  assert.deepEqual([...baseline.values()].map(({ kind }) => kind).sort(), [
    "controller-mapper-import",
    "hardcoded-production-mock",
  ]);
  assert.deepEqual(
    compareArchitectureViolations(baseline, candidate)
      .map(({ kind }) => kind)
      .sort(),
    [
      "controller-mapper-import",
      "cross-module-mapper-import",
      "domain-forbidden-import",
      "hardcoded-production-mock",
      "mapper-api-response-dto-result",
    ],
  );
});

test("keeps RF-10 reverse type dependencies at current zero", () => {
  const files = new Map([
    [
      "backend/src/main/java/com/gighub/work/dto/WorkCaseResponse.java",
      "package com.gighub.work.dto;\n" +
        "import com.gighub.work.mapper.result.WorkCaseRow;\n" +
        "public final class WorkCaseResponse { private WorkCaseRow row; }\n",
    ],
  ]);
  const baseline = findArchitectureViolations(files, ARCHITECTURE_MANIFEST);
  const candidate = findArchitectureViolations(files, ARCHITECTURE_MANIFEST);

  assert.deepEqual(compareArchitectureViolations(baseline, candidate), []);
  assert.deepEqual(
    selectBlockingArchitectureViolations(baseline, candidate).map(
      ({ kind }) => kind,
    ),
    ["api-dto-mapper-type-import"],
  );
});

test("fails closed for unmapped package roots and lowercase Mock flags", () => {
  const violations = findArchitectureViolations(
    new Map([
      [
        "backend/src/main/java/com/gighub/newmodule/service/NewService.java",
        "package com.gighub.newmodule.service;\nimport com.gighub.work.mapper.WorkCaseMapper;\n",
      ],
      [
        "backend/src/main/java/com/gighub/attendance/service/AttendanceService.java",
        "package com.gighub.attendance.service;\nimport com.gighub.unknown.mapper.UnknownMapper;\n",
      ],
      ["frontend/src/services/worker.js", "const useMockWorker = true;\n"],
    ]),
    ARCHITECTURE_MANIFEST,
  );

  assert.deepEqual([...violations.values()].map(({ kind }) => kind).sort(), [
    "hardcoded-production-mock",
    "unmapped-mapper-package-root",
    "unmapped-source-package-root",
  ]);
});

test("fails closed when API, Domain, and Mapper persistence type boundaries regress", () => {
  const violations = findArchitectureViolations(
    new Map([
      [
        "backend/src/main/java/com/gighub/work/dto/WorkCaseResponse.java",
        "package com.gighub.work.dto;\n" +
          "public class WorkCaseResponse {" +
          " private com.gighub.work.mapper.result.WorkCaseRow row; }\n",
      ],
      [
        "backend/src/main/java/com/gighub/work/dto/WorkCaseRequest.java",
        "package com.gighub.work.dto;\n" +
          "import com.gighub.work.mapper.param.*;\n" +
          "public class WorkCaseRequest { private WorkCaseParam param; }\n",
      ],
      [
        "backend/src/main/java/com/gighub/work/dto/WorkPersistenceSnapshot.java",
        "package com.gighub.work.dto;\n" +
          "import com.gighub.work.mapper.result.WorkCaseRow;\n" +
          "public class WorkPersistenceSnapshot {}\n",
      ],
      [
        "backend/src/main/java/com/gighub/work/dto/WorkEnvelopeResponse.java",
        "package com.gighub.work.dto;\n" +
          "public class WorkEnvelopeResponse {" +
          " private com.gighub.work.dto.item.WorkNestedItem item;" +
          " private WorkCaseItem caseItem; }\n",
      ],
      [
        "backend/src/main/java/com/gighub/work/dto/WorkCaseItem.java",
        "package com.gighub.work.dto;\npublic class WorkCaseItem {}\n",
      ],
      [
        "backend/src/main/java/com/gighub/work/dto/item/WorkNestedItem.java",
        "package com.gighub.work.dto.item;\n" +
          "import com.gighub.work.mapper.result.WorkCaseRow;\n" +
          "public class WorkNestedItem {}\n",
      ],
      [
        "backend/src/main/java/com/gighub/work/mapper/WorkCaseMapper.java",
        "package com.gighub.work.mapper;\n" +
          "public interface WorkCaseMapper {" +
          " com.gighub.work.dto.WorkCaseRequest find(); }\n",
      ],
      [
        "backend/src/main/java/com/gighub/common/api/PageResponse.java",
        "package com.gighub.common.api;\n" +
          "import com.gighub.work.mapper.result.WorkCaseRow;\n" +
          "public class PageResponse { private WorkCaseRow row; }\n",
      ],
      [
        "backend/src/main/java/com/gighub/work/mapper/WorkPageMapper.java",
        "package com.gighub.work.mapper;\n" +
          "import com.gighub.common.api.PageResponse;\n" +
          "public interface WorkPageMapper { PageResponse find(); }\n",
      ],
      [
        "backend/src/main/java/com/gighub/support/TestLoginResponse.java",
        "package com.gighub.support;\n" +
          "public final class TestLoginResponse {" +
          " private com.gighub.work.mapper.result.WorkCaseRow row; }\n",
      ],
      [
        "backend/src/main/java/com/gighub/support/mapper/TestLoginMapper.java",
        "package com.gighub.support.mapper;\n" +
          "public interface TestLoginMapper {" +
          " com.gighub.support.TestLoginResponse find(); }\n",
      ],
      [
        "backend/src/main/java/com/gighub/attendance/dto/AttendanceScanView.java",
        "package com.gighub.attendance.dto;\n" +
          "import com.gighub.attendance.mapper.result.AttendanceScanRow;\n" +
          "public class AttendanceScanView {}\n",
      ],
      [
        "backend/src/main/java/com/gighub/attendance/dto/AttendanceScanResult.java",
        "package com.gighub.attendance.dto;\n" +
          "import com.fasterxml.jackson.annotation.JsonTypeInfo;\n" +
          "@JsonTypeInfo(use = JsonTypeInfo.Id.NAME)\n" +
          "public interface AttendanceScanResult {}\n",
      ],
      [
        "backend/src/main/java/com/gighub/attendance/dto/AttendanceRecordedView.java",
        "package com.gighub.attendance.dto;\n" +
          "import com.gighub.attendance.mapper.result.AttendanceScanRow;\n" +
          "public final class AttendanceRecordedView implements AttendanceScanResult {}\n",
      ],
      [
        "backend/src/main/java/com/gighub/attendance/domain/AttendancePolicy.java",
        "package com.gighub.attendance.domain;\n" +
          "public class AttendancePolicy {" +
          " private com.gighub.attendance.dto.AttendanceScanResponse response; }\n",
      ],
      [
        "backend/src/main/java/com/gighub/attendance/controller/AttendanceController.java",
          "package com.gighub.attendance.controller;\n" +
          "import com.gighub.attendance.dto.AttendanceScanResponse;\n" +
          "import com.gighub.attendance.dto.AttendanceScanResult;\n" +
          "import com.gighub.attendance.dto.AttendanceScanView;\n" +
          "public class AttendanceController {}\n",
      ],
      [
        "backend/src/main/resources/mappers/AttendanceMapper.xml",
        '<mapper namespace="com.gighub.attendance.mapper.AttendanceRecordMapper">' +
          '<resultMap id="response" type="com.gighub.attendance.dto.AttendanceScanResponse" />' +
          '<insert id="insert" parameterType="com.gighub.work.dto.WorkCaseRequest">' +
          "INSERT INTO attendance_records (id) VALUES (1)" +
          "</insert>" +
          '<select id="page" resultType="com.gighub.common.api.PageResponse">' +
          "SELECT 1" +
          "</select>" +
          '<select id="nested" resultType="com.gighub.work.dto.WorkEnvelopeResponse$WorkerSummary">' +
          "SELECT 1" +
          "</select>" +
          '<resultMap id="nestedObjects" type="com.gighub.attendance.mapper.result.AttendanceScanRow">' +
          '<association property="response" javaType="com.gighub.work.dto.WorkEnvelopeResponse" />' +
          '<collection property="items" ofType="com.gighub.work.dto.WorkCaseItem" />' +
          '<discriminator javaType="string" column="kind">' +
          '<case value="worker" resultType="com.gighub.work.dto.WorkEnvelopeResponse$WorkerSummary" />' +
          "</discriminator>" +
          "<constructor>" +
          '<arg javaType="com.gighub.work.dto.WorkCaseItem" />' +
          "</constructor>" +
          "</resultMap>" +
          "</mapper>\n",
      ],
      [
        "backend/src/main/java/com/gighub/work/service/WorkApplicationPort.java",
        "package com.gighub.work.service;\n" +
          "public interface WorkApplicationPort {" +
          " javax.servlet.http.HttpServletRequest request();" +
          " org.springframework.http.ResponseEntity<?> response();" +
          " com.fasterxml.jackson.databind.JsonNode json(); }\n",
      ],
      [
        "backend/src/main/java/com/gighub/work/service/WorkApplicationServiceImpl.java",
        "package com.gighub.work.service;\n" +
          "import javax.servlet.http.HttpServletRequest;\n" +
          "import org.springframework.http.ResponseEntity;\n" +
          "import com.fasterxml.jackson.databind.JsonNode;\n" +
          "public class WorkApplicationServiceImpl {}\n",
      ],
    ]),
    ARCHITECTURE_MANIFEST,
  );

  assert.deepEqual(
    [...violations.values()].map(({ kind }) => kind).sort(),
    [
      "api-dto-mapper-type-import",
      "api-dto-mapper-type-import",
      "api-dto-mapper-type-import",
      "api-dto-mapper-type-import",
      "api-dto-mapper-type-import",
      "api-dto-mapper-type-import",
      "api-dto-mapper-type-import",
      "application-interface-web-import",
      "application-interface-web-import",
      "application-interface-web-import",
      "cross-module-mapper-import",
      "domain-forbidden-import",
      "mapper-api-dto-import",
      "mapper-api-dto-import",
      "mapper-api-dto-import",
      "mapper-api-request-dto-parameter",
      "mapper-api-response-dto-result",
      "mapper-api-response-dto-result",
      "mapper-api-response-dto-result",
      "mapper-api-response-dto-result",
      "mapper-api-response-dto-result",
      "mapper-api-response-dto-result",
      "mapper-api-response-dto-result",
    ],
  );
});

test("freezes Mapper XML API DTO coupling without flagging persistence-only DTOs", () => {
  const files = new Map([
    [
      "backend/src/main/java/com/gighub/document/controller/DocumentController.java",
      "package com.gighub.document.controller;\n" +
        "import com.gighub.document.dto.Document;\n" +
        "import com.gighub.document.dto.DocumentListItem;\n" +
        "public class DocumentController {}\n",
    ],
    [
      "backend/src/main/resources/mappers/DocumentQueryMapper.xml",
      '<mapper namespace="document">' +
        '<resultMap id="document" type="com.gighub.document.dto.Document" />' +
        '<select id="list" resultType="com.gighub.document.dto.DocumentListItem" />' +
        "</mapper>\n",
    ],
    [
      "backend/src/main/resources/mappers/WalletMapper.xml",
      '<mapper namespace="wallet">' +
        '<resultMap id="snapshot" type="com.gighub.wallet.dto.WalletBalanceSnapshot" />' +
        "</mapper>\n",
    ],
  ]);

  const baseline = findArchitectureViolations(files, ARCHITECTURE_MANIFEST);
  assert.deepEqual(
    [...baseline.values()]
      .filter(({ kind }) => kind === "mapper-api-response-dto-result")
      .map(({ target }) => target.split("#")[0])
      .sort(),
    [
      "com.gighub.document.dto.Document",
      "com.gighub.document.dto.DocumentListItem",
    ],
  );

  const withoutControllerImports = new Map(files);
  withoutControllerImports.delete(
    "backend/src/main/java/com/gighub/document/controller/DocumentController.java",
  );
  assert.deepEqual(
    [
      ...findArchitectureViolations(
        withoutControllerImports,
        ARCHITECTURE_MANIFEST,
      ).values(),
    ]
      .filter(({ kind }) => kind === "mapper-api-response-dto-result")
      .map(({ target }) => target.split("#")[0])
      .sort(),
    [
      "com.gighub.document.dto.Document",
      "com.gighub.document.dto.DocumentListItem",
    ],
  );

  const candidate = new Map(files);
  candidate.set(
    "backend/src/main/resources/mappers/NewMapper.xml",
    '<mapper namespace="new"><select id="find" resultType="com.gighub.newfeature.dto.NewResponse" /></mapper>\n',
  );
  assert.deepEqual(
    compareArchitectureViolations(
      baseline,
      findArchitectureViolations(candidate, ARCHITECTURE_MANIFEST),
    ).map(({ kind }) => kind),
    ["mapper-api-response-dto-result"],
  );

  const duplicateTypeCandidate = new Map(files);
  duplicateTypeCandidate.set(
    "backend/src/main/resources/mappers/DocumentQueryMapper.xml",
    files
      .get("backend/src/main/resources/mappers/DocumentQueryMapper.xml")
      .replace(
        "</mapper>",
        '<select id="secondList" resultType="com.gighub.document.dto.DocumentListItem" /></mapper>',
      ),
  );
  assert.deepEqual(
    compareArchitectureViolations(
      baseline,
      findArchitectureViolations(duplicateTypeCandidate, ARCHITECTURE_MANIFEST),
    ).map(({ target }) => target),
    ["com.gighub.document.dto.DocumentListItem#select:secondList"],
  );
});

test("enforces Mapper XML DML table ownership while allowing owner SELECT projections", () => {
  const files = new Map([
    [
      "backend/src/main/java/com/gighub/work/mapper/AnnotatedWorkMapper.java",
      "package com.gighub.work.mapper;\n" +
        "import org.apache.ibatis.annotations.Update;\n" +
        "public interface AnnotatedWorkMapper {\n" +
        '  @Update("UPDATE work_cases SET status = \'READY\'") int update();\n' +
        "}\n",
    ],
    [
      "backend/src/main/resources/mappers/WorkCaseMapper.xml",
      '<mapper namespace="com.gighub.work.mapper.WorkCaseMapper">' +
        '<update id="transition">UPDATE work_cases SET status = #{status}</update>' +
        '<update id="commentBypass">/* UPDATE work_cases SET status = 1 */ UPDATE documents SET status = 1</update>' +
        '<update id="multiTable">UPDATE work_cases wc JOIN work_invitations wi ON wi.work_case_id = wc.id SET wc.status = 1, wi.status = 1</update>' +
        '<select id="detail" resultType="java.lang.Long">SELECT id FROM documents</select>' +
        "</mapper>",
    ],
    [
      "backend/src/main/resources/mappers/AttendanceLifecycleMapper.xml",
      '<mapper namespace="com.gighub.attendance.mapper.AttendanceLifecycleMapper">' +
        '<update id="wrongOwner">UPDATE work_cases SET status = #{status}</update>' +
        "</mapper>",
    ],
    [
      "backend/src/main/resources/mappers/UnknownMapper.xml",
      '<mapper namespace="com.gighub.attendance.mapper.UnknownMapper">' +
        '<insert id="unknown">INSERT INTO unknown_events(id) VALUES (1)</insert>' +
        "</mapper>",
    ],
  ]);

  assert.deepEqual(
    [...findArchitectureViolations(files, ARCHITECTURE_MANIFEST).values()]
      .filter(
        ({ kind }) =>
          kind.startsWith("mapper-dml") ||
          kind === "mapper-annotation-dml-forbidden",
      )
      .map(({ kind }) => kind)
      .sort(),
    [
      "mapper-annotation-dml-forbidden",
      "mapper-dml-owner-mismatch",
      "mapper-dml-owner-mismatch",
      "mapper-dml-table-unowned",
      "mapper-dml-target-unresolved",
    ],
  );
});

test("keeps file, LOC, and new type thresholds as review warnings", () => {
  const changedFiles = new Set(
    Array.from(
      { length: 10 },
      (_, index) => `frontend/src/feature-${index}.js`,
    ),
  );
  const warnings = buildReviewScopeWarnings({
    changedFiles,
    lineStats: [
      { file: "frontend/src/large-feature.js", added: 450, deleted: 50 },
    ],
    addedEntries: [
      {
        file: "backend/src/main/java/com/gighub/work/domain/WorkPolicy.java",
        content:
          "public interface WorkPolicy {}\nclass WorkPolicyException extends RuntimeException {}\n",
      },
    ],
  });

  assert.equal(warnings.length, 3);
  assert.match(warnings.join("\n"), /10 changed files/);
  assert.match(warnings.join("\n"), /changes 500 lines/);
  assert.match(warnings.join("\n"), /WorkPolicy/);
  assert.match(warnings.join("\n"), /WorkPolicyException/);
  assert.deepEqual(
    parseNumstat("12\t3\tfrontend/src/a.js\n-\t-\tasset.bin\n"),
    [{ file: "frontend/src/a.js", added: 12, deleted: 3 }],
  );
  assert.deepEqual(
    parseNumstat("12\t3\tfrontend/src/한글\t줄\n파일.js\0-\t-\tasset.bin\0"),
    [{ file: "frontend/src/한글\t줄\n파일.js", added: 12, deleted: 3 }],
  );

  assert.deepEqual(
    buildReviewScopeWarnings({
      changedFiles: new Set(
        Array.from(
          { length: 9 },
          (_, index) => `frontend/src/small-${index}.js`,
        ),
      ),
      lineStats: [
        { file: "frontend/src/small-feature.js", added: 450, deleted: 49 },
      ],
      addedEntries: [],
    }),
    [],
  );
});

test("normalizes only CRLF before computing a stable SHA-256 digest", () => {
  assert.equal(normalizeSpecContent("first\r\nsecond\r\n"), "first\nsecond\n");
  assert.equal(
    hashNormalizedSpecContent("first\r\nsecond\r\n"),
    hashNormalizedSpecContent("first\nsecond\n"),
  );
  assert.notEqual(
    hashNormalizedSpecContent("first\rsecond"),
    hashNormalizedSpecContent("first\nsecond"),
  );
});

test("requires a strict sorted protected-spec manifest schema", () => {
  assert.match(parseSpecManifest("null").errors.join("\n"), /JSON object/);

  const validManifest = createSpecManifest({
    "docs/specs/API_SPEC.md": "api\n",
    "docs/specs/REQUIREMENTS.md": "requirements\n",
  });
  assert.deepEqual(parseSpecManifest(validManifest).errors, []);

  const invalidManifest = JSON.stringify({
    version: 1,
    algorithm: "sha256",
    normalization: "crlf-to-lf",
    files: [
      {
        path: "docs/specs/REQUIREMENTS.md",
        sha256: "a".repeat(64),
      },
      {
        path: "docs/specs/API_SPEC.md",
        sha256: "b".repeat(64),
      },
    ],
  });
  assert.match(
    parseSpecManifest(invalidManifest).errors.join("\n"),
    /sorted by path/,
  );
});

test("requires canonical spec Markdown release metadata to stay aligned", () => {
  const files = new Map(
    Object.entries({
      "docs/specs/README.md": [
        "# Spec",
        "",
        "Unrelated version `9.9.9` must not become release metadata.",
        "",
        "| Release | `3.0.1` |",
        "",
        "## Release history",
        "",
        "| Version | Date |",
        "| --- | --- |",
        "| `3.0.1` | today |",
      ].join("\n"),
      "docs/specs/REQUIREMENTS.md": "# Requirements\n\n| Release | `3.0.1` |\n",
      "docs/specs/API_SPEC.md": "# API\n\n| Release | `3.0.1` |\n",
      "docs/specs/DECISIONS.md": "# Decisions\n\n| Release | `3.0.1` |\n",
      "docs/specs/SPEC_TRACEABILITY.md":
        "# Traceability\n\n| Release | `3.0.1` |\n",
    }),
  );

  assert.equal(
    extractSpecReleaseVersion(files.get("docs/specs/README.md")),
    "3.0.1",
  );
  assert.deepEqual(
    extractReadmeReleaseRows(files.get("docs/specs/README.md")),
    ["3.0.1"],
  );
  assert.deepEqual(verifySpecReleaseMetadata(files), []);

  assert.equal(
    extractSpecReleaseVersion("# API\n\n## 본문\n\n| Release | `3.0.1` |\n"),
    null,
  );

  files.set("docs/specs/API_SPEC.md", "# API\n\n| Release | `3.0.0` |\n");
  assert.match(
    verifySpecReleaseMetadata(files).join("\n"),
    /release versions must match/,
  );

  files.set("docs/specs/API_SPEC.md", "# API\n\n| Release | `3.0.1` |\n");
  files.set(
    "docs/specs/README.md",
    "# Spec\n\n| Release | `3.0.1` |\n\n## Release history\n\n| Version | Date |\n| --- | --- |\n| `3.0.0` | yesterday |\n",
  );
  assert.match(
    verifySpecReleaseMetadata(files).join("\n"),
    /latest release row \(3\.0\.0\).*header \(3\.0\.1\)/,
  );
});

test("validates Runtime Swagger release against the canonical spec parser", () => {
  const specReadmeContent = "# Spec\n\n| Release | `3.0.1` |\n";
  const swaggerConfigContent = [
    "class SwaggerConfig {",
    '  static final String SPEC_RELEASE_VERSION = "3.0.1";',
    "}",
  ].join("\n");

  assert.equal(extractSwaggerSpecReleaseVersion(swaggerConfigContent), "3.0.1");
  assert.deepEqual(
    verifyRuntimeSwaggerSpecVersion({
      specReadmeContent,
      swaggerConfigContent,
    }),
    [],
  );

  assert.match(
    verifyRuntimeSwaggerSpecVersion({
      specReadmeContent,
      swaggerConfigContent: swaggerConfigContent.replace("3.0.1", "3.0.0"),
    }).join("\n"),
    /Runtime Swagger release \(3\.0\.0\).*canonical spec release \(3\.0\.1\)/,
  );
  assert.match(
    verifyRuntimeSwaggerSpecVersion({
      specReadmeContent,
      swaggerConfigContent: "class SwaggerConfig {}",
    }).join("\n"),
    /must declare SPEC_RELEASE_VERSION/,
  );
});

test("validates lightweight Patch paths, metadata, sections, and duplicate IDs", () => {
  const validPath = patchPath("wallet-contract");
  assert.deepEqual(
    parsePatchDocument(validPath, createPatchDocument()).errors,
    [],
  );

  assert.match(
    parsePatchDocument(
      "docs/spec-patches/proposed/INVALID.md",
      createPatchDocument(),
    ).errors.join("\n"),
    /must match/,
  );

  const invalidMetadata = createPatchDocument(
    { status: "proposed", author: "flamingo7562" },
    { omit: ["base_spec_version"] },
  );
  const metadataErrors = parsePatchDocument(
    validPath,
    invalidMetadata,
  ).errors.join("\n");
  assert.match(metadataErrors, /missing required metadata: base_spec_version/);
  assert.match(metadataErrors, /unsupported metadata: author/);
  assert.match(metadataErrors, /status must be one of: draft, accepted/);

  const missingSections = createPatchDocument()
    .replace(/## 추가 사항[\s\S]*?## 완료 조건/, "## 완료 조건")
    .replace("- 지갑 계약과 검증 조건의 연결을 확인할 수 있다.", "");
  const sectionErrors = parsePatchDocument(
    validPath,
    missingSections,
  ).errors.join("\n");
  assert.match(sectionErrors, /requires a non-empty "## 추가 사항" section/);
  assert.match(sectionErrors, /requires a non-empty "## 완료 조건" section/);

  const secondPath = patchPath("wallet-contract-followup");
  const duplicateResult = verifyPatchSnapshot({
    changedFiles: new Set([validPath, secondPath]),
    currentFiles: createPatchSnapshot({
      [validPath]: createPatchDocument(),
      [secondPath]: createPatchDocument(),
    }),
    previousFiles: new Map(),
  });
  assert.match(duplicateResult.errors.join("\n"), /Patch ID is duplicated/);
});

test("requires complete draft content without template placeholders", () => {
  const file = patchPath("wallet-contract");
  const placeholder = createPatchDocument(
    {
      base_spec_version: "0.0.0",
      targets: [{ requirement: "REQUIREMENT-ID" }],
    },
    { bodySuffix: "TODO: replace <stable-id>." },
  );
  const errors = parsePatchDocument(file, placeholder).errors.join("\n");
  assert.match(errors, /must not contain placeholders/);
  assert.match(errors, /must replace every TEMPLATE sentinel/);
});

test("keeps draft mutable and accepted immutable in a two-state lifecycle", () => {
  const draftPath = patchPath("wallet-contract");
  const archivePath = patchPath("wallet-contract", "archive");
  const draftDocument = createPatchDocument();
  const acceptedDocument = createPatchDocument({ status: "accepted" });

  const editedDraft = verifyPatchSnapshot({
    changedFiles: new Set([draftPath]),
    currentFiles: createPatchSnapshot({
      [draftPath]: `${draftDocument}\n추가 설명.\n`,
    }),
    previousFiles: createPatchSnapshot({ [draftPath]: draftDocument }),
  });
  assert.deepEqual(editedDraft.errors, []);

  const deletedDraft = verifyPatchSnapshot({
    changedFiles: new Set([draftPath]),
    currentFiles: createPatchSnapshot(),
    previousFiles: createPatchSnapshot({ [draftPath]: draftDocument }),
  });
  assert.deepEqual(deletedDraft.errors, []);

  const newAccepted = verifyPatchSnapshot({
    changedFiles: new Set([archivePath]),
    currentFiles: createPatchSnapshot({ [archivePath]: acceptedDocument }),
    previousFiles: createPatchSnapshot(),
  });
  assert.match(newAccepted.errors.join("\n"), /new Patch must start in draft/);

  const rewrittenAccepted = verifyPatchSnapshot({
    changedFiles: new Set([archivePath]),
    currentFiles: createPatchSnapshot({
      [archivePath]: `${acceptedDocument}\nEditorial rewrite.\n`,
    }),
    previousFiles: createPatchSnapshot({ [archivePath]: acceptedDocument }),
  });
  assert.match(
    rewrittenAccepted.errors.join("\n"),
    /accepted Patch is immutable/,
  );

  const deletedAccepted = verifyPatchSnapshot({
    changedFiles: new Set([archivePath]),
    currentFiles: createPatchSnapshot(),
    previousFiles: createPatchSnapshot({ [archivePath]: acceptedDocument }),
  });
  assert.match(deletedAccepted.errors.join("\n"), /must not be deleted/);
});

test("requires the Patch governance scaffold even when every file is deleted", () => {
  const result = verifyPatchSnapshot({
    changedFiles: new Set(Object.keys(PATCH_SCAFFOLD)),
    currentFiles: new Map(),
    previousFiles: createPatchSnapshot(),
  });

  for (const file of Object.keys(PATCH_SCAFFOLD)) {
    assert.match(
      result.errors.join("\n"),
      new RegExp(file.replaceAll(".", "\\.")),
    );
  }
});

test("allows separately approved DDL scope but still isolates protected specs", () => {
  const draftPath = patchPath("wallet-contract");
  const applicationPath = "frontend/src/services/wallet.js";
  const currentFiles = createPatchSnapshot({
    [draftPath]: createPatchDocument(),
  });

  const implementation = verifyPatchSnapshot({
    changedFiles: new Set([draftPath, applicationPath]),
    currentFiles,
    previousFiles: createPatchSnapshot(),
  });
  assert.deepEqual(implementation.errors, []);

  const forbidden = verifyPatchSnapshot({
    changedFiles: new Set([
      draftPath,
      applicationPath,
      "backend/src/main/resources/db/migration/V1__wallet.sql",
      "docs/specs/REQUIREMENTS.md",
    ]),
    currentFiles,
    previousFiles: createPatchSnapshot(),
  });
  assert.match(
    forbidden.warnings.join("\n"),
    /includes protected Migration or DDL/,
  );
  assert.match(
    forbidden.errors.join("\n"),
    /Draft Patch change must not include protected spec changes/,
  );
});

test("accepts a draft only with an atomic canonical SPEC release", () => {
  const draftPath = patchPath("wallet-contract");
  const archivePath = patchPath("wallet-contract", "archive");
  const targets = [
    { requirement: "WALLET-003" },
    { decision: "DEC-WALLET" },
    { operation: "POST /api/wallet/charge" },
    { traceability: "WALLET-003" },
  ];
  const draftDocument = createPatchDocument({ targets });
  const acceptedDocument = createPatchDocument({ status: "accepted", targets });
  const previousFiles = createPatchSnapshot({ [draftPath]: draftDocument });
  const currentFiles = createPatchSnapshot({ [archivePath]: acceptedDocument });

  const incompleteRelease = verifyPatchSnapshot({
    changedFiles: new Set([draftPath, archivePath]),
    currentFiles,
    previousFiles,
  });
  for (const file of [
    "docs/specs/SPEC_LOCK.json",
    "docs/specs/README.md",
    "docs/specs/REQUIREMENTS.md",
    "docs/specs/DECISIONS.md",
    "docs/specs/API_SPEC.md",
    "docs/specs/SPEC_TRACEABILITY.md",
  ]) {
    assert.match(
      incompleteRelease.errors.join("\n"),
      new RegExp(`atomically update ${file.replaceAll(".", "\\.")}`),
    );
  }

  const releasePaths = new Set([
    draftPath,
    archivePath,
    "docs/specs/SPEC_LOCK.json",
    "docs/specs/README.md",
    "docs/specs/REQUIREMENTS.md",
    "docs/specs/DECISIONS.md",
    "docs/specs/API_SPEC.md",
    "docs/specs/SPEC_TRACEABILITY.md",
  ]);
  const completeRelease = verifyPatchSnapshot({
    changedFiles: releasePaths,
    canonicalSpecVersion: "3.0.1",
    previousCanonicalSpecVersion: "3.0.0",
    currentFiles,
    previousFiles,
  });
  assert.deepEqual(completeRelease.errors, []);

  const unchangedRelease = verifyPatchSnapshot({
    changedFiles: releasePaths,
    canonicalSpecVersion: "3.0.1",
    previousCanonicalSpecVersion: "3.0.1",
    currentFiles,
    previousFiles,
  });
  assert.match(
    unchangedRelease.errors.join("\n"),
    /must advance the canonical release beyond 3\.0\.1/,
  );

  const changedContent = verifyPatchSnapshot({
    changedFiles: releasePaths,
    canonicalSpecVersion: "3.0.1",
    previousCanonicalSpecVersion: "3.0.0",
    currentFiles: createPatchSnapshot({
      [archivePath]: `${acceptedDocument}\nChanged during acceptance.\n`,
    }),
    previousFiles,
  });
  assert.match(
    changedContent.errors.join("\n"),
    /accepted transition may change only status/,
  );

  const mixedApplication = verifyPatchSnapshot({
    changedFiles: new Set([...releasePaths, "frontend/src/services/wallet.js"]),
    canonicalSpecVersion: "3.0.1",
    previousCanonicalSpecVersion: "3.0.0",
    currentFiles,
    previousFiles,
  });
  assert.match(
    mixedApplication.errors.join("\n"),
    /accepted transition must not include application code/,
  );
});

test("blocks approved releases while any draft Patch remains", () => {
  const file = patchPath("wallet-contract");
  const release = verifyPatchSnapshot({
    changedFiles: new Set(),
    currentFiles: createPatchSnapshot({ [file]: createPatchDocument() }),
    previousFiles: createPatchSnapshot({ [file]: createPatchDocument() }),
    requireDraftAcceptance: true,
  });
  assert.match(release.errors.join("\n"), /release is blocked by draft Patch/);
});

test("warns when API boundary code changes without a Patch", () => {
  const result = verifyPatchSnapshot({
    changedFiles: new Set([
      "frontend/src/services/wallet.js",
      "backend/src/main/java/com/gighub/wallet/dto/WalletResponse.java",
    ]),
    currentFiles: createPatchSnapshot(),
    previousFiles: createPatchSnapshot(),
  });

  assert.equal(result.errors.length, 0);
  assert.match(
    result.warnings.join("\n"),
    /API boundary change requires a Spec Patch or an explicit N\/A rationale/,
  );
});

test("warns about stale draft bases and shared draft targets", () => {
  const firstPath = patchPath("wallet-contract");
  const secondPath = patchPath("wallet-contract-followup", "draft", 2);
  const result = verifyPatchSnapshot({
    canonicalSpecVersion: "3.0.1",
    changedFiles: new Set([firstPath, secondPath]),
    currentFiles: createPatchSnapshot({
      [firstPath]: createPatchDocument({
        targets: [{ operation: "POST /api/wallet/charge" }],
      }),
      [secondPath]: createPatchDocument({
        patch_id: "SPEC-205-02",
        targets: [{ rest_operation: "POST /api/wallet/charge" }],
      }),
    }),
    previousFiles: new Map(),
  });

  assert.equal(result.errors.length, 0);
  assert.match(result.warnings.join("\n"), /stale base_spec_version/);
  assert.match(result.warnings.join("\n"), /target conflict/);
});

test("all mode allows a committed draft Patch with its implementation", () => {
  const temporaryRepository = fs.mkdtempSync(
    path.join(os.tmpdir(), "gighub-patch-pr-scope-"),
  );
  const script = path.resolve(__dirname, "check-project-guardrails.js");

  try {
    execFileSync("git", ["init", "--quiet"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    execFileSync("git", ["config", "user.name", "Guardrail Test"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    execFileSync("git", ["config", "user.email", "guardrail@example.com"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    writeSpecFixture(temporaryRepository);
    for (const [file, content] of Object.entries(PATCH_SCAFFOLD)) {
      writeRepositoryFile(temporaryRepository, file, content);
    }
    execFileSync("git", ["add", "."], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    execFileSync("git", ["commit", "--quiet", "-m", "baseline"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    const baseCommit = execFileSync("git", ["rev-parse", "HEAD"], {
      cwd: temporaryRepository,
      encoding: "utf8",
    }).trim();
    const missingBase = spawnSync(process.execPath, [script, "--all"], {
      cwd: temporaryRepository,
      encoding: "utf8",
    });
    assert.equal(missingBase.status, 1);
    assert.match(missingBase.stderr, /requires refs\/remotes\/origin\/dev/);
    execFileSync("git", ["update-ref", "refs/remotes/origin/dev", baseCommit], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });

    const file = patchPath("wallet-contract");
    writeRepositoryFile(temporaryRepository, file, createPatchDocument());
    writeRepositoryFile(
      temporaryRepository,
      "frontend/src/wallet.js",
      "export const wallet = 'mixed';\n",
    );
    execFileSync("git", ["add", "."], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    execFileSync("git", ["commit", "--quiet", "-m", "mixed patch"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });

    const result = spawnSync(process.execPath, [script, "--all"], {
      cwd: temporaryRepository,
      encoding: "utf8",
    });
    assert.equal(result.status, 0, result.stderr);
  } finally {
    fs.rmSync(temporaryRepository, { recursive: true, force: true });
  }
});

test("release mode blocks a committed draft Patch until SPEC acceptance", () => {
  const temporaryRepository = fs.mkdtempSync(
    path.join(os.tmpdir(), "gighub-draft-patch-scope-"),
  );
  const script = path.resolve(__dirname, "check-project-guardrails.js");

  try {
    execFileSync("git", ["init", "--quiet"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    execFileSync("git", ["config", "user.name", "Guardrail Test"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    execFileSync("git", ["config", "user.email", "guardrail@example.com"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    writeSpecFixture(temporaryRepository);
    for (const [file, content] of Object.entries(PATCH_SCAFFOLD)) {
      writeRepositoryFile(temporaryRepository, file, content);
    }
    execFileSync("git", ["add", "."], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    execFileSync("git", ["commit", "--quiet", "-m", "baseline"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    const baseCommit = execFileSync("git", ["rev-parse", "HEAD"], {
      cwd: temporaryRepository,
      encoding: "utf8",
    }).trim();
    execFileSync("git", ["update-ref", "refs/remotes/origin/dev", baseCommit], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });

    const file = patchPath("wallet-contract");
    writeRepositoryFile(temporaryRepository, file, createPatchDocument());
    writeRepositoryFile(
      temporaryRepository,
      "frontend/src/services/wallet.js",
      "export const wallet = 'draft';\n",
    );
    execFileSync("git", ["add", "."], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    execFileSync("git", ["commit", "--quiet", "-m", "add draft patch"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });

    const draft = spawnSync(process.execPath, [script, "--all"], {
      cwd: temporaryRepository,
      encoding: "utf8",
    });
    assert.equal(draft.status, 0, draft.stderr);

    const release = spawnSync(process.execPath, [script, "--release"], {
      cwd: temporaryRepository,
      encoding: "utf8",
    });
    assert.equal(release.status, 1);
    assert.match(release.stderr, /release is blocked by draft Patch/);
  } finally {
    fs.rmSync(temporaryRepository, { recursive: true, force: true });
  }
});

test("all mode fails closed after Patch governance was deleted from HEAD", () => {
  const temporaryRepository = fs.mkdtempSync(
    path.join(os.tmpdir(), "gighub-patch-deletion-base-"),
  );
  const script = path.resolve(__dirname, "check-project-guardrails.js");

  try {
    execFileSync("git", ["init", "--quiet"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    execFileSync("git", ["config", "user.name", "Guardrail Test"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    execFileSync("git", ["config", "user.email", "guardrail@example.com"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    writeSpecFixture(temporaryRepository);
    for (const [file, content] of Object.entries(PATCH_SCAFFOLD)) {
      writeRepositoryFile(temporaryRepository, file, content);
    }
    execFileSync("git", ["add", "."], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    execFileSync("git", ["commit", "--quiet", "-m", "baseline"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });

    fs.rmSync(path.join(temporaryRepository, "docs", "spec-patches"), {
      recursive: true,
      force: true,
    });
    execFileSync("git", ["add", "-A"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    execFileSync("git", ["commit", "--quiet", "-m", "delete governance"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });

    const result = spawnSync(process.execPath, [script, "--all"], {
      cwd: temporaryRepository,
      encoding: "utf8",
    });
    assert.equal(result.status, 1);
    assert.match(result.stderr, /requires refs\/remotes\/origin\/dev/);
  } finally {
    fs.rmSync(temporaryRepository, { recursive: true, force: true });
  }
});

test("all mode rejects a Patch committed directly as accepted", () => {
  const temporaryRepository = fs.mkdtempSync(
    path.join(os.tmpdir(), "gighub-patch-lifecycle-"),
  );
  const script = path.resolve(__dirname, "check-project-guardrails.js");

  try {
    execFileSync("git", ["init", "--quiet"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    execFileSync("git", ["config", "user.name", "Guardrail Test"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    execFileSync("git", ["config", "user.email", "guardrail@example.com"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    writeSpecFixture(temporaryRepository);
    for (const [file, content] of Object.entries(PATCH_SCAFFOLD)) {
      writeRepositoryFile(temporaryRepository, file, content);
    }
    execFileSync("git", ["add", "."], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    execFileSync("git", ["commit", "--quiet", "-m", "baseline"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    const baseCommit = execFileSync("git", ["rev-parse", "HEAD"], {
      cwd: temporaryRepository,
      encoding: "utf8",
    }).trim();
    execFileSync("git", ["update-ref", "refs/remotes/origin/dev", baseCommit], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });

    const skippedFile = patchPath("skipped-draft", "archive", 2);
    writeRepositoryFile(
      temporaryRepository,
      skippedFile,
      createPatchDocument({
        patch_id: "SPEC-205-02",
        status: "accepted",
      }),
    );
    execFileSync("git", ["add", "."], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    execFileSync("git", ["commit", "--quiet", "-m", "skip draft"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });

    const invalid = spawnSync(process.execPath, [script, "--all"], {
      cwd: temporaryRepository,
      encoding: "utf8",
    });
    assert.equal(invalid.status, 1);
    assert.match(invalid.stderr, /new Patch must start in draft/);
  } finally {
    fs.rmSync(temporaryRepository, { recursive: true, force: true });
  }
});

test("all mode accepts an atomic draft-to-SPEC release followed by app work", () => {
  const repository = initializePatchHistoryRepository(
    "gighub-atomic-spec-history-",
    { draft: true },
  );
  const script = path.resolve(__dirname, "check-project-guardrails.js");
  const draft = patchPath("wallet-contract");
  const archive = patchPath("wallet-contract", "archive");

  try {
    fs.rmSync(path.join(repository, ...draft.split("/")));
    writeRepositoryFile(
      repository,
      archive,
      createPatchDocument({ status: "accepted" }),
    );
    writeSpecFixture(repository, specFixtureForVersion("3.0.1"));
    execFileSync("git", ["add", "-A"], { cwd: repository });
    execFileSync("git", ["commit", "--quiet", "-m", "accept patch"], {
      cwd: repository,
    });
    writeRepositoryFile(
      repository,
      "frontend/src/feature.js",
      "export const feature = true;\n",
    );
    execFileSync("git", ["add", "frontend/src/feature.js"], { cwd: repository });
    execFileSync("git", ["commit", "--quiet", "-m", "implement feature"], {
      cwd: repository,
    });

    const result = spawnSync(process.execPath, [script, "--all"], {
      cwd: repository,
      encoding: "utf8",
    });
    assert.equal(result.status, 0, result.stderr);
  } finally {
    fs.rmSync(repository, { recursive: true, force: true });
  }
});

test("all mode rejects application code mixed into an accepted release", () => {
  const repository = initializePatchHistoryRepository(
    "gighub-mixed-spec-history-",
    { draft: true },
  );
  const script = path.resolve(__dirname, "check-project-guardrails.js");
  const draft = patchPath("wallet-contract");
  const archive = patchPath("wallet-contract", "archive");

  try {
    fs.rmSync(path.join(repository, ...draft.split("/")));
    writeRepositoryFile(
      repository,
      archive,
      createPatchDocument({ status: "accepted" }),
    );
    writeSpecFixture(repository, specFixtureForVersion("3.0.1"));
    writeRepositoryFile(
      repository,
      "frontend/src/release.js",
      "export const mixedRelease = true;\n",
    );
    execFileSync("git", ["add", "-A"], { cwd: repository });
    execFileSync("git", ["commit", "--quiet", "-m", "mixed acceptance"], {
      cwd: repository,
    });

    const result = spawnSync(process.execPath, [script, "--all"], {
      cwd: repository,
      encoding: "utf8",
    });
    assert.equal(result.status, 1);
    assert.match(
      result.stderr,
      /accepted transition must not include application code/,
    );
  } finally {
    fs.rmSync(repository, { recursive: true, force: true });
  }
});

test("detects forbidden frontend dependencies without flagging Vue reactivity", () => {
  const violations = findViolations([
    {
      file: "frontend/src/invalid.js",
      content: "import library from 'react';",
    },
    {
      file: "frontend/src/valid.js",
      content: "import { reactive } from 'vue';",
    },
  ]);

  assert.deepEqual(
    violations.map(({ file, rule }) => `${file}:${rule.name}`),
    ["frontend/src/invalid.js:React dependency"],
  );
});

test("detects forbidden backend frameworks and persistence APIs", () => {
  const violations = findViolations([
    {
      file: "backend/build.gradle",
      content:
        "implementation 'org.springframework.boot:spring-boot-starter-web'",
    },
    {
      file: "backend/src/main/java/InvalidEntity.java",
      content: "import jakarta.persistence.Entity;",
    },
    {
      file: "backend/settings.gradle",
      content: "pluginManagement { id 'org.springframework.boot' }",
    },
    {
      file: "backend/gradle/libs.versions.toml",
      content: 'jpa = { module = "org.springframework.data:spring-data-jpa" }',
    },
  ]);

  assert.deepEqual(
    violations.map(({ rule }) => rule.name),
    ["Spring Boot", "JPA", "Spring Boot", "JPA"],
  );
});

test("allows forbidden technology names in documentation and hook files", () => {
  const entries = [
    { file: "docs/STACK.md", content: "React, Spring Boot, JPA" },
    { file: ".github/ISSUE_TEMPLATE/task.yml", content: "React" },
    { file: ".husky/pre-commit", content: "Spring Boot" },
  ];

  assert.deepEqual(findViolations(entries), []);
});

test("staged mode reads index content while all mode reads the working tree", () => {
  const temporaryRepository = fs.mkdtempSync(
    path.join(os.tmpdir(), "gighub-guardrails-"),
  );
  const script = path.resolve(__dirname, "check-project-guardrails.js");
  const sourceDirectory = path.join(temporaryRepository, "frontend", "src");
  const sourceFile = path.join(sourceDirectory, "example.js");

  try {
    execFileSync("git", ["init", "--quiet"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    writeSpecFixture(temporaryRepository);
    fs.mkdirSync(sourceDirectory, { recursive: true });
    fs.writeFileSync(sourceFile, "import { ref } from 'vue';\n", "utf8");
    execFileSync("git", ["add", "frontend/src/example.js", "docs/specs"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });

    fs.writeFileSync(sourceFile, "import library from 'react';\n", "utf8");

    const stagedSafe = spawnSync(process.execPath, [script, "--staged"], {
      cwd: temporaryRepository,
      encoding: "utf8",
    });
    const workingTreeViolation = spawnSync(
      process.execPath,
      [script, "--all"],
      {
        cwd: temporaryRepository,
        encoding: "utf8",
      },
    );

    assert.equal(stagedSafe.status, 0);
    assert.equal(workingTreeViolation.status, 1);

    execFileSync("git", ["add", "frontend/src/example.js"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    fs.writeFileSync(sourceFile, "import { ref } from 'vue';\n", "utf8");

    const stagedViolation = spawnSync(process.execPath, [script, "--staged"], {
      cwd: temporaryRepository,
      encoding: "utf8",
    });

    assert.equal(stagedViolation.status, 1);
    assert.match(stagedViolation.stderr, /React dependency/);
  } finally {
    fs.rmSync(temporaryRepository, { recursive: true, force: true });
  }
});

test("architecture CLI separates staged index, working tree, and untracked sources", () => {
  const temporaryRepository = fs.mkdtempSync(
    path.join(os.tmpdir(), "gighub-architecture-guardrails-"),
  );
  const script = path.resolve(__dirname, "check-project-guardrails.js");
  const sourceFile =
    "backend/src/main/java/com/gighub/attendance/service/AttendanceService.java";
  const safeSource =
    "package com.gighub.attendance.service;\npublic class AttendanceService {}\n";
  const violatingSource =
    "package com.gighub.attendance.service;\nimport com.gighub.work.mapper.WorkCaseMapper;\npublic class AttendanceService {}\n";
  const mapperFile =
    "backend/src/main/resources/mappers/AttendanceCharacterizationMapper.xml";
  const safeMapper =
    '<mapper namespace="attendance"><select id="find" resultType="com.gighub.attendance.mapper.result.AttendanceRow" /></mapper>\n';
  const violatingMapper =
    '<mapper namespace="attendance"><select id="find" resultType="com.gighub.attendance.dto.AttendanceResponse" /></mapper>\n';
  const controllerFile =
    "backend/src/main/java/com/gighub/work/controller/WorkController.java";
  const unchangedController =
    "package com.gighub.work.controller;\n" +
    "import com.gighub.work.dto.WorkCaseEnvelopeResponse;\n" +
    "import com.gighub.work.dto.WorkCaseView;\n" +
    "public class WorkController {}\n";
  const envelopeResponseFile =
    "backend/src/main/java/com/gighub/work/dto/WorkCaseEnvelopeResponse.java";
  const unchangedEnvelopeResponse =
    "package com.gighub.work.dto;\n" +
    "public final class WorkCaseEnvelopeResponse { private WorkCaseItem item; }\n";
  const itemFile =
    "backend/src/main/java/com/gighub/work/dto/WorkCaseItem.java";
  const safeItem =
    "package com.gighub.work.dto;\npublic final class WorkCaseItem {}\n";
  const violatingItem =
    "package com.gighub.work.dto;\n" +
    "public final class WorkCaseItem {" +
    " private com.gighub.work.mapper.result.WorkCaseRow row; }\n";
  const viewFile =
    "backend/src/main/java/com/gighub/work/dto/WorkCaseView.java";
  const safeView =
    "package com.gighub.work.dto;\npublic class WorkCaseView {}\n";
  const violatingView =
    "package com.gighub.work.dto;\n" +
    "import com.gighub.work.mapper.result.WorkCaseRow;\n" +
    "public class WorkCaseView {}\n";
  const mapperInterfaceFile =
    "backend/src/main/java/com/gighub/work/mapper/WorkCaseQueryMapper.java";
  const safeMapperInterface =
    "package com.gighub.work.mapper;\npublic interface WorkCaseQueryMapper {}\n";
  const violatingMapperInterface =
    "package com.gighub.work.mapper;\n" +
    "public interface WorkCaseQueryMapper {" +
    " com.gighub.work.dto.WorkCaseItem find(); }\n";
  const workMapperFile =
    "backend/src/main/resources/mappers/WorkCaseCharacterizationMapper.xml";
  const safeWorkMapper =
    '<mapper namespace="work"><select id="find" resultType="com.gighub.work.mapper.result.WorkCaseRow" /></mapper>\n';
  const violatingWorkMapper =
    '<mapper namespace="work">' +
    '<select id="find" resultType="com.gighub.work.dto.WorkCaseItem" parameterType="com.gighub.work.dto.WorkCaseEnvelopeResponse" />' +
    "</mapper>\n";
  const nestedWorkMapper =
    '<mapper namespace="work"><select id="find" resultType="com.gighub.work.dto.WorkCaseEnvelopeResponse$WorkerSummary" /></mapper>\n';
  const commonApiWorkMapper =
    '<mapper namespace="work"><select id="find" resultType="com.gighub.common.api.PageResponse" parameterType="com.gighub.common.api.PageResponse" /></mapper>\n';
  const commonApiMapperInterface =
    "package com.gighub.work.mapper;\n" +
    "public interface WorkCaseQueryMapper {" +
    " com.gighub.common.api.PageResponse find(); }\n";
  const commonPageResponseFile =
    "backend/src/main/java/com/gighub/common/api/PageResponse.java";
  const safeCommonPageResponse =
    "package com.gighub.common.api;\npublic final class PageResponse {}\n";
  const violatingCommonPageResponse =
    "package com.gighub.common.api;\n" +
    "public final class PageResponse {" +
    " private com.gighub.work.mapper.result.WorkCaseRow row; }\n";
  const supportControllerFile =
    "backend/src/main/java/com/gighub/support/TestLoginController.java";
  const unchangedSupportController =
    "package com.gighub.support;\n" +
    "public final class TestLoginController {" +
    " public TestLoginResponse login() { return null; } }\n";
  const supportResponseFile =
    "backend/src/main/java/com/gighub/support/TestLoginResponse.java";
  const safeSupportResponse =
    "package com.gighub.support;\npublic final class TestLoginResponse {}\n";
  const violatingSupportResponse =
    "package com.gighub.support;\n" +
    "public final class TestLoginResponse {" +
    " private com.gighub.work.mapper.result.WorkCaseRow row; }\n";
  const scanControllerFile =
    "backend/src/main/java/com/gighub/attendance/controller/AttendanceScanController.java";
  const unchangedScanController =
    "package com.gighub.attendance.controller;\n" +
    "import com.gighub.attendance.dto.AttendanceScanResult;\n" +
    "public final class AttendanceScanController {" +
    " public AttendanceScanResult scan() { return null; } }\n";
  const scanResultFile =
    "backend/src/main/java/com/gighub/attendance/dto/AttendanceScanResult.java";
  const safeScanResult =
    "package com.gighub.attendance.dto;\npublic interface AttendanceScanResult {}\n";
  const recordedViewFile =
    "backend/src/main/java/com/gighub/attendance/dto/AttendanceRecordedView.java";
  const safeRecordedView =
    "package com.gighub.attendance.dto;\n" +
    "public final class AttendanceRecordedView implements AttendanceScanResult {}\n";
  const violatingRecordedView =
    "package com.gighub.attendance.dto;\n" +
    "public final class AttendanceRecordedView implements AttendanceScanResult {" +
    " private com.gighub.attendance.mapper.result.AttendanceScanRow row; }\n";
  const domainPolicyFile =
    "backend/src/main/java/com/gighub/attendance/domain/AttendancePolicy.java";
  const safeDomainPolicy =
    "package com.gighub.attendance.domain;\npublic final class AttendancePolicy {}\n";
  const violatingDomainPolicy =
    "package com.gighub.attendance.domain;\n" +
    "public final class AttendancePolicy {" +
    " private com.gighub.attendance.mapper.result.AttendanceScanRow row; }\n";
  const applicationPortFile =
    "backend/src/main/java/com/gighub/work/service/WorkApplicationPort.java";
  const safeApplicationPort =
    "package com.gighub.work.service;\npublic interface WorkApplicationPort {}\n";
  const violatingApplicationPort =
    "package com.gighub.work.service;\n" +
    "public interface WorkApplicationPort {" +
    " org.springframework.http.ResponseEntity<?> call(); }\n";
  const environment = guardrailEnvironment({
    GIGHUB_GUARDRAIL_BASE_REF: "dev2",
  });

  try {
    execFileSync("git", ["init", "--quiet"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    execFileSync("git", ["config", "user.name", "Guardrail Test"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    execFileSync("git", ["config", "user.email", "guardrail@example.com"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    execFileSync(
      "git",
      ["remote", "add", "origin", "https://example.invalid/repository.git"],
      { cwd: temporaryRepository, stdio: "ignore" },
    );
    writeSpecFixture(temporaryRepository);
    writeRepositoryFile(
      temporaryRepository,
      "docs/agent/MODULE_BOUNDARIES.json",
      `${JSON.stringify(ARCHITECTURE_MANIFEST, null, 2)}\n`,
    );
    writeRepositoryFile(temporaryRepository, sourceFile, safeSource);
    writeRepositoryFile(temporaryRepository, mapperFile, safeMapper);
    writeRepositoryFile(temporaryRepository, controllerFile, unchangedController);
    writeRepositoryFile(
      temporaryRepository,
      envelopeResponseFile,
      unchangedEnvelopeResponse,
    );
    writeRepositoryFile(temporaryRepository, itemFile, safeItem);
    writeRepositoryFile(temporaryRepository, viewFile, safeView);
    writeRepositoryFile(
      temporaryRepository,
      mapperInterfaceFile,
      safeMapperInterface,
    );
    writeRepositoryFile(temporaryRepository, workMapperFile, safeWorkMapper);
    writeRepositoryFile(
      temporaryRepository,
      commonPageResponseFile,
      safeCommonPageResponse,
    );
    writeRepositoryFile(
      temporaryRepository,
      supportControllerFile,
      unchangedSupportController,
    );
    writeRepositoryFile(
      temporaryRepository,
      supportResponseFile,
      safeSupportResponse,
    );
    writeRepositoryFile(
      temporaryRepository,
      scanControllerFile,
      unchangedScanController,
    );
    writeRepositoryFile(temporaryRepository, scanResultFile, safeScanResult);
    writeRepositoryFile(temporaryRepository, recordedViewFile, safeRecordedView);
    writeRepositoryFile(temporaryRepository, domainPolicyFile, safeDomainPolicy);
    writeRepositoryFile(
      temporaryRepository,
      applicationPortFile,
      safeApplicationPort,
    );
    execFileSync("git", ["add", "."], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    execFileSync("git", ["commit", "--quiet", "-m", "baseline"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    const baseCommit = execFileSync("git", ["rev-parse", "HEAD"], {
      cwd: temporaryRepository,
      encoding: "utf8",
    }).trim();
    execFileSync(
      "git",
      ["update-ref", "refs/remotes/origin/dev2", baseCommit],
      { cwd: temporaryRepository, stdio: "ignore" },
    );

    writeRepositoryFile(temporaryRepository, sourceFile, violatingSource);
    execFileSync("git", ["add", sourceFile], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    writeRepositoryFile(temporaryRepository, sourceFile, safeSource);

    const staged = spawnSync(process.execPath, [script, "--staged"], {
      cwd: temporaryRepository,
      encoding: "utf8",
      env: environment,
    });
    const working = spawnSync(process.execPath, [script, "--all"], {
      cwd: temporaryRepository,
      encoding: "utf8",
      env: environment,
    });
    assert.equal(staged.status, 1);
    assert.match(staged.stderr, /cross-module-mapper-import/);
    assert.equal(working.status, 0);

    execFileSync("git", ["reset", "--quiet", "HEAD", "--", sourceFile], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    writeRepositoryFile(temporaryRepository, mapperFile, violatingMapper);
    execFileSync("git", ["add", mapperFile], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    writeRepositoryFile(temporaryRepository, mapperFile, safeMapper);

    const stagedMapper = spawnSync(process.execPath, [script, "--staged"], {
      cwd: temporaryRepository,
      encoding: "utf8",
      env: environment,
    });
    const workingMapper = spawnSync(process.execPath, [script, "--all"], {
      cwd: temporaryRepository,
      encoding: "utf8",
      env: environment,
    });
    assert.equal(stagedMapper.status, 1);
    assert.match(stagedMapper.stderr, /mapper-api-response-dto-result/);
    assert.equal(workingMapper.status, 0);

    execFileSync("git", ["reset", "--quiet", "HEAD", "--", mapperFile], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });

    // Controller가 바뀌지 않아도 그 Controller가 노출하는 비표준명 DTO Registry를 읽어야 합니다.
    writeRepositoryFile(temporaryRepository, viewFile, violatingView);
    execFileSync("git", ["add", viewFile], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    const stagedView = spawnSync(process.execPath, [script, "--staged"], {
      cwd: temporaryRepository,
      encoding: "utf8",
      env: environment,
    });
    assert.equal(stagedView.status, 1);
    assert.match(stagedView.stderr, /api-dto-mapper-type-import/);
    execFileSync("git", ["reset", "--quiet", "HEAD", "--", viewFile], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    writeRepositoryFile(temporaryRepository, viewFile, safeView);

    // Response가 같은 package의 Item을 import 없이 참조해도 API DTO closure에 포함됩니다.
    writeRepositoryFile(temporaryRepository, itemFile, violatingItem);
    execFileSync("git", ["add", itemFile], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    const stagedNestedItem = spawnSync(process.execPath, [script, "--staged"], {
      cwd: temporaryRepository,
      encoding: "utf8",
      env: environment,
    });
    const workingNestedItem = spawnSync(process.execPath, [script, "--all"], {
      cwd: temporaryRepository,
      encoding: "utf8",
      env: environment,
    });
    assert.equal(stagedNestedItem.status, 1);
    assert.match(stagedNestedItem.stderr, /api-dto-mapper-type-import/);
    assert.equal(workingNestedItem.status, 1);
    assert.match(workingNestedItem.stderr, /api-dto-mapper-type-import/);
    execFileSync("git", ["reset", "--quiet", "HEAD", "--", itemFile], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    writeRepositoryFile(temporaryRepository, itemFile, safeItem);

    writeRepositoryFile(
      temporaryRepository,
      mapperInterfaceFile,
      violatingMapperInterface,
    );
    const workingMapperInterface = spawnSync(
      process.execPath,
      [script, "--all"],
      {
        cwd: temporaryRepository,
        encoding: "utf8",
        env: environment,
      },
    );
    assert.equal(workingMapperInterface.status, 1);
    assert.match(workingMapperInterface.stderr, /mapper-api-dto-import/);
    writeRepositoryFile(
      temporaryRepository,
      mapperInterfaceFile,
      safeMapperInterface,
    );

    writeRepositoryFile(temporaryRepository, workMapperFile, violatingWorkMapper);
    execFileSync("git", ["add", workMapperFile], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    const stagedNestedItemMapper = spawnSync(
      process.execPath,
      [script, "--staged"],
      {
        cwd: temporaryRepository,
        encoding: "utf8",
        env: environment,
      },
    );
    const workingNestedItemMapper = spawnSync(
      process.execPath,
      [script, "--all"],
      {
        cwd: temporaryRepository,
        encoding: "utf8",
        env: environment,
      },
    );
    assert.equal(stagedNestedItemMapper.status, 1);
    assert.match(
      stagedNestedItemMapper.stderr,
      /mapper-api-response-dto-result/,
    );
    assert.equal(workingNestedItemMapper.status, 1);
    assert.match(
      workingNestedItemMapper.stderr,
      /mapper-api-response-dto-result/,
    );
    execFileSync("git", ["reset", "--quiet", "HEAD", "--", workMapperFile], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    writeRepositoryFile(temporaryRepository, workMapperFile, safeWorkMapper);

    writeRepositoryFile(temporaryRepository, workMapperFile, nestedWorkMapper);
    execFileSync("git", ["add", workMapperFile], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    const stagedNestedApiType = spawnSync(
      process.execPath,
      [script, "--staged"],
      {
        cwd: temporaryRepository,
        encoding: "utf8",
        env: environment,
      },
    );
    assert.equal(stagedNestedApiType.status, 1);
    assert.match(
      stagedNestedApiType.stderr,
      /mapper-api-response-dto-result/,
    );
    execFileSync("git", ["reset", "--quiet", "HEAD", "--", workMapperFile], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    writeRepositoryFile(temporaryRepository, workMapperFile, safeWorkMapper);

    // 공통 Page/Envelope 타입도 dto 디렉터리 밖의 공개 API 경계입니다.
    writeRepositoryFile(
      temporaryRepository,
      commonPageResponseFile,
      violatingCommonPageResponse,
    );
    execFileSync("git", ["add", commonPageResponseFile], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    const stagedCommonApi = spawnSync(process.execPath, [script, "--staged"], {
      cwd: temporaryRepository,
      encoding: "utf8",
      env: environment,
    });
    const workingCommonApi = spawnSync(process.execPath, [script, "--all"], {
      cwd: temporaryRepository,
      encoding: "utf8",
      env: environment,
    });
    assert.equal(stagedCommonApi.status, 1);
    assert.match(stagedCommonApi.stderr, /api-dto-mapper-type-import/);
    assert.equal(workingCommonApi.status, 1);
    assert.match(workingCommonApi.stderr, /api-dto-mapper-type-import/);
    execFileSync(
      "git",
      ["reset", "--quiet", "HEAD", "--", commonPageResponseFile],
      { cwd: temporaryRepository, stdio: "ignore" },
    );
    writeRepositoryFile(
      temporaryRepository,
      commonPageResponseFile,
      safeCommonPageResponse,
    );

    writeRepositoryFile(
      temporaryRepository,
      mapperInterfaceFile,
      commonApiMapperInterface,
    );
    const workingCommonApiMapper = spawnSync(
      process.execPath,
      [script, "--all"],
      {
        cwd: temporaryRepository,
        encoding: "utf8",
        env: environment,
      },
    );
    assert.equal(workingCommonApiMapper.status, 1);
    assert.match(workingCommonApiMapper.stderr, /mapper-api-dto-import/);
    writeRepositoryFile(
      temporaryRepository,
      mapperInterfaceFile,
      safeMapperInterface,
    );

    writeRepositoryFile(
      temporaryRepository,
      workMapperFile,
      commonApiWorkMapper,
    );
    execFileSync("git", ["add", workMapperFile], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    const stagedCommonApiMapper = spawnSync(
      process.execPath,
      [script, "--staged"],
      {
        cwd: temporaryRepository,
        encoding: "utf8",
        env: environment,
      },
    );
    assert.equal(stagedCommonApiMapper.status, 1);
    assert.match(
      stagedCommonApiMapper.stderr,
      /mapper-api-(?:request-dto-parameter|response-dto-result)/,
    );
    execFileSync("git", ["reset", "--quiet", "HEAD", "--", workMapperFile], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    writeRepositoryFile(temporaryRepository, workMapperFile, safeWorkMapper);

    // support package의 *Response도 디렉터리 이름과 무관하게 API 경계로 취급합니다.
    writeRepositoryFile(
      temporaryRepository,
      supportResponseFile,
      violatingSupportResponse,
    );
    execFileSync("git", ["add", supportResponseFile], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    const stagedSupportResponse = spawnSync(
      process.execPath,
      [script, "--staged"],
      {
        cwd: temporaryRepository,
        encoding: "utf8",
        env: environment,
      },
    );
    const workingSupportResponse = spawnSync(
      process.execPath,
      [script, "--all"],
      {
        cwd: temporaryRepository,
        encoding: "utf8",
        env: environment,
      },
    );
    assert.equal(stagedSupportResponse.status, 1);
    assert.match(stagedSupportResponse.stderr, /api-dto-mapper-type-import/);
    assert.equal(workingSupportResponse.status, 1);
    assert.match(workingSupportResponse.stderr, /api-dto-mapper-type-import/);
    execFileSync(
      "git",
      ["reset", "--quiet", "HEAD", "--", supportResponseFile],
      { cwd: temporaryRepository, stdio: "ignore" },
    );
    writeRepositoryFile(
      temporaryRepository,
      supportResponseFile,
      safeSupportResponse,
    );

    // 공개 interface 응답의 concrete 구현도 전이적으로 API 계약에 포함됩니다.
    writeRepositoryFile(
      temporaryRepository,
      recordedViewFile,
      violatingRecordedView,
    );
    execFileSync("git", ["add", recordedViewFile], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    const stagedPolymorphicResponse = spawnSync(
      process.execPath,
      [script, "--staged"],
      {
        cwd: temporaryRepository,
        encoding: "utf8",
        env: environment,
      },
    );
    const workingPolymorphicResponse = spawnSync(
      process.execPath,
      [script, "--all"],
      {
        cwd: temporaryRepository,
        encoding: "utf8",
        env: environment,
      },
    );
    assert.equal(stagedPolymorphicResponse.status, 1);
    assert.match(
      stagedPolymorphicResponse.stderr,
      /api-dto-mapper-type-import/,
    );
    assert.equal(workingPolymorphicResponse.status, 1);
    assert.match(
      workingPolymorphicResponse.stderr,
      /api-dto-mapper-type-import/,
    );
    execFileSync(
      "git",
      ["reset", "--quiet", "HEAD", "--", recordedViewFile],
      { cwd: temporaryRepository, stdio: "ignore" },
    );
    writeRepositoryFile(
      temporaryRepository,
      recordedViewFile,
      safeRecordedView,
    );

    writeRepositoryFile(
      temporaryRepository,
      domainPolicyFile,
      violatingDomainPolicy,
    );
    const workingDomainFqcn = spawnSync(process.execPath, [script, "--all"], {
      cwd: temporaryRepository,
      encoding: "utf8",
      env: environment,
    });
    assert.equal(workingDomainFqcn.status, 1);
    assert.match(workingDomainFqcn.stderr, /domain-forbidden-import/);
    writeRepositoryFile(
      temporaryRepository,
      domainPolicyFile,
      safeDomainPolicy,
    );

    writeRepositoryFile(
      temporaryRepository,
      applicationPortFile,
      violatingApplicationPort,
    );
    const workingApplicationFqcn = spawnSync(
      process.execPath,
      [script, "--all"],
      {
        cwd: temporaryRepository,
        encoding: "utf8",
        env: environment,
      },
    );
    assert.equal(workingApplicationFqcn.status, 1);
    assert.match(
      workingApplicationFqcn.stderr,
      /application-interface-web-import/,
    );
    writeRepositoryFile(
      temporaryRepository,
      applicationPortFile,
      safeApplicationPort,
    );

    const untrackedSource =
      "backend/src/main/java/com/gighub/newmodule/service/NewService.java";
    writeRepositoryFile(
      temporaryRepository,
      untrackedSource,
      "package com.gighub.newmodule.service;\nimport com.gighub.work.mapper.WorkCaseMapper;\n",
    );
    const untracked = spawnSync(process.execPath, [script, "--all"], {
      cwd: temporaryRepository,
      encoding: "utf8",
      env: environment,
    });
    assert.equal(untracked.status, 1);
    assert.match(untracked.stderr, /unmapped-source-package-root/);
    fs.rmSync(path.join(temporaryRepository, ...untrackedSource.split("/")));

    for (let index = 0; index < 10; index += 1) {
      writeRepositoryFile(
        temporaryRepository,
        `notes/review-${index}.md`,
        `# Review ${index}\n`,
      );
    }
    execFileSync("git", ["init", "--quiet", "notes/isolated-worktree"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    const warningOnly = spawnSync(process.execPath, [script, "--all"], {
      cwd: temporaryRepository,
      encoding: "utf8",
      env: environment,
    });
    assert.equal(warningOnly.status, 0);
    assert.match(warningOnly.stderr, /Review scope warnings/);
    assert.doesNotMatch(warningOnly.stderr, /warning calculation failed/);
  } finally {
    fs.rmSync(temporaryRepository, { recursive: true, force: true });
  }
});

test("fails clearly when the protected-spec manifest is absent", () => {
  const temporaryRepository = fs.mkdtempSync(
    path.join(os.tmpdir(), "gighub-spec-lock-missing-"),
  );
  const script = path.resolve(__dirname, "check-project-guardrails.js");

  try {
    execFileSync("git", ["init", "--quiet"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    writeRepositoryFile(
      temporaryRepository,
      "docs/specs/API_SPEC.md",
      "# Unlocked\n",
    );
    execFileSync("git", ["add", "docs/specs/API_SPEC.md"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });

    const workingResult = spawnSync(process.execPath, [script, "--all"], {
      cwd: temporaryRepository,
      encoding: "utf8",
    });
    const stagedResult = spawnSync(process.execPath, [script, "--staged"], {
      cwd: temporaryRepository,
      encoding: "utf8",
    });

    assert.equal(workingResult.status, 1);
    assert.match(workingResult.stderr, /manifest is missing/);
    assert.equal(stagedResult.status, 1);
    assert.match(stagedResult.stderr, /missing from the staged index/);
  } finally {
    fs.rmSync(temporaryRepository, { recursive: true, force: true });
  }
});

test("checks working tree and staged spec contents independently", () => {
  const temporaryRepository = fs.mkdtempSync(
    path.join(os.tmpdir(), "gighub-spec-lock-content-"),
  );
  const script = path.resolve(__dirname, "check-project-guardrails.js");
  const specPath = "docs/specs/API_SPEC.md";

  try {
    execFileSync("git", ["init", "--quiet"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    writeSpecFixture(temporaryRepository);
    execFileSync("git", ["add", "docs/specs"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });

    const initialWorking = spawnSync(process.execPath, [script, "--all"], {
      cwd: temporaryRepository,
      encoding: "utf8",
    });
    const initialStaged = spawnSync(process.execPath, [script, "--staged"], {
      cwd: temporaryRepository,
      encoding: "utf8",
    });
    assert.equal(initialWorking.status, 0);
    assert.equal(initialStaged.status, 0);

    writeRepositoryFile(
      temporaryRepository,
      specPath,
      "# API contract\n\nUnstaged divergence.\n",
    );

    const stagedStillLocked = spawnSync(
      process.execPath,
      [script, "--staged"],
      {
        cwd: temporaryRepository,
        encoding: "utf8",
      },
    );
    const workingMismatch = spawnSync(process.execPath, [script, "--all"], {
      cwd: temporaryRepository,
      encoding: "utf8",
    });

    assert.equal(stagedStillLocked.status, 0);
    assert.equal(workingMismatch.status, 1);
    assert.match(workingMismatch.stderr, /hash mismatch/);

    execFileSync("git", ["add", specPath], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    const stagedMismatch = spawnSync(process.execPath, [script, "--staged"], {
      cwd: temporaryRepository,
      encoding: "utf8",
    });
    assert.equal(stagedMismatch.status, 1);
    assert.match(stagedMismatch.stderr, /hash mismatch/);
  } finally {
    fs.rmSync(temporaryRepository, { recursive: true, force: true });
  }
});

test("rejects unlisted and deleted protected spec files", () => {
  const temporaryRepository = fs.mkdtempSync(
    path.join(os.tmpdir(), "gighub-spec-lock-shape-"),
  );
  const script = path.resolve(__dirname, "check-project-guardrails.js");
  const listedPath = "docs/specs/API_SPEC.md";
  const unlistedPath = "docs/specs/UNLISTED.md";

  try {
    execFileSync("git", ["init", "--quiet"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    writeSpecFixture(temporaryRepository);
    execFileSync("git", ["add", "docs/specs"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });

    writeRepositoryFile(temporaryRepository, unlistedPath, "# Unlisted\n");
    const unlistedWorking = spawnSync(process.execPath, [script, "--all"], {
      cwd: temporaryRepository,
      encoding: "utf8",
    });
    assert.equal(unlistedWorking.status, 1);
    assert.match(unlistedWorking.stderr, /not listed/);

    execFileSync("git", ["add", unlistedPath], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    const unlistedStaged = spawnSync(process.execPath, [script, "--staged"], {
      cwd: temporaryRepository,
      encoding: "utf8",
    });
    assert.equal(unlistedStaged.status, 1);
    assert.match(unlistedStaged.stderr, /not listed/);

    fs.rmSync(path.join(temporaryRepository, ...unlistedPath.split("/")));
    execFileSync("git", ["add", "-A", "docs/specs"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    fs.rmSync(path.join(temporaryRepository, ...listedPath.split("/")));

    const deletedWorking = spawnSync(process.execPath, [script, "--all"], {
      cwd: temporaryRepository,
      encoding: "utf8",
    });
    assert.equal(deletedWorking.status, 1);
    assert.match(deletedWorking.stderr, /missing or deleted/);

    execFileSync("git", ["add", "-A", "docs/specs"], {
      cwd: temporaryRepository,
      stdio: "ignore",
    });
    const deletedStaged = spawnSync(process.execPath, [script, "--staged"], {
      cwd: temporaryRepository,
      encoding: "utf8",
    });
    assert.equal(deletedStaged.status, 1);
    assert.match(deletedStaged.stderr, /missing or deleted/);
  } finally {
    fs.rmSync(temporaryRepository, { recursive: true, force: true });
  }
});
