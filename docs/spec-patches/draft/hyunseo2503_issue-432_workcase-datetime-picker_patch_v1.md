---
patch_id: SPEC-432-02
status: draft
issue: 432
base_spec_version: 8.1.0
targets:
  - requirement: WORK-003
---

# SPEC-432-02: 근무 등록·수정 화면의 날짜·시각 입력을 다이얼/캘린더 방식으로 변경

## 추가 사항

OWNER가 근무 포지션을 등록·수정하는 화면(`POST /api/workplaces/{id}/work-cases`,
`PATCH /api/work-cases/{id}`의 Frontend 입력 폼)에서 `workDate`·`startTime`·`endTime`
값을 입력하는 방식을 네이티브 date/time input에서 커스텀 바텀시트 피커로 바꾼다.
서버로 보내는 값의 형식·의미(`workDate` "YYYY-MM-DD",
`startTime`/`endTime` "HH:mm")와 WORK-003의 검증 규칙(필수값·근무 길이 상한 등)은
바꾸지 않는다 — 입력 UI만 바뀐다.

- `startTime`·`endTime`: 오전/오후·시·분 세 컬럼 휠 다이얼. 분은 10분 단위로만 선택할
  수 있다(1분 단위 미지원). Android Chrome이 네이티브 time input의 `step` 속성을
  무시해 분 단위 조정이 매우 번거로웠던 것을 대체한다.
- `workDate`: 월 그리드 캘린더(요일 헤더 + 날짜 셀). 지난달·다음달 셀은 비활성화되어
  헤더의 이전/다음 버튼으로 달을 넘겨야 선택할 수 있다.
- 기존에 10분 단위가 아닌 값(과거 데이터·다른 경로로 등록된 값)을 수정 화면에서 열면
  가장 가까운 10분 단위로 반올림해 표시하고, 그 반올림된 값이 실제 저장값과 항상
  일치한다(사용자가 시간 필드를 건드리지 않아도 폼 값 자체가 10분 단위로 정규화된다).

## 완료 조건

- [x] 근무 등록·수정 화면에서 `startTime`/`endTime`은 10분 단위로만 선택할 수 있다.
- [x] 근무 등록·수정 화면에서 `workDate`는 월 그리드 캘린더로 선택하며, 지난달/다음달
      날짜는 헤더의 이전/다음 버튼으로 이동해야 선택할 수 있다.
- [x] 저장되는 `workDate`/`startTime`/`endTime`의 형식과 WORK-003 검증 규칙은
      변경 전과 동일하다(형식·필수값·근무 길이 상한).
- [x] 10분 단위가 아닌 기존 값을 수정 화면에서 열면 화면에 보이는 값과 저장되는 값이
      항상 일치한다.
