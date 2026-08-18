---
patch_id: SPEC-168-01
status: draft
issue: 168
base_spec_version: 8.0.0
targets:
  - requirement: DASH-002
  - decision: DEC-OPEN-DASHBOARD-BREAK
  - operation: GET /api/worker/home
---

# SPEC-168-01: WORKER 홈 근무 경과 예상금액 — 비금융 참고 표시

## 추가 사항

`DASH-002`의 공식 시간 경과 확보 금액 계산은 계속 `Deferred`로 두고, Backend는 공식 확보
금액이나 시간대별 임금 계산값을 새로 제공하지 않는다.

Frontend WORKER 홈은 `GET /api/worker/home`이 반환하는 `dailyWage`와 오늘 근무의 예정
`startsAt`/`endsAt`만으로 파생한 단순 근무 경과 참고값을 표시한다. 이 값은 지갑 잔액,
예치금, 실제 지급액, 예상 실수령액(`expectedNetAmount`), 정산 금액을 변경하거나 결정하지
않으며, 휴게시간·실제 출퇴근 시각·지각·세금·공제까지 반영한 임금 계산으로 취급하지 않는다.
서버가 제공하는 `expectedNetAmount`는 경과 시간과 무관하게 고정된 별도 값으로 그대로
표시한다.

화면 문구는 `확보`, `실시간 적립`처럼 실제 자금 귀속으로 오해할 수 있는 표현을 쓰지 않고
`근무 경과 예상금액`, `참고값`처럼 참고용 추정치임이 드러나는 명칭과 안내를 사용한다. 안내
팝오버는 이 값이 지갑 잔액·예치금·실제 지급액과 무관하며 실제 정산 금액을 결정하지 않음을
명시한다.

오늘 근무 상태가 `NO_SHOW` 또는 `CANCELED`이면 지급을 기대할 수 없는 상태이므로 이 참고값을
노출하지 않는다. 다른 상태에서는 오늘의 근무 카드와 함께 계속 노출한다.

표시값은 60초마다 클라이언트에서 로컬로 재계산할 뿐 `GET /api/worker/home`을 다시 호출하지
않으며, 서버 상태를 변경하지 않는다(`SPEC-161-01`의 "확보 안심금액은 승인 기준값으로
계산하고 API를 매분 Polling하지 않는다" 완료 조건과 일치).

## 완료 조건

- [ ] WORKER 홈의 참고값 카드 제목·부제·안내 문구에 `확보`, `실시간 적립` 표현이 없고
      참고용 추정치임이 드러난다.
- [ ] 참고값은 `dailyWage`와 오늘 근무의 `startsAt`/`endsAt`(및 그로부터 파생한 표시용
      `workDate`/`startTime`/`endTime`)만으로 계산되고, 지갑·예치금·정산 관련 Store나 API를
      호출하지 않는다.
- [ ] 오늘 근무 상태가 `NO_SHOW` 또는 `CANCELED`일 때 참고값 카드가 렌더링되지 않고, 다른
      상태에서는 계속 렌더링된다.
- [ ] 시계가 흘러 참고값(표시용 경과 금액)이 갱신되는 동안에도 `earning.agreedWage`,
      `earning.expectedNetAmount`처럼 서버가 준 실제 금액 필드 값은 바뀌지 않는다.
- [ ] 참고값 갱신은 `GET /api/worker/home`을 다시 호출하지 않고 클라이언트 로컬 재계산만
      수행한다.
