## 관련 이슈와 통합

- Refs #이슈번호
- Parent: #이슈번호 또는 `N/A`
- Native blocked-by: #이슈번호 목록 또는 `N/A`
- Target integration branch: `dev`, 승인된 프로그램 브랜치 또는 `main`
- Risk: `R0` | `R1` | `R2` | `R3`

<!-- 이슈의 목표·AC·비범위를 복사하지 않습니다. 실제 Parent, native dependency와 대상 브랜치를 다시 확인합니다. -->

## 실제 Diff

- 실제로 바뀐 코드·설정·문서와 사용자/운영 영향을 적습니다.

## 계약 대비 차이

- Spec Patch / Operation / Architecture 결정 또는 `N/A` 근거:
- 목표 계약과 현재 구현 상태를 혼동하지 않았는지:

## 검증 결과

- 실행한 명령과 성공·실패 결과:
- 생략한 필수 검증과 근거:

## 잔여 위험

- 남은 기능 공백, blocker, 운영·호환 위험 또는 `N/A`:

## 리뷰와 Migration

- 필요한 역할 검토: 제품 명세 / Backend / Frontend / DB / 보안·금액 / 통합 / `N/A`
- Migration scope: 승인 이슈·table·invariant·신규 Flyway 또는 `N/A`
- DB 변경 시: 기존 Migration 불변, 통합 DDL·Mapper·Service·테스트 동시 검토, disposable/local DB 검증, 공유·Staging·Production 미적용 확인

<!-- R3 보안·금액·Schema 변경은 명시된 전문 검토가 필요합니다. Patch나 기능 요구 자체는 Migration 권한이 아닙니다. -->

## 종료 상태

- 작업 PR: `Refs`와 Development 연결을 사용합니다.
- 프로그램 서브 이슈: 승인 통합 브랜치(예: `dev2`) 병합과 AC 확인 후 수동 종료합니다.
- Parent/Milestone: 최종 프로그램 브랜치 → `dev` 통합 감사 후 종료합니다.
- `main` 반영은 별도 Release 사실이며 위 통합 상태와 같은 의미가 아닙니다.

## 체크리스트

- [ ] 실제 Diff, 계약 차이, 검증 결과와 잔여 위험을 기록했습니다.
- [ ] PR 대상이 이슈 또는 Parent가 승인한 실제 통합 브랜치입니다.
- [ ] 관련 이슈를 PR의 Development 항목에 연결했습니다.
- [ ] 보호 명세·Migration·DDL 변경은 현재 이슈에 대한 명시적 PM/Admin 승인 범위 안입니다.
- [ ] 신규 Architecture 위반과 production hardcoded Mock을 추가하지 않았습니다.
- [ ] 실패하거나 생략한 필수 검증을 성공으로 기록하지 않았습니다.
- [ ] Project 상태를 현재 단계에 맞게 갱신했습니다.
