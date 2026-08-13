# 기술 스택과 의존성 기준

> 상태: 현재 기준
>
> 적용 대상: 언어, 런타임, 프레임워크, 직접 의존성, 빌드 도구, 컨테이너 이미지
>
> 읽는 시점: 위 항목을 추가·삭제·교체·업그레이드하거나 새로운 기술을 제안하기 전

이 문서는 Gig Hub에서 **어떤 기술을 기본으로 사용하고, 무엇을 금지하거나 팀 합의 대상으로 두는지** 정하는 기준 문서다. 패키지의 실제 설치 상태를 복제하는 목록이 아니라 기술 선택과 직접 의존성의 역할을 설명하는 결정 기록이다.

## 1. 진실 원본과 문서 책임

정확한 현재 버전과 실제 해석 결과는 다음 실행 설정을 우선한다.

| 대상                               | 진실 원본                                          |
| ---------------------------------- | -------------------------------------------------- |
| Backend 언어·Plugin·직접 의존성    | `backend/build.gradle`                             |
| Gradle 버전                        | `backend/gradle/wrapper/gradle-wrapper.properties` |
| Frontend 직접 의존성·Node/npm 범위 | `frontend/package.json`                            |
| Frontend 실제 npm 해석 결과        | `frontend/package-lock.json`                       |
| 저장소 공통 개발 도구              | 루트 `package.json`, `package-lock.json`           |
| MySQL·Flyway 이미지와 로컬 인프라  | `compose.yaml`                                     |
| 외부 Tomcat 배포 절차              | `backend/README.md`와 팀 실행 환경                 |

- 이 문서는 기술의 허용 상태, 직접 의존성의 이름과 역할, 호환 경계와 변경 절차를 관리한다.
- Lockfile과 Gradle 의존성 해석 결과는 전이 의존성의 진실 원본이다. 전이 의존성 전체를 이 문서에 복제하지 않는다.
- 문서와 실행 설정이 다르면 현재 동작을 판단할 때 실행 설정을 우선한다. 다만 관련 작업은 둘의 불일치를 해소해야 완료된다.
- 직접 의존성을 새로 추가·삭제·대체하거나 역할을 바꾸면 실행 설정과 이 문서를 같은 PR에서 갱신한다.
- 단순 버전 변경은 아래의 기술 경계, 호환 판단 또는 문서에 적힌 기준값이 달라질 때 이 문서도 갱신한다.

## 2. 정책 분류

| 분류      | 의미                                                                                    |
| --------- | --------------------------------------------------------------------------------------- |
| 기본·필수 | 새 기능도 이 기술을 우선 사용하며 다른 기술로 교체하려면 별도 아키텍처 결정이 필요하다. |
| 허용      | 현재 직접 의존성으로 승인되어 해당 역할 범위에서 사용할 수 있다.                        |
| 조건부    | 금지는 아니지만 명확한 필요와 영향 분석, 팀 합의가 있어야 도입할 수 있다.               |
| 금지      | 현재 교육 범위나 아키텍처 경계와 충돌하므로 추가하지 않는다.                            |

이 문서에 없는 새 직접 의존성은 자동으로 허용되지 않는다. 먼저 조건부 후보로 취급하고 변경 절차에 따라 검토한다.

## 3. 고정 기술 경계

| 영역               | 현재 기준                               | 정책                                                                              |
| ------------------ | --------------------------------------- | --------------------------------------------------------------------------------- |
| Backend 언어       | Java `17`                               | 필수. Gradle toolchain과 compile release를 17로 유지한다.                         |
| Backend Framework  | Spring Framework `5.3.x`, 현재 `5.3.39` | 필수. Spring Boot 없이 외부 Tomcat에 배포하는 WAR를 만든다.                       |
| Servlet Container  | 외부 Tomcat `9`, 팀 배포 기준 `9.0.118` | 필수. 저장소가 Tomcat patch를 설치하지 않으므로 실제 실행 환경에서 별도 확인한다. |
| Servlet 경계       | Servlet `4.0`, `javax.*`                | 필수. Tomcat 10/Spring 6의 `jakarta.servlet.*` 경계를 섞지 않는다.                |
| DB 접근            | MyBatis `3.5.x`, 현재 `3.5.19`          | 필수. SQL은 Mapper XML에 두고 Spring Transaction 경계를 사용한다.                 |
| DBMS·Migration     | MySQL `8.4`와 Flyway                    | 필수. 현재 Docker 이미지는 `compose.yaml`에서 관리한다.                           |
| Frontend Framework | Vue `3`와 Vite                          | 필수. React를 함께 사용하지 않는다.                                               |
| Frontend 작성 언어 | JavaScript 기본                         | 기본. TypeScript는 6절의 팀 합의 절차를 거치는 조건부 기술이다.                   |
| Backend 빌드       | Gradle Wrapper, WAR                     | 필수. 시스템 Gradle 대신 저장소 Wrapper를 사용한다.                               |
| 패키지 설치        | npm manifest와 lockfile                 | 필수. 팀과 CI는 lockfile 기반으로 같은 해석 결과를 사용한다.                      |

현재 실행 도구의 범위는 Java 17, Gradle Wrapper `9.3.0`, Node `>=20.19.0 <25`, npm `>=11 <12`다. MySQL과 Flyway의 정확한 이미지 태그는 `compose.yaml`에서 확인한다.

## 4. 현재 허용된 직접 의존성

아래 목록은 직접 선언된 패키지의 존재와 역할을 기록한다. 정확한 버전은 1절의 실행 설정에서 확인한다.

### Backend

| 역할               | 직접 의존성                                                                                                             |
| ------------------ | ----------------------------------------------------------------------------------------------------------------------- |
| Spring 기반        | Spring Framework BOM, `spring-context`, `spring-webmvc`, `spring-jdbc`, `spring-tx`                                     |
| 인증·인가          | Spring Security BOM, `spring-security-web`, `spring-security-config`, `spring-security-core`                            |
| DB 접근            | `mybatis`, `mybatis-spring`, `HikariCP`, MySQL Connector/J                                                              |
| 입력 검증          | Bean Validation 2.0 API, Hibernate Validator                                                                            |
| JSON               | Jackson BOM, `jackson-databind`, `jackson-datatype-jsr310`                                                              |
| Logging            | SLF4J API, Log4j2 BOM·API·Core, `log4j-slf4j2-impl`                                                                     |
| 계약 PDF·QR        | Apache PDFBox, ZXing Core·JavaSE                                                                                        |
| Runtime API 탐색   | Springfox `springfox-swagger2`, `springfox-swagger-ui`, `springfox-oas`                                                 |
| Container 제공 API | Servlet API와 Annotation API를 `compileOnly`로 사용하고, MockMvc 테스트에는 Servlet API를 `testImplementation`으로 사용 |
| 코드 생성          | Lombok을 compile/test annotation processor로 사용                                                                       |
| 테스트             | JUnit Jupiter·Platform Launcher, Spring Test, Mockito Core·JUnit Jupiter, JSONAssert, JsonPath, Hamcrest, GlassFish EL  |
| Gradle Plugin      | `java`, `war`, `checkstyle`                                                                                             |

다음 결합 관계를 함께 유지한다.

- Spring Framework, Spring Security, Jackson, Log4j2, JUnit 모듈은 각 BOM의 버전선으로 정렬한다.
- Servlet API와 Annotation API는 Tomcat이 제공하므로 운영 WAR에 중복 포함하지 않는다.
- Connector/J 버전을 변경하면 `backend/build.gradle`과 `compose.yaml`의 Flyway driver mount 파일명을 같은 PR에서 변경한다.
- 계약 PDF의 한글 Font는 `frontend/src/assets/fonts/`의 Pretendard(SIL Open Font License 1.1)를 `backend/src/main/resources/fonts/`에 복제해 재사용한다. 별도 Font 라이선스 구매나 신규 패키지 의존성이 아니며, PDFBox가 WAR에 Embed한다.
- Hibernate Validator는 Bean Validation 구현체이며 금지된 Hibernate ORM에 해당하지 않는다.
- Springfox는 현재 구현을 탐색하는 Runtime 도구이며 `docs/specs/API_SPEC.md`를 대체하지 않는다. Springfox 버전을 올릴 때는 사용 중인 `EnableOpenApi`(OAS 3.0) 경계와 호환되는지 먼저 확인한다.

### Frontend

| 역할         | 직접 의존성                                                                                            |
| ------------ | ------------------------------------------------------------------------------------------------------ |
| UI Framework | `vue`                                                                                                  |
| Routing·상태 | `vue-router`, `pinia`                                                                                  |
| HTTP         | `axios`                                                                                                |
| UI·Icon      | `bootstrap`, `@popperjs/core`, `lucide-vue-next`                                                       |
| QR 생성      | `qrcode`                                                                                               |
| Build        | `vite`, `@vitejs/plugin-vue`, `vite-svg-loader`                                                        |
| Lint·Format  | `eslint`, `@eslint/js`, `eslint-plugin-vue`, `vue-eslint-parser`, `prettier`, `eslint-config-prettier` |
| 테스트       | `vitest`, `@vue/test-utils`, `jsdom`                                                                   |

`package.json`의 버전 범위는 허용 범위이고 `package-lock.json`은 실제 설치 결과를 고정한다. Manifest와 lockfile을 함께 변경하고, 현재 패키지를 과거 문서의 설치 명령으로 다시 설치하지 않는다.

`qrcode`는 사장 QR 화면에서 브라우저가 QR 이미지를 그리는 용도다. Backend의 ZXing은 계약 PDF 등 서버 측 생성에 사용하므로 역할이 겹치지 않는다. 서버가 QR 이미지를 직접 내려주는 방식으로 바꾸면 이 의존성은 제거 대상이 된다.

### 저장소 공통 도구와 인프라

| 역할                   | 직접 의존성·이미지                 |
| ---------------------- | ---------------------------------- |
| Git Hook·staged format | `husky`, `lint-staged`             |
| DB                     | `mysql:8.4` 계열 이미지            |
| Migration              | `flyway/flyway` 이미지             |
| 배포 Runtime           | `tomcat:9.0-jdk17-temurin` 이미지  |
| 운영 알림 Runtime      | AWS Lambda `nodejs24.x`            |

MySQL 이미지 태그를 변경하면 DB 서비스와 seed 도구가 같은 버전선을 사용하는지 확인한다.

`tomcat:9.0-jdk17-temurin`은 `backend/Dockerfile`이 WAR을 포장할 때만 사용하며 로컬
개발 실행에는 관여하지 않는다. Tomcat 9와 Java 17이라는 고정 제약을 그대로 따르므로,
태그를 바꿀 때는 `backend/build.gradle`의 toolchain과 같은 버전선인지 확인한다.
로컬 인프라는 루트 `compose.yaml`, 배포 런타임은 `deploy/compose.prod.yaml`이 기준이다.

AWS Lambda `nodejs24.x`는 CloudWatch 부하 알람을 Slack으로 보내는 운영 알림 함수
(`deploy/lambda/slack-alert/`)의 실행 환경이다. 애플리케이션 스택이 아니라 저장소 밖
운영 도구이며, Frontend·Backend 어느 빌드에도 관여하지 않는다.

- **새 언어를 도입하지 않는다.** 3절의 실행 도구 범위 `Node >=20.19.0 <25` 안이고,
  `scripts/`의 저장소 자동화와 같은 언어다.
- **직접 의존성이 없다.** 전역 `fetch`, `AbortSignal.timeout`, `Intl`만 사용하므로
  `package.json`도 `node_modules`도 만들지 않는다. 패키지를 추가하려면 7절의 직접
  의존성 변경 절차를 먼저 밟는다.
- 함수 코드는 저장소에 있지만 실행 사본은 AWS 계정 안에 있고 자동 배포되지 않는다.
  런타임 식별자를 바꿀 때는 `deploy/SETUP.md` 13.5절과 이 표를 함께 갱신한다.

## 5. 금지 기술

- React, React DOM과 React를 전제로 하는 애플리케이션 UI 도구
- Spring Boot, Spring Boot Starter와 내장 Servlet Container 중심 구성
- JPA, Hibernate ORM, Spring Data JPA
- Spring 6/Tomcat 10 이상을 전제로 하는 `jakarta.servlet.*` 또는 Jakarta Validation 3.x 경계의 혼용
- 기존 MyBatis·Vue·Spring MVC 경계를 대체하는 별도 Framework를 아키텍처 결정 없이 추가하는 행위

금지 여부는 직접 의존성만으로 판단하지 않는다. 새 패키지가 금지 기술을 전이 의존성으로 끌어오는지도 lockfile과 Gradle 해석 결과에서 확인한다.

## 6. TypeScript 조건부 도입 기준

TypeScript는 절대 금지 기술이 아니다. 다만 현재 팀의 학습 범위와 유지보수 비용을 고려해 **JavaScript를 기본값**으로 유지한다.

팀 합의 전에는 다음 변경을 하지 않는다.

- `.ts`, `.tsx` 소스 또는 Vue SFC의 `<script lang="ts">` 추가
- `tsconfig.json`, TypeScript compiler, `@typescript-eslint/*` 같은 전용 도구의 직접 도입
- 개인 선호에 따른 기존 JavaScript 파일의 부분적·일괄 변환

도입이 필요하면 이슈 또는 PR에 다음 내용을 먼저 기록하고 팀 합의를 받는다.

1. TypeScript가 필요한 구체적인 문제와 기대 효과
2. 적용할 디렉터리·기능·파일 범위
3. JavaScript로 유지하는 대안과 그 한계
4. Build, lint, test, IDE, CI 설정에 미치는 영향
5. 팀 학습·리뷰·장기 유지보수 비용
6. JavaScript와의 공존 방식, 단계적 전환 또는 철회 방법

승인된 도입 PR은 필요한 직접 의존성, 설정, 코드, 테스트와 이 문서를 함께 갱신한다. 승인 범위 밖의 기존 JavaScript는 임의로 변환하지 않는다.

Lockfile에 나타나는 `.d.ts`, `@types/*`, optional peer metadata 또는 전이 의존성의 TypeScript 문자열만으로는 프로젝트가 TypeScript를 도입한 것으로 보지 않는다.

## 7. 직접 의존성 변경 절차

직접 의존성이나 도구를 추가·삭제·대체하기 전에 이슈 또는 PR에 다음을 남긴다.

1. 해결하려는 문제와 패키지의 역할
2. 기존 스택이나 표준 API로 해결하는 대안
3. Java·Node·Browser·Tomcat·Servlet·Vue·Spring·MyBatis 호환성
4. 라이선스, 보안 취약점, 유지보수 상태와 전이 의존성 영향
5. 적용 범위, 제거 조건과 영향받는 테스트

같은 PR에서 다음 항목을 함께 처리한다.

1. `build.gradle`, `package.json`, `compose.yaml` 등 직접 선언 원본
2. npm lockfile 또는 필요한 Gradle·Container 결합 설정
3. 이 문서의 허용 목록, 역할 또는 정책 분류
4. 관련 Build, lint, test, 실행 설정과 사용 코드

의존성 변경은 애플리케이션·빌드 결과에 영향을 주므로 PR 인계 전에 루트 `npm run check`를 수행한다. 이 명령이 Build·패키징·모든 테스트를 대신하지는 않으므로 변경 영역에 따라 다음 검증을 추가한다.

- Frontend Runtime·Build·Test 의존성: Frontend build와 Vitest
- Backend Runtime·Build·Test 의존성: Gradle dependency 해석과 영향받는 test, WAR 생성
- Container 이미지·Compose 결합 설정: `docker compose config`와 영향받는 Runbook 검증

문서만 수정하는 PR은 `docs/agent/PROJECT_RULES.md`의 Markdown 전용 검증 규칙을 따른다.

## 8. 문서 갱신 기준

다음 변경에서는 이 문서를 반드시 검토한다.

- 고정 기술의 major·호환 경계 변경
- 직접 의존성이나 Gradle Plugin, npm 개발 도구, Container 이미지의 추가·삭제·대체
- 직접 의존성의 역할 또는 허용 상태 변경
- TypeScript 같은 조건부 기술의 승인 또는 철회
- 금지 기술 예외를 요구하는 아키텍처 결정

단순 전이 의존성 갱신이나 lockfile 내부 해석 변화만으로 목록을 추가하지 않는다. 다만 보안·호환성 판단이 달라지면 관련 근거와 정책을 갱신한다.
