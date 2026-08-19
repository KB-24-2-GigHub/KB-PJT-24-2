# 통합 시연 SEED

`test-contract-escrow.sql`, `test-invitation-accept.sql`은 기존 고정 Fixture입니다.
`demo-*.sql`은 실행할 때마다 Flyway Schema는 보존하고 **모든 애플리케이션 데이터와
문서 저장소를 지운 뒤**, 실행 시점의 Asia/Seoul 날짜·시각을 기준으로 데이터를 다시 만듭니다.

## 로컬 추천 명령

Backend가 같은 로컬 Compose DB를 사용하도록 실행한 상태에서 저장소 루트에서 실행합니다.

```powershell
npm run db:seed:demo -- functional confirm-local reset-all-data
npm run db:seed:demo -- video-01-onboarding confirm-local reset-all-data
npm run db:seed:demo -- video-02-check-in confirm-local reset-all-data
npm run db:seed:demo -- video-03-check-out confirm-local reset-all-data
npm run db:seed:demo -- video-04-three-years confirm-local reset-all-data
```

npm 11은 `--confirm-local` 같은 알 수 없는 긴 옵션을 npm 설정으로 가로채므로 npm Script에서는
위 위치 확인값을 사용합니다. `node scripts/prepare-demo-seed.js ...`로 직접 실행할 때는 기존
`--confirm-local --confirm-reset-all-data` 형식도 지원합니다.

- `functional`: 기능 통합 점검. SQL 뒤 실제 API로 김성실의 초대 수락·계약서 생성과 보건증
  업로드/공유까지 수행하고, 이수면의 미수락 초대 URL을 표준출력에만 보여 줍니다.
- `video-01-onboarding`: 사업장이 없는 사장님 첫 로그인과 지갑 충전/출금 출발점입니다.
- `video-02-check-in`: 김성실 정상 출근, 이수면 30분 지각 출근, 박잠수 자동 노쇼 출발점입니다.
  앱 실행 후 다음 60초 Scheduler 주기에서 박잠수가 실제 `NO_SHOW`로 바뀝니다.
- `video-03-check-out`: 김성실·이수면은 퇴근 가능한 `IN_PROGRESS`, 박잠수는 `NO_SHOW`입니다.
- `video-04-three-years`: 3개 사업장, 누적 근무, 3/2/1 근로자 배지, 오늘 3호점 일정,
  3년 보존기간이 지난 파기 계약을 만듭니다.

모든 시나리오는 독립적이며 앞 시나리오의 데이터를 이어받지 않습니다. 이수면의 30분 지각
기록은 포함하지만 실제 임금 비례 차감·부분 지급·차액 환불은 GitHub 이슈 #424 구현 후
활성화합니다.

## 계정과 고정 위치

| 역할 | 이름 | 아이디 | 비밀번호 |
|---|---|---|---|
| 사장님 | 긱사장 | `gigsajang` | `Demo1234!` |
| A | 김성실 | `hardworker` | `Demo1234!` |
| B | 이수면 | `ilovesleep` | `Demo1234!` |
| C | 박잠수 | `submarine` | `Demo1234!` |

1호점 위치는 `서울 광진구 능동로 195-16`, 위도 `37.5481384`, 경도
`127.0733972`, 출석 반경 `100m`입니다.

## 운영 RDS 수동 실행

GitHub Actions의 `Seed DB` 수동 워크플로에서 다음 값을 사용합니다.

- `file`: 적용할 `demo-*.sql` 파일명
- `confirm`: `reset-all-data`

워크플로는 앱을 정지하고 운영 문서 저장소와 RDS 애플리케이션 데이터를 모두 초기화한 뒤
앱을 다시 기동합니다. `demo-functional.sql`은 기동 확인 후 공개 API로 하이브리드 준비까지
자동 수행합니다. 기존 `test-*.sql`은 종전처럼 `confirm=seed`를 사용합니다.
