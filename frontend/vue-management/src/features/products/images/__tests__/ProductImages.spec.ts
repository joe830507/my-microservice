import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { AxiosError, AxiosHeaders } from 'axios'
import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { confirmDanger } from '@/composables/useConfirm'
import { problem } from '@/features/auth/__tests__/fixtures'
import { storageClient } from '../api'
import ProductImages from '../ProductImages.vue'
import type { ProductImage } from '../types'
import { MAX_IMAGE_BYTES, moveItem, validateImageFile } from '../validate'

vi.mock('@/composables/useConfirm', () => ({
  confirmDanger: vi.fn<(message: string, title?: string) => Promise<boolean>>(),
}))

const server = setupServer()

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterAll(() => server.close())

const STORAGE_URL = 'http://storage.test/product-images/tmp/products/p1/abc.png?X-Amz-Signature=sig'

function image(id: string, sortOrder: number, isPrimary = false): ProductImage {
  return {
    id,
    url: `http://storage.test/product-images/products/p1/${id}.png`,
    altText: null,
    sortOrder,
    isPrimary,
    version: 0,
  }
}

// 伺服器上目前的圖片；GET 會回傳它
let stored: ProductImage[] = []

beforeEach(() => {
  stored = [image('a', 0, true), image('b', 1), image('c', 2)]
  vi.mocked(confirmDanger).mockReset()
  server.use(
    http.get('*/products/p1/images', () => HttpResponse.json({ productId: 'p1', images: stored })),
  )
})

afterEach(() => {
  vi.restoreAllMocks()
  server.resetHandlers()
  ElMessage.closeAll()
  document.body.innerHTML = ''
})

async function mountImages(readOnly = false): Promise<VueWrapper> {
  const wrapper = mount(ProductImages, {
    props: { productId: 'p1', readOnly },
    attachTo: document.body,
  })
  await flushPromises()
  return wrapper
}

function ids(wrapper: VueWrapper): string[] {
  return wrapper
    .findAll('.image-card img')
    .map((img) => (img.attributes('src') ?? '').replace(/^.*\/(\w+)\.png$/, '$1'))
}

function card(wrapper: VueWrapper, index: number) {
  const found = wrapper.findAll('.image-card')[index]
  if (!found) {
    throw new Error(`沒有第 ${index + 1} 張圖片`)
  }
  return found
}

function pngFile(size = 1024, type = 'image/png', name = 'photo.png'): File {
  const file = new File([new Uint8Array(Math.min(size, 16))], name, { type })
  if (size > 16) {
    Object.defineProperty(file, 'size', { value: size })
  }
  return file
}

async function chooseFile(wrapper: VueWrapper, file: File): Promise<void> {
  const input = wrapper.find('input[type="file"]')
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
  await input.trigger('change')
  await flushPromises()
}

describe('validateImageFile', () => {
  it.each([
    ['JPEG', 'image/jpeg', 1000, null],
    ['WebP', 'image/webp', MAX_IMAGE_BYTES, null],
    ['GIF', 'image/gif', 1000, '只能上傳 JPEG、PNG、WebP 圖片'],
    ['沒有類型', '', 1000, '只能上傳 JPEG、PNG、WebP 圖片'],
    ['空檔案', 'image/png', 0, '檔案是空的'],
    ['超過 5 MB', 'image/png', MAX_IMAGE_BYTES + 1, '圖片不能超過 5 MB'],
  ])('%s', (_, type, size, expected) => {
    expect(validateImageFile(pngFile(size, type))).toBe(expected)
  })
})

describe('moveItem', () => {
  it('把項目移到新位置', () => {
    expect(moveItem(['a', 'b', 'c'], 0, 2)).toEqual(['b', 'c', 'a'])
    expect(moveItem(['a', 'b', 'c'], 2, 0)).toEqual(['c', 'a', 'b'])
  })
})

describe('ProductImages 顯示', () => {
  it('依順序顯示，標出主圖', async () => {
    const wrapper = await mountImages()

    expect(ids(wrapper)).toEqual(['a', 'b', 'c'])
    expect(card(wrapper, 0).find('.primary-tag').exists()).toBe(true)
    expect(card(wrapper, 1).find('.primary-tag').exists()).toBe(false)
    expect(card(wrapper, 0).find<HTMLButtonElement>('.primary-button').element.disabled).toBe(true)
  })

  it('沒有主圖：提醒上架前要設定', async () => {
    stored = [image('a', 0), image('b', 1)]

    const wrapper = await mountImages()

    expect(wrapper.find('.no-primary-notice').exists()).toBe(true)
  })

  it('唯讀（商品已封存）：沒有上傳與操作按鈕', async () => {
    const wrapper = await mountImages(true)

    expect(wrapper.find('.upload-button').exists()).toBe(false)
    expect(wrapper.find('.image-actions').exists()).toBe(false)
    expect(card(wrapper, 0).attributes('draggable')).toBe('false')
  })
})

// vitest 4.1 的 jsdom 橋接無法把 Blob、File 當成請求 body，MSW 收不到上傳本體；
// 所以 PUT 到物件儲存這一步直接 spy storageClient.put，其他 API 照樣用 MSW
describe('ProductImages 上傳', () => {
  function serveUploadUrl(onBody?: (body: unknown) => void): void {
    server.use(
      http.post('*/products/p1/images/upload-url', async ({ request }) => {
        onBody?.(await request.json())
        return HttpResponse.json({
          objectKey: 'tmp/products/p1/abc.png',
          uploadUrl: STORAGE_URL,
          requiredHeaders: { 'Content-Type': 'image/png', 'Content-Length': '1024' },
          expiresAt: '2026-11-10T00:10:00Z',
        })
      }),
    )
  }

  it('申請上傳網址 → PUT 到物件儲存 → 登記，完成後重新讀取', async () => {
    const calls: string[] = []
    let uploadUrlBody: unknown
    let registerBody: unknown
    serveUploadUrl((body) => {
      calls.push('upload-url')
      uploadUrlBody = body
    })
    server.use(
      http.post('*/products/p1/images', async ({ request }) => {
        calls.push('register')
        registerBody = await request.json()
        stored = [...stored, image('d', 3)]
        return HttpResponse.json(image('d', 3), { status: 201 })
      }),
    )
    const put = vi.spyOn(storageClient, 'put').mockImplementation(async () => {
      calls.push('put')
      return { status: 200 }
    })
    const wrapper = await mountImages()
    const file = pngFile(1024)

    await chooseFile(wrapper, file)

    expect(calls).toEqual(['upload-url', 'put', 'register'])
    expect(uploadUrlBody).toEqual({ contentType: 'image/png', contentLength: 1024 })
    // 檔案本體直接 PUT 到簽名網址；Content-Length 由瀏覽器依 body 自動帶上，不能手動設定
    expect(put).toHaveBeenCalledWith(
      STORAGE_URL,
      file,
      expect.objectContaining({ headers: { 'Content-Type': 'image/png' } }),
    )
    expect(registerBody).toEqual({ objectKey: 'tmp/products/p1/abc.png', altText: null })
    expect(ids(wrapper)).toEqual(['a', 'b', 'c', 'd'])
  })

  it('物件儲存用另一個 axios 實例：不帶 credentials、不套 baseURL、沒有 auth 攔截器', () => {
    expect(storageClient.defaults.withCredentials).toBe(false)
    expect(storageClient.defaults.baseURL).toBeUndefined()
    const handlers = (storageClient.interceptors.request as unknown as { handlers: unknown[] })
      .handlers
    expect(handlers).toEqual([])
  })

  it.each([
    ['類型不符', pngFile(1024, 'image/gif', 'anim.gif'), '只能上傳 JPEG、PNG、WebP 圖片'],
    ['超過 5 MB', pngFile(MAX_IMAGE_BYTES + 1), '圖片不能超過 5 MB'],
  ])('%s：顯示訊息，不發任何請求', async (_, file, message) => {
    // 沒有註冊上傳相關的 handler：發出請求會因 onUnhandledRequest: 'error' 失敗
    const put = vi.spyOn(storageClient, 'put')
    const wrapper = await mountImages()

    await chooseFile(wrapper, file)

    expect(wrapper.find('.image-error').text()).toContain(message)
    expect(put).not.toHaveBeenCalled()
  })

  it('PUT 到物件儲存失敗：不登記，顯示上傳失敗', async () => {
    let registered = false
    serveUploadUrl()
    server.use(
      http.post('*/products/p1/images', () => {
        registered = true
        return HttpResponse.json(image('d', 3), { status: 201 })
      }),
    )
    // 物件儲存回 403（例如簽章不符），body 是 S3 的 XML
    const config = { headers: new AxiosHeaders() }
    vi.spyOn(storageClient, 'put').mockRejectedValue(
      new AxiosError(
        'Request failed',
        AxiosError.ERR_BAD_REQUEST,
        config,
        {},
        {
          status: 403,
          statusText: 'Forbidden',
          headers: {},
          config,
          data: '<Error><Code>SignatureDoesNotMatch</Code></Error>',
        },
      ),
    )
    const wrapper = await mountImages()

    await chooseFile(wrapper, pngFile(1024))

    expect(registered).toBe(false)
    expect(wrapper.find('.image-error').text()).toContain('圖片上傳失敗，請重新上傳')
    expect(ids(wrapper)).toEqual(['a', 'b', 'c'])
  })

  it('後端拒絕申請（IMAGE_TOO_LARGE）：顯示訊息', async () => {
    server.use(http.post('*/products/p1/images/upload-url', () => problem(400, 'IMAGE_TOO_LARGE')))
    const wrapper = await mountImages()

    await chooseFile(wrapper, pngFile(1024))

    expect(wrapper.find('.image-error').text()).toContain('圖片不能超過 5 MB')
  })
})

describe('ProductImages 主圖、刪除', () => {
  it('設為主圖：用回應的列表更新', async () => {
    server.use(
      http.post('*/products/p1/images/b/primary', () =>
        HttpResponse.json({
          productId: 'p1',
          images: [image('a', 0), image('b', 1, true), image('c', 2)],
        }),
      ),
    )
    const wrapper = await mountImages()

    await card(wrapper, 1).find('.primary-button').trigger('click')
    await flushPromises()

    expect(card(wrapper, 1).find('.primary-tag').exists()).toBe(true)
    expect(card(wrapper, 0).find('.primary-tag').exists()).toBe(false)
  })

  it('刪除：確認後刪除並重新讀取', async () => {
    vi.mocked(confirmDanger).mockResolvedValue(true)
    server.use(
      http.delete('*/products/p1/images/b', () => {
        stored = stored.filter((item) => item.id !== 'b')
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const wrapper = await mountImages()

    await card(wrapper, 1).find('.delete-button').trigger('click')
    await flushPromises()

    expect(ids(wrapper)).toEqual(['a', 'c'])
  })

  it('刪除主圖時的確認訊息會提醒要另外設定主圖', async () => {
    vi.mocked(confirmDanger).mockResolvedValue(false)
    const wrapper = await mountImages()

    await card(wrapper, 0).find('.delete-button').trigger('click')
    await flushPromises()

    expect(confirmDanger).toHaveBeenCalledWith(expect.stringContaining('要另外設定主圖'))
    expect(ids(wrapper)).toEqual(['a', 'b', 'c'])
  })

  it('上架中的商品刪除主圖：顯示原因', async () => {
    vi.mocked(confirmDanger).mockResolvedValue(true)
    server.use(
      http.delete('*/products/p1/images/a', () =>
        problem(400, 'PRODUCT_IMAGE_PRIMARY_UNDELETABLE'),
      ),
    )
    const wrapper = await mountImages()

    await card(wrapper, 0).find('.delete-button').trigger('click')
    await flushPromises()

    expect(wrapper.find('.image-error').text()).toContain('上架中的商品不能刪除主圖')
    expect(ids(wrapper)).toEqual(['a', 'b', 'c'])
  })
})

describe('ProductImages 排序', () => {
  function recordOrder(): string[][] {
    const bodies: string[][] = []
    server.use(
      http.put('*/products/p1/images/order', async ({ request }) => {
        const { imageIds } = (await request.json()) as { imageIds: string[] }
        bodies.push(imageIds)
        return HttpResponse.json({
          productId: 'p1',
          images: imageIds.map((id, index) => image(id, index, id === 'a')),
        })
      }),
    )
    return bodies
  }

  it('往後移：送出所有圖片的新順序', async () => {
    const bodies = recordOrder()
    const wrapper = await mountImages()

    await card(wrapper, 0).find('.move-right-button').trigger('click')
    await flushPromises()

    expect(bodies).toEqual([['b', 'a', 'c']])
    expect(ids(wrapper)).toEqual(['b', 'a', 'c'])
  })

  it('往前移', async () => {
    const bodies = recordOrder()
    const wrapper = await mountImages()

    await card(wrapper, 2).find('.move-left-button').trigger('click')
    await flushPromises()

    expect(bodies).toEqual([['a', 'c', 'b']])
  })

  it('第一張不能往前、最後一張不能往後', async () => {
    const wrapper = await mountImages()

    expect(card(wrapper, 0).find<HTMLButtonElement>('.move-left-button').element.disabled).toBe(
      true,
    )
    expect(card(wrapper, 2).find<HTMLButtonElement>('.move-right-button').element.disabled).toBe(
      true,
    )
  })

  it('拖曳：把第三張拖到第一張的位置', async () => {
    const bodies = recordOrder()
    const wrapper = await mountImages()

    await card(wrapper, 2).trigger('dragstart')
    await card(wrapper, 0).trigger('drop')
    await flushPromises()

    expect(bodies).toEqual([['c', 'a', 'b']])
    expect(ids(wrapper)).toEqual(['c', 'a', 'b'])
  })

  it('排序失敗：改回原本的順序並顯示原因', async () => {
    server.use(
      http.put('*/products/p1/images/order', () => problem(400, 'PRODUCT_IMAGE_IDS_MISMATCH')),
    )
    const wrapper = await mountImages()

    await card(wrapper, 0).find('.move-right-button').trigger('click')
    await flushPromises()

    expect(ids(wrapper)).toEqual(['a', 'b', 'c'])
    expect(wrapper.find('.image-error').text()).toContain('圖片已被其他人變更，請重新整理後再排序')
  })
})
