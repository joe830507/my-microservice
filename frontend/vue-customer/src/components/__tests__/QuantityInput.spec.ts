import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { defineComponent, ref } from 'vue'
import QuantityInput from '../QuantityInput.vue'

// 用外層元件掛載，才有 v-model 的雙向綁定
function mountWith(initial: number, max?: number) {
  const value = ref(initial)
  const Host = defineComponent({
    components: { QuantityInput },
    setup: () => ({ value, max }),
    template: '<QuantityInput v-model="value" :max="max" />',
  })
  return { wrapper: mount(Host), value }
}

describe('QuantityInput', () => {
  it('+ 與 − 增減數量', async () => {
    const { wrapper, value } = mountWith(2)

    await wrapper.find('[aria-label="增加數量"]').trigger('click')
    expect(value.value).toBe(3)

    await wrapper.find('[aria-label="減少數量"]').trigger('click')
    await wrapper.find('[aria-label="減少數量"]').trigger('click')
    expect(value.value).toBe(1)
  })

  it('到下限、上限時按鈕停用', () => {
    expect(
      mountWith(1).wrapper.find('[aria-label="減少數量"]').attributes('disabled'),
    ).toBeDefined()
    expect(
      mountWith(5, 5).wrapper.find('[aria-label="增加數量"]').attributes('disabled'),
    ).toBeDefined()
    expect(
      mountWith(3, 5).wrapper.find('[aria-label="增加數量"]').attributes('disabled'),
    ).toBeUndefined()
  })

  it.each([
    ['超過上限', '500', 99],
    ['超過自訂上限', '9', 5],
    ['0', '0', 1],
    ['負數', '-4', 1],
    ['小數', '2.7', 2],
    ['空白', '', 1],
    ['不是數字', 'abc', 1],
  ])('直接輸入 %s：限制回範圍內', async (_, text, expected) => {
    const { wrapper, value } = mountWith(3, text === '9' ? 5 : undefined)
    const input = wrapper.find('input')

    // type="number" 的輸入框在 jsdom 會把非數字清成空字串，這裡直接設定值模擬使用者輸入
    ;(input.element as HTMLInputElement).value = text
    await input.trigger('change')

    expect(value.value).toBe(expected)
    expect((input.element as HTMLInputElement).value).toBe(String(expected))
  })
})
