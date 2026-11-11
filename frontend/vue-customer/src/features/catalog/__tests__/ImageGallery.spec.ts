import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import ImageGallery from '../ImageGallery.vue'
import type { ProductImage } from '../types'

function image(n: number, overrides: Partial<ProductImage> = {}): ProductImage {
  return {
    id: `img-${n}`,
    url: `http://img.example/${n}.jpg`,
    altText: null,
    sortOrder: n,
    isPrimary: false,
    ...overrides,
  }
}

function mainSrc(wrapper: ReturnType<typeof mount>): string | undefined {
  return wrapper.find('.gallery__main img').attributes('src')
}

describe('ImageGallery', () => {
  it('沒有圖片：顯示預設圖', () => {
    const wrapper = mount(ImageGallery, { props: { images: [], productName: '鞋' } })

    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.find('[aria-label="尚無商品圖片"]').exists()).toBe(true)
  })

  it('只有一張：顯示大圖，沒有縮圖列；alt 沒有說明就用商品名稱', () => {
    const wrapper = mount(ImageGallery, { props: { images: [image(1)], productName: '運動鞋' } })

    expect(mainSrc(wrapper)).toBe('http://img.example/1.jpg')
    expect(wrapper.find('.gallery__main img').attributes('alt')).toBe('運動鞋')
    expect(wrapper.find('.gallery__thumbs').exists()).toBe(false)
  })

  it('主圖排第一張，即使後端的順序不是這樣', () => {
    const wrapper = mount(ImageGallery, {
      props: { images: [image(1), image(2, { isPrimary: true }), image(3)], productName: '鞋' },
    })

    expect(mainSrc(wrapper)).toBe('http://img.example/2.jpg')
    expect(wrapper.findAll('.gallery__thumbs img').map((img) => img.attributes('src'))).toEqual([
      'http://img.example/2.jpg',
      'http://img.example/1.jpg',
      'http://img.example/3.jpg',
    ])
  })

  it('點縮圖切換大圖，並標示目前選取的', async () => {
    const wrapper = mount(ImageGallery, {
      props: { images: [image(1), image(2), image(3)], productName: '鞋' },
    })

    await wrapper.findAll('.gallery__thumbs button')[2]?.trigger('click')

    expect(mainSrc(wrapper)).toBe('http://img.example/3.jpg')
    expect(wrapper.find('[aria-pressed="true"]').attributes('aria-label')).toBe('第 3 張圖片')
  })

  it('圖片換成別的商品：回到第一張', async () => {
    const wrapper = mount(ImageGallery, {
      props: { images: [image(1), image(2)], productName: '鞋' },
    })
    await wrapper.findAll('.gallery__thumbs button')[1]?.trigger('click')

    await wrapper.setProps({ images: [image(7), image(8)] })

    expect(mainSrc(wrapper)).toBe('http://img.example/7.jpg')
  })
})
