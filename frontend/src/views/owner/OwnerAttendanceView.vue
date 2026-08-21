<script setup>
/**
 * [C] 사장 근태관리  ·  /owner/attendance  ·  OWNER  (탭 화면)
 * 근태 현황(채용중·근무중) + 근무 리스트(검색·필터) + '근무 포지션 추가'(→ /owner/attendance/work-cases/new).
 * 지점 컨텍스트: useWorkplaceStore().selectedId 기준.
 * 연계 API: GET /workplaces/{id}/work-cases/summary · GET /workplaces/{id}/work-cases
 *          POST /work-cases/{id}/invitations (수락 전 항목의 연결 링크 발급·복사)
 *   →  @/services/workCases (getWorkCaseSummary, listWorkCases, createInvite)
 * 공통: StatusChip(근무 상태) · EmptyState · 항목 클릭 → /owner/attendance/work-cases/:workCaseId
 *
 * 보기 방식(목록형 ↔ 캘린더)
 *   - 두 뷰 모두 **같은 조회(listWorkCases)** 결과를 쓴다. 캘린더일 때만 보고 있는 달로
 *     from/to 를 좁혀 요청하고, 결과를 날짜별로 묶어 그린다(@/utils/calendar).
 *   - 적용한 필터는 뷰를 바꿔도 그대로 유지된다(같은 appliedFilter 를 공유).
 *     단 캘린더 뷰에서는 보고 있는 달이 필터의 기간을 덮는다(AttendanceFilterSheet 가 잠금).
 *   - 마지막으로 고른 뷰는 localStorage 에 남겨 재진입 시 복원한다(@/utils/storage).
 *   - 항목 클릭 이동 경로는 두 뷰가 동일하다(AttendanceWorkCaseList 를 공유).
 */
import { Plus, Search } from 'lucide-vue-next'
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import EmptyState from '@/components/common/EmptyState.vue'
import AttendanceCalendar from '@/components/owner/AttendanceCalendar.vue'
import AttendanceFilterSheet, {
  buildAttendanceFilterParams
} from '@/components/owner/AttendanceFilterSheet.vue'
import AttendanceViewToggle from '@/components/owner/AttendanceViewToggle.vue'
import AttendanceWorkCaseList from '@/components/owner/AttendanceWorkCaseList.vue'
import {
  WORK_CASE_SUMMARY,
  emptyWorkCaseSummary,
  workCaseStatusColor,
  workCaseStatusLabel
} from '@/constants/workCaseStatus'
import { createInvite, getWorkCaseSummary, listWorkCases } from '@/services/workCases'
import { useUiStore } from '@/stores/ui'
import { useWorkplaceStore } from '@/stores/workplace'
import {
  currentMonthKey,
  formatDateKeyWithWeekday,
  formatMonthLabel,
  monthRange
} from '@/utils/calendar'
import { copyText } from '@/utils/clipboard'
import { formatSeoulDateTime } from '@/utils/format'
import { readPreference, writePreference } from '@/utils/storage'

const router = useRouter()
const ui = useUiStore()
const workplaceStore = useWorkplaceStore()

const summary = ref(emptyWorkCaseSummary())
const workCases = ref([])
const loading = ref(false)
const loadingMore = ref(false)
const page = ref({ number: 0, size: 20, totalElements: 0, totalPages: 0 })
const hasNextPage = computed(() => page.value.number + 1 < page.value.totalPages)

/* ---- 검색·필터 -----------------------------------------------------------
 * 적용 중인 필터를 **서버 파라미터 형태 그대로** 들고 있다(사장 홈의 송금상세와 같은 방식).
 * 기본값은 빈 객체 — 비어 있는 항목은 애초에 키가 없다. 정렬은 서버가 정한다(WORK-002).
 * 요약 카드 토글과 시트의 '유형' 선택이 이 하나의 status 를 함께 쓴다. */
const appliedFilter = ref(buildAttendanceFilterParams())
const filterOpen = ref(false)

/* ---- 보기 방식(목록형 ↔ 캘린더) ------------------------------------------ */

// 저장 키. 다른 화면의 취향 값과 섞이지 않도록 화면 이름을 접두사로 둔다.
const VIEW_MODE_KEY = 'owner.attendance.viewMode'
const VIEW_MODES = ['list', 'calendar']

// 재진입 시 마지막 모드로 복원한다. 저장값이 이상하면(수동 편집 등) 목록형으로 되돌린다.
const savedViewMode = readPreference(VIEW_MODE_KEY)
const viewMode = ref(VIEW_MODES.includes(savedViewMode) ? savedViewMode : 'list')
const isCalendar = computed(() => viewMode.value === 'calendar')

const monthKey = ref(currentMonthKey()) // 캘린더가 보고 있는 달 'YYYY-MM'
const selectedDate = ref(null) // 캘린더에서 고른 날짜 'YYYY-MM-DD' | null

// 캘린더는 달 전체를 한 번에 보여줘야 하므로 목록형보다 큰 Page를 요청한다(승인 상한 100).
const LIST_PAGE_SIZE = 20
const CALENDAR_PAGE_SIZE = 100

/**
 * 선택 지점 기준으로 요약·리스트를 다시 조회한다.
 * 필터는 서버 파라미터로만 넘긴다 — 프론트에서 목록을 재계산하지 않는다.
 * 캘린더 뷰일 때만 보고 있는 달(from~to)로 조회 범위를 좁힌다.
 * 요약(채용중·근무중 건수)은 상태 필터와 무관한 전체 집계라 그대로 둔다.
 */
async function load() {
  const workplaceId = workplaceStore.selectedId
  if (workplaceId == null) return

  // 캘린더는 한 달치만 필요하다. 목록형은 기존대로 기간 제한 없이 최신순 전체를 본다.
  // range 를 뒤에 펼쳐 캘린더에서는 보고 있는 달이 필터의 from/to 를 덮는다
  // (그래서 시트도 캘린더에서는 기간 항목을 잠근다).
  const range = isCalendar.value ? monthRange(monthKey.value) : {}
  const size = isCalendar.value ? CALENDAR_PAGE_SIZE : LIST_PAGE_SIZE

  loading.value = true
  try {
    const [summaryRes, listRes] = await Promise.all([
      getWorkCaseSummary(workplaceId),
      listWorkCases(workplaceId, { ...appliedFilter.value, ...range, page: 0, size })
    ])
    summary.value = summaryRes
    workCases.value = listRes.content ?? []
    page.value = listRes.page ?? { number: 0, size, totalElements: 0, totalPages: 0 }
  } catch {
    ui.toast('근태 정보를 불러오지 못했어요.', { type: 'danger' })
  } finally {
    loading.value = false
  }
}

/** 목록형 뷰에서 다음 Page를 이어 붙인다. 캘린더는 한 번에 큰 Page를 받으므로 대상이 아니다. */
async function loadMore() {
  if (isCalendar.value || !hasNextPage.value || loadingMore.value) return
  const workplaceId = workplaceStore.selectedId
  if (workplaceId == null) return

  loadingMore.value = true
  try {
    const listRes = await listWorkCases(workplaceId, {
      ...appliedFilter.value,
      page: page.value.number + 1,
      size: LIST_PAGE_SIZE
    })
    workCases.value = [...workCases.value, ...(listRes.content ?? [])]
    page.value = listRes.page ?? page.value
  } catch {
    ui.toast('다음 목록을 불러오지 못했어요.', { type: 'danger' })
  } finally {
    loadingMore.value = false
  }
}

// 지점 목록이 준비되면 selectedId 가 채워지고, 그때 watcher 가 조회한다.
onMounted(() => workplaceStore.load())
watch(() => workplaceStore.selectedId, load, { immediate: true })

// 뷰를 바꾸면 조회 범위(달 제한 유무)가 달라지므로 다시 조회하고, 고른 뷰를 저장한다.
watch(viewMode, (mode) => {
  writePreference(VIEW_MODE_KEY, mode)
  load()
})

// 캘린더에서 달을 옮기면 그 달을 다시 조회한다.
watch(monthKey, load)

/** 시트에서 '적용'을 누르면 그 파라미터로 갈아끼우고 다시 조회한다. */
function onApplyFilter(params) {
  appliedFilter.value = params
  load()
}

/**
 * 요약 카드 토글 — 시트의 '유형'과 같은 status 를 건드린다.
 * 같은 상태를 다시 누르면 키를 지워 전체 보기로 돌아간다(status 없음 = 전체).
 */
function toggleStatus(status) {
  const next = { ...appliedFilter.value }
  if (next.status === status) delete next.status
  else next.status = status
  appliedFilter.value = next
  load()
}

// 빈 값·기본값은 애초에 키가 없으므로, 키가 하나라도 있으면 무언가 걸러진 상태다.
const isFiltered = computed(() => Object.keys(appliedFilter.value).length > 0)

// 상태 라벨·색은 상수 단일 소스만 사용(컴포넌트에 문자열 하드코딩 금지).
const statusLabel = (status) => workCaseStatusLabel(status)
const statusColor = (status) => workCaseStatusColor(status)

/* ---- 요약 7종 배치 -----------------------------------------------------
 * WORK_CASE_SUMMARY(단일 소스)의 순서는 상태 전이 순서(미배정→계약완료→근무예정→근무중
 * →확인필요→근무완료→노쇼)다. 화면에는 그 순서를 그대로 쓰지 않고 두 줄로 나눠
 * 배치한다 — 위 줄은 "진행 중" 4종(미배정·계약완료·근무예정·근무중), 아래 줄은
 * "완료·이탈" 3종(근무완료·노쇼·확인필요) + 전체 합계. 카운트를 나누는 배치만이고
 * 기간 필터·API 계약은 그대로 둔다(#412가 다루는 영역). */
const bucketByKey = (key) => WORK_CASE_SUMMARY.find((b) => b.key === key)
const summaryInProgress = computed(() =>
  ['draft', 'accepted', 'ready', 'inProgress'].map(bucketByKey)
)
const summaryPast = computed(() => ['completed', 'noShow', 'checkOutMissing'].map(bucketByKey))

/**
 * "전체" pill의 카운트 — 요약 카드 7종 합 + canceled(카드로는 안 보여주지만 서버 응답에
 * 별도 필드로 내려온다). "전체"를 누르면 status 필터를 아예 지워(toggleStatus) 취소 건도
 * 포함한 전체 목록을 보여주므로, 카운트도 취소를 포함해야 클릭 결과와 맞는다.
 */
const totalCount = computed(
  () =>
    WORK_CASE_SUMMARY.reduce((sum, bucket) => sum + (summary.value[bucket.key] ?? 0), 0) +
    (summary.value.canceled ?? 0)
)
// 상태뿐 아니라 검색어·기간까지 하나도 안 걸려 있어야 "전체"가 실제로 맞다.
const isAllActive = computed(() => !isFiltered.value)

const listTitle = computed(() =>
  appliedFilter.value.status ? `${statusLabel(appliedFilter.value.status)} 근무` : '근무 목록'
)

/**
 * 캘린더에서 고른 날짜의 근무 — 조회 결과를 그대로 거른 것이라 목록형과 같은 데이터다.
 * 하루 안에서는 시작 시간 순으로 보여준다(그날의 흐름대로 읽히게).
 */
const selectedDayWorkCases = computed(() => {
  if (!selectedDate.value) return []
  return workCases.value
    .filter((workCase) => workCase.workDate === selectedDate.value)
    .sort((a, b) => String(a.startsAt).localeCompare(String(b.startsAt)))
})

/** 선택한 날짜 제목: "2026.07.22 (수) · 2건" */
const selectedDayTitle = computed(() =>
  selectedDate.value
    ? `${formatDateKeyWithWeekday(selectedDate.value)} · ${selectedDayWorkCases.value.length}건`
    : ''
)

const monthLabel = computed(() => formatMonthLabel(monthKey.value))

const copyingId = ref(null) // 링크 생성 중인 근무(중복 클릭 방지)

/**
 * 매칭전 근무의 알바생 연결 링크를 만들어 클립보드에 복사한다.
 * 링크는 1회성·유효기간이며, 확정 후에는 서버가 생성을 막는다(docs/rules/api.md).
 *
 * 발급에 성공해도 canIssueInvitation 을 로컬에서 내리지 않는다 — 링크를 잘못 보냈거나
 * 복사에 실패한 경우 다시 발급할 수 있어야 한다. 발급 가능 여부의 권위는 서버이고,
 * 다음 조회(load)에서 갱신된 capability 로 버튼 노출이 결정된다.
 */
/** 초대 발급 실패를 승인 오류 Code 별로 구분해 안내한다. */
function inviteErrorMessage(err) {
  switch (err?.code) {
    case 'WORK_CASE_LOCKED':
      // 서버는 DRAFT·미매칭·시작 전 세 조건을 하나의 오류로 합친다(정보 노출 방지).
      return '이미 수락됐거나 시작 시각이 지난 근무는 링크를 발급할 수 없어요.'
    case 'CONFLICT':
      return '링크를 발급할 수 없는 상태예요. 목록을 새로고침한 뒤 다시 시도해주세요.'
    case 'ROLE_MISMATCH':
    case 'FORBIDDEN':
      return '이 근무의 링크를 발급할 권한이 없어요.'
    case 'RESOURCE_NOT_FOUND':
      return '근무를 찾을 수 없어요. 목록을 새로고침해주세요.'
    default:
      return '링크를 만들지 못했어요. 잠시 후 다시 시도해주세요.'
  }
}

async function onCopyInvite(workCaseId) {
  copyingId.value = workCaseId
  try {
    const { inviteUrl, expiresAt } = await createInvite(workCaseId)
    // 만료 시각은 근무 시작 시각이다 — 언제까지 유효한지 알려야 전송 시점을 판단할 수 있다.
    const expiryText = expiresAt ? ` ${formatSeoulDateTime(expiresAt)}까지 유효해요.` : ''

    if (await copyText(inviteUrl)) {
      ui.toast(`연결 링크를 복사했어요.${expiryText}`, { type: 'success', duration: 6000 })
    } else {
      // 브라우저가 복사를 막은 경우 — 링크를 띄워 직접 복사할 수 있게 한다.
      // Token 은 화면에만 노출하고 Console·Analytics 로는 내보내지 않는다.
      ui.toast(`복사가 막혔어요. 링크: ${inviteUrl}`, { type: 'warning', duration: 8000 })
    }
  } catch (err) {
    ui.toast(inviteErrorMessage(err), { type: 'danger' })
  } finally {
    copyingId.value = null
  }
}

const goDetail = (workCaseId) => router.push(`/owner/attendance/work-cases/${workCaseId}`)
const goNew = () => router.push('/owner/attendance/work-cases/new')
</script>

<template>
  <div class="attendance">
    <!--
      고정 영역 — 요약·검색·뷰 토글·목록 머리까지 화면 위에 붙어 있고, 그 아래(근무 목록 ·
      캘린더)만 스크롤한다. AppTopBar 바로 밑에 멈추도록 sticky 로 잡는다.
    -->
    <div class="sticky-head">
      <!--
        상태별 요약 7종 — pill을 누르면 해당 상태만, 다시 누르면 전체를 본다.
        위 줄(진행중·조치 필요 5종) / 아래 줄(지나간 기록 2종)로 나눠 배치한다 — 순서는
        WORK_CASE_SUMMARY(상태 전이 순서)를 그대로 따른다.
      -->
      <section class="summary">
        <div class="summary-row">
          <button
            v-for="bucket in summaryInProgress"
            :key="bucket.key"
            type="button"
            class="stat-pill"
            :class="{ active: appliedFilter.status === bucket.status }"
            :aria-pressed="appliedFilter.status === bucket.status"
            @click="toggleStatus(bucket.status)"
          >
            <span class="stat-label">{{ statusLabel(bucket.status) }}</span>
            <strong class="stat-value" :style="{ color: statusColor(bucket.status) }">
              {{ summary[bucket.key] ?? 0 }}
            </strong>
          </button>
        </div>
        <div class="summary-row summary-row--past">
          <button
            v-for="bucket in summaryPast"
            :key="bucket.key"
            type="button"
            class="stat-pill"
            :class="{ active: appliedFilter.status === bucket.status }"
            :aria-pressed="appliedFilter.status === bucket.status"
            @click="toggleStatus(bucket.status)"
          >
            <span class="stat-label">{{ statusLabel(bucket.status) }}</span>
            <strong class="stat-value" :style="{ color: statusColor(bucket.status) }">
              {{ summary[bucket.key] ?? 0 }}
            </strong>
          </button>
          <!--
            전체 합계 — 걸린 상태 필터를 지우고 전체를 본다(toggleStatus 가 이미 "같은 값이면
            해제"를 하므로 지금 걸린 status 를 그대로 넘기면 지워진다). 색을 다르게 둬 상태
            pill과 구분한다.
          -->
          <button
            type="button"
            class="stat-pill stat-pill--total"
            :class="{ active: isAllActive }"
            :aria-pressed="isAllActive"
            @click="toggleStatus(appliedFilter.status)"
          >
            <span class="stat-label">전체</span>
            <strong class="stat-value">{{ totalCount }}</strong>
          </button>
        </div>
      </section>

      <!-- 보기 방식 전환 — 필터는 그대로 두고 표시 방법만 바꾼다 -->
      <AttendanceViewToggle v-model="viewMode" />

      <!--
        목록 머리 — 제목 + '검색·필터' + '근무 포지션 추가'.
        두 뷰가 공유하고 로딩 중에도 남기려고 v-if 분기 밖에 둔다. 캘린더 뷰에는
        달력이 자체 월 헤더를 갖고 있어 제목을 겹쳐 쓰지 않고 버튼만 보여준다.
      -->
      <div class="list-header">
        <h2 v-if="!isCalendar" class="list-title">{{ listTitle }}</h2>
        <!-- 걸러진 상태를 색으로 표시한다 — 검색어·기간은 제목에 드러나지 않기 때문이다. -->
        <button
          type="button"
          class="filter-btn"
          :class="{ 'is-active': isFiltered }"
          @click="filterOpen = true"
        >
          <Search :size="14" />
          검색·필터
        </button>
        <button type="button" class="add-btn" @click="goNew">
          <Plus :size="14" />
          근무 포지션 추가
        </button>
      </div>
    </div>

    <p v-if="loading" class="loading">불러오는 중…</p>

    <!-- ① 목록형 뷰 -->
    <section v-else-if="!isCalendar" class="list-section">
      <EmptyState
        v-if="workCases.length === 0 && isFiltered"
        message="조건에 맞는 근무가 없습니다."
      >
        검색·필터 조건을 바꾸거나 위 카드를 다시 눌러 전체를 확인해보세요.
      </EmptyState>

      <EmptyState v-else-if="workCases.length === 0" message="등록된 근무가 없습니다.">
        위 버튼으로 첫 근무 포지션을 추가해보세요.
      </EmptyState>

      <AttendanceWorkCaseList
        v-else
        :work-cases="workCases"
        :copying-id="copyingId"
        @select="goDetail"
        @copy-invite="onCopyInvite"
      />

      <button
        v-if="!isCalendar && hasNextPage"
        type="button"
        class="load-more"
        :disabled="loadingMore"
        @click="loadMore"
      >
        {{ loadingMore ? '불러오는 중…' : '더 보기' }}
      </button>
    </section>

    <!-- ② 캘린더 뷰 — 월 그리드 + 선택한 날짜의 근무 목록 -->
    <section v-else class="calendar-section">
      <AttendanceCalendar
        v-model:month-key="monthKey"
        v-model:selected-date="selectedDate"
        :work-cases="workCases"
      />

      <!-- 그 달에 근무가 아예 없을 때(필터 때문일 수도 있어 문구를 나눈다) -->
      <EmptyState
        v-if="workCases.length === 0 && isFiltered"
        :message="`${monthLabel}에는 조건에 맞는 근무가 없습니다.`"
      >
        검색·필터 조건을 바꾸거나 위 카드를 다시 눌러 전체를 확인해보세요.
      </EmptyState>

      <EmptyState
        v-else-if="workCases.length === 0"
        :message="`${monthLabel}에는 등록된 근무가 없습니다.`"
      >
        좌우 화살표로 다른 달을 보거나, 위 버튼으로 근무를 추가해보세요.
      </EmptyState>

      <!-- 날짜를 아직 고르지 않은 상태 — 무엇을 하면 되는지 알려준다 -->
      <EmptyState v-else-if="!selectedDate" message="날짜를 선택해보세요.">
        근무가 있는 날에 색 점이 표시돼요. 날짜를 누르면 그날의 근무를 볼 수 있어요.
      </EmptyState>

      <!-- 고른 날짜의 근무 목록 — 항목 모양·이동 경로는 목록형과 동일하다 -->
      <div v-else class="day-section">
        <h2 class="list-title">{{ selectedDayTitle }}</h2>

        <EmptyState v-if="selectedDayWorkCases.length === 0" message="이 날짜에는 근무가 없습니다.">
          다른 날짜를 선택해보세요.
        </EmptyState>

        <AttendanceWorkCaseList
          v-else
          :work-cases="selectedDayWorkCases"
          :copying-id="copyingId"
          :show-date="false"
          @select="goDetail"
          @copy-invite="onCopyInvite"
        />
      </div>
    </section>

    <!--
      캘린더 뷰에서는 기간 항목을 잠근다 — 보고 있는 달이 조회 범위를 정하므로(load 의 range)
      기간을 따로 받으면 두 범위가 충돌한다.
    -->
    <AttendanceFilterSheet
      :open="filterOpen"
      :model-value="appliedFilter"
      :date-range-locked="isCalendar"
      @close="filterOpen = false"
      @apply="onApplyFilter"
    />
  </div>
</template>

<style scoped>
.attendance {
  display: flex;
  flex-direction: column;
  /* 자식은 .sticky-head 와 본문 하나뿐이다. 둘 사이 간격은 .sticky-head 의 padding-bottom 이
     맡는다 — gap 으로 띄우면 그 빈 띠가 투명해서 스크롤되는 내용이 비쳐 보인다. */
  gap: 0;
}

/* ---- 고정 영역(요약·검색·뷰 토글·목록 머리) ---- */
.sticky-head {
  /* AppTopBar 는 sticky top:0 이고 높이는 12px 패딩 + 28px 로고 + 12px 패딩 = 53px 이다.
     고정 영역은 그 바로 아래에 멈춰야 하므로 같은 값을 쓴다.
     이 요소의 스크롤 전 자연스러운 위치는 AppTopBar 높이(53px) + OwnerTabLayout
     .screen-body 의 상단 패딩(--space-lg, 16px) = 69px 이라 top:53px 과 16px 어긋난다.
     top 값만 69px 로 올리면 그 16px 구간에서는 아직 안 붙은 상태라 배경도 없어
     스크롤되는 목록이 AppTopBar 바로 밑으로 비쳐 보인다(이번에 겪은 문제). 그래서 top
     값 대신 이 요소 자체를 screen-body 상단 패딩만큼 위로 끌어올리고(margin-top 음수)
     그만큼을 padding-top 으로 안에서 되돌린다 — 좌우 full-bleed 와 같은 방식이다.
     이러면 스크롤 전 자연 위치가 정확히 53px 이 되어 top:53px 과 맞아떨어지고, 늘어난
     박스가 불투명 배경까지 그 구간을 덮어 비쳐 보이는 문제도 함께 없어진다.
     ※ AppTopBar 높이나 screen-body 상단 패딩을 바꾸면 이 값도 함께 고쳐야 한다. */
  position: sticky;
  top: 53px;
  /* AppTopBar(.topbar)도 같은 --z-tabbar 를 쓰는 sticky 라 여기서 그대로 쓰면 DOM
     순서상 이 영역이 나중에 그려져 AppTopBar 위에 덮인다 — 스크롤 중 재계산 시 경계가
     맞물리면서 AppTopBar 하단 회색 구분선이 가려져 사라져 보였다(원인). 1만큼 낮춰 항상
     AppTopBar 아래에 머물게 한다(그래도 일반 흐름인 목록 콘텐츠보다는 위다). */
  z-index: calc(var(--z-tabbar) - 1);

  display: flex;
  flex-direction: column;
  gap: var(--space-lg);

  /* full-bleed — screen-body 의 상하좌우 패딩만큼 밖으로 빼고 안에서 되돌린다. 좌우는
     그러지 않으면 스크롤되는 내용이 양옆 16px 여백으로 비쳐 보이기 때문이고, 위쪽은
     위 주석의 sticky top 정렬 때문이다(아래는 그대로 padding-bottom 만 준다). */
  margin: calc(-1 * var(--space-lg)) calc(-1 * var(--space-lg)) 0;
  padding: var(--space-lg);
  /* 스크롤되는 내용이 뒤로 비치지 않게 불투명 배경을 깐다.
     색은 .app 컨테이너와 같은 --color-surface — --color-bg(회색)를 쓰면 이 영역만 띠로 보인다. */
  background: var(--color-surface);
  /* 고정 영역(근무 목록 제목까지)과 그 아래 스크롤되는 목록 사이 경계선. */
  border-bottom: 1px solid var(--color-border);
}

/* ---- 근태 현황 요약(8종, 압축된 pill 2줄) ----
 * 이전엔 카드 7장이 그리드 두 줄을 꽉 채워 세로 공간을 많이 차지했다. 값(count)만
 * 확인하면 되는 요약이라 라벨+숫자를 한 pill에 묶어 가로로 늘어놓는다.
 * - 위 줄: 진행 중 4종(미배정·계약완료·근무예정·근무중).
 * - 아래 줄: 완료·이탈 3종(근무완료·노쇼·확인필요) + 맨 끝에 전체 합계 pill.
 * 상태 pill 배경은 안심지갑 잔액 카드(WalletBalanceCard)와 같은 옅은 하늘색
 * (--color-owner-weak)을 써 통일감을 주고, "전체" pill만 진한 파랑(--color-owner)
 * 배경 + 흰 글자로 다르게 둬 다른 pill과 구분한다.
 *
 * 각 줄은 정확히 4개씩이라 flex-wrap 대신 grid-template-columns: repeat(4, 1fr)로 4칸을
 * 고정한다 — 폭이 좁아져도 다음 줄로 밀리지 않고(#476) 칸 자체가 좁아지며, 폭이 넓어지면
 * 4칸이 줄 전체를 나눠 가져 오른쪽에 빈 공간이 남지 않는다(가운데 정렬 효과를 겸함).
 * min-width는 더 이상 기준값이 아니다 — 칸이 좁아질 때 내용이 넘치지 않도록 pill에
 * min-width: 0을 주고, 가장 긴 라벨(확인필요)도 word-break: keep-all로 필요하면
 * 공백 단위로만 줄바꿈되게 한다(가로 넘침 대신 pill 높이가 늘어난다). */
.summary {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}
.summary-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--space-xs);
}
.stat-pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-width: 0;
  padding: 6px var(--space-sm);
  background: var(--color-owner-weak);
  /* 2px 고정 — active 시에만 색을 바꾸면 폭이 그대로라 선택 시 레이아웃이 밀리지 않는다. */
  border: 2px solid transparent;
  border-radius: var(--radius-pill);
}
/* 선택된 상태 pill — 옅은 배경 위 1px 테두리는 잘 안 보여 2px로 굵게 해 눈에 띄게 한다. */
.stat-pill.active {
  border-color: var(--color-owner);
}
/* 전체 합계 pill — 상태 pill들과 헷갈리지 않게 진한 파랑 + 흰 글자로 다르게 둔다. */
.stat-pill--total {
  background: var(--color-owner);
}
.stat-pill--total .stat-label,
.stat-pill--total .stat-value {
  color: var(--color-surface);
}
/* 전체 선택 시 테두리 — 배경이 이미 진한 파랑이라 연한 파랑(--color-owner-weak) 테두리로 띄운다. */
.stat-pill--total.active {
  border-color: var(--color-owner-weak);
}
.stat-label {
  font-size: var(--text-sm);
  color: var(--color-text);
  /* "확인필요"이 음절 단위로 잘려 줄바꿈되지 않게 공백 단위로만 줄바꿈한다. */
  word-break: keep-all;
}
/* 값 색은 상태색(상수)으로 인라인 바인딩한다 */
.stat-value {
  font-size: var(--text-sm);
  font-weight: var(--weight-bold);
}

/* ---- 근무 리스트 ---- */
/* 제목 + 추가 버튼. 캘린더 뷰에는 제목이 없으므로 버튼을 margin-left 로 오른쪽에 붙인다
   (space-between 은 제목이 없을 때 버튼이 왼쪽으로 붙는다). */
.list-header {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}
/* margin·line-height 를 h2 기본값에 맡기지 않고 명시한다.
   - margin: 0. 값이 남으면 align-items:center 가 margin 포함 박스를 기준으로 잡아
     제목이 추가 버튼보다 위로 올라간다.
   - line-height: 1.5. 16px 에 19.2px 이하의 라인박스는 한글 어센더가 잘려 보인다. */
.list-title {
  margin: 0;
  font-size: var(--text-lg);
  line-height: 1.5;
  font-weight: var(--weight-bold);
  color: var(--color-text);
}
/* 검색·필터 — 항상 하늘색 배경(안심지갑 카드와 같은 --color-owner-weak), 테두리 없음. */
.filter-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--space-xs);
  margin-left: auto;
  padding: var(--space-xs) var(--space-md);
  border: none;
  border-radius: var(--radius-pill);
  background: var(--color-owner-weak);
  font-size: var(--text-sm);
  color: var(--color-owner);
}
/* 걸러진 상태 — 검색어·기간은 제목에 안 드러나므로 배경·글자색을 반전해 알린다(add-btn과
   같은 진한 파랑 방식). 글자 굵기만으로는 옅은 배경 위에서 거의 안 보였다. */
.filter-btn.is-active {
  background: var(--color-owner);
  color: var(--color-surface);
  font-weight: var(--weight-medium);
}
.add-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--space-xs);
  padding: var(--space-xs) var(--space-sm);
  border: 1px solid var(--color-owner);
  border-radius: var(--radius-sm);
  background: var(--color-owner);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  color: var(--color-surface);
}
.loading {
  padding: var(--space-xl) 0;
  text-align: center;
  font-size: var(--text-md);
  color: var(--color-text-sub);
}

.load-more {
  width: 100%;
  margin-top: var(--space-md);
  padding: var(--space-sm);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
.load-more:disabled {
  opacity: 0.6;
}

/* ---- 캘린더 뷰(달력 + 선택한 날짜 목록) ---- */
.calendar-section {
  display: flex;
  flex-direction: column;
  gap: var(--space-lg);
}
</style>
