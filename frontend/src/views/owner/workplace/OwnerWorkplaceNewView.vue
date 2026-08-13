<script setup>
/**
 * [D] 사업장 등록  ·  /owner/workplaces/new  ·  OWNER
 * 폼: 사업자등록번호·상호명·대표자명·사업장 주소·사업장 전화번호.
 * 주소는 도로명(다음 우편번호 검색 embed, @/utils/daumPostcode · 실패 시 직접 입력)과 세부주소를
 * 나눠 입력받아 승인 계약대로 roadAddress·detailAddress 로 각각 보낸다.
 * 좌표 자동 변환은 별도 지오코딩 API 필요(미구현). 인증 반경은 서버가 100m 를 적용한다(미전송).
 * 진입 경로 2가지: ① 첫 로그인(G7, 사업장 0개) 강제 진입 ② /owner/mypage/workplaces 에서 수동 추가.
 * 연계 API: POST /workplaces  →  @/services/workplaces (createWorkplace)
 * 등록 성공 후: useWorkplaceStore().load({force:true}) 갱신(실패해도 무시) →
 *   세션을 다시 조회해 서버가 계산한 needsWorkplaceSetup 확인. 목록 갱신·세션 재조회
 *   둘 다 실패해도 등록 자체는 이미 성공했으므로 성공 토스트를 띄운다.
 *   G7 강제 진입이었고 재조회 결과 needsWorkplaceSetup=false 로 확인되면 /owner/home,
 *   수동 추가였으면 /owner/mypage/workplaces 로 복귀. 세션 재조회가 실패해 확인이
 *   안 되면 이동하지 않고 화면에 머무른다(다음 라우팅에서 가드가 최신 상태로 판단).
 *   (⚠ 서버 확인 없이 홈으로 보내면 G7 가드가 즉시 되돌려 무한 루프)
 * 공통: AppField · BaseButton · @/utils/validators (isBusinessNumber, isPhone)
 *
 * 실시간 검증(#238): useFieldValidation 이 형식 오류(errors)를 담당한다. 이 화면은 중복확인이
 * 없어(사업자등록번호·상호명 모두 서버에 물어보지 않는다) AuthSignupForm 의 checkErrors 같은
 * 별도 슬롯이 필요 없다 — errors 하나로 충분하다.
 */
import { nextTick, ref } from 'vue'
import { useRouter } from 'vue-router'

import AppBackHeader from '@/components/common/AppBackHeader.vue'
import AppField from '@/components/common/AppField.vue'
import BaseButton from '@/components/common/BaseButton.vue'
import BaseModal from '@/components/common/BaseModal.vue'
import { useFieldValidation } from '@/composables/useFieldValidation'
import { createWorkplace } from '@/services/workplaces'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'
import { useWorkplaceStore } from '@/stores/workplace'
import { embedAddressSearch } from '@/utils/daumPostcode'
import { blockNonDigitKeydown, formatBusinessNumberInput, formatPhoneInput } from '@/utils/format'
import { isBusinessNumber, isPhone, isRequired } from '@/utils/validators'

const router = useRouter()
const authStore = useAuthStore()
const workplaceStore = useWorkplaceStore()
const ui = useUiStore()

// 진입 시점의 needsWorkplaceSetup 값으로 "G7 강제 진입"이었는지 기억해둔다.
// (등록 성공 후 이 값을 false 로 바꾸므로, 바뀌기 전에 미리 캡처해야 한다)
const cameFromForcedSetup = authStore.needsWorkplaceSetup

const businessRegistrationNumber = ref('')
const name = ref('')
const representativeName = ref('')
const roadAddress = ref('')
const detailAddress = ref('')
const phone = ref('')

// 형식 오류 전용. 규칙은 기존 validate() 가 쓰던 것을 그대로 옮겼다 — 규칙 자체는 불변.
const { errors, handleBlur, validateAll } = useFieldValidation(
  () => ({
    businessRegistrationNumber: businessRegistrationNumber.value,
    name: name.value,
    representativeName: representativeName.value,
    roadAddress: roadAddress.value,
    phone: phone.value
  }),
  {
    businessRegistrationNumber: (v) => isBusinessNumber(v.businessRegistrationNumber),
    name: (v) => isRequired(v.name, '상호명'),
    representativeName: (v) => isRequired(v.representativeName, '대표자명'),
    roadAddress: (v) => isRequired(v.roadAddress, '사업장 주소'),
    phone: (v) => isPhone(v.phone, { required: true })
  }
)

const submitting = ref(false)

function onBusinessNumberInput(v) {
  businessRegistrationNumber.value = formatBusinessNumberInput(v)
}

function onPhoneInput(v) {
  phone.value = formatPhoneInput(v)
}

const addressSearchOpen = ref(false)
const addressSearchContainer = ref(null)

async function searchAddress() {
  addressSearchOpen.value = true
  await nextTick()
  embedAddressSearch(
    addressSearchContainer.value,
    (result) => {
      // roadAddress.value 를 바꾸면 useFieldValidation 의 watcher 가(이미 떠났던 필드라면)
      // 자동으로 재검증해 오류를 지운다 — 여기서 따로 지울 필요가 없다.
      roadAddress.value = result.address
      addressSearchOpen.value = false
    },
    () => {
      addressSearchOpen.value = false
      ui.toast('주소 검색을 불러오지 못했어요. 직접 입력해주세요.', { type: 'danger' })
    }
  )
}

/**
 * 주소 확정 실패와 위치 확인 서비스 장애는 사용자가 할 일이 다르다(SPEC-343-01).
 * 전자는 주소를 고쳐야 하고 후자는 그대로 다시 시도하면 되므로 안내를 구분한다.
 * 재시도를 안내하는 쪽만 warning 으로 낮춰 "고칠 것이 있다"는 신호와 섞이지 않게 한다.
 */
const CREATE_ERROR_MESSAGES = {
  WORKPLACE_ADDRESS_NOT_RESOLVABLE: {
    message: '입력한 주소로 위치를 찾지 못했어요. 도로명주소를 다시 확인해주세요.',
    type: 'danger'
  },
  WORKPLACE_GEOCODING_TEMPORARILY_UNAVAILABLE: {
    message: '위치 확인 서비스가 일시적으로 응답하지 않아요. 잠시 후 다시 시도해주세요.',
    type: 'warning'
  }
}

function createErrorInfo(err) {
  const byCode = CREATE_ERROR_MESSAGES[err?.code]
  if (byCode) return byCode
  return {
    message: err?.response?.data?.message || '사업장 등록에 실패했어요.',
    type: 'danger'
  }
}

async function handleSubmit() {
  if (!validateAll()) return

  submitting.value = true
  try {
    // 승인 Body 는 radius 를 받지 않는다 — 서버가 100m 를 적용한다(API_SPEC.md:341).
    // 좌표도 보내지 않는다. 서버가 도로명주소를 변환해 확정하며, 실어 보내면 400 이다
    // (SPEC-343-01).
    await createWorkplace({
      businessRegistrationNumber: businessRegistrationNumber.value,
      name: name.value,
      representativeName: representativeName.value,
      roadAddress: roadAddress.value,
      detailAddress: detailAddress.value,
      phone: phone.value
    })
    // 목록 갱신은 편의 동작이다. 실패해도 등록 자체는 이미 성공했으므로 전파하지
    // 않는다 — GET /api/workplaces 는 아직 서버에 없어서(#145 후속) 지금은 항상
    // 404 다. 전파하면 등록에 성공하고도 실패 토스트가 뜬다.
    try {
      await workplaceStore.load({ force: true })
    } catch {
      // 다음 화면이 목록을 다시 읽는다.
    }

    // needsWorkplaceSetup 은 서버가 요청 시점 DB 로 계산한다. 등록 성공만 보고
    // 클라이언트가 false 로 단정하면 서버와 어긋난 채 홈으로 보내게 된다.
    // 재조회 자체가 실패해도(일시적 네트워크 오류 등) 등록은 이미 성공했으므로 등록
    // 실패로 보고하지 않는다 — 다만 서버 확인이 없는 채로 홈으로 보낼 수는 없으므로
    // session 을 undefined 로 남겨 아래 게이트를 자연히 통과시키지 않는다(화면에 머무름).
    let session
    try {
      session = await authStore.refreshSession()
    } catch {
      // 다음 라우팅 시도에서 가드가 최신 세션으로 다시 판단한다.
    }
    ui.toast('사업장을 등록했어요.', { type: 'success' })

    if (cameFromForcedSetup && session?.needsWorkplaceSetup === false) {
      router.push('/owner/home')
    } else if (!cameFromForcedSetup) {
      router.push('/owner/mypage/workplaces')
    }
  } catch (err) {
    const info = createErrorInfo(err)
    ui.toast(info.message, { type: info.type })
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="sub-page">
    <AppBackHeader title="사업장 등록" to="/owner/home" />
    <main class="screen-body">
      <form class="new-form" @submit.prevent="handleSubmit">
        <AppField
          :model-value="businessRegistrationNumber"
          label="사업자등록번호 (숫자 10자리)"
          digits-only
          placeholder="000-00-00000"
          required
          maxlength="12"
          :error="errors.businessRegistrationNumber"
          @keydown="blockNonDigitKeydown"
          @update:model-value="onBusinessNumberInput"
          @blur="handleBlur('businessRegistrationNumber')"
        />
        <!-- maxlength 는 WorkplaceCreateRequest 의 @Size(max=...) 를 그대로 반영한다 — 임의로 바꾸지 말 것 -->
        <AppField
          v-model="name"
          label="상호명"
          placeholder="상호명을 입력하세요"
          required
          maxlength="120"
          :error="errors.name"
          @blur="handleBlur('name')"
        />
        <AppField
          v-model="representativeName"
          label="대표자명"
          placeholder="대표자명을 입력하세요"
          required
          maxlength="100"
          :error="errors.representativeName"
          @blur="handleBlur('representativeName')"
        />
        <AppField
          v-model="roadAddress"
          label="사업장 주소 (도로명)"
          placeholder="주소 검색을 이용하거나 직접 입력하세요"
          required
          maxlength="255"
          :error="errors.roadAddress"
          @blur="handleBlur('roadAddress')"
        >
          <template #suffix>
            <BaseButton type="button" variant="secondary" @click="searchAddress">검색</BaseButton>
          </template>
        </AppField>
        <AppField
          v-model="detailAddress"
          label="세부 주소"
          placeholder="건물명·동·호수 등을 입력하세요"
          maxlength="100"
        />
        <AppField
          :model-value="phone"
          label="사업장 전화번호 (지역번호 포함 9~11자리)"
          type="tel"
          digits-only
          placeholder="02-0000-0000"
          required
          maxlength="13"
          :error="errors.phone"
          @keydown="blockNonDigitKeydown"
          @update:model-value="onPhoneInput"
          @blur="handleBlur('phone')"
        />

        <BaseButton type="submit" variant="owner" block :disabled="submitting">등록</BaseButton>
      </form>
    </main>

    <BaseModal :open="addressSearchOpen" title="주소 검색" @close="addressSearchOpen = false">
      <div ref="addressSearchContainer" class="postcode-embed"></div>
    </BaseModal>
  </div>
</template>

<style scoped>
.screen-body {
  padding: var(--space-lg);
}
.new-form {
  display: flex;
  flex-direction: column;
  gap: var(--space-lg);
}
.postcode-embed {
  height: 400px;
}
</style>
