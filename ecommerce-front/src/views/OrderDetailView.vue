<template>
  <section class="detail-page">
    <header class="detail-hero">
      <div>
        <p class="eyebrow">订单详情</p>
        <h1>订单详情</h1>
      </div>
      <div class="hero-actions">
        <button v-if="order" class="ghost-action" type="button" :disabled="rebuying" @click="rebuyOrder">
          {{ rebuying ? '加入中...' : '再买一次' }}
        </button>
        <RouterLink class="back-link" to="/orders">返回订单列表</RouterLink>
      </div>
    </header>

    <div v-if="loading" class="panel">正在加载订单详情...</div>
    <div v-else-if="errorMessage" class="panel error">{{ errorMessage }}</div>
    <div v-else-if="order" class="grid">
      <section class="panel summary">
        <div>
          <span class="label">状态</span>
          <strong>{{ formatStatus(order.status) }}</strong>
        </div>
        <div>
          <span class="label">总金额</span>
          <strong>{{ Number(order.totalAmount).toFixed(2) }} 元</strong>
        </div>
        <div>
          <span class="label">支付方式</span>
          <strong>{{ formatPaymentMethod(order.paymentMethod) }}</strong>
        </div>
        <div>
          <span class="label">交易号</span>
          <strong>{{ order.gatewayTradeNo || '-' }}</strong>
        </div>
        <div v-if="order.status === 'CREATED'" class="pay-mode-group">
          <button class="pay-mode" :class="{ active: paymentMode === 'alipay' }" type="button" @click="paymentMode = 'alipay'">
            <strong>支付宝沙箱</strong>
          </button>
          <button class="pay-mode" :class="{ active: paymentMode === 'demo' }" type="button" @click="paymentMode = 'demo'">
            <strong>站内快速演示</strong>
          </button>
        </div>
        <div v-if="order.status === 'CREATED'" class="summary-actions">
          <button class="sync-btn" :disabled="syncing || paying" @click="continuePayment">
            {{ paying ? '跳转中...' : '继续支付' }}
          </button>
          <button class="ghost-btn" :disabled="syncing || paying" @click="syncPaymentStatus">
            {{ syncing ? '同步中...' : '同步支付状态' }}
          </button>
        </div>
      </section>

      <section class="panel items">
        <h2>商品清单</h2>
        <article
          v-for="item in order.items"
          :key="item.id"
          class="item-row clickable"
          role="button"
          tabindex="0"
          @click="openOrderItem(item)"
          @keyup.enter="openOrderItem(item)"
          @keyup.space.prevent="openOrderItem(item)"
        >
          <img :src="item.imageUrl || fallbackImage(item.productId)" :alt="item.productName" loading="lazy" decoding="async" />
          <div>
            <strong>{{ item.productName }}</strong>
          </div>
          <div class="price-col">
            <span>x{{ item.quantity }}</span>
            <strong>{{ Number(item.lineTotal).toFixed(2) }} 元</strong>
          </div>
        </article>
      </section>
    </div>
  </section>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import { useAuth } from '../composables/useAuth'
import { useCart } from '../composables/useCart'
import { api, type OrderDetailDto, type OrderItemDto } from '../services/api'

const route = useRoute()
const router = useRouter()
const { currentUser } = useAuth()
const { refreshCart } = useCart()
const order = ref<OrderDetailDto | null>(null)
const loading = ref(false)
const errorMessage = ref('')
const syncing = ref(false)
const paying = ref(false)
const rebuying = ref(false)
const paymentMode = ref<'demo' | 'alipay'>('alipay')
let pollTimer: number | undefined

const fallbackImage = (seed: string) => `https://picsum.photos/80/80?random=${seed}`

const cacheOrderItemPreview = (item: OrderItemDto) => {
  try {
    window.sessionStorage.setItem(`product-preview:${item.productId}`, JSON.stringify({
      id: item.productId,
      name: item.productName,
      description: '',
      price: Number(item.unitPrice),
      imageUrl: item.imageUrl || fallbackImage(item.productId),
      tags: ''
    }))
  } catch {
    // ignore session storage failures
  }
}

const openOrderItem = (item: OrderItemDto) => {
  cacheOrderItemPreview(item)
  router.push(`/products/${item.productId}`)
}

const formatStatus = (value: string) => {
  if (value === 'PAID') return '已支付'
  if (value === 'CREATED') return '待支付'
  if (value === 'CANCELLED') return '已取消'
  return value
}

const formatPaymentMethod = (value?: string) => {
  if (value === 'ALIPAY_SANDBOX') return '支付宝沙箱'
  if (value === 'ALIPAY_DEMO') return '站内快速演示'
  if (value === 'ALIPAY') return '支付宝'
  return value || '支付宝'
}

const stopPolling = () => {
  if (pollTimer) {
    window.clearInterval(pollTimer)
    pollTimer = undefined
  }
}

const restartPolling = () => {
  stopPolling()
  if (order.value?.status !== 'CREATED') {
    return
  }
  pollTimer = window.setInterval(() => {
    if (!loading.value && !syncing.value) {
      void loadOrder(true)
    }
  }, 5000)
}

const loadOrder = async (silent = false) => {
  const id = String(route.params.id || '')
  if (!id) return
  if (!silent) {
    loading.value = true
  }
  try {
    order.value = await api.getOrderDetail(id)
    errorMessage.value = ''
  } catch {
    errorMessage.value = '订单详情加载失败。'
  } finally {
    if (!silent) {
      loading.value = false
    }
    restartPolling()
  }
}

const syncPaymentStatus = async () => {
  if (!order.value) return
  syncing.value = true
  try {
    await api.queryPayment(order.value.id)
    await loadOrder(true)
  } catch {
    errorMessage.value = '订单状态同步失败，请稍后重试。'
  } finally {
    syncing.value = false
  }
}

const continuePayment = async () => {
  if (!order.value) return

  paying.value = true
  errorMessage.value = ''
  try {
    const paymentSession = await api.createPayment(order.value.id, paymentMode.value)
    if (paymentSession.mode === 'demo') {
      router.push(`/payment/callback?orderId=${encodeURIComponent(order.value.id)}&status=PAID&tradeNo=${encodeURIComponent(paymentSession.gatewayTradeNo)}&provider=ALIPAY_DEMO`)
      return
    }

    const paymentWindow = window.open(paymentSession.paymentUrl, '_blank')
    if (paymentWindow) {
      paymentWindow.focus()
    } else {
      window.location.href = paymentSession.paymentUrl
      return
    }

    router.push(`/payment/callback?orderId=${encodeURIComponent(order.value.id)}&pending=1`)
  } catch (error) {
    const errorText = error instanceof Error ? error.message : ''
    errorMessage.value = errorText.includes('支付宝沙箱未配置完成')
      ? '当前环境还没有完成支付宝沙箱配置，请先补全后端 appId、私钥和公钥。'
      : '继续支付失败，请稍后重试。'
  } finally {
    paying.value = false
  }
}

const rebuyOrder = async () => {
  if (!order.value) return
  if (!currentUser.value) {
    router.push('/login')
    return
  }

  rebuying.value = true
  errorMessage.value = ''
  try {
    for (const item of order.value.items) {
      await api.addCartItem(currentUser.value.id, item.productId, item.quantity)
    }
    await refreshCart()
    await router.push('/cart')
  } catch {
    errorMessage.value = '再次购买失败，请确认商品仍然存在并重试。'
  } finally {
    rebuying.value = false
  }
}

onMounted(async () => {
  await loadOrder()
})

onBeforeUnmount(() => {
  stopPolling()
})
</script>

<style scoped>
.detail-page { max-width: 1100px; margin: 0 auto; display: grid; gap: 18px; }
.detail-hero { display: flex; justify-content: space-between; gap: 14px; align-items: center; padding: 24px; border-radius: 24px; background: linear-gradient(135deg,#16334e 0%,#24556a 52%,#d58a3a 100%); color: #fff8ef; }
.hero-actions { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
.eyebrow { margin: 0 0 8px; font-size: 12px; letter-spacing: .16em; text-transform: uppercase; color: #ffd59e; }
h1 { margin: 0 0 8px; }
.back-link { color: #fff8ef; text-decoration: none; }
.grid { display: grid; grid-template-columns: 320px 1fr; gap: 18px; }
.panel { padding: 22px; border-radius: 22px; background: rgba(255,255,255,.88); border: 1px solid #eadfce; }
.panel.error { background: #fff1ec; color: #a53f2d; }
.summary { display: grid; gap: 14px; }
.pending-note { padding: 12px 14px; border-radius: 14px; background: #fff4df; color: #8a5a24; line-height: 1.5; }
.pay-mode-group { display: grid; gap: 10px; }
.pay-mode { display: grid; gap: 4px; padding: 14px; border-radius: 16px; border: 1px solid #e2d6c7; background: #fff9f2; text-align: left; cursor: pointer; }
.pay-mode strong { font-size: 15px; }
.pay-mode span { color: #7d6c5c; font-size: 12px; line-height: 1.5; }
.pay-mode.active { border-color: #1f6f5c; background: #edf7f2; }
.summary-actions { display: flex; gap: 10px; flex-wrap: wrap; }
.sync-btn, .ghost-btn, .ghost-action { border-radius: 999px; padding: 10px 16px; cursor: pointer; }
.sync-btn { border: none; background: #1f6f5c; color: #fff; }
.ghost-btn, .ghost-action { border: 1px solid rgba(255,255,255,.22); background: rgba(255,255,255,.12); color: inherit; }
.ghost-btn { border-color: #d8cbb7; background: #fff; color: #5b5249; }
.sync-btn:disabled, .ghost-btn:disabled, .ghost-action:disabled { opacity: .7; cursor: not-allowed; }
.label { display: block; margin-bottom: 6px; font-size: 12px; color: #776a5c; text-transform: uppercase; }
.items { display: grid; gap: 12px; }
.items h2 { margin: 0 0 6px; }
.item-row { display: grid; grid-template-columns: 80px 1fr auto; gap: 14px; align-items: center; padding: 14px; border-radius: 16px; background: #fff8ef; }
.item-row.clickable { cursor: pointer; transition: transform 180ms ease, box-shadow 180ms ease, border-color 180ms ease; }
.item-row.clickable:hover, .item-row.clickable:focus-visible { transform: translateY(-2px); border-color: #dbc7ad; box-shadow: 0 12px 22px rgba(73, 52, 24, 0.08); outline: none; }
.item-row img { width: 80px; height: 80px; border-radius: 14px; object-fit: cover; }
.price-col { display: grid; justify-items: end; gap: 6px; }
@media (max-width: 900px) { .grid { grid-template-columns: 1fr; } .detail-hero, .hero-actions, .summary-actions { flex-direction: column; align-items: flex-start; } }
</style>
