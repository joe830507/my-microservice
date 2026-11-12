import { createOrder, ensureCustomer, login, post } from './support/api'
import { readE2eData } from './support/data'
import { test } from './support/test'

// E6 event-only paths that are not part of the browser purchase-flow scenario.
test('cancellation and failed payment enqueue their notification emails', async ({ request }) => {
  const { product } = readE2eData()
  const email = `e2e-notification-${Date.now()}@example.com`
  const password = 'password123'
  await ensureCustomer(request, email, password)
  const customer = await login(request, email, password)

  const failedPaymentOrder = await createOrder(customer, product.id, 1)
  const payment = await post<{ id: string }>(customer, '/payments', { orderId: failedPaymentOrder.id })
  await post(customer, `/payments/${payment.id}/mock-callback`, { result: 'FAILED' })

  const cancelledOrder = await createOrder(customer, product.id, 1)
  await post(customer, `/orders/${cancelledOrder.id}/cancel`)
})
