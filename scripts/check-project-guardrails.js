#!/usr/bin/env node

const { execFileSync } = require("node:child_process");
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");

const SPEC_ROOT = "docs/specs";
const SPEC_MANIFEST_PATH = `${SPEC_ROOT}/SPEC_LOCK.json`;
const SPEC_MANIFEST_VERSION = 1;
const SPEC_HASH_ALGORITHM = "sha256";
const SPEC_NORMALIZATION = "crlf-to-lf";
const CANONICAL_SPEC_MARKDOWN_PATHS = [
  `${SPEC_ROOT}/README.md`,
  `${SPEC_ROOT}/REQUIREMENTS.md`,
  `${SPEC_ROOT}/API_SPEC.md`,
  `${SPEC_ROOT}/DECISIONS.md`,
  `${SPEC_ROOT}/SPEC_TRACEABILITY.md`,
];
const PATCH_ROOT = "docs/spec-patches";
const PATCH_README_PATH = `${PATCH_ROOT}/README.md`;
const PATCH_TEMPLATE_PATH = `${PATCH_ROOT}/TEMPLATE.md`;
const PATCH_KEEP_PATHS = new Set([
  `${PATCH_ROOT}/draft/.gitkeep`,
  `${PATCH_ROOT}/archive/.gitkeep`,
]);
const PATCH_REQUIRED_METADATA = [
  "patch_id",
  "status",
  "issue",
  "base_spec_version",
  "targets",
];
const PATCH_SUPPORTED_METADATA = new Set(PATCH_REQUIRED_METADATA);
const PATCH_REQUIRED_SECTIONS = ["추가 사항", "완료 조건"];
const PATCH_STATUSES = new Set(["draft", "accepted"]);
const PATCH_ACTIVE_STATUSES = new Set(["draft"]);
const PATCH_ARCHIVED_STATUSES = new Set(["accepted"]);
const PATCH_ALLOWED_TRANSITIONS = new Map([
  ["draft", new Set(["accepted"])],
  ["accepted", new Set()],
]);
const PATCH_TARGET_SPEC_PATHS = new Map([
  ["requirement", `${SPEC_ROOT}/REQUIREMENTS.md`],
  ["api", `${SPEC_ROOT}/API_SPEC.md`],
  ["operation", `${SPEC_ROOT}/API_SPEC.md`],
  ["rest_operation", `${SPEC_ROOT}/API_SPEC.md`],
  ["rest-operation", `${SPEC_ROOT}/API_SPEC.md`],
  ["decision", `${SPEC_ROOT}/DECISIONS.md`],
  ["traceability", `${SPEC_ROOT}/SPEC_TRACEABILITY.md`],
]);
const ARCHITECTURE_MANIFEST_PATH = "docs/agent/MODULE_BOUNDARIES.json";
const BACKEND_PRODUCTION_ROOT = "backend/src/main/java/";
const BACKEND_MAPPER_ROOT = "backend/src/main/resources/mappers/";
const FRONTEND_PRODUCTION_ROOT = "frontend/src/";
const FROZEN_MAPPER_API_DTO_TYPES = new Set([
  "com.gighub.badge.dto.UserBadge",
  "com.gighub.document.dto.Document",
  "com.gighub.document.dto.DocumentListItem",
  "com.gighub.document.dto.DocumentShare",
  "com.gighub.document.dto.DocumentVersion",
]);
const MIGRATION_ROOT = "backend/src/main/resources/db/migration/";
const REVIEW_FILE_WARNING_THRESHOLD = 10;
const REVIEW_LOC_WARNING_THRESHOLD = 500;
const ISSUE_FORM_PATHS = [
  ".github/ISSUE_TEMPLATE/feature_request.yml",
  ".github/ISSUE_TEMPLATE/bug_report.yml",
  ".github/ISSUE_TEMPLATE/task.yml",
];
const PULL_REQUEST_TEMPLATE_PATH = ".github/pull_request_template.md";
const CODEOWNERS_PATH = ".github/CODEOWNERS";
const GOVERNANCE_TEMPLATE_PATHS = [
  ...ISSUE_FORM_PATHS,
  PULL_REQUEST_TEMPLATE_PATH,
  CODEOWNERS_PATH,
];
const REQUIRED_ISSUE_FORM_IDS = [
  "goal",
  "acceptance",
  "non_goals",
  "risk",
  "primary_module",
  "affected_modules",
  "required_operations",
  "migration_scope",
  "verification",
  "depends_on",
];
const PATCH_FILE_PATTERN = new RegExp(
  `^${PATCH_ROOT}/(draft|archive)/` +
    "([a-z0-9]+(?:-[a-z0-9]+)*)_" +
    "issue-([1-9][0-9]*)_" +
    "([a-z0-9]+(?:-[a-z0-9]+)*)_patch_v([1-9][0-9]*)\\.md$",
);
const PATCH_ID_PATTERN = /^SPEC-([1-9][0-9]*)-(?!00$)([0-9]{2})$/;
const SEMVER_PATTERN = /^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$/;
const DEFAULT_INTEGRATION_BASE = "dev";
const ALLOWED_INTEGRATION_BASES = new Set(["main", "dev", "dev2"]);
const LOCAL_BASE_ENVIRONMENT_VARIABLE = "GIGHUB_GUARDRAIL_BASE_REF";

function git(args, options = {}) {
  return execFileSync("git", args, {
    encoding: "utf8",
    stdio: ["ignore", "pipe", "inherit"],
    ...options,
  });
}

function gitOptional(args) {
  try {
    return execFileSync("git", args, {
      encoding: "utf8",
      stdio: ["ignore", "pipe", "pipe"],
    });
  } catch {
    return null;
  }
}

function normalizePath(file) {
  return file.replace(/\\/g, "/");
}

function comparePaths(left, right) {
  if (left < right) return -1;
  if (left > right) return 1;
  return 0;
}

function compareSemverVersions(left, right) {
  const leftParts = left.split(".").map(Number);
  const rightParts = right.split(".").map(Number);
  for (let index = 0; index < 3; index += 1) {
    if (leftParts[index] !== rightParts[index]) {
      return leftParts[index] - rightParts[index];
    }
  }
  return 0;
}

function splitNullSeparated(output) {
  return output.split("\0").map(normalizePath).filter(Boolean);
}

function getStagedFiles() {
  return splitNullSeparated(
    git(["diff", "--cached", "--name-only", "-z", "--diff-filter=ACMR"]),
  );
}

function selectIntegrationBaseBranch({
  githubBaseRef,
  localBaseRef,
  hasOriginRemote,
}) {
  const normalize = (value) => (typeof value === "string" ? value.trim() : "");
  const githubBase = hasOriginRemote ? normalize(githubBaseRef) : "";
  const localBase = hasOriginRemote ? normalize(localBaseRef) : "";

  if (githubBase && localBase && githubBase !== localBase) {
    throw new Error(
      `Guardrail comparison base mismatch: GITHUB_BASE_REF=${githubBase}, ${LOCAL_BASE_ENVIRONMENT_VARIABLE}=${localBase}.`,
    );
  }

  const selectedBase = githubBase || localBase || DEFAULT_INTEGRATION_BASE;
  if (!ALLOWED_INTEGRATION_BASES.has(selectedBase)) {
    throw new Error(
      `Guardrail comparison base must be one of ${[...ALLOWED_INTEGRATION_BASES].join(", ")}; received ${selectedBase}.`,
    );
  }
  return selectedBase;
}

function getIntegrationBaseBranch() {
  const hasOriginRemote = gitOptional(["remote", "get-url", "origin"]) !== null;
  return selectIntegrationBaseBranch({
    githubBaseRef: process.env.GITHUB_BASE_REF,
    localBaseRef: process.env[LOCAL_BASE_ENVIRONMENT_VARIABLE],
    hasOriginRemote,
  });
}

function getAllComparisonBase() {
  const head = gitOptional(["rev-parse", "--verify", "HEAD"]);
  if (head === null) return null;

  const integrationBase = getIntegrationBaseBranch();
  const remoteBaseRef = `refs/remotes/origin/${integrationBase}`;
  const remoteBase = gitOptional(["rev-parse", "--verify", remoteBaseRef]);
  if (remoteBase === null) {
    throw new Error(
      `Guardrail full-scope validation requires ${remoteBaseRef}; fetch latest origin/${integrationBase} before continuing.`,
    );
  }

  const mergeBase = gitOptional(["merge-base", "HEAD", remoteBaseRef])?.trim();
  if (!mergeBase) {
    throw new Error(
      `Guardrail full-scope validation requires a merge base with origin/${integrationBase}; fetch full history before continuing.`,
    );
  }
  return mergeBase;
}

function getChangedFiles(mode) {
  const changed = new Set();
  const diffArguments = [
    "--name-only",
    "-z",
    "--diff-filter=ACMRD",
    "--no-renames",
  ];
  const commands = [];

  if (mode === "staged") {
    commands.push(["diff", "--cached", ...diffArguments]);
  } else {
    const comparisonBase = getAllComparisonBase();

    // PR 전체 범위와 아직 커밋하지 않은 후보 상태를 한 번에 비교한다.
    if (comparisonBase) {
      commands.push(["diff", comparisonBase, ...diffArguments]);
    } else {
      commands.push(["diff", "--cached", ...diffArguments]);
      commands.push(["diff", ...diffArguments]);
    }
    commands.push(["ls-files", "--others", "--exclude-standard", "-z"]);
  }

  for (const args of commands) {
    const output = gitOptional(args);
    if (output === null) continue;
    for (const file of splitNullSeparated(output)) {
      changed.add(file);
    }
  }

  return changed;
}

function getWorkingTreeFiles() {
  return splitNullSeparated(
    git(["ls-files", "--cached", "--others", "--exclude-standard", "-z"]),
  );
}

function isIgnoredDocumentation(file) {
  return (
    file.startsWith("docs/") ||
    file.startsWith(".github/") ||
    file.startsWith(".husky/") ||
    file.endsWith(".md") ||
    file === ".gitmessage.txt"
  );
}

function isPackageManifest(file) {
  return /(^|\/)(package(-lock)?\.json|pnpm-lock\.yaml|yarn\.lock)$/.test(file);
}

function isFrontendSourceOrConfig(file) {
  return (
    (file.startsWith("frontend/") && /\.[cm]?[jt]sx?$|\.vue$/.test(file)) ||
    /(^|\/)vite\.config\.[cm]?[jt]s$/.test(file)
  );
}

function isBackendSourceOrBuild(file) {
  return (
    (file.startsWith("backend/") &&
      /(^|\/)(pom\.xml|build\.gradle(\.kts)?|settings\.gradle(\.kts)?|gradle\.properties|.*\.java|.*\.xml|.*\.toml)$/.test(
        file,
      )) ||
    /^(pom\.xml|build\.gradle(\.kts)?|settings\.gradle(\.kts)?|gradle\.properties|gradle\/.*\.toml)$/.test(
      file,
    )
  );
}

function isFrontendProductionSource(file) {
  return (
    file.startsWith(FRONTEND_PRODUCTION_ROOT) &&
    /\.(?:js|mjs|cjs|ts|tsx|vue)$/.test(file) &&
    !/(?:^|\/)(?:__tests__|test|tests)(?:\/|$)/.test(file) &&
    !/\.(?:spec|test)\.[^.]+$/.test(file)
  );
}

function isBackendProductionJava(file) {
  return file.startsWith(BACKEND_PRODUCTION_ROOT) && file.endsWith(".java");
}

function isBackendControllerJava(file) {
  return isBackendProductionJava(file) && file.includes("/controller/");
}

function isBackendMapperXml(file) {
  return file.startsWith(BACKEND_MAPPER_ROOT) && file.endsWith(".xml");
}

function isArchitectureSource(file) {
  return (
    isBackendProductionJava(file) ||
    isBackendMapperXml(file) ||
    isFrontendProductionSource(file)
  );
}

function parseArchitectureManifest(content) {
  let manifest;
  try {
    manifest = JSON.parse(String(content));
  } catch (error) {
    return {
      manifest: null,
      errors: [
        `${ARCHITECTURE_MANIFEST_PATH} is not valid JSON: ${error.message}`,
      ],
    };
  }

  const errors = [];
  if (!isPlainObject(manifest)) {
    return {
      manifest: null,
      errors: [`${ARCHITECTURE_MANIFEST_PATH} root must be a JSON object.`],
    };
  }
  if (manifest.schemaVersion !== 1) {
    errors.push(`${ARCHITECTURE_MANIFEST_PATH} schemaVersion must be 1.`);
  }
  if (
    !Array.isArray(manifest.logicalModules) ||
    manifest.logicalModules.length === 0
  ) {
    errors.push(
      `${ARCHITECTURE_MANIFEST_PATH} logicalModules must be a non-empty array.`,
    );
  }

  const moduleIds = new Set();
  const packageRoots = new Set();
  for (const module of manifest.logicalModules ?? []) {
    if (
      !isPlainObject(module) ||
      typeof module.id !== "string" ||
      !/^[a-z][a-z0-9-]*$/.test(module.id) ||
      !Array.isArray(module.packageRoots) ||
      module.packageRoots.length === 0
    ) {
      errors.push(
        `${ARCHITECTURE_MANIFEST_PATH} each logical module must declare id and packageRoots.`,
      );
      continue;
    }
    if (moduleIds.has(module.id)) {
      errors.push(
        `${ARCHITECTURE_MANIFEST_PATH} duplicates logical module ${module.id}.`,
      );
    }
    moduleIds.add(module.id);
    for (const packageRoot of module.packageRoots) {
      if (
        typeof packageRoot !== "string" ||
        !/^[a-z][a-z0-9]*$/.test(packageRoot)
      ) {
        errors.push(
          `${ARCHITECTURE_MANIFEST_PATH} has invalid package root ${String(packageRoot)}.`,
        );
      } else if (packageRoots.has(packageRoot)) {
        errors.push(
          `${ARCHITECTURE_MANIFEST_PATH} maps package root ${packageRoot} more than once.`,
        );
      }
      packageRoots.add(packageRoot);
    }
  }

  const guardRules = manifest.guardRules;
  if (!isPlainObject(guardRules)) {
    errors.push(`${ARCHITECTURE_MANIFEST_PATH} guardRules must be an object.`);
  } else {
    for (const rule of [
      "controllerMayImportMapper",
      "crossModuleMapperImport",
      "queryExceptionMayWrite",
    ]) {
      if (guardRules[rule] !== false) {
        errors.push(
          `${ARCHITECTURE_MANIFEST_PATH} guardRules.${rule} must be false.`,
        );
      }
    }
    for (const rule of [
      "domainForbiddenImportPrefixes",
      "domainForbiddenImportRegexes",
    ]) {
      if (!Array.isArray(guardRules[rule]) || guardRules[rule].length === 0) {
        errors.push(
          `${ARCHITECTURE_MANIFEST_PATH} guardRules.${rule} must be a non-empty array.`,
        );
      }
      for (const entry of guardRules[rule] ?? []) {
        if (typeof entry !== "string" || entry.trim() === "") {
          errors.push(
            `${ARCHITECTURE_MANIFEST_PATH} guardRules.${rule} entries must be non-empty strings.`,
          );
        }
      }
    }
    for (const pattern of guardRules.domainForbiddenImportRegexes ?? []) {
      try {
        new RegExp(pattern);
      } catch (error) {
        errors.push(
          `${ARCHITECTURE_MANIFEST_PATH} has invalid domain import regex ${String(pattern)}: ${error.message}`,
        );
      }
    }
  }

  return { manifest, errors };
}

function verifyArchitectureManifestEvolution(baseline, candidate) {
  if (!baseline) return [];
  const errors = [];
  const baselineModules = moduleByPackageRoot(baseline);
  const candidateModules = moduleByPackageRoot(candidate);

  for (const [packageRoot, moduleId] of baselineModules) {
    if (!candidateModules.has(packageRoot)) {
      errors.push(
        `${ARCHITECTURE_MANIFEST_PATH} must not remove governed package root ${packageRoot}.`,
      );
    } else if (candidateModules.get(packageRoot) !== moduleId) {
      errors.push(
        `${ARCHITECTURE_MANIFEST_PATH} must not reassign package root ${packageRoot} from ${moduleId} to ${candidateModules.get(packageRoot)}.`,
      );
    }
  }

  for (const rule of [
    "domainForbiddenImportPrefixes",
    "domainForbiddenImportRegexes",
  ]) {
    const candidateEntries = new Set(candidate.guardRules[rule]);
    for (const entry of baseline.guardRules[rule]) {
      if (!candidateEntries.has(entry)) {
        errors.push(
          `${ARCHITECTURE_MANIFEST_PATH} guardRules.${rule} must not remove baseline rule ${entry}.`,
        );
      }
    }
  }
  return errors;
}

function moduleByPackageRoot(manifest) {
  const result = new Map();
  for (const module of manifest.logicalModules ?? []) {
    for (const packageRoot of module.packageRoots ?? []) {
      result.set(packageRoot, module.id);
    }
  }
  return result;
}

function javaImports(content) {
  return [
    ...String(content).matchAll(/^\s*import\s+(?:static\s+)?([^;\s]+)\s*;/gm),
  ].map((match) => match[1]);
}

function controllerDtoImports(files) {
  const result = new Set();
  for (const [file, content] of files) {
    if (!isBackendControllerJava(file)) continue;
    for (const imported of javaImports(content)) {
      if (/^com\.gighub\..*\.dto\./.test(imported)) {
        result.add(imported);
      }
    }
  }
  return result;
}

function addArchitectureViolation(violations, kind, file, target, message) {
  const id = `${kind}:${file}:${target}`;
  violations.set(id, { id, kind, file, target, message });
}

function findArchitectureViolations(files, manifest) {
  const violations = new Map();
  const modules = moduleByPackageRoot(manifest);
  const apiBoundaryDtoTypes = new Set([
    ...FROZEN_MAPPER_API_DTO_TYPES,
    ...controllerDtoImports(files),
  ]);
  const forbiddenPrefixes = manifest.guardRules.domainForbiddenImportPrefixes;
  const forbiddenRegexes = manifest.guardRules.domainForbiddenImportRegexes.map(
    (pattern) => new RegExp(pattern),
  );

  for (const [file, content] of files) {
    if (isBackendProductionJava(file)) {
      const sourceRoot = file
        .slice(BACKEND_PRODUCTION_ROOT.length)
        .split("/")[2];
      const sourceModule = modules.get(sourceRoot);
      const imports = javaImports(content);

      if (!sourceModule) {
        addArchitectureViolation(
          violations,
          "unmapped-source-package-root",
          file,
          sourceRoot || "<missing>",
          "Every production Java package root must belong to one logical module.",
        );
      }

      for (const imported of imports) {
        const mapperMatch =
          /^com\.gighub\.([a-z][a-z0-9]*)\..*\.mapper\.|^com\.gighub\.([a-z][a-z0-9]*)\.mapper\./.exec(
            imported,
          );
        if (mapperMatch) {
          const targetRoot = mapperMatch[1] ?? mapperMatch[2];
          const targetModule = modules.get(targetRoot);
          if (!targetModule) {
            addArchitectureViolation(
              violations,
              "unmapped-mapper-package-root",
              file,
              targetRoot,
              "Every imported Mapper package root must belong to one logical module.",
            );
          }
          if (file.includes("/controller/")) {
            addArchitectureViolation(
              violations,
              "controller-mapper-import",
              file,
              imported,
              "Controller must call an Application Service or Orchestrator instead of a Mapper.",
            );
          }
          if (sourceModule && targetModule && sourceModule !== targetModule) {
            addArchitectureViolation(
              violations,
              "cross-module-mapper-import",
              file,
              imported,
              `Logical module ${sourceModule} must use ${targetModule}'s public Command/Query boundary.`,
            );
          }
        }

        if (
          file.includes("/domain/") &&
          (forbiddenPrefixes.some(
            (prefix) =>
              imported === prefix || imported.startsWith(`${prefix}.`),
          ) ||
            forbiddenRegexes.some((pattern) => pattern.test(imported)))
        ) {
          addArchitectureViolation(
            violations,
            "domain-forbidden-import",
            file,
            imported,
            "Domain code must not depend on Spring, MyBatis, Web DTO, or persistence types.",
          );
        }
      }
    }

    if (isFrontendProductionSource(file)) {
      const hardcodedMockPattern =
        /\b([A-Za-z_$][\w$]*mock[\w$]*)\s*=\s*true\b/gi;
      for (const match of String(content).matchAll(hardcodedMockPattern)) {
        addArchitectureViolation(
          violations,
          "hardcoded-production-mock",
          file,
          match[1],
          "Production source must gate Mock behavior behind an explicit DEV/test-only adapter boundary.",
        );
      }
    }

    if (isBackendMapperXml(file)) {
      const resultTagPattern = /<(select|resultMap)\b[^>]*>/g;
      for (const tagMatch of String(content).matchAll(resultTagPattern)) {
        const tag = tagMatch[0];
        const typeMatch =
          /\b(?:resultType|type)\s*=\s*["'](com\.gighub\.[^"']*\.dto\.[^"']+)["']/.exec(
            tag,
          );
        if (!typeMatch) continue;
        if (
          !apiBoundaryDtoTypes.has(typeMatch[1]) &&
          !typeMatch[1].endsWith("Response")
        ) {
          continue;
        }
        const idMatch = /\bid\s*=\s*["']([^"']+)["']/.exec(tag);
        const mappingId = idMatch
          ? `${tagMatch[1]}:${idMatch[1]}`
          : `${tagMatch[1]}:offset-${tagMatch.index}`;
        addArchitectureViolation(
          violations,
          "mapper-api-response-dto-result",
          file,
          `${typeMatch[1]}#${mappingId}`,
          "Mapper XML must map persistence rows to Mapper result or read-model types, not an API Response DTO.",
        );
      }
    }
  }

  return violations;
}

function collectGitSourceSnapshot(ref, selectedFiles = null) {
  const files = new Map();
  if (!ref) return files;
  if (selectedFiles !== null) {
    for (const file of selectedFiles) {
      if (!isArchitectureSource(file)) continue;
      const content = gitOptional(["show", `${ref}:${file}`]);
      if (content !== null) files.set(file, content);
    }
    return files;
  }
  const output = gitOptional([
    "ls-tree",
    "-r",
    "--name-only",
    "-z",
    ref,
    "--",
    BACKEND_PRODUCTION_ROOT,
    BACKEND_MAPPER_ROOT,
    FRONTEND_PRODUCTION_ROOT,
  ]);
  if (output === null) return files;

  for (const file of splitNullSeparated(output)) {
    if (!isArchitectureSource(file)) continue;
    const content = gitOptional(["show", `${ref}:${file}`]);
    if (content !== null) files.set(file, content);
  }
  return files;
}

function collectStagedSourceSnapshot(selectedFiles = null) {
  const files = new Map();
  if (selectedFiles !== null) {
    for (const file of selectedFiles) {
      if (!isArchitectureSource(file)) continue;
      const content = gitOptional(["show", `:${file}`]);
      if (content !== null) files.set(file, content);
    }
    return files;
  }
  const output = git([
    "ls-files",
    "--cached",
    "-z",
    "--",
    BACKEND_PRODUCTION_ROOT,
    BACKEND_MAPPER_ROOT,
    FRONTEND_PRODUCTION_ROOT,
  ]);
  for (const file of splitNullSeparated(output)) {
    if (!isArchitectureSource(file)) continue;
    files.set(file, git(["show", `:${file}`]));
  }
  return files;
}

function collectWorkingTreeSourceSnapshot(selectedFiles = null) {
  const files = new Map();
  if (selectedFiles !== null) {
    for (const file of selectedFiles) {
      if (!isArchitectureSource(file) || !fs.existsSync(file)) continue;
      files.set(file, fs.readFileSync(file, "utf8"));
    }
    return files;
  }
  for (const file of getWorkingTreeFiles()) {
    if (!isArchitectureSource(file) || !fs.existsSync(file)) continue;
    files.set(file, fs.readFileSync(file, "utf8"));
  }
  return files;
}

function getCandidateArchitectureManifest(mode) {
  if (mode === "staged") {
    return gitOptional(["show", `:${ARCHITECTURE_MANIFEST_PATH}`]);
  }
  if (!fs.existsSync(ARCHITECTURE_MANIFEST_PATH)) return null;
  return fs.readFileSync(ARCHITECTURE_MANIFEST_PATH, "utf8");
}

function compareArchitectureViolations(baseline, candidate) {
  return [...candidate.values()].filter(
    (violation) => !baseline.has(violation.id),
  );
}

function validateArchitectureGovernance(mode) {
  try {
    const baselineRef = mode === "staged" ? "HEAD" : getAllComparisonBase();
    const baselineManifestContent = baselineRef
      ? gitOptional(["show", `${baselineRef}:${ARCHITECTURE_MANIFEST_PATH}`])
      : null;
    const candidateManifestContent = getCandidateArchitectureManifest(mode);

    // Older standalone fixtures and repositories without RF-02 remain compatible.
    // Once the manifest exists in the comparison base, deleting it fails closed.
    if (baselineManifestContent === null && candidateManifestContent === null) {
      return { errors: [], warnings: [] };
    }
    if (candidateManifestContent === null) {
      return {
        errors: [
          `Required architecture manifest is missing: ${ARCHITECTURE_MANIFEST_PATH}`,
        ],
        warnings: [],
      };
    }

    const candidateManifest = parseArchitectureManifest(
      candidateManifestContent,
    );
    if (candidateManifest.errors.length > 0) {
      return { errors: candidateManifest.errors, warnings: [] };
    }

    const baselineManifest = baselineManifestContent
      ? parseArchitectureManifest(baselineManifestContent)
      : candidateManifest;
    if (baselineManifest.errors.length > 0) {
      return { errors: baselineManifest.errors, warnings: [] };
    }
    const manifestEvolutionErrors = verifyArchitectureManifestEvolution(
      baselineManifest.manifest,
      candidateManifest.manifest,
    );
    if (manifestEvolutionErrors.length > 0) {
      return { errors: manifestEvolutionErrors, warnings: [] };
    }

    const changedFiles = getChangedFiles(mode);
    const architectureSourceChanges = [...changedFiles].filter(
      isArchitectureSource,
    );
    const manifestChanged = changedFiles.has(ARCHITECTURE_MANIFEST_PATH);
    if (architectureSourceChanges.length === 0 && !manifestChanged) {
      return { errors: [], warnings: [] };
    }
    const responseBoundaryChanged = architectureSourceChanges.some(
      (file) => isBackendMapperXml(file) || isBackendControllerJava(file),
    );
    const selectedFiles =
      manifestChanged || responseBoundaryChanged
        ? null
        : architectureSourceChanges;
    const baselineFiles = collectGitSourceSnapshot(baselineRef, selectedFiles);
    const candidateFiles =
      mode === "staged"
        ? collectStagedSourceSnapshot(selectedFiles)
        : collectWorkingTreeSourceSnapshot(selectedFiles);
    const baselineViolations = findArchitectureViolations(
      baselineFiles,
      candidateManifest.manifest,
    );
    const candidateViolations = findArchitectureViolations(
      candidateFiles,
      candidateManifest.manifest,
    );
    const newViolations = compareArchitectureViolations(
      baselineViolations,
      candidateViolations,
    );

    return {
      errors: newViolations.map(
        (violation) =>
          `${violation.kind}: ${violation.file} -> ${violation.target}. ${violation.message}`,
      ),
      warnings: [],
    };
  } catch (error) {
    return { errors: [error.message], warnings: [] };
  }
}

function isApplicationImplementationPath(file) {
  return (
    isBackendProductionJava(file) ||
    isFrontendProductionSource(file) ||
    file.startsWith("backend/src/main/resources/")
  );
}

function parseNumstat(output) {
  const stats = [];
  const raw = String(output);
  const records = raw.includes("\0") ? raw.split("\0") : raw.split(/\r?\n/);
  for (const record of records) {
    if (!record) continue;
    const firstTab = record.indexOf("\t");
    const secondTab = record.indexOf("\t", firstTab + 1);
    if (firstTab < 0 || secondTab < 0) continue;
    const added = record.slice(0, firstTab);
    const deleted = record.slice(firstTab + 1, secondTab);
    const file = normalizePath(record.slice(secondTab + 1));
    if (!file || added === "-" || deleted === "-") continue;
    stats.push({ file, added: Number(added), deleted: Number(deleted) });
  }
  return stats;
}

function findJavaTypeDeclarations(files) {
  const declarations = new Map();
  const pattern =
    /\b(?:public\s+)?(?:abstract\s+)?(class|interface|record|enum)\s+([A-Za-z_$][\w$]*)/g;
  for (const [file, content] of files) {
    if (!isBackendProductionJava(file)) continue;
    for (const match of String(content).matchAll(pattern)) {
      const id = `${file}:${match[1]}:${match[2]}`;
      declarations.set(id, `${file}:${match[2]}`);
    }
  }
  return declarations;
}

function buildReviewScopeWarnings({
  changedFiles,
  lineStats,
  addedEntries = [],
  addedTypes = null,
}) {
  const warnings = [];
  if (changedFiles.size >= REVIEW_FILE_WARNING_THRESHOLD) {
    warnings.push(
      `Review scope contains ${changedFiles.size} changed files (review threshold: ${REVIEW_FILE_WARNING_THRESHOLD}). Confirm that one issue still owns the whole diff.`,
    );
  }

  const implementationLines = lineStats
    .filter(({ file }) => isApplicationImplementationPath(file))
    .reduce((total, { added, deleted }) => total + added + deleted, 0);
  if (implementationLines >= REVIEW_LOC_WARNING_THRESHOLD) {
    warnings.push(
      `Application implementation scope changes ${implementationLines} lines (review threshold: ${REVIEW_LOC_WARNING_THRESHOLD}). Record why the change remains reviewable.`,
    );
  }

  let newTypes = addedTypes;
  if (newTypes === null) {
    newTypes = [
      ...findJavaTypeDeclarations(
        new Map(addedEntries.map(({ file, content }) => [file, content])),
      ).values(),
    ];
  }
  if (newTypes.length > 0) {
    warnings.push(
      `New Java abstractions, exceptions, or types require necessity review: ${newTypes.join(", ")}.`,
    );
  }

  return warnings;
}

function collectReviewScopeWarnings(mode) {
  try {
    const changedFiles = getChangedFiles(mode);
    const baseArguments =
      mode === "staged"
        ? ["diff", "--cached"]
        : ["diff", getAllComparisonBase()];
    const numstatOutput =
      gitOptional([...baseArguments, "--numstat", "-z", "--no-renames"]) ?? "";
    const lineStats = parseNumstat(numstatOutput);
    const addedOutput =
      gitOptional([
        ...baseArguments,
        "--name-only",
        "-z",
        "--diff-filter=A",
        "--no-renames",
      ]) ?? "";
    const addedFiles = new Set(splitNullSeparated(addedOutput));

    if (mode !== "staged") {
      for (const file of splitNullSeparated(
        gitOptional(["ls-files", "--others", "--exclude-standard", "-z"]) ?? "",
      )) {
        addedFiles.add(file);
        if (fs.existsSync(file)) {
          const content = fs.readFileSync(file, "utf8");
          lineStats.push({
            file,
            added: content.split(/\r?\n/).length,
            deleted: 0,
          });
        }
      }
    }

    const addedEntries = [];
    for (const file of addedFiles) {
      let content = null;
      if (mode === "staged") {
        content = gitOptional(["show", `:${file}`]);
      } else if (fs.existsSync(file)) {
        content = fs.readFileSync(file, "utf8");
      }
      if (content !== null) addedEntries.push({ file, content });
    }
    const baselineRef = mode === "staged" ? "HEAD" : getAllComparisonBase();
    const changedJavaFiles = [...changedFiles].filter(isBackendProductionJava);
    const baselineTypes = findJavaTypeDeclarations(
      collectGitSourceSnapshot(baselineRef, changedJavaFiles),
    );
    const candidateTypes = findJavaTypeDeclarations(
      mode === "staged"
        ? collectStagedSourceSnapshot(changedJavaFiles)
        : collectWorkingTreeSourceSnapshot(changedJavaFiles),
    );
    const addedTypes = [...candidateTypes]
      .filter(([id]) => !baselineTypes.has(id))
      .map(([, display]) => display);
    return buildReviewScopeWarnings({
      changedFiles,
      lineStats,
      addedEntries,
      addedTypes,
    });
  } catch (error) {
    return [`Review scope warning calculation failed: ${error.message}`];
  }
}

function verifyGovernanceTemplateSnapshot(files) {
  const errors = [];
  for (const file of GOVERNANCE_TEMPLATE_PATHS) {
    if (!files.has(file)) {
      errors.push(`Required governance template is missing: ${file}`);
    }
  }
  if (errors.length > 0) return errors;

  for (const file of ISSUE_FORM_PATHS) {
    const content = String(files.get(file));
    const ids = [...content.matchAll(/^\s+id:\s*([a-z][a-z0-9_-]*)\s*$/gm)].map(
      (match) => match[1],
    );
    const duplicateIds = ids.filter((id, index) => ids.indexOf(id) !== index);
    if (duplicateIds.length > 0) {
      errors.push(
        `${file} duplicates field ids: ${[...new Set(duplicateIds)].join(", ")}.`,
      );
    }
    for (const requiredId of REQUIRED_ISSUE_FORM_IDS) {
      if (!ids.includes(requiredId)) {
        errors.push(
          `${file} is missing required workflow field id: ${requiredId}.`,
        );
      }
    }
    for (const risk of ["R0", "R1", "R2", "R3"]) {
      if (!new RegExp(`^\\s+- ${risk}(?:\\s|$)`, "m").test(content)) {
        errors.push(`${file} risk options must include ${risk}.`);
      }
    }
    if (!/3\s*[~～-]\s*7/.test(content)) {
      errors.push(`${file} acceptance guidance must request 3~7 checks.`);
    }

    for (const optionalId of ["required_operations", "migration_scope"]) {
      const start = content.search(
        new RegExp(`^\\s+id:\\s*${optionalId}\\s*$`, "m"),
      );
      const remainder = start < 0 ? "" : content.slice(start);
      const nextField = remainder.slice(1).search(/^\s*- type:\s*/m);
      const block =
        nextField < 0 ? remainder : remainder.slice(0, nextField + 1);
      if (/^\s+required:\s*true\s*$/m.test(block)) {
        errors.push(`${file} ${optionalId} must remain optional.`);
      }
    }
  }

  const pullRequestTemplate = String(files.get(PULL_REQUEST_TEMPLATE_PATH));
  for (const heading of [
    "관련 이슈와 통합",
    "실제 Diff",
    "계약 대비 차이",
    "검증 결과",
    "잔여 위험",
    "리뷰와 Migration",
    "종료 상태",
  ]) {
    if (!new RegExp(`^## ${heading}$`, "m").test(pullRequestTemplate)) {
      errors.push(
        `${PULL_REQUEST_TEMPLATE_PATH} is missing heading: ${heading}.`,
      );
    }
  }

  const codeowners = String(files.get(CODEOWNERS_PATH));
  for (const ownedPath of [
    "/.github/ISSUE_TEMPLATE/",
    "/docs/GITHUB_PROJECTS_PANEL_GUIDE.md",
  ]) {
    if (!codeowners.includes(ownedPath)) {
      errors.push(`${CODEOWNERS_PATH} must protect ${ownedPath}.`);
    }
  }

  return errors;
}

function validateGovernanceTemplates(mode) {
  try {
    const changedFiles = getChangedFiles(mode);
    if (!GOVERNANCE_TEMPLATE_PATHS.some((file) => changedFiles.has(file))) {
      return [];
    }
    const baselineRef = mode === "staged" ? "HEAD" : getAllComparisonBase();
    const baselineHasGovernance = baselineRef
      ? GOVERNANCE_TEMPLATE_PATHS.some(
          (file) => gitOptional(["show", `${baselineRef}:${file}`]) !== null,
        )
      : false;
    if (!baselineHasGovernance) return [];

    const files = new Map();
    for (const file of GOVERNANCE_TEMPLATE_PATHS) {
      const content =
        mode === "staged"
          ? gitOptional(["show", `:${file}`])
          : fs.existsSync(file)
            ? fs.readFileSync(file, "utf8")
            : null;
      if (content !== null) files.set(file, content);
    }
    return verifyGovernanceTemplateSnapshot(files);
  } catch (error) {
    return [error.message];
  }
}

function collectGitMigrationSnapshot(ref, selectedFiles = null) {
  const files = new Map();
  if (!ref) return files;
  if (selectedFiles !== null) {
    for (const file of selectedFiles) {
      if (!file.startsWith(MIGRATION_ROOT)) continue;
      const content = gitOptional(["show", `${ref}:${file}`]);
      if (content !== null) files.set(file, content);
    }
    return files;
  }
  const output = gitOptional([
    "ls-tree",
    "-r",
    "--name-only",
    "-z",
    ref,
    "--",
    MIGRATION_ROOT,
  ]);
  if (output === null) return files;
  for (const file of splitNullSeparated(output)) {
    const content = gitOptional(["show", `${ref}:${file}`]);
    if (content !== null) files.set(file, content);
  }
  return files;
}

function collectCandidateMigrationSnapshot(mode, selectedFiles = null) {
  const files = new Map();
  if (selectedFiles !== null) {
    for (const file of selectedFiles) {
      if (!file.startsWith(MIGRATION_ROOT)) continue;
      const content =
        mode === "staged"
          ? gitOptional(["show", `:${file}`])
          : fs.existsSync(file)
            ? fs.readFileSync(file, "utf8")
            : null;
      if (content !== null) files.set(file, content);
    }
    return files;
  }
  if (mode === "staged") {
    const output = git(["ls-files", "--cached", "-z", "--", MIGRATION_ROOT]);
    for (const file of splitNullSeparated(output)) {
      files.set(file, git(["show", `:${file}`]));
    }
    return files;
  }
  for (const file of getWorkingTreeFiles()) {
    if (!file.startsWith(MIGRATION_ROOT) || !fs.existsSync(file)) continue;
    files.set(file, fs.readFileSync(file, "utf8"));
  }
  return files;
}

function verifyMigrationImmutability({ baselineFiles, candidateFiles }) {
  const errors = [];
  for (const [file, baselineContent] of baselineFiles) {
    if (!candidateFiles.has(file)) {
      errors.push(`Applied Flyway Migration must not be deleted: ${file}`);
    } else if (
      normalizeSpecContent(candidateFiles.get(file)) !==
      normalizeSpecContent(baselineContent)
    ) {
      errors.push(`Applied Flyway Migration must remain immutable: ${file}`);
    }
  }
  return errors;
}

function validateMigrationImmutability(mode) {
  try {
    const changedMigrations = [...getChangedFiles(mode)].filter((file) =>
      file.startsWith(MIGRATION_ROOT),
    );
    if (changedMigrations.length === 0) return [];
    const baselineRef = mode === "staged" ? "HEAD" : getAllComparisonBase();
    return verifyMigrationImmutability({
      baselineFiles: collectGitMigrationSnapshot(
        baselineRef,
        changedMigrations,
      ),
      candidateFiles: collectCandidateMigrationSnapshot(
        mode,
        changedMigrations,
      ),
    });
  } catch (error) {
    return [error.message];
  }
}

const rules = [
  {
    name: "React dependency",
    appliesTo: (file) =>
      isPackageManifest(file) || isFrontendSourceOrConfig(file),
    contentPattern:
      /("|'|`)(react|react-dom|@vitejs\/plugin-react)("|'|`)|from\s+("|'|`)react("|'|`)/i,
    message:
      "Frontend는 Vue.js를 사용해야 하므로 React 관련 의존성 또는 import를 추가할 수 없습니다.",
  },
  {
    name: "Spring Boot",
    appliesTo: isBackendSourceOrBuild,
    contentPattern: /spring-boot|org\.springframework\.boot/i,
    message:
      "Backend는 Spring Framework legacy를 사용해야 하므로 Spring Boot를 추가할 수 없습니다.",
  },
  {
    name: "JPA",
    appliesTo: isBackendSourceOrBuild,
    contentPattern:
      /JpaRepository|@Entity\b|javax\.persistence|jakarta\.persistence|hibernate-entitymanager|spring-data-jpa/i,
    message:
      "Persistence는 MyBatis를 사용해야 하므로 JPA 관련 코드를 추가할 수 없습니다.",
  },
];

function findViolations(entries) {
  const violations = [];

  for (const { file, content } of entries) {
    if (isIgnoredDocumentation(file)) {
      continue;
    }

    for (const rule of rules) {
      if (rule.appliesTo(file) && rule.contentPattern.test(content)) {
        violations.push({ file, rule });
      }
    }
  }

  return violations;
}

function readStagedEntries() {
  return getStagedFiles()
    .filter(
      (file) =>
        !isIgnoredDocumentation(file) &&
        rules.some((rule) => rule.appliesTo(file)),
    )
    .map((file) => ({
      file,
      content: git(["show", `:${file}`]),
    }));
}

function readWorkingTreeEntries() {
  const entries = [];

  for (const file of getWorkingTreeFiles()) {
    if (isIgnoredDocumentation(file) || !fs.existsSync(file)) {
      continue;
    }

    if (!rules.some((rule) => rule.appliesTo(file))) {
      continue;
    }

    entries.push({
      file,
      content: fs.readFileSync(file, "utf8"),
    });
  }

  return entries;
}

function normalizeSpecContent(content) {
  const text = Buffer.isBuffer(content)
    ? content.toString("utf8")
    : String(content);
  return text.replace(/\r\n/g, "\n");
}

function hashNormalizedSpecContent(content) {
  return crypto
    .createHash(SPEC_HASH_ALGORITHM)
    .update(normalizeSpecContent(content), "utf8")
    .digest("hex");
}

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function hasExactKeys(value, expectedKeys) {
  if (!isPlainObject(value)) return false;
  const actualKeys = Object.keys(value).sort(comparePaths);
  const sortedExpected = [...expectedKeys].sort(comparePaths);
  return (
    actualKeys.length === sortedExpected.length &&
    actualKeys.every((key, index) => key === sortedExpected[index])
  );
}

function isValidProtectedSpecPath(file) {
  if (typeof file !== "string" || file.length === 0) return false;
  if (file !== normalizePath(file)) return false;
  if (!file.startsWith(`${SPEC_ROOT}/`)) return false;
  if (file === SPEC_MANIFEST_PATH || file.endsWith("/")) return false;

  const segments = file.split("/");
  return !segments.includes(".") && !segments.includes("..");
}

function parseSpecManifest(content) {
  let parsed;
  try {
    parsed = JSON.parse(String(content));
  } catch (error) {
    return {
      entries: [],
      errors: [`${SPEC_MANIFEST_PATH} is not valid JSON: ${error.message}`],
    };
  }

  if (!isPlainObject(parsed)) {
    return {
      entries: [],
      errors: [`${SPEC_MANIFEST_PATH} root must be a JSON object.`],
    };
  }

  const errors = [];
  if (
    !hasExactKeys(parsed, ["algorithm", "files", "normalization", "version"])
  ) {
    errors.push(
      `${SPEC_MANIFEST_PATH} must contain exactly version, algorithm, normalization, and files.`,
    );
  }

  if (parsed.version !== SPEC_MANIFEST_VERSION) {
    errors.push(
      `${SPEC_MANIFEST_PATH} version must be ${SPEC_MANIFEST_VERSION}.`,
    );
  }
  if (parsed.algorithm !== SPEC_HASH_ALGORITHM) {
    errors.push(
      `${SPEC_MANIFEST_PATH} algorithm must be "${SPEC_HASH_ALGORITHM}".`,
    );
  }
  if (parsed.normalization !== SPEC_NORMALIZATION) {
    errors.push(
      `${SPEC_MANIFEST_PATH} normalization must be "${SPEC_NORMALIZATION}".`,
    );
  }
  if (!Array.isArray(parsed.files) || parsed.files.length === 0) {
    errors.push(`${SPEC_MANIFEST_PATH} files must be a non-empty array.`);
    return { entries: [], errors };
  }

  const entries = [];
  const seenPaths = new Set();

  parsed.files.forEach((entry, index) => {
    const label = `${SPEC_MANIFEST_PATH} files[${index}]`;
    if (!hasExactKeys(entry, ["path", "sha256"])) {
      errors.push(`${label} must contain exactly path and sha256.`);
      return;
    }
    if (!isValidProtectedSpecPath(entry.path)) {
      errors.push(
        `${label}.path must be a normalized repository-relative file under ${SPEC_ROOT}/ and must not be the manifest itself.`,
      );
      return;
    }
    if (!/^[0-9a-f]{64}$/.test(entry.sha256)) {
      errors.push(`${label}.sha256 must be 64 lowercase hexadecimal digits.`);
      return;
    }
    if (seenPaths.has(entry.path)) {
      errors.push(`${SPEC_MANIFEST_PATH} lists ${entry.path} more than once.`);
      return;
    }

    seenPaths.add(entry.path);
    entries.push({ path: entry.path, sha256: entry.sha256 });
  });

  const sortedPaths = entries.map((entry) => entry.path).sort(comparePaths);
  const manifestPaths = entries.map((entry) => entry.path);
  if (
    manifestPaths.length === sortedPaths.length &&
    manifestPaths.some((file, index) => file !== sortedPaths[index])
  ) {
    errors.push(`${SPEC_MANIFEST_PATH} files must be sorted by path.`);
  }

  return { entries, errors };
}

function collectWorkingTreeSpecSnapshot(cwd = process.cwd()) {
  const absoluteSpecRoot = path.join(cwd, ...SPEC_ROOT.split("/"));
  const absoluteManifest = path.join(cwd, ...SPEC_MANIFEST_PATH.split("/"));

  if (!fs.existsSync(absoluteManifest)) {
    throw new Error(
      `Required protected-spec manifest is missing: ${SPEC_MANIFEST_PATH}`,
    );
  }
  if (!fs.existsSync(absoluteSpecRoot)) {
    throw new Error(`Protected spec directory is missing: ${SPEC_ROOT}`);
  }

  const files = new Map();

  function visit(absoluteDirectory, relativeDirectory) {
    const directoryEntries = fs
      .readdirSync(absoluteDirectory, { withFileTypes: true })
      .sort((left, right) => comparePaths(left.name, right.name));

    for (const entry of directoryEntries) {
      const absoluteEntry = path.join(absoluteDirectory, entry.name);
      const relativeEntry = normalizePath(
        path.posix.join(relativeDirectory, entry.name),
      );

      if (entry.isSymbolicLink()) {
        throw new Error(
          `Protected spec paths must not be symbolic links: ${relativeEntry}`,
        );
      }
      if (entry.isDirectory()) {
        visit(absoluteEntry, relativeEntry);
        continue;
      }
      if (!entry.isFile()) {
        throw new Error(
          `Unsupported protected spec filesystem entry: ${relativeEntry}`,
        );
      }
      if (relativeEntry !== SPEC_MANIFEST_PATH) {
        files.set(relativeEntry, fs.readFileSync(absoluteEntry));
      }
    }
  }

  visit(absoluteSpecRoot, SPEC_ROOT);

  return {
    files,
    manifestContent: fs.readFileSync(absoluteManifest, "utf8"),
  };
}

function collectStagedSpecSnapshot() {
  const stagedSpecPaths = splitNullSeparated(
    git(["ls-files", "--cached", "-z", "--", `${SPEC_ROOT}/`]),
  ).sort(comparePaths);

  if (!stagedSpecPaths.includes(SPEC_MANIFEST_PATH)) {
    throw new Error(
      `Required protected-spec manifest is missing from the staged index: ${SPEC_MANIFEST_PATH}`,
    );
  }

  const files = new Map();
  for (const file of stagedSpecPaths) {
    if (file !== SPEC_MANIFEST_PATH) {
      files.set(file, git(["show", `:${file}`]));
    }
  }

  return {
    files,
    manifestContent: git(["show", `:${SPEC_MANIFEST_PATH}`]),
  };
}

function extractSpecReleaseVersion(content) {
  const normalized = normalizeSpecContent(content);
  const firstSectionIndex = normalized.search(/^##\s+/m);
  const header =
    firstSectionIndex === -1
      ? normalized
      : normalized.slice(0, firstSectionIndex);
  const semver = "((?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*))";
  const releaseRow = new RegExp(
    `^\\|\\s*(?:명세 릴리스|Release)\\s*\\|\\s*\\\`${semver}\\\`\\s*\\|`,
    "m",
  );
  return releaseRow.exec(header)?.[1] ?? null;
}

function extractReadmeReleaseRows(content) {
  const normalized = normalizeSpecContent(content);
  const releaseHistory =
    getMarkdownSection(normalized, "릴리스 기록", 2) ??
    getMarkdownSection(normalized, "Release history", 2);
  if (releaseHistory === null) return [];

  return [...releaseHistory.matchAll(/^\|\s*`([^`]+)`\s*\|/gm)].map(
    (match) => match[1],
  );
}

function verifySpecReleaseMetadata(files) {
  const errors = [];
  const versions = new Map();

  for (const file of CANONICAL_SPEC_MARKDOWN_PATHS) {
    if (!files.has(file)) {
      errors.push(`Canonical spec release file is missing: ${file}`);
      continue;
    }
    const version = extractSpecReleaseVersion(files.get(file));
    if (!version) {
      errors.push(
        `${file} must declare a complete SemVer release in its header.`,
      );
    } else {
      versions.set(file, version);
    }
  }

  const uniqueVersions = [...new Set(versions.values())];
  if (uniqueVersions.length > 1) {
    errors.push(
      `Canonical spec Markdown release versions must match: ${[...versions]
        .map(([file, version]) => `${file}=${version}`)
        .join(", ")}.`,
    );
  }

  const readmeVersion = versions.get(`${SPEC_ROOT}/README.md`);
  if (readmeVersion && files.has(`${SPEC_ROOT}/README.md`)) {
    const releaseRows = extractReadmeReleaseRows(
      files.get(`${SPEC_ROOT}/README.md`),
    );
    if (releaseRows.length === 0) {
      errors.push(`${SPEC_ROOT}/README.md must contain a release-history row.`);
    } else if (releaseRows[0] !== readmeVersion) {
      errors.push(
        `${SPEC_ROOT}/README.md latest release row (${releaseRows[0]}) must match its header (${readmeVersion}).`,
      );
    }
  }

  return errors;
}

function verifySpecSnapshot({ files, manifestContent }) {
  const { entries, errors } = parseSpecManifest(manifestContent);
  if (errors.length > 0) {
    return errors;
  }

  const expectedByPath = new Map(
    entries.map((entry) => [entry.path, entry.sha256]),
  );
  const verificationErrors = verifySpecReleaseMetadata(files);

  for (const expectedPath of expectedByPath.keys()) {
    if (!files.has(expectedPath)) {
      verificationErrors.push(
        `Protected spec file is missing or deleted: ${expectedPath}`,
      );
    }
  }

  for (const actualPath of [...files.keys()].sort(comparePaths)) {
    if (!expectedByPath.has(actualPath)) {
      verificationErrors.push(
        `Protected spec file is not listed in ${SPEC_MANIFEST_PATH}: ${actualPath}`,
      );
    }
  }

  for (const [expectedPath, expectedHash] of expectedByPath) {
    if (!files.has(expectedPath)) continue;
    const actualHash = hashNormalizedSpecContent(files.get(expectedPath));
    if (actualHash !== expectedHash) {
      verificationErrors.push(
        `Protected spec hash mismatch: ${expectedPath} (expected ${expectedHash}, actual ${actualHash})`,
      );
    }
  }

  return verificationErrors;
}

function validateSpecLock(mode) {
  try {
    const snapshot =
      mode === "staged"
        ? collectStagedSpecSnapshot()
        : collectWorkingTreeSpecSnapshot();
    return verifySpecSnapshot(snapshot);
  } catch (error) {
    return [error.message];
  }
}

function parsePatchPath(file) {
  const match = PATCH_FILE_PATTERN.exec(file);
  if (!match) return null;

  return {
    directory: match[1],
    author: match[2],
    issue: Number(match[3]),
    summary: match[4],
    revision: Number(match[5]),
  };
}

function parsePatchScalar(rawValue, label, errors) {
  const value = rawValue.trim();
  if (value === "null" || value === "~") return null;
  if (value === "[]") return [];
  if (/^[0-9]+$/.test(value)) return Number(value);

  if (value.startsWith('"') || value.endsWith('"')) {
    if (!(value.startsWith('"') && value.endsWith('"'))) {
      errors.push(`${label} has an unterminated double-quoted value.`);
      return value;
    }
    try {
      return JSON.parse(value);
    } catch (error) {
      errors.push(
        `${label} has an invalid double-quoted value: ${error.message}`,
      );
      return value;
    }
  }

  if (value.startsWith("'") || value.endsWith("'")) {
    if (!(value.startsWith("'") && value.endsWith("'"))) {
      errors.push(`${label} has an unterminated single-quoted value.`);
      return value;
    }
    return value.slice(1, -1).replace(/''/g, "'");
  }

  return value;
}

function parsePatchFrontMatter(content, file) {
  const normalized = normalizeSpecContent(content).replace(/^\uFEFF/, "");
  const lines = normalized.split("\n");
  const errors = [];

  if (lines[0] !== "---") {
    return {
      body: normalized,
      errors: [`${file} must start with YAML front matter delimited by ---.`],
      metadata: {},
    };
  }

  const closingIndex = lines.indexOf("---", 1);
  if (closingIndex === -1) {
    return {
      body: "",
      errors: [`${file} YAML front matter is missing its closing ---.`],
      metadata: {},
    };
  }

  const metadata = {};
  let collection = null;

  for (let index = 1; index < closingIndex; index += 1) {
    const line = lines[index];
    const label = `${file} front matter line ${index + 1}`;
    if (line.trim() === "") continue;

    const topLevel = /^([a-z][a-z0-9_]*):(?:\s*(.*))?$/.exec(line);
    if (topLevel) {
      const [, key, rawValue = ""] = topLevel;
      if (Object.prototype.hasOwnProperty.call(metadata, key)) {
        errors.push(`${file} metadata key appears more than once: ${key}`);
        collection = null;
        continue;
      }

      if (rawValue === "" && key === "targets") {
        metadata[key] = [];
        collection = key;
      } else if (rawValue === "") {
        errors.push(`${label} must provide a scalar value for ${key}.`);
        metadata[key] = "";
        collection = null;
      } else {
        metadata[key] = parsePatchScalar(rawValue, label, errors);
        collection = null;
      }
      continue;
    }

    if (collection === "targets") {
      const target = /^  - ([a-z][a-z0-9_-]*):\s*(.+)$/.exec(line);
      if (target) {
        const value = parsePatchScalar(target[2], label, errors);
        metadata.targets.push({ [target[1]]: value });
        continue;
      }
    }

    errors.push(`${label} is not supported Patch metadata syntax.`);
  }

  return {
    body: lines.slice(closingIndex + 1).join("\n"),
    errors,
    metadata,
  };
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function getMarkdownSection(body, title, level) {
  const marker = "#".repeat(level);
  const heading = new RegExp(`^${marker} ${escapeRegExp(title)}\\s*$`, "m");
  const match = heading.exec(body);
  if (!match) return null;

  const sectionStart = match.index + match[0].length;
  const remainder = body.slice(sectionStart);
  const nextHeading = new RegExp(`^#{1,${level}}\\s+`, "m").exec(remainder);
  const sectionEnd = nextHeading ? nextHeading.index : remainder.length;
  return remainder.slice(0, sectionEnd).trim();
}

function hasPatchPlaceholder(content) {
  return /<[^>\n]+>|\b(?:TODO|TBD|FIXME)\b|작성\s*필요|추후\s*결정|미정/i.test(
    content,
  );
}

function hasTemplateSentinel(metadata, body, targets) {
  return (
    metadata.base_spec_version === "0.0.0" ||
    targets.some((target) =>
      /^(?:REQUIREMENT-ID|DECISION-ID|REST-OPERATION)$/i.test(target.value),
    ) ||
    /^#\s+SPEC-000-01(?::|\s|$)|^#\s+.*명세 Patch 제목\s*$|추가 사항을 작성한다|완료 조건을 작성한다/im.test(
      body,
    )
  );
}

function parsePatchDocument(file, content) {
  const pathInfo = parsePatchPath(file);
  if (!pathInfo) {
    return {
      errors: [
        `${file} must match ${PATCH_ROOT}/draft|archive/<github-id>_issue-<number>_<kebab-summary>_patch_v<revision>.md.`,
      ],
      patch: null,
    };
  }

  const { body, errors, metadata } = parsePatchFrontMatter(content, file);
  const metadataKeys = new Set(Object.keys(metadata));
  for (const key of PATCH_REQUIRED_METADATA) {
    if (!metadataKeys.has(key)) {
      errors.push(`${file} is missing required metadata: ${key}.`);
    }
  }
  for (const key of metadataKeys) {
    if (!PATCH_SUPPORTED_METADATA.has(key)) {
      errors.push(`${file} has unsupported metadata: ${key}.`);
    }
  }

  const idMatch =
    typeof metadata.patch_id === "string"
      ? PATCH_ID_PATTERN.exec(metadata.patch_id)
      : null;
  if (!idMatch) {
    errors.push(
      `${file} patch_id must match SPEC-<issue>-<two-digit-sequence>.`,
    );
  } else if (Number(idMatch[1]) !== metadata.issue) {
    errors.push(`${file} patch_id issue must match the issue metadata.`);
  }

  if (metadata.issue !== pathInfo.issue) {
    errors.push(`${file} issue metadata must match the filename issue number.`);
  }
  if (!PATCH_STATUSES.has(metadata.status)) {
    errors.push(
      `${file} status must be one of: ${[...PATCH_STATUSES].join(", ")}.`,
    );
  }
  if (!Number.isInteger(metadata.issue) || metadata.issue <= 0) {
    errors.push(`${file} issue must be a positive integer.`);
  }
  if (
    typeof metadata.base_spec_version !== "string" ||
    !SEMVER_PATTERN.test(metadata.base_spec_version)
  ) {
    errors.push(`${file} base_spec_version must be a complete SemVer value.`);
  }

  const targets = [];
  if (!Array.isArray(metadata.targets) || metadata.targets.length === 0) {
    errors.push(`${file} targets must be a non-empty list of single-key maps.`);
  } else {
    const seenTargets = new Set();
    metadata.targets.forEach((entry, index) => {
      const keys = isPlainObject(entry) ? Object.keys(entry) : [];
      if (keys.length !== 1) {
        errors.push(`${file} targets[${index}] must contain exactly one key.`);
        return;
      }
      const type = keys[0];
      const value = entry[type];
      if (typeof value !== "string" || value.trim() === "") {
        errors.push(
          `${file} targets[${index}] must name a stable contract ID.`,
        );
        return;
      }
      const normalizedType = [
        "api",
        "operation",
        "rest_operation",
        "rest-operation",
      ].includes(type)
        ? "rest_operation"
        : type;
      const normalizedTarget = `${normalizedType}:${value.trim()}`;
      if (seenTargets.has(normalizedTarget)) {
        errors.push(`${file} lists target ${normalizedTarget} more than once.`);
        return;
      }
      seenTargets.add(normalizedTarget);
      targets.push({ type, value: value.trim(), key: normalizedTarget });
    });
  }

  if (
    PATCH_ACTIVE_STATUSES.has(metadata.status) &&
    pathInfo.directory !== "draft"
  ) {
    errors.push(`${file} draft Patch status must be stored under draft/.`);
  }
  if (
    PATCH_ARCHIVED_STATUSES.has(metadata.status) &&
    pathInfo.directory !== "archive"
  ) {
    errors.push(`${file} accepted Patch status must be stored under archive/.`);
  }

  for (const title of PATCH_REQUIRED_SECTIONS) {
    const section = getMarkdownSection(body, title, 2);
    if (section === null || section === "") {
      errors.push(`${file} requires a non-empty "## ${title}" section.`);
    }
  }
  // draft도 구현 기준으로 사용되므로 빈 초안이나 미결정 Placeholder를 허용하지 않는다.
  if (hasPatchPlaceholder(`${JSON.stringify(metadata)}\n${body}`)) {
    errors.push(`${file} Patch must not contain placeholders.`);
  }
  if (hasTemplateSentinel(metadata, body, targets)) {
    errors.push(`${file} Patch must replace every TEMPLATE sentinel.`);
  }

  return {
    errors,
    patch: {
      body,
      content: normalizeSpecContent(content),
      file,
      metadata,
      pathInfo,
      targets,
    },
  };
}

function isAllowedPatchSupportPath(file) {
  return (
    file === PATCH_README_PATH ||
    file === PATCH_TEMPLATE_PATH ||
    PATCH_KEEP_PATHS.has(file)
  );
}

function collectWorkingTreePatchSnapshot() {
  const files = new Map();
  for (const file of getWorkingTreeFiles()) {
    if (!file.startsWith(`${PATCH_ROOT}/`) || !fs.existsSync(file)) continue;
    files.set(file, fs.readFileSync(file, "utf8"));
  }
  return files;
}

function collectStagedPatchSnapshot() {
  const files = new Map();
  const paths = splitNullSeparated(
    git(["ls-files", "--cached", "-z", "--", `${PATCH_ROOT}/`]),
  );
  for (const file of paths) {
    files.set(file, git(["show", `:${file}`]));
  }
  return files;
}

function collectGitPatchSnapshot(ref) {
  if (!ref) return new Map();
  const output = gitOptional([
    "ls-tree",
    "-r",
    "--name-only",
    "-z",
    ref,
    "--",
    `${PATCH_ROOT}/`,
  ]);
  if (output === null) return new Map();

  const files = new Map();
  for (const file of splitNullSeparated(output)) {
    const content = gitOptional(["show", `${ref}:${file}`]);
    if (content !== null) files.set(file, content);
  }
  return files;
}

function collectPreviousPatchSnapshot(mode) {
  const ref = mode === "staged" ? "HEAD" : getAllComparisonBase();
  return collectGitPatchSnapshot(ref);
}

function indexPatchDocuments(files, errors, validateContent) {
  const patches = [];
  for (const [file, content] of files) {
    if (isAllowedPatchSupportPath(file)) continue;
    const parsed = parsePatchDocument(file, content);
    if (validateContent) errors.push(...parsed.errors);
    if (parsed.patch) patches.push(parsed.patch);
  }
  return patches;
}

function indexLifecyclePatches(files, errors) {
  const byId = new Map();
  for (const patch of indexPatchDocuments(files, [], false)) {
    const id = patch.metadata.patch_id;
    if (typeof id !== "string") continue;
    if (byId.has(id)) {
      errors.push(
        `Patch ID is duplicated: ${id} (${byId.get(id).file}, ${patch.file}).`,
      );
    } else {
      byId.set(id, patch);
    }
  }
  return byId;
}

function verifyPatchLifecycleSnapshots(previousFiles, currentFiles) {
  const errors = [];
  const previousById = indexLifecyclePatches(previousFiles, errors);
  const currentById = indexLifecyclePatches(currentFiles, errors);
  errors.push(...verifyPatchLifecycle(previousById, currentById));
  return errors;
}

function validateAllPatchLifecycle(currentFiles) {
  const comparisonBase = getAllComparisonBase();
  if (!comparisonBase) return [];

  const errors = [];
  let previousFiles = collectGitPatchSnapshot(comparisonBase);
  const commitOutput =
    gitOptional([
      "rev-list",
      "--reverse",
      "--first-parent",
      `${comparisonBase}..HEAD`,
    ]) ?? "";
  const commits = commitOutput.split(/\r?\n/).filter(Boolean);

  for (const commit of commits) {
    const commitFiles = collectGitPatchSnapshot(commit);
    errors.push(
      ...verifyPatchLifecycleSnapshots(previousFiles, commitFiles).map(
        (error) => `${commit.slice(0, 12)}: ${error}`,
      ),
    );
    previousFiles = commitFiles;
  }

  errors.push(...verifyPatchLifecycleSnapshots(previousFiles, currentFiles));
  return errors;
}

function isPatchContentChanged(previous, current) {
  return (
    !previous ||
    previous.file !== current.file ||
    previous.content !== current.content
  );
}

function getPatchTransitionContent(patch) {
  const metadata = { ...patch.metadata };
  delete metadata.status;
  return JSON.stringify({ body: patch.body, metadata });
}

function verifyPatchLifecycle(previousById, currentById) {
  const errors = [];
  const allIds = new Set([...previousById.keys(), ...currentById.keys()]);

  for (const id of allIds) {
    const previous = previousById.get(id);
    const current = currentById.get(id);
    if (previous && !current) {
      if (previous.metadata.status === "accepted") {
        errors.push(
          `Accepted Patch audit record must not be deleted: ${id} (${previous.file}).`,
        );
      }
      continue;
    }
    if (!current || !isPatchContentChanged(previous, current)) continue;
    if (!previous) {
      if (current.metadata.status !== "draft") {
        errors.push(`${current.file} new Patch must start in draft status.`);
      }
      continue;
    }

    const previousStatus = previous.metadata.status;
    const currentStatus = current.metadata.status;
    if (previousStatus === currentStatus) {
      if (currentStatus !== "draft") {
        errors.push(
          `${current.file} ${currentStatus} Patch is immutable; create a new revision instead.`,
        );
      }
      continue;
    }

    // #236 전환에서 과거 applied 감사 기록을 동일 의미의 accepted로 한 번 이관한다.
    if (
      previousStatus === "applied" &&
      currentStatus === "accepted" &&
      previous.pathInfo.directory === "archive" &&
      current.pathInfo.directory === "archive"
    ) {
      continue;
    }

    if (!PATCH_ALLOWED_TRANSITIONS.get(previousStatus)?.has(currentStatus)) {
      errors.push(
        `${current.file} disallows Patch status transition ${previousStatus} -> ${currentStatus}.`,
      );
    } else if (previousStatus === "draft") {
      if (
        path.posix.basename(previous.file) !== path.posix.basename(current.file)
      ) {
        errors.push(
          `${current.file} accepted transition must preserve the draft filename and revision.`,
        );
      }
      if (
        getPatchTransitionContent(previous) !==
        getPatchTransitionContent(current)
      ) {
        errors.push(
          `${current.file} accepted transition may change only status and lifecycle directory.`,
        );
      }
    }
  }

  return errors;
}

function isDdlPath(file) {
  return (
    file.startsWith("docs/database/") ||
    file === "docs/DATABASE_SCHEMA_ERD.md" ||
    /(^|\/)(?:ddl|schema)(?:[-_.\/]|$)/i.test(file) ||
    file.endsWith(".sql")
  );
}

function isApiContractPath(file) {
  return (
    /^backend\/src\/main\/java\/.+\/(?:controller|dto)\//.test(file) ||
    file.startsWith("frontend/src/services/")
  );
}

function verifyPatchSnapshot({
  currentFiles,
  previousFiles,
  changedFiles,
  canonicalSpecVersion = null,
  previousCanonicalSpecVersion = null,
  requireDraftAcceptance = false,
  validateLifecycle = true,
}) {
  const errors = [];
  const warnings = [];

  if (
    currentFiles.size > 0 ||
    previousFiles.size > 0 ||
    [...changedFiles].some((file) => file.startsWith(`${PATCH_ROOT}/`))
  ) {
    for (const requiredPath of [
      PATCH_README_PATH,
      PATCH_TEMPLATE_PATH,
      ...PATCH_KEEP_PATHS,
    ]) {
      if (!currentFiles.has(requiredPath)) {
        errors.push(
          `Required Patch governance file is missing: ${requiredPath}`,
        );
      }
    }
  }

  const currentPatches = indexPatchDocuments(currentFiles, errors, true);
  const previousPatches = indexPatchDocuments(previousFiles, [], false);
  const currentById = new Map();
  const previousById = new Map();

  for (const patch of currentPatches) {
    const id = patch.metadata.patch_id;
    if (typeof id !== "string") continue;
    if (currentById.has(id)) {
      errors.push(
        `Patch ID is duplicated: ${id} (${currentById.get(id).file}, ${patch.file}).`,
      );
    } else {
      currentById.set(id, patch);
    }
  }
  for (const patch of previousPatches) {
    const id = patch.metadata.patch_id;
    if (typeof id === "string" && !previousById.has(id)) {
      previousById.set(id, patch);
    }
  }

  if (validateLifecycle) {
    errors.push(...verifyPatchLifecycle(previousById, currentById));
  }

  const targetOwners = new Map();
  for (const patch of currentPatches) {
    if (!PATCH_ACTIVE_STATUSES.has(patch.metadata.status)) continue;
    if (
      canonicalSpecVersion &&
      patch.metadata.base_spec_version !== canonicalSpecVersion
    ) {
      warnings.push(
        `Active Patch ${patch.metadata.patch_id} uses stale base_spec_version ${patch.metadata.base_spec_version}; current release is ${canonicalSpecVersion}.`,
      );
    }
    for (const target of patch.targets) {
      const owners = targetOwners.get(target.key) ?? [];
      owners.push(patch.metadata.patch_id);
      targetOwners.set(target.key, owners);
    }
  }
  for (const [target, owners] of targetOwners) {
    const uniqueOwners = [...new Set(owners)];
    if (uniqueOwners.length > 1) {
      warnings.push(
        `Active Patch target conflict requires Controller review: ${target} (${uniqueOwners.join(", ")}).`,
      );
    }
  }

  const changedPatchPaths = [...changedFiles].filter((file) =>
    PATCH_FILE_PATTERN.test(file),
  );
  const scopeCurrentPatches = currentPatches.filter((patch) =>
    changedFiles.has(patch.file),
  );
  if (changedPatchPaths.length > 0) {
    const mixedApplication = [...changedFiles].filter(
      (file) => file.startsWith("frontend/") || file.startsWith("backend/"),
    );
    const mixedDdl = [...changedFiles].filter(isDdlPath);
    for (const file of new Set(mixedDdl)) {
      warnings.push(
        `Patch change includes protected Migration or DDL: ${file}. The Patch does not grant schema authority; the pull request must link a separately scoped PM/Repository Administrator approval and migration_scope.`,
      );
    }

    if (mixedApplication.length > 0) {
      // draft는 구현과 함께 dev로 가지만 accepted 전환은 정식 명세 릴리스만 담는다.
      for (const patch of scopeCurrentPatches) {
        if (patch.metadata.status === "accepted") {
          errors.push(
            `${patch.file} accepted transition must not include application code.`,
          );
        }
      }
    }

    const protectedSpecChanges = [...changedFiles].filter((file) =>
      file.startsWith(`${SPEC_ROOT}/`),
    );
    if (protectedSpecChanges.length > 0) {
      const isControllerRelease =
        scopeCurrentPatches.some(
          (patch) => patch.metadata.status === "accepted",
        ) &&
        scopeCurrentPatches.every((patch) =>
          PATCH_ARCHIVED_STATUSES.has(patch.metadata.status),
        );
      if (!isControllerRelease) {
        for (const file of protectedSpecChanges) {
          errors.push(
            `Draft Patch change must not include protected spec changes: ${file}`,
          );
        }
      }
    }
  }

  if (changedPatchPaths.length === 0) {
    const apiBoundaryChanges = [...changedFiles].filter(isApiContractPath);
    if (apiBoundaryChanges.length > 0) {
      warnings.push(
        `API boundary change requires a Spec Patch or an explicit N/A rationale in the pull request: ${apiBoundaryChanges.join(", ")}.`,
      );
    }
  }

  if (requireDraftAcceptance) {
    for (const patch of currentPatches) {
      if (patch.metadata.status === "draft") {
        errors.push(
          `Approved release is blocked by draft Patch ${patch.metadata.patch_id} (${patch.file}); accept it into the canonical SPEC or remove its implementation from the release.`,
        );
      }
    }
  }

  for (const current of scopeCurrentPatches) {
    if (current.metadata.status !== "accepted") continue;

    if (
      canonicalSpecVersion &&
      previousCanonicalSpecVersion &&
      compareSemverVersions(
        canonicalSpecVersion,
        previousCanonicalSpecVersion,
      ) <= 0
    ) {
      errors.push(
        `${current.file} accepted transition must advance the canonical release beyond ${previousCanonicalSpecVersion}.`,
      );
    }

    const requiredReleasePaths = new Set([
      SPEC_MANIFEST_PATH,
      `${SPEC_ROOT}/README.md`,
    ]);
    for (const target of current.targets) {
      const specPath = PATCH_TARGET_SPEC_PATHS.get(target.type);
      if (!specPath) {
        errors.push(
          `${current.file} accepted target type has no canonical spec mapping: ${target.type}.`,
        );
      } else {
        requiredReleasePaths.add(specPath);
      }
    }
    for (const requiredPath of requiredReleasePaths) {
      if (!changedFiles.has(requiredPath)) {
        errors.push(
          `${current.file} accepted transition must atomically update ${requiredPath}.`,
        );
      }
    }
  }

  return { errors, warnings };
}

function getCandidateSpecVersion(mode) {
  try {
    const snapshot =
      mode === "staged"
        ? collectStagedSpecSnapshot()
        : collectWorkingTreeSpecSnapshot();
    return extractSpecReleaseVersion(
      snapshot.files.get(`${SPEC_ROOT}/README.md`),
    );
  } catch {
    return null;
  }
}

function getPreviousCanonicalSpecVersion(mode) {
  const ref = mode === "staged" ? "HEAD" : getAllComparisonBase();
  if (!ref) return null;
  const readme = gitOptional(["show", `${ref}:${SPEC_ROOT}/README.md`]);
  return readme === null ? null : extractSpecReleaseVersion(readme);
}

function validatePatchGovernance(mode) {
  try {
    const currentFiles =
      mode === "staged"
        ? collectStagedPatchSnapshot()
        : collectWorkingTreePatchSnapshot();
    const result = verifyPatchSnapshot({
      canonicalSpecVersion: getCandidateSpecVersion(mode),
      changedFiles: getChangedFiles(mode),
      currentFiles,
      previousCanonicalSpecVersion: getPreviousCanonicalSpecVersion(mode),
      previousFiles: collectPreviousPatchSnapshot(mode),
      requireDraftAcceptance: mode === "release",
      validateLifecycle: mode === "staged",
    });
    if (mode !== "staged") {
      result.errors.push(...validateAllPatchLifecycle(currentFiles));
    }
    return result;
  } catch (error) {
    return { errors: [error.message], warnings: [] };
  }
}

function parseMode(args) {
  if (
    args.length !== 1 ||
    !["--staged", "--all", "--release"].includes(args[0])
  ) {
    throw new Error(
      "Use one mode: node scripts/check-project-guardrails.js --staged|--all|--release",
    );
  }

  if (args[0] === "--staged") return "staged";
  if (args[0] === "--release") return "release";
  return "all";
}

function printViolations(violations) {
  console.error("\n프로젝트 기술 제약 위반이 감지되었습니다.\n");
  for (const violation of violations) {
    console.error(`- ${violation.file}: ${violation.rule.name}`);
    console.error(`  ${violation.rule.message}`);
  }
  console.error("\n허용 기술: Vue.js, Spring Framework legacy, MyBatis\n");
}

function printSpecLockErrors(errors) {
  console.error("\nProtected specification lock check failed.\n");
  for (const error of errors) {
    console.error(`- ${error}`);
  }
  console.error(
    "\nProtected specs and their manifest may change only in a scoped administrative spec release.\n",
  );
}

function printPatchGovernanceErrors(errors) {
  console.error("\nSpecification Patch governance check failed.\n");
  for (const error of errors) {
    console.error(`- ${error}`);
  }
  console.error(
    "\nDraft Patch changes and Controller acceptance must follow the documented two-state lifecycle and atomic scope.\n",
  );
}

function printPatchGovernanceWarnings(warnings) {
  console.warn("\nSpecification Patch governance review warnings.\n");
  for (const warning of warnings) {
    console.warn(`- ${warning}`);
  }
  console.warn(
    "\nWarnings require Controller review but do not replace semantic approval.\n",
  );
}

function printArchitectureGovernanceErrors(errors) {
  console.error("\nArchitecture boundary guardrail check failed.\n");
  for (const error of errors) {
    console.error(`- ${error}`);
  }
  console.error(
    "\nExisting RF-02 violations are a frozen baseline; new Mapper, Mapper-to-API-Response, Domain, Controller, or production Mock violations are not allowed.\n",
  );
}

function printReviewScopeWarnings(warnings) {
  console.warn("\nReview scope warnings (non-blocking).\n");
  for (const warning of warnings) {
    console.warn(`- ${warning}`);
  }
  console.warn(
    "\nThese thresholds request human review and never replace issue scope or acceptance criteria.\n",
  );
}

function printGovernanceTemplateErrors(errors) {
  console.error("\nIssue and pull request workflow template check failed.\n");
  for (const error of errors) {
    console.error(`- ${error}`);
  }
  console.error(
    "\nWorkflow templates must preserve the lightweight issue card, review evidence, and protected ownership contract.\n",
  );
}

function printMigrationImmutabilityErrors(errors) {
  console.error("\nFlyway Migration immutability check failed.\n");
  for (const error of errors) {
    console.error(`- ${error}`);
  }
  console.error(
    "\nA scoped approval may add a new forward-only Migration but never rewrite or delete an applied one.\n",
  );
}

function runGuardrails(mode) {
  const entries =
    mode === "staged" ? readStagedEntries() : readWorkingTreeEntries();
  const violations = findViolations(entries);
  const specLockErrors = validateSpecLock(mode);
  const patchGovernance = validatePatchGovernance(mode);
  const architectureGovernance = validateArchitectureGovernance(mode);
  const governanceTemplateErrors = validateGovernanceTemplates(mode);
  const migrationImmutabilityErrors = validateMigrationImmutability(mode);
  const reviewScopeWarnings = collectReviewScopeWarnings(mode);

  if (violations.length > 0) {
    printViolations(violations);
  }
  if (specLockErrors.length > 0) {
    printSpecLockErrors(specLockErrors);
  }
  if (patchGovernance.errors.length > 0) {
    printPatchGovernanceErrors(patchGovernance.errors);
  }
  if (patchGovernance.warnings.length > 0) {
    printPatchGovernanceWarnings(patchGovernance.warnings);
  }
  if (architectureGovernance.errors.length > 0) {
    printArchitectureGovernanceErrors(architectureGovernance.errors);
  }
  if (governanceTemplateErrors.length > 0) {
    printGovernanceTemplateErrors(governanceTemplateErrors);
  }
  if (migrationImmutabilityErrors.length > 0) {
    printMigrationImmutabilityErrors(migrationImmutabilityErrors);
  }
  if (reviewScopeWarnings.length > 0) {
    printReviewScopeWarnings(reviewScopeWarnings);
  }
  if (
    violations.length > 0 ||
    specLockErrors.length > 0 ||
    patchGovernance.errors.length > 0 ||
    architectureGovernance.errors.length > 0 ||
    governanceTemplateErrors.length > 0 ||
    migrationImmutabilityErrors.length > 0
  ) {
    return 1;
  }

  const scope =
    mode === "staged"
      ? "staged index content"
      : mode === "release"
        ? "release candidate working tree content"
        : "working tree content";
  console.log(`Project guardrail check passed (${scope}).`);
  return 0;
}

function main() {
  try {
    return runGuardrails(parseMode(process.argv.slice(2)));
  } catch (error) {
    console.error(error.message);
    return 1;
  }
}

if (require.main === module) {
  process.exitCode = main();
}

module.exports = {
  ARCHITECTURE_MANIFEST_PATH,
  CANONICAL_SPEC_MARKDOWN_PATHS,
  PATCH_FILE_PATTERN,
  PATCH_ID_PATTERN,
  PATCH_ROOT,
  PATCH_STATUSES,
  SPEC_HASH_ALGORITHM,
  SPEC_MANIFEST_PATH,
  SPEC_MANIFEST_VERSION,
  SPEC_NORMALIZATION,
  SPEC_ROOT,
  buildReviewScopeWarnings,
  compareArchitectureViolations,
  collectStagedSpecSnapshot,
  collectWorkingTreeSpecSnapshot,
  extractReadmeReleaseRows,
  extractSpecReleaseVersion,
  findArchitectureViolations,
  findViolations,
  hashNormalizedSpecContent,
  isBackendSourceOrBuild,
  isFrontendSourceOrConfig,
  isIgnoredDocumentation,
  isPackageManifest,
  normalizePath,
  normalizeSpecContent,
  parseArchitectureManifest,
  parseNumstat,
  parsePatchDocument,
  parseMode,
  parseSpecManifest,
  selectIntegrationBaseBranch,
  splitNullSeparated,
  validatePatchGovernance,
  validateSpecLock,
  verifyPatchSnapshot,
  verifyGovernanceTemplateSnapshot,
  verifyArchitectureManifestEvolution,
  verifyMigrationImmutability,
  verifySpecReleaseMetadata,
  verifySpecSnapshot,
};
