import { computed, type ComputedRef } from 'vue'
import { useRoute, useRouter, type LocationQuery, type LocationQueryRaw } from 'vue-router'

// 把列表條件放在 URL query：重新整理、上一頁、分享連結都能保留篩選與分頁
// state 從目前的 URL 解析出來；update 改的是 URL，畫面再跟著 URL 變化重新讀取
export function useRouteQuery<T>(
  parse: (query: LocationQuery) => T,
  serialize: (state: T) => LocationQueryRaw,
): { state: ComputedRef<T>; update: (next: T) => Promise<unknown> } {
  const route = useRoute()
  const router = useRouter()

  const state = computed(() => parse(route.query))

  function update(next: T): Promise<unknown> {
    return router.push({ query: serialize(next) })
  }

  return { state, update }
}
