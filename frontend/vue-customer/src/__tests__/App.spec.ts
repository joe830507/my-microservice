import { describe, expect, it } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'

import App from '../App.vue'
import { routes } from '../router'

// 冒煙測試：App + router 能掛載，首頁顯示在 StoreLayout 裡；打錯網址顯示 404
describe('App', () => {
  async function mountAt(path: string) {
    const router = createRouter({ history: createMemoryHistory(), routes })
    await router.push(path)
    await router.isReady()
    const wrapper = mount(App, { global: { plugins: [createPinia(), router] } })
    await flushPromises()
    return wrapper
  }

  it('renders the home page inside StoreLayout', async () => {
    const wrapper = await mountAt('/')

    expect(wrapper.find('.store-brand').text()).toBe('Joe 商城')
    expect(wrapper.find('input[type="search"]').exists()).toBe(true)
    expect(wrapper.find('main h1').text()).toBe('歡迎光臨')
    expect(wrapper.find('footer').exists()).toBe(true)
  })

  it('renders the 404 page for unknown paths', async () => {
    const wrapper = await mountAt('/no/such/page')

    expect(wrapper.find('main h1').text()).toBe('404')
  })
})
