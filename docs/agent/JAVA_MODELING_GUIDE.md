# Java 모델링 가이드

이 문서는 Java 코드의 역할과 수명주기를 드러내면서 불필요한 타입과 계층을 줄이기 위한
공유 Review 기준이다. 타입 수나 LOC 자체를 목표로 최적화하지 않으며, 제품 동작과 공개 API,
Transaction, Lock, Mapper 소유권은 보호 명세와
[`MODULE_BOUNDARIES.md`](MODULE_BOUNDARIES.md)를 따른다.

## 적용 순서

1. 변경할 사용자 명령과 보호해야 할 동작·Transaction·Lock·멱등 위험을 먼저 적는다.
2. API, Application, Domain, Persistence 타입의 역할과 수명주기를 구분한다.
3. 새 추상화를 추가하기 전에 전달만 하는 계층, 같은 수명주기의 중복 타입, 상태 없는 helper를
   먼저 검토한다.
4. 생성자, Lombok, interface, Exception은 아래 기준을 만족하는 현재 변경 Slice에만 적용한다.
5. 가장 좁은 특성화 테스트부터 실행하고 이슈 완료 경계에서 공유 Guardrail과 전체 Check를
   실행한다.

전 저장소 mass rename, 일괄 format, blanket Lombok 전환은 별도 근거가 없는 한 하지 않는다.
MyBatis 수직 Slice나 금융·보안 변경은 분리하면서 불변식이 약해진다면 한 Review 단위로 유지하고
검토 순서를 기록한다.

## Lombok과 생성자

### Spring Bean

- 모든 의존성이 `final`이고 생성자가 대입만 하면 `@RequiredArgsConstructor`를 사용한다.
- 고정 `Clock`을 받는 테스트 seam, 선택 의존성, 입력 검증, 생성 순서 제약이 있으면 명시적
  생성자를 유지한다.
- Field injection과 생성자 안의 업무 로직은 허용하지 않는다.
- 한 Slice의 생성자 방식을 맞추되, 이 규칙만을 위해 무관한 Bean을 일괄 변환하지 않는다.

### Domain Model과 Value

- 불변 값에 필요한 `@Value`, `@Getter`, `@Builder`만 선택한다.
- `@Data`와 광범위 Setter는 사용하지 않는다.
- 상태 변경은 의미 메서드로 표현한다.
- equality가 업무 의미와 일치할 때만 `@Value`를 사용한다.
- Builder가 불가능한 중간 상태를 만들 수 있으면 정적 factory나 명시 생성자로 입력을 제한한다.

### API와 MyBatis 타입

- API Request/Response는 Jackson 생성, Bean Validation, 정규화, 응답 allowlist가 먼저다.
- Request를 mutable Domain 객체로 재사용하지 않는다.
- MyBatis Row/Param에는 실제 mapping에 필요한 생성자와 Setter만 둔다.
- Mapper Row/Param은 persistence package 밖으로 노출하지 않는다.
- Lombok Builder가 XML mapping을 복잡하게 만들면 명시 생성자나 필요한 Setter를 유지한다.

## 타입 역할

Production 타입은 다음 네 범주 중 하나로 식별한다.

1. **API Request/Response**: HTTP 직렬화, Validation, 공개 필드 allowlist
2. **Application Command/Result**: 인증·Header 결합을 제거한 유스케이스 입력과 결과
3. **Domain Model/Policy**: Framework 없는 불변식, 계산, 상태 결정
4. **Mapper Row/Param**: SQL nullable 값, 컬럼 표현, MyBatis mapping

Query Projection은 읽기 전용 경계에서만 사용하는 명시적 예외다. 다음 차이 중 하나가 있으면
타입을 분리할 근거가 된다.

- Web annotation 또는 직렬화 Shape
- 인증 Principal이나 Header 결합
- 정규화, 시간대 또는 offset 변환
- Domain 불변식
- SQL nullable 또는 컬럼 표현
- 비공개 필드 차단
- Transaction 전후의 서로 다른 수명주기

위 차이가 없고 데이터와 수명주기가 같으면 별도 변환 타입을 추가하지 않는다. 이름이 비슷해도
Mapper Row와 공개 Result, 지급 전 정책 입력과 지급 후 대사 결과처럼 경계가 다르면 합치지 않는다.

## Interface와 구현

다음 책임 중 하나가 있으면 interface를 유지하거나 도입할 수 있다.

- 외부 Adapter 또는 Gateway
- 둘 이상의 실제 구현
- 호출자의 Transaction에 참여하는 Command port
- 논리 모듈의 공개 경계
- 구체 구현을 대체해야 하는 실제 테스트 seam
- 순환 compile dependency 차단

이 근거 없이 같은 package에서 `Impl` 이름만 만들기 위한 1:1 interface는 제거 후보이다. 제거 전에
Spring Transaction annotation 위치, JDK/CGLIB proxy 타입, 주입 지점, 테스트 대역, 공개 모듈
계약을 모두 확인한다. 다른 공개 interface도 함께 구현하는 Bean은 concrete class 주입으로
성급히 바꾸지 않는다.

단순 전달 class도 별도 정책, Transaction, Adapter, 재시도 또는 감사 경계를 만들지 않으면
소비자가 승인된 공개 port를 직접 사용하도록 줄일 수 있다.

## Exception

전용 Exception은 호출자가 다음 중 하나를 실제로 구분할 때 유지한다.

- 외부 Error Code 또는 HTTP Status
- retry 가능성
- rollback 또는 cleanup 정책
- logging severity 또는 감사 요구
- caller의 분기 행동

차이가 메시지뿐이면 공통 의미 오류와 context를 사용한다. 반대로 Domain 오류를 예치 Conflict,
출금 잔액 오류, 정산 무결성 오류처럼 각 caller가 다르게 번역한다면 Domain 분기 Exception을
없애지 않는다. SQL·Framework 예외는 공개 경계 밖으로 직접 노출하지 않는다.

## Service 복잡성과 추출

다음은 자동 실패가 아니라 Review warning이다.

- 변경 파일 10개 초과
- 일반 리팩터링 500 LOC 초과
- 한 메서드가 여러 상태, 금액, 외부 Adapter를 동시에 조정
- 한 Service가 여러 논리 모듈의 Mapper를 소유
- 새 타입, Exception 또는 추상화 증가

대형 Service는 사용자 명령의 단계가 위에서 아래로 읽혀야 한다. 상태 없는 변환·검증·조립은
package-private helper로 분리할 수 있지만 다음은 원래 Transaction participant에 남긴다.

- `@Transactional` 경계와 propagation
- Lock 취득 순서와 Mapper 호출 순서
- expected-state update와 영향 행 검증
- 원장 쓰기와 중복 Key 예외 번역
- 호출 사이에만 유효한 private capability 또는 lock snapshot

DB CHECK는 저장 가능한 행 Shape를 보장한다. 현재 명령과 잠긴 행의 소유권·금액·원장 쌍이
일치하는지는 Application이 계속 검증하며, schema 제약을 이유로 이를 제거하지 않는다.

## 주석

- 비자명한 business rule, Transaction, Lock, idempotency, schema compatibility에는 “무엇”보다
  “왜”를 설명하는 간결한 한국어 주석을 둔다.
- 자명한 대입, 반복되는 코드 설명, 이슈 번호에만 의존하는 임시 주석은 추가하지 않는다.
- 코드를 바꿀 때 의미가 달라진 기존 주석은 함께 고친다.

## 측정과 Review 기록

변경의 품질은 Review 본문 또는 커밋 근거에 다음 before/after를 같은 계산 기준으로 기록한다.
중앙 기능 현황 문서나 완료 인벤토리는 만들지 않는다.

- Production type 수: API, Application, Domain, Mapper, Query Projection, 기타 역할별
- Service 전체 LOC와 핵심 public method의 대략 LOC
- 전용 Exception 선언 수
- 논리 모듈 사이의 production import edge 수
- 제거·추가한 interface, 전달 계층, helper와 각 근거
- Test 개수가 아니라 보호한 API, 상태, rollback, 동시성, 원장 위험
- Controller, API DTO, Mapper SQL, Migration을 포함한 외부 동작·API diff 유무

측정 대상은 이슈가 지정한 merge commit의 first-parent Java 합집합으로 고정하고, 후속 변경에서
삭제되거나 이동된 옛 경로는 현재 대상에서 제외한다. 새 helper가 생기면 원본 합집합과 별도로
표시해 타입을 숨기지 않는다. 숫자는 삭제 목표가 아니라 책임과 Review 범위를 설명하는 증거다.

## 검증

- `@Data`, Domain Setter, Field injection scan
- interface와 구현체의 production 사용처 및 Spring proxy 확인
- 전용 Exception의 외부 code·retry·rollback·caller 분기 확인
- Controller·API DTO·Mapper SQL·Migration diff 확인
- 변경 Slice의 focused test와 [`VERIFICATION_GUIDE.md`](VERIFICATION_GUIDE.md)의 위험별 테스트
- `npm.cmd run check:guardrails`, `npm.cmd run test:harness`, backend Gradle `check`
- 이슈 완료 경계의 root `npm.cmd run check`

실제 MySQL Transaction, 동시성, UNIQUE/FK와 원장 보존을 변경했다면 Mock 단위 테스트만으로
완료하지 않고 관련 `databaseTest`를 실행한다.
