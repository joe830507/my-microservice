import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'

import App from '../App.vue'
import { routes } from '../router'

// 冒煙測試：App + router 能掛載，首頁在 AdminLayout 裡顯示；Element Plus 元件經由按需引入正常渲染
// 這裡的 router 沒有掛登入守衛，守衛另外在 router 的測試裡測
describe('App', () => {
  it('renders the home page inside AdminLayout with Element Plus components', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes })
    await router.push('/')
    await router.isReady()

    const wrapper = mount(App, { global: { plugins: [createPinia(), router] } })

    expect(wrapper.find('.admin-brand').text()).toBe('商城管理後台')
    expect(wrapper.find('h2').text()).toContain('歡迎回來')
    // <el-menu> 有被解析成 Element Plus 元件，而不是當成未知標籤原樣輸出
    expect(wrapper.find('ul.el-menu').exists()).toBe(true)
  })
})
