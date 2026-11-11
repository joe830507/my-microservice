import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import App from '@/App.vue'
import { routes } from '@/router'

// 404、403 頁面：顯示狀態，並能回首頁
describe.each([
  ['/no-such-page', '404', '找不到這個頁面'],
  ['/403', '403', '你沒有權限查看這個頁面'],
])('%s', (path, title, subTitle) => {
  it(`顯示 ${title}，按「回首頁」回到首頁`, async () => {
    const router = createRouter({ history: createMemoryHistory(), routes })
    await router.push(path)
    await router.isReady()
    const wrapper = mount(App, { global: { plugins: [createPinia(), router] } })
    await flushPromises()

    expect(wrapper.find('.el-result__title').text()).toBe(title)
    expect(wrapper.find('.el-result__subtitle').text()).toBe(subTitle)
    // 錯誤頁不在 AdminLayout 裡
    expect(wrapper.find('.admin-brand').exists()).toBe(false)

    await wrapper.find('.el-result__extra button').trigger('click')

    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('home'))
  })
})
