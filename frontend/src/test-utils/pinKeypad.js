/**
 * PinKeypad 를 실제 사용자 경로(숫자 버튼 클릭)로 조작하는 테스트 헬퍼.
 * 숨은 input 은 readonly 라 setValue() 로는 실사용자가 값을 바꿀 수 없는 경로를
 * 검증하게 된다 — PIN 을 입력하는 테스트는 항상 이 헬퍼를 써야 한다.
 *
 * 숫자 배치가 매 마운트·재입력마다 무작위로 섞이므로 인덱스가 아니라 버튼 라벨
 * (숫자 문자)로 찾는다.
 */
export async function typePin(wrapper, pin) {
  for (const digit of pin) {
    const key = wrapper.findAll('button.key').find((b) => b.text() === digit)
    await key.trigger('click')
  }
}
