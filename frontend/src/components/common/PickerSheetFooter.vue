<script setup>
/**
 * 바텀시트 피커(AppTimeField·AppDateFieldCalendar)의 취소/확인 footer.
 * 두 컴포넌트에 글자 하나 다르지 않게 중복돼 있던 마크업·스타일을 한 곳으로 모았다.
 *
 * 사용: <PickerSheetFooter @cancel="open = false" @confirm="confirm" />
 */
import BaseButton from './BaseButton.vue'

defineEmits(['cancel', 'confirm'])
</script>

<template>
  <div class="sheet-actions">
    <BaseButton variant="secondary" block @click="$emit('cancel')">취소</BaseButton>
    <BaseButton variant="owner" block @click="$emit('confirm')">확인</BaseButton>
  </div>
</template>

<style scoped>
.sheet-actions {
  display: flex;
  gap: var(--space-sm);
}
</style>
