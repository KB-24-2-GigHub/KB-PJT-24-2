import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig, loadEnv } from 'vite'
import svgLoader from 'vite-svg-loader'

import { mockModuleExclusionPlugin } from './vite-plugins/mockModuleGuard'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')

  return {
    plugins: [
      vue(),
      mockModuleExclusionPlugin(),
      // SVGO 기본값은 viewBox 를 제거해 CSS 크기 조정 시 로고가 잘린다.
      // viewBox 를 보존해 컴포넌트 SVG 가 지정 크기로 정상 축소되도록 한다.
      svgLoader({
        svgoConfig: {
          plugins: [{ name: 'preset-default', params: { overrides: { removeViewBox: false } } }]
        }
      })
    ],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url))
      }
    },
    server: {
      port: 5173,
      strictPort: true,
      proxy: {
        '/api': {
          target: env.DEV_PROXY_TARGET || 'http://localhost:8080',
          changeOrigin: true
        }
      }
    },
    test: {
      environment: 'jsdom'
    }
  }
})
