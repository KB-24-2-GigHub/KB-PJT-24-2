import path from 'node:path'

const MOCK_SEGMENT = '/src/mocks/'

function normalizeModuleId(moduleId) {
  return path.resolve(moduleId).replaceAll('\\', '/')
}

export function findBundledMockModules(bundle) {
  const violations = new Set()
  for (const output of Object.values(bundle)) {
    if (output.type !== 'chunk') continue
    for (const moduleId of Object.keys(output.modules ?? {})) {
      const normalized = normalizeModuleId(moduleId)
      if (normalized.includes(MOCK_SEGMENT)) violations.add(normalized)
    }
  }
  return [...violations].sort()
}

/** Hard-fail a production build if any DEV/Test mock reached a Rollup chunk. */
export function mockModuleExclusionPlugin() {
  return {
    name: 'mock-module-exclusion',
    apply: 'build',
    generateBundle(_options, bundle) {
      const violations = findBundledMockModules(bundle)
      if (violations.length > 0) {
        this.error(`Production bundle contains mock modules:\n${violations.join('\n')}`)
      }
    }
  }
}
