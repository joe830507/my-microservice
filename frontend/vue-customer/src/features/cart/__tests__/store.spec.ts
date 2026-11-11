import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { CART_KEY, MAX_QTY, useCartStore } from '../store'

// localStorage 要在建立 store 之前準備好：store 建立時才讀
function createStore() {
  setActivePinia(createPinia())
  return useCartStore()
}

function stored(): unknown {
  return JSON.parse(localStorage.getItem(CART_KEY) ?? 'null')
}

beforeEach(() => localStorage.clear())
afterEach(() => vi.restoreAllMocks())

describe('useCartStore.add', () => {
  it('加入商品：存進 localStorage，件數是數量加總', () => {
    const cart = createStore()

    cart.add('p1', 2)
    cart.add('p2', 1)

    expect(cart.items).toEqual([
      { productId: 'p1', qty: 2 },
      { productId: 'p2', qty: 1 },
    ])
    expect(cart.count).toBe(3)
    expect(stored()).toEqual(cart.items)
  })

  it('同一商品再加入：合併數量，不新增一列', () => {
    const cart = createStore()

    cart.add('p1', 2)
    cart.add('p1', 3)

    expect(cart.items).toEqual([{ productId: 'p1', qty: 5 }])
  })

  it(`合併後最多 ${MAX_QTY} 件`, () => {
    const cart = createStore()

    cart.add('p1', 90)
    cart.add('p1', 50)
    cart.add('p2', 500)

    expect(cart.items).toEqual([
      { productId: 'p1', qty: MAX_QTY },
      { productId: 'p2', qty: MAX_QTY },
    ])
  })

  it.each([0, -3, 1.9, Number.NaN])('數量 %s：至少加 1，取整數', (qty) => {
    const cart = createStore()

    cart.add('p1', qty)

    expect(cart.items[0]?.qty).toBe(Number.isNaN(qty) ? 1 : Math.max(1, Math.floor(qty)))
  })
})

describe('useCartStore 讀取', () => {
  it('重新整理後還在', () => {
    localStorage.setItem(CART_KEY, JSON.stringify([{ productId: 'p1', qty: 4 }]))

    const cart = createStore()

    expect(cart.items).toEqual([{ productId: 'p1', qty: 4 }])
    expect(cart.count).toBe(4)
  })

  it.each([
    ['不是 JSON', '{oops'],
    ['不是陣列', '{"productId":"p1"}'],
    ['null', 'null'],
  ])('內容壞掉（%s）：當成空的購物車', (_, raw) => {
    localStorage.setItem(CART_KEY, raw)

    expect(createStore().items).toEqual([])
  })

  it('不合法的項目丟掉、重複的合併、數量限制在範圍內', () => {
    localStorage.setItem(
      CART_KEY,
      JSON.stringify([
        { productId: 'p1', qty: 2 },
        { productId: 'p1', qty: 3 },
        { productId: 'p2', qty: 0 },
        { productId: 'p3', qty: 'x' },
        { qty: 1 },
        null,
        { productId: 'p4', qty: 1000 },
      ]),
    )

    expect(createStore().items).toEqual([
      { productId: 'p1', qty: 5 },
      { productId: 'p4', qty: MAX_QTY },
    ])
  })
})

describe('localStorage 不能用', () => {
  it('讀取丟出例外：空的購物車，仍然可以加入', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })

    const cart = createStore()
    cart.add('p1', 1)

    expect(cart.items).toEqual([{ productId: 'p1', qty: 1 }])
  })

  it('寫入丟出例外：這次使用不受影響（只是不保存）', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('quota exceeded')
    })
    const cart = createStore()

    expect(() => cart.add('p1', 2)).not.toThrow()
    expect(cart.count).toBe(2)
  })
})
