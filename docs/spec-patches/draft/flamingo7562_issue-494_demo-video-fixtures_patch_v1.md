---
patch_id: SPEC-494-01
status: draft
issue: 494
base_spec_version: 9.0.0
targets:
  - requirement: ATT-003
  - decision: DEC-WORKPLACE-RADIUS
  - decision: DEC-ATTENDANCE-LOCATION-AUDIT
  - operation: POST /api/attendance/scans
---

# SPEC-494-01: 시연 전용 좌표 인증 반경

## 추가 사항

로컬·폐기 가능한 시연 영상 환경에 명시적으로 적용하는 Demo Seed는 사업장 인증 반경을
`999999m`로 저장할 수 있다. 근태 스캔은 잠근 현재 활성 사업장의 저장 반경을 사용한다.

일반 사업장 등록과 Demo Seed를 적용하지 않은 환경의 반경은 기존 `100m`를 유지한다. 이
예외를 공유·Staging·Production 데이터에 적용하지 않는다.

## 완료 조건

- [ ] 일반 사업장과 반경 `100m`인 기존 데이터는 반올림 전 거리가 `100m`를 초과하면 거절된다.
- [ ] 시연 Seed 사업장은 반경 `999999m`를 저장하고 그 범위 안의 스캔을 허용한다.
- [ ] 시연 반경 완화를 위해 Flyway Migration, DDL 또는 정식 보호 명세를 변경하지 않는다.
