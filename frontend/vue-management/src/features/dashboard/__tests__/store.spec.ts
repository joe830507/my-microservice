import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useDashboardCountsStore } from '../store'
import { useAuthStore } from '@/features/auth/store'

beforeEach(() => {
  setActivePinia(createPinia())
  vi.useFakeTimers()
})
afterEach(() => vi.useRealTimers())

it('shares pending requests and caches successful counts for thirty seconds', async () => {
  const store = useDashboardCountsStore()
  const loader = vi.fn().mockResolvedValueOnce(7).mockResolvedValue(9)
  expect(await Promise.all([store.get('paid', loader), store.get('paid', loader)])).toEqual([7, 7])
  expect(loader).toHaveBeenCalledTimes(1)
  vi.advanceTimersByTime(29_999)
  expect(await store.get('paid', loader)).toBe(7)
  vi.advanceTimersByTime(1)
  expect(await store.get('paid', loader)).toBe(9)
  expect(loader).toHaveBeenCalledTimes(2)
})

it('does not cache failed requests', async () => {
  const store = useDashboardCountsStore()
  const loader = vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValue(2)
  await expect(store.get('users', loader)).rejects.toThrow('offline')
  expect(await store.get('users', loader)).toBe(2)
})

it('discards results from the previous account and clears cached values on logout', async () => {
  const store = useDashboardCountsStore()
  const auth = useAuthStore()
  let finish!: (value: number) => void
  const old = store.get(
    'users',
    () =>
      new Promise<number>((resolve) => {
        finish = resolve
      }),
  )
  auth.user = {
    id: 'new-admin',
    email: 'admin@example.com',
    displayName: 'Admin',
    status: 'ACTIVE',
    roles: ['ADMIN'],
    createdAt: 'now',
    version: 0,
  }
  const loader = vi.fn().mockResolvedValue(9)
  expect(await store.get('users', loader)).toBe(9)
  finish(3)
  await old
  expect(await store.get('users', loader)).toBe(9)
  auth.user = null
  await store.get('users', loader)
  expect(loader).toHaveBeenCalledTimes(2)
})
