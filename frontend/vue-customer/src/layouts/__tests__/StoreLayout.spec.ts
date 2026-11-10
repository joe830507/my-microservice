import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'

import StoreLayout from '../StoreLayout.vue'

describe('StoreLayout', () => {
  it('shows header, slot content area and footer', async () => {
    const Child = { template: '<p class="child">content</p>' }
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        {
          path: '/',
          name: 'home',
          component: StoreLayout,
          children: [{ path: '', component: Child }],
        },
      ],
    })
    await router.push('/')
    await router.isReady()

    const wrapper = mount({ template: '<RouterView />' }, { global: { plugins: [router] } })

    expect(wrapper.find('header .store-brand').exists()).toBe(true)
    expect(wrapper.find('header input[type="search"]').exists()).toBe(true)
    expect(wrapper.find('nav').text()).toContain('購物車')
    expect(wrapper.find('main .child').text()).toBe('content')
    expect(wrapper.find('footer').text()).toContain('Joe 商城')
  })
})
