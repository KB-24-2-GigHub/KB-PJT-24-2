# 명세 Patch 운영 가이드

`docs/spec-patches/`는 보호된 정식 명세를 기능 개발마다 직접 고치지 않고, 구현에 필요한
변경분만 짧게 기록하는 개발 계약 영역이다. Patch는 전체 명세를 다시 작성하지 않는다.

- **정식 SPEC**: Controller가 릴리스하고 `SPEC_LOCK.json`으로 잠근 `docs/specs/**`
- **draft Patch**: 승인된 통합 브랜치에서 해당 기능의 구현과 테스트에 사용하는 임시 개발 계약
- **accepted Patch**: 내용이 정식 SPEC에 반영되어 보관된 기록
- **Controller**: 정식 SPEC 반영과 Patch 수락을 담당하는 PM/Repository Admin

개발할 때는 정식 SPEC에 현재 작업과 직접 관련된 `draft` Patch만 더해 계약을 해석한다.
다른 기능의 Patch를 임의로 합성하거나 하나의 새 제품 계약처럼 추론하지 않는다. 같은 대상에서
정식 SPEC과 관련 `draft`가 다르면 `draft`에 명시된 변경분을 해당 기능의 승인된 통합 브랜치 구현에 적용한다.

일반 작업의 승인된 통합 브랜치는 `dev`다. 현재 Issue 또는 승인된 Parent 프로그램이 별도
통합 브랜치를 선언하면 그 원격 추적 브랜치를 사용하며, 충돌하거나 존재하지 않는 선언을
임의로 보정하지 않는다.

## 디렉터리

```text
docs/specs/                  # 정식 SPEC: PM/Admin 전용
docs/spec-patches/
  README.md                  # 이 운영 가이드
  TEMPLATE.md                # 경량 Patch 템플릿
  draft/                     # 승인된 통합 브랜치에서 사용하는 draft Patch
  archive/                   # 정식 SPEC에 반영된 accepted Patch
```

`README.md`와 `TEMPLATE.md`는 작성 지원 문서이며 Patch가 아니다.

## 파일명

기존 이력과 도구 호환을 위해 다음 형식을 유지한다.

```text
<github-id>_issue-<number>_<kebab-summary>_patch_v<revision>.md
```

예시:

```text
flamingo7562_issue-236_spec-patch-simplification_patch_v1.md
```

- GitHub ID와 요약은 영문 소문자, 숫자, 하이픈만 사용한다.
- 파일명의 Issue 번호와 `issue` 메타데이터는 같아야 한다.
- `draft`는 같은 파일에서 자유롭게 다듬는다.
- 이미 `accepted`된 내용의 후속 변경은 새 Patch ID로 작성한다.

## 최소 메타데이터

Patch는 아래 다섯 필드만 필수로 가진다.

| 필드                | 규칙                                                                                   |
| ------------------- | -------------------------------------------------------------------------------------- |
| `patch_id`          | 저장소에서 유일한 `SPEC-<issue>-<2자리 순번>` 형식이다.                                |
| `status`            | `draft` 또는 `accepted`만 사용한다.                                                    |
| `issue`             | Patch를 추적하는 양의 GitHub Issue 번호이다.                                           |
| `base_spec_version` | 작성 기준이 된 정식 SPEC SemVer이다. Commit SHA는 Git이 추적하므로 적지 않는다.        |
| `targets`           | 요구사항 ID, 결정 ID, REST Operation 등 변경 후에도 찾을 수 있는 계약 식별자 목록이다. |

작성자, 작성일, 기준 Commit, 전달 방식, 변경 유형, 의존성, 대체 관계와 PR 번호는 Git과
GitHub에서 확인한다. Patch에 중복해서 적지 않는다.

## 최소 본문

필수 본문은 두 섹션뿐이다.

### 추가 사항

구현에 필요한 제품 동작의 추가·변경분만 작성한다. 현재 명세 전체, 영향 없는 영역,
Frontend·Backend 구현 계획을 반복하지 않는다.

### 완료 조건

사용자가 관찰하거나 테스트로 검증할 수 있는 결과를 체크리스트로 작성한다.

API, 데이터·Migration, 보안, 화면 등 별도 설명이 실제로 필요한 경우에만 자유로운 선택 섹션을
추가한다. `영향 없음`을 채우기 위한 빈 섹션은 만들지 않는다.

`draft`는 실제 구현 기준이므로 Placeholder, `TODO`, `TBD`, `미정`처럼 구현을 막는 핵심
미결정 사항을 포함할 수 없다. 결정이 끝나지 않았다면 Patch를 승인된 통합 브랜치에 병합하지 않는다.

## 상태

허용 상태와 전이는 다음뿐이다.

```text
draft ── 정식 SPEC 반영 ──> accepted
```

| 상태       | 의미                                                     | 위치       | 수정 가능 여부 |
| ---------- | -------------------------------------------------------- | ---------- | -------------- |
| `draft`    | 승인된 통합 브랜치에서 해당 기능의 구현과 테스트에 사용하는 개발 계약 | `draft/`   | 가능           |
| `accepted` | 내용이 정식 SPEC에 반영된 보관 기록                      | `archive/` | 불가           |

`accepted`는 단순 리뷰 승인 상태가 아니다. 정식 SPEC 반영이 끝난 상태만 뜻한다. 기존의
`proposed`, `rejected`, `superseded`, `applied` 상태는 사용하지 않는다.

## 개발 흐름

1. 최신 정식 SPEC 버전을 확인한다.
2. `TEMPLATE.md`를 `draft/`에 올바른 파일명으로 복사한다.
3. 최소 메타데이터, 추가 사항과 완료 조건을 작성한다.
4. 기능 코드와 관련 `draft` Patch를 같은 PR로 승인된 통합 브랜치에 병합한다.
5. 개발 중 계약이 바뀌면 관련 코드·테스트와 같은 변경에서 `draft`를 수정한다.
6. 기능을 철회하면 구현과 `draft`를 함께 제거한다. 이력은 Git과 PR에 남는다.

Patch와 함께 Flyway Migration, DDL 또는 통합 Schema를 변경하지 않는다. 그런 변경은 사용자의
명시적인 관리자 승인 범위에서 별도 작업으로 처리한다. `draft`와 정식 SPEC 파일도 같은 기능
PR에서 함께 바꾸지 않는다.

## Controller 수락

Controller가 Patch를 정식 SPEC에 반영할 때 다음을 한 변경으로 처리한다.

1. 승인된 통합 브랜치의 최신 원격 추적 ref, `base_spec_version`, 대상이 겹치는 다른 `draft`를 확인한다.
2. Patch 변경분을 영향받는 정식 요구사항·API·결정·추적 문서에 편집 통합한다.
3. 정식 SPEC SemVer와 릴리스 기록을 갱신한다.
4. `SPEC_LOCK.json`을 갱신한다.
5. Patch 내용은 바꾸지 않고 `status: accepted`로 전환해 `archive/`로 이동한다.

부분 상태를 중간 Commit이나 Push로 공개하지 않는다. 수락 후 제품 계약의 정식 원본은 갱신된
`docs/specs/**`이며, 보관 Patch는 변경 이력을 찾기 위한 기록이다.

이미 `accepted`된 계약을 되돌리거나 다시 바꿀 때는 보관 문서를 수정하지 않는다. 새 Issue와
새 `draft` Patch를 만들고 동일한 흐름으로 정식 SPEC에 반영한다.

## 자동 검증

Guardrail은 문서 분량이나 모든 영향 영역을 강제하지 않는다. 다음 최소 정합성만 검사한다.

- 파일명, Issue 번호와 Patch ID 형식
- 다섯 필수 메타데이터와 두 필수 본문
- Placeholder와 중복 Patch ID·대상
- `draft/`와 `archive/`의 상태 일치
- 새 Patch가 `draft`로 시작하고 `accepted` 기록이 변경·삭제되지 않는지 여부
- `draft`와 구현 코드의 동반 허용, Migration·DDL·정식 SPEC 혼합 금지
- `accepted` 전환 시 정식 SPEC, 릴리스 버전과 Lock의 원자 갱신
- 승인·운영 릴리스에 `draft`가 남아 있지 않은지 여부

## 제출 전 점검

- [ ] Patch가 하나의 작고 독립적인 기능 변경만 설명한다.
- [ ] `patch_id`, `issue`, 파일명의 Issue 번호가 일치한다.
- [ ] 현재 정식 SPEC의 `base_spec_version`과 안정적인 `targets`를 적었다.
- [ ] 추가 사항과 완료 조건만으로 구현·검증할 수 있다.
- [ ] Placeholder나 구현을 막는 미결정 사항이 없다.
- [ ] 영향 없는 영역이나 Git·PR 메타데이터를 반복하지 않았다.
- [ ] 기능 PR의 Patch는 `draft`, 정식 SPEC에 반영된 Patch만 `accepted`이다.
