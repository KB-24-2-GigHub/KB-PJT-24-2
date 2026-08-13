---
patch_id: SPEC-343-01
status: draft
issue: 343
base_spec_version: 8.0.0
targets:
  - requirement: WORKPLACE-001
  - requirement: WORKPLACE-002
  - requirement: ATT-003
  - decision: DEC-WORKPLACE-ATTENDANCE-LOCATION
  - decision: DEC-WORKPLACE-IMMUTABLE
  - operation: POST /api/workplaces
  - operation: PATCH /api/workplaces/{workplaceId}
  - operation: PUT /api/workplaces/{workplaceId}/coordinates
---

# SPEC-343-01: 사업장 좌표를 서버 주소 변환으로 확정

> 이 Patch는 `DEC-WORKPLACE-ATTENDANCE-LOCATION`이 확정한 "서버는 주소로 좌표를 추정하지
> 않는다"를 반대 방향으로 바꾼다. 승인된 계약의 방향 전환이므로 draft 단계에서는 이 문서가
> 통합 브랜치의 임시 계약이며, 정식 `docs/specs/**` 반영은 Controller의 별도 SPEC 릴리스로만
> 이루어진다. 구현 착수 전 Kakao 앱의 REST API 키 발급과 개발·배포 환경 전달이 선행되어야 한다.

## 추가 사항

### 좌표의 유일한 출처는 서버 주소 변환이다

사업장 좌표는 OWNER가 입력한 `roadAddress`를 서버가 외부 주소 변환 서비스(Kakao Local REST
API)로 변환해 확정한다. 클라이언트가 보낸 `latitude`·`longitude`는 저장 근거로 신뢰하지 않는다.

- `POST /api/workplaces` 요청에서 `latitude`·`longitude`는 허용 필드가 아니다. 보내면
  `400 VALIDATION_ERROR`다. 기존의 "좌표를 함께 입력하거나 모두 생략한다"와 좌표 쌍 검증은
  대체된다.
- 서버는 변환에 성공한 좌표만 저장한다. 정확도와 측정 시각은 저장하지 않는다.
- 저장 정밀도는 현행 `decimal(10,7)`을 그대로 사용한다. 스키마 변경은 없다.

### 좌표 없는 사업장은 만들지 않는다

주소 변환에 실패하면 사업장을 생성하지 않는다. 좌표가 비어 있는 사업장은 이 Patch 이후 새로
생기지 않는다.

- 변환 실패는 사업장 생성 트랜잭션 전체를 취소한다. `WORKPLACE-001`이 같은 트랜잭션에서
  만들도록 정한 활성 고정 QR도 생성되지 않는다.
- 기존의 "좌표 없는 등록도 허용하지만 출퇴근 위치 미확정으로 표시하고 READY 진입을 막는다"는
  신규 등록 경로에서 적용되지 않는다. 이미 좌표가 없는 기존 사업장에 대한 READY 차단 동작은
  그대로 유지한다.

### 주소를 바꾸면 좌표를 다시 계산한다

`roadAddress` 변경은 좌표 재계산을 동반한다.

- 좌표가 이미 있는 사업장의 주소 변경을 막던 `409 WORKPLACE_LOCATION_LOCKED`는 도로명주소
  변경에 대해 적용하지 않는다.
- 재계산에 실패하면 수정 요청 전체를 취소하고 기존 주소와 기존 좌표를 그대로 유지한다. 주소만
  바뀌고 좌표가 과거 위치에 남는 중간 상태를 만들지 않는다.
- `latitude`·`longitude`·`radiusMeters`·`representativeName`·`businessRegistrationNumber`를
  직접 보내면 `400 VALIDATION_ERROR`라는 기존 규칙은 유지한다. 사용자는 여전히 좌표를 직접
  수정할 수 없고(`DEC-WORKPLACE-IMMUTABLE` 취지 유지), 서버만 주소로부터 좌표를 바꾼다.

### 현장 위치 확정 Endpoint는 제공하지 않는다

`PUT /api/workplaces/{workplaceId}/coordinates`는 좌표가 비어 있는 사업장을 전제로 한
경로였다. 신규 사업장이 항상 좌표를 가지므로 이 Patch 범위에서는 제공하지 않는다. 기존
좌표 없는 사업장의 보정은 이 Patch에서 다루지 않으며, 대상과 검증 범위를 정한 별도 관리자
승인 이슈로 분리한다.

### 실패를 원인별로 구분한다

주소 자체의 문제와 외부 서비스 장애를 사용자 안내에서 구분한다.

| 상황                                                         | 상태 | Code                                          |
| ------------------------------------------------------------ | ---: | --------------------------------------------- |
| 주소를 좌표로 변환할 수 없음, 결과가 모호해 하나로 확정 불가 |  422 | `WORKPLACE_ADDRESS_NOT_RESOLVABLE`            |
| 외부 변환 서비스 Timeout·오류·인증 실패                      |  503 | `WORKPLACE_GEOCODING_TEMPORARILY_UNAVAILABLE` |

- `WORKPLACE_ADDRESS_NOT_RESOLVABLE`은 사용자가 주소를 고쳐 다시 시도해야 하는 확정 실패다.
- `WORKPLACE_GEOCODING_TEMPORARILY_UNAVAILABLE`은 잠시 후 같은 요청을 다시 시도할 수 있는
  일시 실패다. 명명은 기존 `ATTENDANCE_TEMPORARILY_UNAVAILABLE`·
  `SETTLEMENT_TEMPORARILY_UNAVAILABLE` 관례를 따른다.
- 외부 서비스의 상태 코드·응답 본문·키를 그대로 사용자에게 노출하지 않는다.

### 외부 호출 경계

- 연결 Timeout과 읽기 Timeout을 명시적으로 설정한다. 무제한 대기를 두지 않는다.
- Timeout 초과는 위 일시 실패로 처리하고 사업장 생성·수정 트랜잭션을 취소한다.
- 변환 결과가 복수일 때 임의로 첫 결과를 선택하지 않는다. 하나로 확정할 수 없으면 확정 실패로
  처리한다.

## 보안

- Kakao REST API 키는 저장소에 커밋하지 않는다. `DatabaseConfig`가 `gighub.database.config`
  시스템 속성으로 외부 properties를 읽는 기존 방식과 같은 경계로 주입한다.
- 키는 서버에서만 사용하고 응답·로그·오류 메시지에 남기지 않는다.
- 가능하면 Kakao 앱에 호출 허용 IP를 설정한다.
- 클라이언트가 좌표를 보내도 저장에 사용하지 않으므로, 좌표 위·변조로 출퇴근 반경 판정을
  움직일 수 없다(`ATT-003`의 반경 검증 기준점 신뢰).

## 완료 조건

- [ ] 도로명주소로 사업장을 등록하면 좌표가 채워져 저장되고, 응답의
      `attendanceLocationConfirmed`가 `true`다.
- [ ] `POST /api/workplaces`에 `latitude`·`longitude`를 보내면 `400 VALIDATION_ERROR`다.
- [ ] 변환할 수 없는 주소로 등록하면 `422 WORKPLACE_ADDRESS_NOT_RESOLVABLE`이고 사업장과 QR이
      모두 생성되지 않는다.
- [ ] 외부 변환 서비스가 Timeout·오류를 내면 상태 `503`,
      `WORKPLACE_GEOCODING_TEMPORARILY_UNAVAILABLE`이고 사업장과 QR이 모두 생성되지 않는다.
- [ ] 두 실패가 화면에서 서로 다른 안내로 구분되고, 일시 실패만 재시도를 안내한다.
- [ ] 좌표가 있는 사업장의 도로명주소를 바꾸면 좌표가 새 주소 기준으로 갱신된다.
- [ ] 주소 변경 중 변환이 실패하면 주소와 좌표가 모두 변경 전 값으로 유지된다.
- [ ] 좌표가 채워진 사업장에서 QR 스캔 반경 검증이 동작한다.
- [ ] 외부 키가 저장소 추적 파일과 응답·로그에 없다.
