/**
 * Mock opt-in 플래그 — 인증·회원·사업장 Service 의 단일 원천.
 *
 * 승인 계약상 Mock 은 명시적 Local/Test 설정에서만 켤 수 있고 프로덕션 빌드에서는
 * 동작할 수 없다. `import.meta.env.DEV` 를 AND 로 묶어 이 경계를 설정이 아니라
 * 코드로 보장한다 — 프로덕션 번들에서는 상수가 false 로 접혀 mock 분기가 제거된다.
 *
 * Legacy Auth/User 전용. RF-12 domain facade는 VITE_MOCK_OPERATIONS를 사용한다.
 * 켜는 법: frontend/.env 에 VITE_USE_MOCK=true (개발 서버에서만 유효)
 */
export const USE_MOCK = import.meta.env.DEV && import.meta.env.VITE_USE_MOCK === 'true'
