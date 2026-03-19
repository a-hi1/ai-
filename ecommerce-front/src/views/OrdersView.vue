<template>
  <section class="orders-page">
    <header class="orders-hero">
      <div>
        <p class="eyebrow">订单时间线</p>
        <h1>订单中心</h1>
      </div>
      <div class="summary-grid" v-if="currentUser">
        <article class="summary-card">
          <span>订单数</span>
          <strong>{{ orders.length }}</strong>
        </article>
        <article class="summary-card success">
          <span>已支付</span>
          <strong>{{ paidCount }}</strong>
        </article>
        <article class="summary-card warm">
          <span>累计金额</span>
          <strong>{{ totalSpent }} 元</strong>
        </article>
      </div>
    </header>

    <div v-if="!currentUser" class="empty-state">
      <h2>请先登录</h2>
      <RouterLink class="action-link" to="/login">前往登录</RouterLink>
    </div>

    <div v-else class="orders-panel">
      <div class="toolbar">
        <button class="refresh" :disabled="loading" @click="loadOrders">{{ loading ? '刷新中...' : '刷新订单' }}</button>
      </div>

      <div class="status-filter">
        <button
          v-for="option in filterOptions"
          :key="option.value"
          class="filter-chip"
          :class="{ active: activeFilter === option.value }"
          type="button"
          @click="selectFilter(option.value)"
        >
          {{ option.label }}
        </button>
      </div>

      <div v-if="errorMessage" class="notice error">{{ errorMessage }}</div>
      <div v-else-if="actionMessage" :class="['notice', actionTone]">{{ actionMessage }}</div>
      <div v-else-if="!filteredOrders.length && !loading" class="notice">当前筛选条件下暂无订单。去聊天页加入商品并支付后，这里会自动展示。</div>

      <div v-else class="orders-list">
        <article v-for="order in filteredOrders" :key="order.id" class="order-card">
          <div class="order-top">
            <div>
              <span class="order-label">订单编号</span>
              <strong class="mono">{{ order.id }}</strong>
            </div>
            <div class="order-actions">
              <span :class="['status', order.status.toLowerCase()]">{{ formatStatus(order.status) }}</span>
              <RouterLink class="detail-link" :to="`/orders/${order.id}`">查看详情</RouterLink>
              <button class="reorder-btn" type="button" :disabled="rebuyingOrderId === order.id" @click="rebuyOrder(order)">
                {{ rebuyingOrderId === order.id ? '加入中...' : '再买一次' }}
              </button>
            </div>
          </div>

          <div class="order-grid">
            <div>
              <span class="order-label">创建时间</span>
              <strong>{{ formatTime(order.createdAt) }}</strong>
            </div>
            <div>
              <span class="order-label">金额</span>
              <strong>{{ Number(order.totalAmount).toFixed(2) }} 元</strong>
            </div>
            <div>
              <span class="order-label">商品数量</span>
              <strong>{{ order.itemCount }}</strong>
            </div>
            <div>
              <span class="order-label">用户 ID</span>
              <strong class="mono compact">{{ order.userId }}</strong>
            </div>
          </div>

          <div class="goods-brief">
            <div class="goods-brief-head">
              <span class="order-label">购买内容</span>
              <strong>共 {{ order.itemCount }} 件</strong>
            </div>
            <div class="goods-list">
              <article
                v-for="item in previewItemsOf(order)"
                :key="`${order.id}-${item.productId}`"
                class="goods-pill clickable"
                role="button"
                tabindex="0"
                @click="openPreviewItem(item)"
                @keyup.enter="openPreviewItem(item)"
                @keyup.space.prevent="openPreviewItem(item)"
              >
                <img :src="item.imageUrl || fallbackImage(item.productId)" :alt="item.productName" loading="lazy" decoding="async" />
                <div>
                  <strong>{{ item.productName }}</strong>
                  <span>x{{ item.quantity }}</span>
                </div>
              </article>
            </div>
          </div>
        </article>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import { useCart } from '../composables/useCart'
import { useAuth } from '../composables/useAuth'
import { api, type OrderDto, type OrderPreviewItemDto } from '../services/api'

const { currentUser } = useAuth()
const { refreshCart } = useCart()
const route = useRoute()
const router = useRouter()

const orders = ref<OrderDto[]>([])
const loading = ref(false)
const errorMessage = ref('')
const actionMessage = ref('')
const actionTone = ref<'success' | 'error'>('success')
const rebuyingOrderId = ref('')
let pollTimer: number | undefined
const filterOptions = [
  { label: '全部', value: 'ALL' },
  { label: '待支付', value: 'CREATED' },
  { label: '已支付', value: 'PAID' },
  { label: '已取消', value: 'CANCELLED' }
]

const paidCount = computed(() => orders.value.filter(order => order.status === 'PAID').length)
const totalSpent = computed(() => orders.value.reduce((sum, order) => sum + Number(order.totalAmount), 0).toFixed(2))
const activeFilter = computed(() => {
  const raw = String(route.query.status || 'ALL').toUpperCase()
  return filterOptions.some(option => option.value === raw) ? raw : 'ALL'
})
const filteredOrders = computed(() => {
  if (activeFilter.value === 'ALL') return orders.value
  return orders.value.filter(order => order.status === activeFilter.value)
})
const formatTime = (value: string) => {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString()
}

const formatStatus = (value: string) => {
  if (value === 'PAID') return '已支付'
  if (value === 'CREATED') return '待支付'
  if (value === 'CANCELLED') return '已取消'
  return value
}

const fallbackImage = (seed: string) => `https://picsum.photos/72/72?random=${seed}`

const previewItemsOf = (order: OrderDto) => order.previewItems ?? []

const cachePreviewItem = (item: OrderPreviewItemDto) => {
  try {
    window.sessionStorage.setItem(`product-preview:${item.productId}`, JSON.stringify({
      id: item.productId,
      name: item.productName,
      description: '',
      price: 0,
      imageUrl: item.imageUrl || fallbackImage(item.productId),
      tags: ''
    }))
  } catch {
    // ignore session storage failures
  }
}

const openPreviewItem = (item: OrderPreviewItemDto) => {
  cachePreviewItem(item)
  router.push(`/products/${item.productId}`)
}

const selectFilter = (status: string) => {
  router.replace({
    path: '/orders',
    query: status === 'ALL' ? {} : { status }
  })
}

const loadOrders = async () => {
  if (!currentUser.value) return
  loading.value = true
  errorMessage.value = ''
  try {
    orders.value = await api.getOrders(currentUser.value.id)
  } catch {
    errorMessage.value = '订单加载失败，请确认后端服务仍在运行。'
  } finally {
    loading.value = false
    restartPolling()
  }
}

const rebuyOrder = async (order: OrderDto) => {
  if (!currentUser.value) {
    router.push('/login')
    return
  }

  rebuyingOrderId.value = order.id
  actionMessage.value = ''
  try {
    const detail = await api.getOrderDetail(order.id)
    for (const item of detail.items) {
      await api.addCartItem(currentUser.value.id, item.productId, item.quantity)
    }
    await refreshCart()
    await router.push('/cart')
  } catch {
    actionTone.value = 'error'
    actionMessage.value = '再次购买失败，请确认商品仍然存在并重试。'
  } finally {
    rebuyingOrderId.value = ''
  }
}

const stopPolling = () => {
  if (pollTimer) {
    window.clearInterval(pollTimer)
    pollTimer = undefined
  }
}

const restartPolling = () => {
  stopPolling()
  if (!orders.value.some(order => order.status === 'CREATED')) {
    return
  }

  pollTimer = window.setInterval(() => {
    if (!loading.value) {
      void loadOrders()
    }
  }, 5000)
}

onMounted(() => {
  void loadOrders()
})

onBeforeUnmount(() => {
  stopPolling()
})
</script>

<style scoped>
.orders-page {
  max-width: 1160px;
  margin: 0 auto;
  display: grid;
  gap: 24px;
}

.orders-hero {
  display: grid;
  grid-template-columns: minmax(0, 1.3fr) minmax(260px, 0.9fr);
  gap: 20px;
  padding: 28px;
  border-radius: 28px;
  background:
    linear-gradient(135deg, rgba(255,255,255,0.58) 0%, rgba(255,255,255,0.22) 100%),
    radial-gradient(circle at top right, rgba(255,77,77,0.12), transparent 30%),
    radial-gradient(circle at bottom left, rgba(0,196,180,0.12), transparent 26%);
  color: var(--text-main);
  box-shadow: var(--shadow-lg);
  border: 1px solid rgba(255,255,255,0.74);
  backdrop-filter: blur(14px);
}

.eyebrow {
  margin: 0 0 8px;
  font-size: 12px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--brand-deep);
}

h1 {
  margin: 0 0 10px;
  font-size: 38px;
}

.copy {
  margin: 0;
  color: var(--text-soft);
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}

.summary-card {
  display: grid;
  gap: 8px;
  padding: 18px;
  border-radius: 20px;
  background: rgba(255,255,255,0.74);
  border: 1px solid rgba(255,255,255,0.8);
  box-shadow: var(--shadow-soft);
}

.summary-card.success {
  background: rgba(88, 181, 124, 0.16);
}

.summary-card.warm {
  background: rgba(255, 188, 92, 0.16);
}

.summary-card span {
  font-size: 13px;
}

.summary-card strong {
  font-size: 24px;
}

.orders-panel,
.empty-state {
  padding: 24px;
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.82);
  border: 1px solid rgba(255,255,255,0.78);
  box-shadow: var(--shadow-lg);
  backdrop-filter: blur(14px);
}

.empty-state {
  text-align: center;
}

.action-link {
  display: inline-block;
  margin-top: 12px;
  padding: 10px 16px;
  border-radius: 999px;
  text-decoration: none;
  color: #fff;
  background: #1f6f5c;
}

.toolbar {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: center;
  margin-bottom: 18px;
}

.toolbar p {
  margin: 0;
  color: #6b6258;
}

.status-filter {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 18px;
}

.filter-chip {
  border: 1px solid #ddd0bd;
  border-radius: 999px;
  padding: 8px 14px;
  background: #fff8ef;
  color: #6b6258;
  cursor: pointer;
}

.filter-chip.active {
  border-color: transparent;
  background: linear-gradient(135deg, rgba(255,77,77,0.96) 0%, rgba(255,111,105,0.88) 100%);
  color: #fff;
}

.refresh {
  border: none;
  border-radius: 999px;
  background: linear-gradient(135deg, rgba(255,77,77,0.96) 0%, rgba(255,111,105,0.88) 100%);
  color: #fff;
  padding: 10px 16px;
  cursor: pointer;
}

.refresh:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.notice {
  padding: 16px;
  border-radius: 18px;
  background: #f7f2ea;
  color: #6b6258;
}

.notice.error {
  background: #fff1ec;
  color: #a53f2d;
}

.notice.success {
  background: #e5f6ef;
  color: #106b53;
}

.orders-list {
  display: grid;
  gap: 14px;
}

.order-card {
  display: grid;
  gap: 16px;
  padding: 18px;
  border-radius: 20px;
  background: linear-gradient(180deg, rgba(255,255,255,0.96) 0%, rgba(255,248,244,0.92) 100%);
  border: 1px solid rgba(255,255,255,0.82);
  box-shadow: var(--shadow-soft);
}

.order-top {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: center;
}

.order-actions {
  display: inline-flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
}

.detail-link,
.reorder-btn {
  color: #1f6f5c;
  text-decoration: none;
  font-size: 13px;
  min-height: 36px;
  padding: 0 14px;
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.detail-link {
  border: 1px solid #d7cab9;
  background: #fff;
}

.reorder-btn {
  border: none;
  background: linear-gradient(135deg, rgba(255,77,77,0.96) 0%, rgba(255,111,105,0.88) 100%);
  color: #fff;
  cursor: pointer;
}

.reorder-btn:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.order-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
}

.order-label {
  display: block;
  margin-bottom: 6px;
  color: #8b7a68;
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.06em;
}

.goods-brief {
  display: grid;
  gap: 12px;
  padding: 14px;
  border-radius: 18px;
  background: #fffaf4;
  border: 1px solid #efe2cf;
}

.goods-brief-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
}

.goods-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 10px;
}

.goods-pill {
  display: grid;
  grid-template-columns: 56px minmax(0, 1fr);
  gap: 10px;
  align-items: center;
  padding: 10px;
  border-radius: 14px;
  background: #fff;
  border: 1px solid #eadfce;
}

.goods-pill.clickable {
  cursor: pointer;
  transition: transform 180ms ease, box-shadow 180ms ease, border-color 180ms ease;
}

.goods-pill.clickable:hover,
.goods-pill.clickable:focus-visible {
  transform: translateY(-2px);
  border-color: #d8c2a2;
  box-shadow: 0 12px 22px rgba(73, 52, 24, 0.08);
  outline: none;
}

.goods-pill img {
  width: 56px;
  height: 56px;
  border-radius: 12px;
  object-fit: cover;
}

.goods-pill div {
  display: grid;
  gap: 4px;
}

.goods-pill span {
  color: #7d6c5c;
  font-size: 13px;
}

.status {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 8px 12px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
}

.status.created {
  background: #fff1cf;
  color: #946200;
}

.status.paid {
  background: #dff4ea;
  color: #0f6f53;
}

.mono {
  font-family: "IBM Plex Mono", "Courier New", monospace;
  word-break: break-all;
}

.compact {
  font-size: 12px;
}

@media (max-width: 900px) {
  .orders-hero,
  .summary-grid,
  .order-grid {
    grid-template-columns: 1fr;
  }

  .toolbar,
  .order-top,
  .goods-brief-head {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>