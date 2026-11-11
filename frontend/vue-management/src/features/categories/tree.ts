import type { Category, CategoryNode } from './types'

// 依 parentId 把平面列表組成樹；同一層維持後端的順序（sortOrder、name）
// 找不到父分類的（理論上不會發生）當成頂層，不讓資料從畫面上消失
export function buildTree(categories: Category[]): CategoryNode[] {
  const ids = new Set(categories.map((category) => category.id))
  const childrenOf = new Map<string, Category[]>()
  const roots: Category[] = []

  for (const category of categories) {
    if (category.parentId && ids.has(category.parentId)) {
      const siblings = childrenOf.get(category.parentId) ?? []
      siblings.push(category)
      childrenOf.set(category.parentId, siblings)
    } else {
      roots.push(category)
    }
  }

  // 用 visited 防止資料有循環時無限遞迴
  const visited = new Set<string>()
  function toNode(category: Category): CategoryNode {
    visited.add(category.id)
    const children = (childrenOf.get(category.id) ?? [])
      .filter((child) => !visited.has(child.id))
      .map(toNode)
    return children.length > 0 ? { ...category, children } : { ...category }
  }

  return roots.map(toNode)
}

// 某個分類自己加上所有子孫的 id；修改時這些都不能當成新的上層分類
export function subtreeIds(categories: Category[], rootId: string): Set<string> {
  const result = new Set<string>([rootId])
  let added = true
  while (added) {
    added = false
    for (const category of categories) {
      if (category.parentId && result.has(category.parentId) && !result.has(category.id)) {
        result.add(category.id)
        added = true
      }
    }
  }
  return result
}
