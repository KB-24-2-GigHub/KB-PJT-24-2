<div align="center">

<img src="frontend/src/assets/images/logo/logo-gighub.svg" alt="GigHub" width="140" />

# GigHub

**단기 알바의 임금 체불을, 돈을 먼저 묶어 두는 방식으로 막습니다.**

사장님이 근무를 등록하면 약정 일급이 전자지갑에서 예치되고,
알바생이 QR로 출퇴근하면 그 예치금이 지급되거나 환불됩니다.

[![Vue](https://img.shields.io/badge/Vue-3.5-42b883)](frontend/package.json)
[![Spring Framework](https://img.shields.io/badge/Spring%20Framework-5.3-6db33f)](backend/build.gradle)
[![Java](https://img.shields.io/badge/Java-17-orange)](backend/build.gradle)
[![MySQL](https://img.shields.io/badge/MySQL-8.4-4479a1)](compose.yaml)

**[서비스 바로가기](https://gighub.store)**

KB IT's Your Life 7기 · 24-2팀

</div>

---

## ✨ 주요 기능

### 🏪 사장님

- **사업장 관리** — 사업장 등록과 목록 조회, 매장에 붙이는 고정 QR 발급·재발급
- **근무 등록** — 날짜·시간·약정 일급·휴게시간을 정해 근무를 만들고, 수락 전까지 수정·삭제
- **초대 링크** — 근무별로 링크를 발급해 알바생에게 전달, 필요하면 재발급
- **전자지갑** — 충전과 출금, 거래내역 조회
- **자동 예치** — 알바생이 근무를 확정하면 약정 일급이 지갑에서 예치금으로 잡힘
- **정산** — 근무 완료 후 일당 지급 승인, 노쇼 발생 시 예치금 전액 환불 승인
- **문서함** — 근로계약서와 공유받은 보건증 열람
- **신뢰 뱃지** — 이용 이력 기반 등급 확인

### 🙋 알바생

- **근무 확정** — 초대 링크로 조건을 확인하고 전자서명에 동의해 계약 성립
- **오늘의 알바** — 홈에서 당일 근무와 출근 가능 시각 확인
- **QR 출퇴근** — 매장 고정 QR을 스캔해 출근·퇴근 기록
- **안심지갑** — 잔액과 거래내역 조회, 본인 계좌로 출금
- **근로계약서** — 확정 시각 기준으로 자동 생성된 계약서 열람
- **보건증** — 업로드 후 사업장별로 공유하고, 필요하면 공유 철회
- **이의 제기** — 근무 관련 분쟁 접수와 이력 조회
- **신뢰 뱃지** — 이용 이력 기반 등급 확인

### 🔐 안전장치

- **세션 인증 + CSRF** — 토큰을 브라우저에 저장하지 않고 `JSESSIONID` 세션만 사용, 상태 변경 요청에 CSRF 헤더 자동 첨부
- **멱등 요청** — 충전·출금·수락·정산에 `Idempotency-Key`를 실어 더블클릭이나 네트워크 재시도로 인한 이중 반영 차단
- **노쇼 자동 판정** — 근무 시작 후 1시간이 지나도 출근 기록이 없으면 스케줄러가 상태를 전환
- **계약서 자동 파기** — 보존 기간 3년이 지난 근로계약서를 매일 새벽 정리
- **실시간 알림** — SSE 스트림으로 근무 확정·서류 제출 등을 즉시 전달
- **문서 접근 감사** — 누가 어떤 문서를 열었는지 기록

> 지각은 **기록만 하고 임의로 차감하지 않습니다.** 정상 근무와 지각 모두 약정 일급 전액을 지급하고, 노쇼만 전액 환불합니다.

---

## 🛠 기술 스택

### Frontend

| 항목 | 버전 | 용도 |
| --- | --- | --- |
| Vue | 3.5.35 | Composition API |
| Vue Router | 5.0.6 | `createWebHistory` — 초대 딥링크 대응 |
| Pinia | 3.0.4 | 클라이언트 상태 |
| Axios | 1.18 | 세션 쿠키·CSRF 헤더 자동 처리 |
| Vite | 8.0 | 개발 서버·번들러 |
| Vitest | 4.1 | 단위 테스트 (jsdom) |
| qrcode | 1.5.4 | 사업장 고정 QR 렌더 |
| lucide-vue-next | 1.0 | 아이콘 |
| vite-svg-loader | 5.1 | SVG를 컴포넌트로 import |

### Backend

| 항목 | 버전 | 용도 |
| --- | --- | --- |
| Java | 17 | Gradle toolchain |
| Spring Framework | 5.3.39 | **Spring Boot 미사용** — 애노테이션 기반 WAR |
| Spring Security | 5.8.16 | HttpSession 인증 |
| MyBatis | 3.5.19 | SQL은 Mapper XML에 분리 |
| HikariCP | 7.0.2 | 커넥션 풀 |
| MySQL Connector/J | 9.7.0 | JDBC 드라이버 |
| Hibernate Validator | 6.2.5 | 요청 DTO 검증 |
| Jackson | 2.17.3 | JSON 직렬화 · `Instant` 처리 |
| Lombok | 1.18.46 | 보일러플레이트 축소 |
| PDFBox · openhtmltopdf | 3.0.8 · 1.1.4 | 근로계약서 PDF 렌더 |
| ZXing | 3.5.4 | QR 생성 |
| springfox | 3.0.0 | Swagger UI · OpenAPI 문서 |
| Tomcat | 9 (외부) | WAR 배포 대상 |

### Database

| 항목 | 값 |
| --- | --- |
| MySQL | 8.4 (로컬 Docker · 운영 RDS) |
| Flyway | 12.9 — 마이그레이션이 스키마의 단일 원본 |
| 문자셋 · 타임존 | `utf8mb4_0900_ai_ci` · `Asia/Seoul` |

### 인프라 · CI/CD

| 항목 | 값 |
| --- | --- |
| 웹 호스팅 | Vercel — `gighub.store` |
| API | nginx(TLS 종단) → Tomcat 9 컨테이너 → RDS |
| 컨테이너 | Docker Compose on EC2 |
| 이미지 | GitHub Container Registry |
| CI | GitHub Actions — 가드레일·테스트·빌드·WAR 아티팩트 |
| 배포 | GitHub Actions — 이미지 push 후 SSH 배포, 스모크 테스트 |
| 운영 알림 | CloudWatch Alarm → SNS → Lambda → Slack |

### 개발 도구

| 항목 | 용도 |
| --- | --- |
| ESLint · Prettier | 프론트 정적 검사와 포매팅 |
| Checkstyle | 백엔드 정적 검사 |
| Husky · lint-staged | 커밋 훅 — 메시지 형식 검사와 자동 포매팅 |

---

## 🏗 시스템 아키텍처

성격이 다른 세 경로를 나눠서 봅니다. 사용자 요청, 코드가 서버에 도달하는 길, 문제가 사람에게 알려지는 길은 각각 다른 문제를 만듭니다.

![GigHub 시스템 아키텍처](docs/assets/architecture.png)

- **TLS는 nginx에서 끝납니다.** Tomcat은 루프백에만 바인딩되어 외부에서 직접 도달할 수 없습니다.
- **DB는 프라이빗 서브넷에 있습니다.** 애플리케이션 컨테이너만 접근합니다.
- **배포 워크플로는 SSH 22번을 상시 열어두는 것에 의존하지 않습니다.** 배포·마이그레이션·시드는 실행할 때마다 러너 IP `/32` 규칙을 보안그룹에 추가했다가 `if: always()`로 회수합니다. 다이어그램의 "SSH · 배포 중에만 22 개방"은 이 `/32` 규칙을 가리킵니다. 상시 22번 규칙이 따로 있는지는 아직 확인되지 않았습니다([`deploy/SETUP.md`](deploy/SETUP.md) 2절).
- **스키마 변경은 배포와 다른 경로입니다.** 애플리케이션은 이전 이미지로 즉시 롤백되지만 적용된 DDL은 돌아오지 않기 때문에, 마이그레이션은 사람이 눌러야만 실행됩니다.
- **Lambda 알림 함수는 자동 배포되지 않습니다.** 코드 원본은 `deploy/lambda/slack-alert/`에 있고 반영은 수동입니다.

서버 구성, 배포·롤백 절차, 모니터링 설정은 [배포 환경 구성](deploy/SETUP.md)에 있습니다.

---

## 🚀 시작하기

### 필수 요구사항

- Git
- Java 17
- Node.js `>=20.19.0 <25`, npm `>=11 <12`
- Docker (로컬 MySQL · Flyway)
- 외부 Tomcat 9

### 설치

루트와 프론트엔드는 서로 다른 lockfile을 씁니다. 각각 설치합니다.

```sh
git clone https://github.com/KB-24-2-GigHub/KB-PJT-24-2.git
cd KB-PJT-24-2

npm ci
npm --prefix frontend ci
```

### 환경 변수

```sh
cp .env.example .env
cp frontend/.env.example frontend/.env
```

| 파일 | 키 | 기본값 | 설명 |
| --- | --- | --- | --- |
| `.env` | `MYSQL_PORT` | `3307` | 로컬 MySQL 노출 포트 |
| `.env` | `MYSQL_DATABASE` | `kb_pjt` | 데이터베이스 이름 |
| `.env` | `MYSQL_USER` · `MYSQL_PASSWORD` | — | 애플리케이션 계정 |
| `.env` | `MYSQL_ROOT_PASSWORD` | — | root 비밀번호 |
| `frontend/.env` | `VITE_API_BASE_URL` | `/api` | Axios base URL |
| `frontend/.env` | `DEV_PROXY_TARGET` | `http://localhost:8080` | Vite `/api` 프록시 대상 |
| `frontend/.env` | `DEV_ALLOWED_HOSTS` | (비움) | HTTPS 터널 접속 시에만 사용 — 실기기 카메라·위치는 보안 컨텍스트 필요 |
| `frontend/.env` | `VITE_USE_MOCK` | `false` | 레거시 Auth·User 데모 어댑터 전용 |
| `frontend/.env` | `VITE_MOCK_OPERATIONS` | (비움) | 개발 환경에서 Operation 단위 mock 선택 (예: `wallet.fetchWallet`) |

### 데이터베이스

```sh
docker compose up -d db
npm run db:migrate
```

애플리케이션은 Flyway를 자동 실행하지 않습니다. Tomcat을 켜기 전에 마이그레이션을 마칩니다.

### 백엔드 실행

```sh
cd backend
./gradlew war    # build/libs/gig-hub.war
```

생성된 WAR를 외부 Tomcat 9에 배포합니다.

### 프론트엔드 실행

```sh
npm --prefix frontend run dev
```

개발 서버는 `http://localhost:5173`에 고정되어 있습니다. 서버가 `/api/**`의 인증 포함 CORS를 이 Origin에서만 허용하므로 포트를 바꾸면 로그인이 막힙니다.

### 검증

```sh
npm run check      # 가드레일 · 하네스 테스트 · 프론트 테스트 · 프론트 빌드 · 프론트/백엔드 린트
npm run test:fe    # 프론트 단위 테스트만
```

`npm run check`는 CI가 실행하는 것과 같은 명령입니다.

### 기타 스크립트

| 명령 | 설명 |
| --- | --- |
| `npm run db:migrate` | Flyway 마이그레이션 적용 |
| `npm run db:seed:contract` | 계약·예치 테스트 시드 |
| `npm run db:seed:demo` | 전체 초기화 후 시연 상태 재생성 (확인값 필요) |
| `npm run db:fixture:invite` | 초대 수락 E2E 사전 데이터 |
| `npm run build:fe` | 프론트 프로덕션 빌드 |

---

## 📁 프로젝트 구조

```text
KB-PJT-24-2/
├── frontend/                    Vue 3 SPA
│   └── src/
│       ├── components/          재사용 UI
│       ├── composables/         뱃지·수익 틱·문서 미리보기
│       ├── layouts/             역할별 탭 레이아웃
│       ├── router/              화면 경로와 가드
│       ├── services/            Axios 클라이언트와 도메인 API
│       ├── stores/              Pinia 상태
│       └── views/               화면 (auth · owner · worker · invite · error)
│
├── backend/                     Spring Framework 5 WAR
│   └── src/main/
│       ├── java/com/gighub/
│       │   ├── config/          Root · MVC · DB 초기화
│       │   ├── auth/ member/ badge/
│       │   ├── workplace/ work/ invitation/ contract/
│       │   ├── attendance/      QR · 위치 기반 출퇴근
│       │   ├── wallet/ settlement/ bank/
│       │   ├── document/ notification/
│       │   ├── idempotency/     금융성 요청 중복 차단
│       │   └── (계층) controller → service → mapper → dto
│       └── resources/
│           ├── mappers/         MyBatis Mapper XML
│           └── db/migration/    Flyway 마이그레이션
│
├── deploy/                      운영 배치 파일 (compose · nginx · lambda)
├── docs/                        명세 · 런북 · 스키마 스냅샷
├── scripts/                     저장소 자동화 (가드레일 · 시드 준비 · 훅)
├── .github/workflows/           CI · 배포 · 마이그레이션 · 시드
└── compose.yaml                 로컬 MySQL · Flyway
```

---

## 📋 주요 플로우

### 1. 근무 등록 → 초대 → 수락 → 예치

돈이 묶이는 지점입니다. 계약 성립과 예치가 함께 끝나야 "계약은 됐는데 돈은 안 묶인" 상태가 생기지 않습니다.

```text
[사장님]                          [서버]                          [알바생]
   │                                │                                │
   ├─ 근무 등록 ─────────────────▶ work_cases 생성 (수락 전)
   │  날짜·시간·약정 일급·휴게      │
   │                                │
   ├─ 초대 링크 발급 ────────────▶ work_invitations 토큰 발급
   │                                │
   │  링크 전달 ──────────────────────────────────────────────────▶ │
   │                                │                                │
   │                                ◀── 조건 확인 ───────────────────┤
   │                                ◀── 동의(전자서명) · 근무 확정 ──┤
   │                                │                                │
   │                                ├─ 계약 성립 · 서명 기록
   │                                ├─ 근로계약서 PDF 자동 생성
   │                                └─ 사장님 지갑에서 약정 일급 예치
   │                                │
   ◀── 실시간 알림 ────────────────┴── 실시간 알림 ────────────────▶ │
```

### 2. QR 출퇴근 → 정산 / 노쇼 환불

세 갈래로 끝납니다. 정상과 지각은 약정 일급 전액 지급, 노쇼는 전액 환불입니다.

```text
[알바생]                          [서버]                          [사장님]
   │                                │                                │
   ├─ 매장 QR 스캔 (출근) ───────▶ 출근 기록 · 상태: 근무중
   │                                ├── 실시간 알림 ────────────────▶ │
   │                                │                                │
   ├─ 매장 QR 스캔 (퇴근) ───────▶ 상태: 근무 완료
   │                                │                                │
   │                                ◀── 일당 지급 승인 ──────────────┤
   │                                ├─ 예치금 해제 → 정산 기록
   ◀── 안심지갑 잔액 증가 ─────────┘                                │
   │                                                                 │
   ├─ 본인 계좌로 출금 ──────────▶ 출금 요청                        │

   ────────────────────────── 노쇼 경로 ──────────────────────────

   (출근 스캔 없음)                 │
                                    ├─ 시작 +1시간 경과 → 상태: 노쇼 (자동)
                                    │                                │
                                    ◀── 노쇼 환불 승인 ──────────────┤
                                    └─ 예치금 전액 환불 · 가용 잔액 복구
```

---

## 🌐 API 연동

> **[Swagger UI에서 API 살펴보기](https://api.gighub.store/swagger-ui/index.html)** — 실행 중인 서버의 엔드포인트와 요청·응답 스키마를 직접 확인할 수 있습니다.

### 연동 규약

| 항목 | 내용 |
| --- | --- |
| Base URL | `VITE_API_BASE_URL` (기본 `/api`) · 로컬은 Vite 프록시로 Tomcat에 전달 |
| 인증 | **세션 전용** — `JSESSIONID` 쿠키. accessToken을 저장하거나 전송하지 않음 |
| CSRF | `XSRF-TOKEN` 쿠키를 읽어 모든 상태 변경 요청에 `X-XSRF-TOKEN` 자동 첨부 |
| 성공 응답 | `{ data }` 를 벗겨 본문만 전달 |
| 오류 응답 | `{ code, message, traceId, fieldErrors }` — 폼 필드 매핑에 사용 |
| 401 처리 | 세션을 버리고 온보딩으로 이동. 초대 딥링크는 알바생 로그인으로 복귀 경로 보존 |
| 멱등성 | 금융성 요청에 `Idempotency-Key` 헤더. 서버가 응답한 4xx는 재시도하지 않음 |
| 시각 표현 | API는 UTC `Instant` 문자열, DB는 `Asia/Seoul` 벽시계 값 |

### 도메인별 엔드포인트

| 도메인 | 주요 경로 |
| --- | --- |
| 인증 | `POST /api/auth/signup` · `/login` · `/logout` <br/> `GET /api/auth/csrf` · `/session` · `/login-id-availability` · `/email-availability` |
| 회원 · 뱃지 | `GET · PATCH /api/users/me` · `GET /api/users/me/badge` |
| 사업장 | `POST · GET /api/workplaces` · `PUT /api/workplaces/{id}/coordinates` |
| QR | `GET /api/workplaces/{id}/qr` · `POST /api/workplaces/{id}/qr/reissue` |
| 근무 | `POST · GET /api/workplaces/{id}/work-cases` · `GET /api/workplaces/{id}/work-cases/summary` <br/> `GET · PATCH · DELETE /api/work-cases/{id}` |
| 초대 | `POST /api/work-cases/{id}/invitations` · `/invitations/reissue` <br/> `GET /api/invitations/{token}` · `POST /api/invitations/{token}/accept` |
| 알바생 | `GET /api/worker/home` · `/work-cases` · `/workplaces` |
| 출퇴근 | `POST /api/attendance/scans` |
| 지갑 | `GET /api/wallet` · `/api/wallet/transactions` <br/> `POST /api/wallet/funding-orders` · `/api/wallet/withdrawal-requests` |
| 정산 | `POST /api/work-cases/{id}/settlement/approve` · `/settlement/no-show-refund/approve` |
| 이의 | `POST · GET /api/work-cases/{id}/disputes` |
| 문서 | `GET · POST /api/documents` · `GET · PATCH · DELETE /api/documents/{id}` <br/> `GET /api/documents/{id}/file` · `POST · GET /api/documents/{id}/shares` · `DELETE /api/documents/{id}/shares/{workplaceId}` |
| 알림 | `GET /api/notifications` · `/unread-count` <br/> `PATCH /api/notifications/{id}/read` · `/api/notifications/read-all` · `GET /api/notifications/stream` |
| 헬스 | `GET /api/health` |

요청·응답 필드의 확정 계약은 `docs/specs/API_SPEC.md`에 있습니다.

---

## 🤝 기여 방법

1. 기본 통합 브랜치는 `dev`입니다.
2. Issue 템플릿(버그 / 기능 / 작업) 중 맞는 것으로 Issue를 먼저 만듭니다.
3. 커밋 메시지는 [`docs/COMMIT_CONVENTION.md`](docs/COMMIT_CONVENTION.md)를 따릅니다. Husky 훅이 형식을 검사합니다.
4. PR 전에 `npm run check`를 통과시킵니다. CI가 같은 명령을 실행합니다.
5. 명세와 DB 마이그레이션은 CODEOWNER 승인이 필요한 보호 경로입니다.

전체 개발 문서는 [`docs/README.md`](docs/README.md)에서 찾습니다.

---

## 📄 License

이 저장소에는 아직 `LICENSE` 파일이 없습니다.

---

## 👥 팀

KB IT's Your Life 7기 · 24-2팀

| 이름 | GitHub | 담당 |
| --- | --- | --- |
| 김인범 | [@Flamingo7562](https://github.com/Flamingo7562) | |
| 이돈녕 | [@donnyeonglee](https://github.com/donnyeonglee) | |
| 이현서 | [@hyunseo2503](https://github.com/hyunseo2503) | |
| 최정원 | [@dkgkrltlfgek](https://github.com/dkgkrltlfgek) | |
| 하성민 | [@hsm9411](https://github.com/hsm9411) | |

<div align="center">

**GigHub** · KB IT's Your Life 7기 24-2팀

</div>
