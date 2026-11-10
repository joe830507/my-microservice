import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'

import App from '../App.vue'
import { routes } from '../router'

// 冒煙測試：App + router 能掛載，首頁顯示；Element Plus 元件經由按需引入正常渲染
// 這裡的 router 沒有掛登入守衛，守衛另外在 router 的測試裡測
describe('App', () => {
  it('renders the home page with Element Plus components', async () => {
    const router = createRouter({ history: createMemoryHistory(), routes })
    await router.push('/')
    await router.isReady()

    const wrapper = mount(App, { global: { plugins: [createPinia(), router] } })

    expect(wrapper.find('h1').text()).toBe('vue-management')
    // <el-button> 有被解析成 Element Plus 元件，而不是當成未知標籤原樣輸出
    const button = wrapper.find('button.el-button')
    expect(button.exists()).toBe(true)
    expect(button.classes()).toContain('el-button--primary')
  })
})
