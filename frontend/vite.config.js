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
      // 기본값은 localhost 만 허용한다. 실기기 카메라·위치 검증처럼 HTTPS 터널로 접속해야
      // 하는 경우에만 DEV_ALLOWED_HOSTS 에 그 호스트를 넣는다. 상수로 박아 두면 개인 터널
      // 주소가 공유 설정에 남고, true 로 열면 DNS Rebinding 방어가 사라진다.
      allowedHosts: env.DEV_ALLOWED_HOSTS
        ? env.DEV_ALLOWED_HOSTS.split(',')
            .map((host) => host.trim())
            .filter(Boolean)
        : undefined,
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
