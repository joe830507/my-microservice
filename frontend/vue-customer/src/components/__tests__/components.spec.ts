import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import { product } from '@/features/catalog/__tests__/fixtures'
import PaginationBar from '../PaginationBar.vue'
import PriceTag from '../PriceTag.vue'
import ProductCard from '../ProductCard.vue'

describe('PriceTag', () => {
  it('沒有原價：只顯示價格', () => {
    const wrapper = mount(PriceTag, { props: { price: 1234.5, originalPrice: null } })

    expect(wrapper.text()).toBe('NT$ 1,234.5')
    expect(wrapper.find('del').exists()).toBe(false)
  })

  it('原價高於售價：顯示原價（刪除線）與折扣', () => {
    const wrapper = mount(PriceTag, { props: { price: 800, originalPrice: 1000 } })

    expect(wrapper.find('del').text()).toBe('NT$ 1,000')
    expect(wrapper.text()).toContain('-20%')
  })

  it('原價不高於售價：不顯示折扣', () => {
    const wrapper = mount(PriceTag, { props: { price: 800, originalPrice: 800 } })

    expect(wrapper.find('del').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('%')
  })
})

describe('ProductCard', () => {
  async function mountCard(item = product(1)) {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/p/:id', name: 'product-detail', component: { template: '<i />' } }],
    })
    return mount(ProductCard, { props: { product: item }, global: { plugins: [router] } })
  }

  it('顯示主圖、名稱、品牌、價格，連到商品詳情', async () => {
    const wrapper = await mountCard(product(1, { brand: 'Nike', originalPrice: 200 }))

    expect(wrapper.find('img').attributes('src')).toBe('http://img.example/1.jpg')
    expect(wrapper.find('img').attributes('alt')).toBe('商品 1')
    expect(wrapper.text()).toContain('Nike')
    expect(wrapper.text()).toContain('NT$ 100')
    expect(wrapper.text()).toContain('-50%')
    expect(wrapper.find('a').attributes('href')).toBe('/p/00000000-0000-4000-8000-000000000001')
  })

  it('沒有主圖：顯示預設圖，不是壞掉的 <img>', async () => {
    const wrapper = await mountCard(product(1, { primaryImageUrl: null }))

    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.find('[aria-label="尚無商品圖片"]').exists()).toBe(true)
  })
})

describe('PaginationBar', () => {
  function pageNumbers(wrapper: ReturnType<typeof mount>): string[] {
    return wrapper
      .findAll('button, .pagination__gap')
      .map((el) => el.text())
      .filter((text) => text !== '上一頁' && text !== '下一頁')
  }

  it('只有一頁：不顯示', () => {
    const wrapper = mount(PaginationBar, { props: { page: 1, totalPages: 1 } })

    expect(wrapper.find('nav').exists()).toBe(false)
  })

  it('頁數少：全部列出，目前頁標示出來', () => {
    const wrapper = mount(PaginationBar, { props: { page: 2, totalPages: 3 } })

    expect(pageNumbers(wrapper)).toEqual(['1', '2', '3'])
    expect(wrapper.find('[aria-current="page"]').text()).toBe('2')
  })

  it('頁數多：目前頁附近加上第一頁與最後一頁，中間用省略號', () => {
    const wrapper = mount(PaginationBar, { props: { page: 10, totalPages: 20 } })

    expect(pageNumbers(wrapper)).toEqual(['1', '…', '8', '9', '10', '11', '12', '…', '20'])
  })

  it('第一頁不能按上一頁，最後一頁不能按下一頁', () => {
    const first = mount(PaginationBar, { props: { page: 1, totalPages: 3 } })
    const last = mount(PaginationBar, { props: { page: 3, totalPages: 3 } })

    expect(first.findAll('button')[0]?.attributes('disabled')).toBeDefined()
    expect(last.findAll('button')[4]?.attributes('disabled')).toBeDefined()
  })

  it('點頁碼、上一頁、下一頁：送出 change；點目前頁不送', async () => {
    const wrapper = mount(PaginationBar, { props: { page: 2, totalPages: 3 } })
    const buttons = wrapper.findAll('button')

    await buttons[0]?.trigger('click') // 上一頁
    await buttons[3]?.trigger('click') // 3
    await buttons[2]?.trigger('click') // 2（目前頁）
    await buttons[4]?.trigger('click') // 下一頁

    expect(wrapper.emitted('change')).toEqual([[1], [3], [3]])
  })
})
