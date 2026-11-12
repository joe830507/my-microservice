import { defineStore } from 'pinia'
import { watch } from 'vue'
import { useAuthStore } from '@/features/auth/store'

export const useDashboardCountsStore = defineStore('dashboard-counts', () => {
  const auth = useAuthStore()
  const cache = new Map<string, { value: number; expires: number }>()
  const pending = new Map<string, Promise<number>>()
  let generation = 0
  watch(
    () => auth.user?.id,
    () => {
      generation++
      cache.clear()
      pending.clear()
    },
    { flush: 'sync' },
  )

  async function get(key: string, loader: () => Promise<number>): Promise<number> {
    const cached = cache.get(key)
    if (cached && cached.expires > Date.now()) return cached.value
    const loading = pending.get(key)
    if (loading) return loading
    const started = generation
    const request = loader()
      .then((value) => {
        if (generation === started) cache.set(key, { value, expires: Date.now() + 30_000 })
        return value
      })
      .finally(() => {
        if (generation === started) pending.delete(key)
      })
    pending.set(key, request)
    return request
  }
  return { get }
})
