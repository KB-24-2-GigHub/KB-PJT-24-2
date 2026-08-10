import { describe, expect, it, vi } from 'vitest'

import { findBundledMockModules, mockModuleExclusionPlugin } from './mockModuleGuard'

describe('production mock module guard', () => {
  it('accepts chunks composed only of production modules', () => {
    expect(
      findBundledMockModules({
        'index.js': {
          type: 'chunk',
          modules: {
            'C:\\repo\\frontend\\src\\services\\api\\walletApi.js': {},
            'C:\\repo\\frontend\\src\\views\\WalletView.vue': {}
          }
        }
      })
    ).toEqual([])
  })

  it('reports mock modules independent of Windows or POSIX path separators', () => {
    const violations = findBundledMockModules({
      'index.js': {
        type: 'chunk',
        modules: {
          'C:\\repo\\frontend\\src\\mocks\\walletMockApi.js': {},
          '/repo/frontend/src/mocks/workerMockApi.js': {}
        }
      }
    })

    expect(violations).toHaveLength(2)
    expect(violations.every((moduleId) => moduleId.includes('/src/mocks/'))).toBe(true)
  })

  it('hard-fails the build when a mock module is emitted', () => {
    const plugin = mockModuleExclusionPlugin()
    const context = {
      error: vi.fn((message) => {
        throw new Error(message)
      })
    }
    const bundle = {
      'worker.js': {
        type: 'chunk',
        modules: { 'C:\\repo\\frontend\\src\\mocks\\workerMockApi.js': {} }
      }
    }

    expect(() => plugin.generateBundle.call(context, {}, bundle)).toThrow(
      'Production bundle contains mock modules'
    )
    expect(context.error).toHaveBeenCalledOnce()
  })
})
