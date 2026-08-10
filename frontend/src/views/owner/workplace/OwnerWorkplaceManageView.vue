<script setup>
/**
 * [D] 사업장 관리  ·  /owner/mypage/workplaces  ·  OWNER
 * 사업장 목록·추가(+)·수정·삭제. 진행 중 근무 있는 지점 삭제 제한(409).
 * 연계 API: GET/POST /workplaces · PATCH/DELETE /workplaces/{id}
 *   →  @/services/workplaces (list, create, update, remove)
 * 진행 중 근무 건수: GET /workplaces/{id}/work-cases/summary → @/services/workCases (getWorkCaseSummary)
 * 변경 후 useWorkplaceStore().load({force:true}) 로 네비 select 갱신.
 *
 * 실시간 검증(#238): 수정 다이얼로그는 중복확인이 없어 errors 슬롯 하나로 충분하다.
 * 다이얼로그를 다시 열 때(openEdit)는 reset() 으로 이전 세션의 touched·errors 를 지운다 —
 * 지우지 않으면 다른 사업장을 열었을 때 이전 오류가 그대로 남아 보인다.
 */
import { Building2 } from 'lucide-vue-next'
import { storeToRefs } from 'pinia'
import { nextTick, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import AppBackHeader from '@/components/common/AppBackHeader.vue'
import AppField from '@/components/common/AppField.vue'
import BaseButton from '@/components/common/BaseButton.vue'
import BaseModal from '@/components/common/BaseModal.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import { useFieldValidation } from '@/composables/useFieldValidation'
import { getWorkCaseSummary } from '@/services/workCases'
import { deleteWorkplace, updateWorkplace } from '@/services/workplaces'
import { useUiStore } from '@/stores/ui'
import { useWorkplaceStore } from '@/stores/workplace'
import { embedAddressSearch } from '@/utils/daumPostcode'
import { blockNonDigitKeydown, formatPhoneInput } from '@/utils/format'
import { isPhone, isRequired } from '@/utils/validators'

const router = useRouter()
const ui = useUiStore()
const workplaceStore = useWorkplaceStore()
const { workplaces } = storeToRefs(workplaceStore)

// 지점별 "진행 중 근무" 건수 — workplaceId 를 키로 하는 로컬 캐시.
const workingCounts = reactive({})

const editOpen = ref(false)
const editTarget = ref(null)
const editName = ref('')
const editRoadAddress = ref('')
const editDetailAddress = ref('')
const editDetailOpen = ref(false)
const editPhone = ref('')
const saving = ref(false)

// 형식 오류 전용. 규칙은 기존 confirmEdit() 이 쓰던 것을 그대로 옮겼다 — 규칙 자체는 불변.
const { errors, handleBlur, validateAll, reset } = useFieldValidation(
  () => ({ name: editName.value, roadAddress: editRoadAddress.value, phone: editPhone.value }),
  {
    name: (v) => isRequired(v.name, '상호명'),
    roadAddress: (v) => isRequired(v.roadAddress, '사업장 주소'),
    phone: (v) => isPhone(v.phone, { required: true })
  }
)

const deleteOpen = ref(false)
const deleteTarget = ref(null)
const deleteError = ref('')
const deleting = ref(false)

onMounted(async () => {
  await workplaceStore.load()
  await Promise.all(
    workplaces.value.map(async (w) => {
      const summary = await getWorkCaseSummary(w.workplaceId)
      workingCounts[w.workplaceId] = summary.inProgress
    })
  )
})

function goCreate() {
  router.push('/owner/workplaces/new')
}

function openEdit(workplace) {
  // 값을 채우기 전에 이전 세션의 touched·errors 를 먼저 지운다. 값 대입 뒤에 reset() 을
  // 부르면 지금은 우연히 맞지만(재검증 watcher 가 비동기로 flush 되어 reset() 이 먼저
  // 실행됨), 그 순서가 정확성의 조건이 되어서는 안 된다(#238 Finding 4).
  reset()
  editTarget.value = workplace
  editName.value = workplace.name
  editRoadAddress.value = workplace.roadAddress
  editDetailAddress.value = workplace.detailAddress ?? ''
  editDetailOpen.value = Boolean(workplace.detailAddress)
  editPhone.value = workplace.phone ? formatPhoneInput(workplace.phone) : ''
  editOpen.value = true
}

function onEditPhoneInput(v) {
  editPhone.value = formatPhoneInput(v)
}

const addressSearchOpen = ref(false)
const addressSearchContainer = ref(null)

async function searchEditAddress() {
  addressSearchOpen.value = true
  await nextTick()
  embedAddressSearch(
    addressSearchContainer.value,
    (result) => {
      // 도로명이 통째로 바뀌므로 이전 세부주소는 버리고 입력란을 연다.
      // editRoadAddress.value 변경은 useFieldValidation 의 watcher 가(떠났던 필드라면)
      // 자동으로 재검증해 오류를 지운다 — 여기서 따로 지울 필요가 없다.
      editRoadAddress.value = result.address
      editDetailAddress.value = ''
      editDetailOpen.value = true
      addressSearchOpen.value = false
    },
    () => {
      addressSearchOpen.value = false
      ui.toast('주소 검색을 불러오지 못했어요. 직접 입력해주세요.', { type: 'danger' })
    }
  )
}

async function confirmEdit() {
  if (!validateAll()) return

  saving.value = true
  try {
    // 승인 수정 허용 필드는 name, roadAddress, detailAddress, phone 뿐이다.
    await updateWorkplace(editTarget.value.workplaceId, {
      name: editName.value,
      roadAddress: editRoadAddress.value,
      detailAddress: editDetailAddress.value,
      phone: editPhone.value
    })
    // 목록 갱신은 편의 동작이다. 수정 자체는 이미 성공했으므로 갱신 실패를 수정 실패로
    // 전파하지 않는다(OwnerWorkplaceNewView 와 동일한 처리).
    try {
      await workplaceStore.load({ force: true })
    } catch {
      // 다음 진입에서 목록을 다시 읽는다.
    }
    editOpen.value = false
    ui.toast('사업장 정보를 수정했어요.', { type: 'success' })
  } catch (err) {
    const unavailable = err?.code === 'FEATURE_UNAVAILABLE'
    ui.toast(
      unavailable
        ? '사업장 수정은 현재 준비 중인 기능입니다.'
        : err?.response?.data?.message || '수정에 실패했어요.',
      { type: unavailable ? 'info' : 'danger' }
    )
  } finally {
    saving.value = false
  }
}

function openDelete(workplace) {
  if (workingCounts[workplace.workplaceId] > 0) {
    ui.toast('진행 중인 근무가 있는 지점은 삭제할 수 없어요.', { type: 'warning' })
    return
  }
  deleteTarget.value = workplace
  deleteError.value = ''
  deleteOpen.value = true
}

async function confirmDelete() {
  deleting.value = true
  deleteError.value = ''
  try {
    await deleteWorkplace(deleteTarget.value.workplaceId)
    // 목록 갱신은 편의 동작이다. 삭제 자체는 이미 성공했으므로 갱신 실패를 삭제 실패로
    // 전파하지 않는다(OwnerWorkplaceNewView 와 동일한 처리).
    try {
      await workplaceStore.load({ force: true })
    } catch {
      // 다음 진입에서 목록을 다시 읽는다.
    }
    deleteOpen.value = false
    ui.toast('사업장을 삭제했어요.', { type: 'success' })
  } catch (err) {
    if (err?.code === 'FEATURE_UNAVAILABLE') {
      deleteError.value = '사업장 삭제는 현재 준비 중인 기능입니다.'
      ui.toast(deleteError.value, { type: 'info' })
      return
    }
    // 진행 중 근무가 있으면 서버가 409 — 메시지를 그대로 노출한다.
    deleteError.value = err?.response?.data?.message || '삭제할 수 없어요.'
  } finally {
    deleting.value = false
  }
}
</script>

<template>
  <div class="sub-page">
    <AppBackHeader title="사업장 관리" />
    <main class="screen-body">
      <div class="header-row">
        <p class="count">내 사업장 {{ workplaces.length }}곳</p>
        <button type="button" class="add-btn" @click="goCreate">+ 사업장 추가</button>
      </div>

      <EmptyState v-if="workplaces.length === 0" message="등록된 사업장이 없어요.">
        <template #icon><Building2 :size="32" /></template>
      </EmptyState>

      <ul v-else class="workplace-list">
        <li v-for="w in workplaces" :key="w.workplaceId" class="workplace-card">
          <div class="wp-info">
            <p class="wp-name">{{ w.name }}</p>
            <p class="wp-address">
              {{ [w.roadAddress, w.detailAddress].filter(Boolean).join(' ') }}
            </p>
            <p class="wp-sub">
              진행 중 근무 {{ workingCounts[w.workplaceId] ?? 0 }}건 · 인증 반경
              {{ w.radiusMeters ?? '-' }}m
            </p>
          </div>
          <div class="wp-actions">
            <button type="button" class="action-btn" @click="openEdit(w)">수정</button>
            <button
              type="button"
              class="action-btn action-btn--danger"
              :disabled="workingCounts[w.workplaceId] > 0"
              @click="openDelete(w)"
            >
              {{ workingCounts[w.workplaceId] > 0 ? '삭제 불가' : '삭제' }}
            </button>
          </div>
        </li>
      </ul>

      <p class="notice">진행 중 근무가 있는 지점은 삭제할 수 없습니다 (소프트 삭제)</p>
    </main>

    <BaseModal :open="editOpen" title="사업장 수정" @close="editOpen = false">
      <div class="edit-form">
        <!-- maxlength 는 WorkplaceCreateRequest 의 @Size(max=...) 를 그대로 반영한다 — 임의로 바꾸지 말 것 -->
        <AppField
          v-model="editName"
          label="상호명"
          required
          maxlength="120"
          :error="errors.name"
          @blur="handleBlur('name')"
        />
        <AppField
          v-model="editRoadAddress"
          :label="editDetailOpen ? '사업장 주소 (도로명)' : '사업장 주소'"
          required
          maxlength="255"
          :error="errors.roadAddress"
          @blur="handleBlur('roadAddress')"
        >
          <template #suffix>
            <BaseButton type="button" variant="secondary" @click="searchEditAddress"
              >검색</BaseButton
            >
          </template>
        </AppField>
        <AppField
          v-if="editDetailOpen"
          v-model="editDetailAddress"
          label="세부 주소"
          placeholder="건물명·동·호수 등을 입력하세요"
          maxlength="100"
        />
        <AppField
          :model-value="editPhone"
          label="사업장 전화번호 (지역번호 포함 9~11자리)"
          type="tel"
          digits-only
          required
          maxlength="13"
          :error="errors.phone"
          @keydown="blockNonDigitKeydown"
          @update:model-value="onEditPhoneInput"
          @blur="handleBlur('phone')"
        />
      </div>
      <template #footer>
        <BaseButton variant="secondary" block :disabled="saving" @click="editOpen = false">
          취소
        </BaseButton>
        <BaseButton variant="owner" block :disabled="saving" @click="confirmEdit">저장</BaseButton>
      </template>
    </BaseModal>

    <BaseModal :open="deleteOpen" title="사업장 삭제" @close="deleteOpen = false">
      <p class="delete-desc">
        '{{ deleteTarget?.name }}'을(를) 삭제하면 되돌릴 수 없습니다. 정말 삭제하시겠어요?
      </p>
      <p v-if="deleteError" class="delete-error">{{ deleteError }}</p>
      <template #footer>
        <BaseButton variant="secondary" block :disabled="deleting" @click="deleteOpen = false">
          취소
        </BaseButton>
        <BaseButton variant="danger" block :disabled="deleting" @click="confirmDelete">
          삭제하기
        </BaseButton>
      </template>
    </BaseModal>

    <BaseModal :open="addressSearchOpen" title="주소 검색" @close="addressSearchOpen = false">
      <div ref="addressSearchContainer" class="postcode-embed"></div>
    </BaseModal>
  </div>
</template>

<style scoped>
.screen-body {
  padding: var(--space-lg);
}

.header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-lg);
}
.count {
  font-size: var(--text-lg);
  font-weight: var(--weight-bold);
  color: var(--color-text);
}
.add-btn {
  padding: var(--space-xs) var(--space-sm);
  border: 1px solid var(--color-owner);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  color: var(--color-owner);
}

.workplace-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}
.workplace-card {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-sm);
  padding: var(--space-md);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}
.wp-info {
  min-width: 0;
}
.wp-name {
  font-size: var(--text-md);
  font-weight: var(--weight-bold);
  color: var(--color-text);
}
.wp-address {
  margin-top: 2px;
  overflow: hidden;
  font-size: var(--text-sm);
  color: var(--color-text-sub);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.wp-sub {
  margin-top: var(--space-xs);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
  word-break: keep-all;
}

.wp-actions {
  display: flex;
  flex-shrink: 0;
  gap: var(--space-xs);
}
.action-btn {
  padding: 4px var(--space-sm);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  color: var(--color-text);
}
.action-btn--danger {
  border-color: var(--color-danger);
  color: var(--color-danger);
}
.action-btn:disabled {
  border-color: var(--color-border);
  color: var(--color-text-sub);
  cursor: not-allowed;
}

.notice {
  margin-top: var(--space-lg);
  padding: var(--space-md);
  background: var(--color-bg);
  border-radius: var(--radius-md);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}

.edit-form {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.delete-desc {
  font-size: var(--text-sm);
  color: var(--color-text);
}
.delete-error {
  margin-top: var(--space-sm);
  font-size: var(--text-sm);
  color: var(--color-danger);
}
.postcode-embed {
  height: 400px;
}
</style>
