---
patch_id: SPEC-349-01
status: draft
issue: 349
base_spec_version: 8.0.0
targets:
  - requirement: WORKPLACE-002
  - decision: DEC-WORKPLACE-IMMUTABLE
  - decision: DEC-WORKPLACE-ATTENDANCE-LOCATION
  - operation: PATCH /api/workplaces/{workplaceId}
---

# SPEC-349-01: 도로명주소 수정 시 좌표를 다시 확정

> `SPEC-343-01`이 사업장 좌표의 출처를 서버 주소 변환 하나로 고정하면서, 등록 이후 주소를
> 바꾸는 경로에도 같은 규칙이 필요해졌다. 이 Patch는 `WORKPLACE-002`의 수정 Endpoint 계약을
> 정하고 그 안에서 좌표 재계산을 규정한다.

## 추가 사항

### 수정 Endpoint는 부분 수정이다

`PATCH /api/workplaces/{workplaceId}`는 인증 OWNER가 소유한 `ACTIVE` 사업장의 수정 가능한
필드만 바꾼다. 성공은 본문 없는 `204`다.

- 허용 필드는 `name`, `roadAddress`, `detailAddress`, `phone`뿐이다.
- 요청에 없는 필드는 바꾸지 않는다. `detailAddress`에 명시적 `null`을 보내면 지운다.
- `businessRegistrationNumber`, `representativeName`, `latitude`, `longitude`,
  `radiusMeters`, `radiusM`, `status`를 보내면 `400 VALIDATION_ERROR`다. 기존 등록 계약의
  거절 규칙을 그대로 유지한다.
- 허용 필드가 하나도 없는 요청은 `400 VALIDATION_ERROR`다. 아무것도 바꾸지 않는 성공을
  만들지 않는다.
- `name`과 `roadAddress`는 존재하면 비울 수 없다. 빈 값·공백만 보내면 `400
  VALIDATION_ERROR`다.
- `phone`은 등록과 같은 국내 전화번호 정규화·형식 규칙을 쓴다.
- 없는 사업장, 다른 OWNER의 사업장, `ACTIVE`가 아닌 사업장은 모두 `404 RESOURCE_NOT_FOUND`로
  구분하지 않는다.

### 도로명주소가 바뀌면 좌표를 다시 확정한다

`roadAddress`가 저장된 값과 다르면 서버가 새 주소를 변환해 `latitude`·`longitude`를 함께
갱신한다. 주소와 좌표는 항상 같은 지점을 가리킨다.

- 좌표 갱신은 주소 갱신과 같은 문장·같은 트랜잭션에서 이루어진다. 주소만 바뀌고 좌표가 과거
  위치에 남는 중간 상태를 만들지 않는다.
- 변환 실패는 수정 요청 전체를 취소한다. 주소·상호·전화번호·상세주소 중 어느 것도 바뀌지
  않는다. 실패 구분은 `SPEC-343-01`과 같다 — 확정 실패는 `422
  WORKPLACE_ADDRESS_NOT_RESOLVABLE`, 외부 서비스 장애와 응답 해석 실패는 `503
  WORKPLACE_GEOCODING_TEMPORARILY_UNAVAILABLE`이다.
- 요청의 `roadAddress`가 저장된 값과 같으면 변환을 호출하지 않고 좌표도 건드리지 않는다.
  상호·전화번호·상세주소만 바꾸는 수정이 외부 호출을 일으키지 않아야 한다.
- 변환 호출은 트랜잭션 밖에서 끝낸다. 외부 서비스 지연이 DB 커넥션을 붙잡지 않는다.
- 좌표를 다시 확정하는 수정은 변환 판단의 근거가 된 저장 주소를 갱신 조건에 포함한다. 그
  사이 다른 요청이 주소를 바꿨으면 `409 CONFLICT`로 거절하고 아무것도 바꾸지 않는다.

### 주소 변경을 `409 WORKPLACE_LOCATION_LOCKED`로 막지 않는다

기존 계약은 좌표가 확정된 사업장의 도로명주소 변경을 `409 WORKPLACE_LOCATION_LOCKED`로
거절했다. 그 규칙은 사용자가 직접 확정한 현장 좌표를 주소 변경이 어긋나게 만드는 것을 막기
위한 것이었다. `SPEC-343-01` 이후 좌표는 사용자 입력이 아니라 주소의 파생값이므로, 주소를
바꾸면서 좌표를 그대로 두는 쪽이 오히려 어긋난 상태다. 이 Patch는 그 거절을 없애고 재계산으로
대체한다.

- `WORKPLACE_LOCATION_LOCKED`는 수정 계약에서 사용하지 않는다.
- `DEC-WORKPLACE-IMMUTABLE`의 원칙은 유지된다. 사용자는 여전히 좌표와 인증 반경을 직접 보낼
  수 없고, 대표자명과 사업자등록번호도 수정할 수 없다.
- `PUT /api/workplaces/{workplaceId}/coordinates`는 이 Patch가 바꾸지 않는다. 좌표가 비어
  있는 기존 사업장만을 위한 경로로 남는다.

### 좌표 갱신 시점의 근태 판정

반경 판정은 언제나 그 시점의 사업장 좌표를 기준으로 한다(`ATT-003`). 주소 수정으로 좌표가
바뀌면 이후 스캔부터 새 좌표가 기준이 되며, 이미 기록된 근태는 다시 판정하지 않는다. 근무가
진행 중인 사업장의 주소 수정을 별도로 막지 않는다.

## API

```http
PATCH /api/workplaces/11
Content-Type: application/json
```

```json
{
  "name": "강남 2호점",
  "roadAddress": "서울 강남구 테헤란로 2",
  "detailAddress": "3층",
  "phone": "02-1234-5678"
}
```

| 상황                                                    | 상태 | Code                                          |
| ------------------------------------------------------- | ---: | --------------------------------------------- |
| 수정 성공                                               |  204 | —                                             |
| 미승인 필드, 허용 필드 없음, 형식·길이 오류, 빈 필수 값 |  400 | `VALIDATION_ERROR`                            |
| OWNER 아님                                              |  403 | `ROLE_MISMATCH`                               |
| 없는 사업장·다른 OWNER·비 `ACTIVE`                      |  404 | `RESOURCE_NOT_FOUND`                          |
| 변환 판단 근거 이후 주소가 바뀜                         |  409 | `CONFLICT`                                    |
| 새 주소를 좌표로 확정할 수 없음                         |  422 | `WORKPLACE_ADDRESS_NOT_RESOLVABLE`            |
| 외부 변환 서비스 장애·응답 해석 실패                    |  503 | `WORKPLACE_GEOCODING_TEMPORARILY_UNAVAILABLE` |

## 완료 조건

- [ ] 좌표가 있는 사업장의 도로명주소를 바꾸면 `204`이고, 저장된 좌표가 새 주소 기준으로
      갱신된다.
- [ ] 주소 변경 중 좌표 변환이 `422`·`503`으로 실패하면 주소·좌표·상호·전화번호가 모두 변경
      전 값으로 유지된다.
- [ ] 도로명주소를 보내지 않거나 같은 값을 보낸 수정은 외부 변환을 호출하지 않고 좌표도 바꾸지
      않는다.
- [ ] 요청에 없는 필드는 바뀌지 않고, `detailAddress`에 `null`을 보내면 지워진다.
- [ ] `representativeName`, `latitude`, `radiusMeters` 등 미승인 필드를 보내면 `400
      VALIDATION_ERROR`이고 아무것도 바뀌지 않는다.
- [ ] 허용 필드가 하나도 없는 요청이 `400 VALIDATION_ERROR`다.
- [ ] 다른 OWNER의 사업장과 없는 사업장이 같은 `404 RESOURCE_NOT_FOUND`로 응답한다.
- [ ] 좌표가 확정된 사업장의 주소 변경이 더 이상 `409 WORKPLACE_LOCATION_LOCKED`로 거절되지
      않는다.
- [ ] `focused` 테스트가 성공 경로와 변환 실패 취소 경로를 모두 검증한다.
