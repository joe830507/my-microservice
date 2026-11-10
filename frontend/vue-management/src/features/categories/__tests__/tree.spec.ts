import { describe, expect, it } from 'vitest'
import { buildTree, subtreeIds } from '../tree'
import { allCategories, category } from './fixtures'

describe('buildTree', () => {
  it('依 parentId 組成樹，同一層維持原本的順序', () => {
    const tree = buildTree(allCategories)

    expect(tree.map((node) => node.name)).toEqual(['Bags', 'Shoes'])
    expect(tree[1]?.children?.map((node) => node.name)).toEqual(['Boots'])
  })

  it('沒有子分類的節點不放 children', () => {
    const tree = buildTree(allCategories)

    expect(tree[0]).not.toHaveProperty('children')
  })

  it('找不到父分類：當成頂層，不會消失', () => {
    const orphan = category({ id: 'orphan', name: 'Orphan', parentId: 'missing' })

    expect(buildTree([orphan]).map((node) => node.id)).toEqual(['orphan'])
  })

  it('資料有循環也不會無限遞迴', () => {
    const a = category({ id: 'a', name: 'A', parentId: 'b' })
    const b = category({ id: 'b', name: 'B', parentId: 'a' })

    // 兩個都有父分類，沒有頂層：不會出現在樹上，但也不會卡住
    expect(buildTree([a, b])).toEqual([])
  })
})

describe('subtreeIds', () => {
  it('包含自己與所有子孫，不包含兄弟', () => {
    const root = category({ id: 'root', name: 'Root' })
    const child = category({ id: 'child', name: 'Child', parentId: 'root' })
    const grandchild = category({ id: 'grandchild', name: 'Grandchild', parentId: 'child' })
    const sibling = category({ id: 'sibling', name: 'Sibling' })

    const ids = subtreeIds([grandchild, child, root, sibling], 'root')

    expect([...ids].sort()).toEqual(['child', 'grandchild', 'root'])
  })
})
