const assert = require("node:assert/strict");
const { execFileSync, spawnSync } = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const test = require("node:test");

const {
  extractReadmeReleaseRows,
  extractSpecReleaseVersion,
  findViolations,
  hashNormalizedSpecContent,
  normalizeSpecContent,
  parsePatchDocument,
  parseMode,
  parseSpecManifest,
  selectIntegrationBaseBranch,
  splitNullSeparated,
  verifyPatchSnapshot,
  verifySpecReleaseMetadata,
} = require("./check-project-guardrails");

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
    execFileSync(
      "git",
      ["config", "user.email", "guardrail@example.com"],
      {
        cwd: temporaryRepository,
        stdio: "ignore",
      },
    );
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

test("allows draft with application code but isolates DDL and protected specs", () => {
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
    forbidden.errors.join("\n"),
    /must not include Migration or DDL/,
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
