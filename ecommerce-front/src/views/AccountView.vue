<template>
  <section class="account-page">
    <div v-if="!currentUser" class="empty-state">
      <h2>请先登录</h2>
      <p>登录后可查看订单、会话、购物车和最近浏览。</p>
      <RouterLink class="primary-link" to="/login">前往登录</RouterLink>
    </div>

    <div v-else class="account-home">
      <header class="profile-banner">
        <div class="profile-main">
          <div class="avatar">{{ userInitial }}</div>
          <div class="profile-copy">
            <p class="eyebrow">我的主页</p>
            <h1>{{ currentUser.displayName || '欢迎回来' }}</h1>
            <p class="subtitle">{{ currentUser.email }}</p>
            <p class="description">把订单、导购记录、购物车和常用入口收进一个首页，操作体验更接近电商 App 的“我的”。</p>
          </div>
        </div>

        <div class="profile-side">
          <div class="member-card">
            <span class="member-label">当前身份</span>
            <strong>{{ formatRole(currentUser.role) }}</strong>
            <small>{{ currentUser.city || '未填写常用城市' }}</small>
          </div>
          <div class="banner-actions">
            <RouterLink to="/chat">继续导购</RouterLink>
            <RouterLink to="/orders">全部订单</RouterLink>
            <RouterLink to="/cart">去购物车</RouterLink>
          </div>
        </div>
      </header>

      <section class="asset-strip">
        <article class="asset-card">
          <span>购物车</span>
          <strong>{{ itemCount }}</strong>
          <small>待结算商品</small>
        </article>
        <article class="asset-card warm">
          <span>全部订单</span>
          <strong>{{ orders.length }}</strong>
          <small>近期待处理 {{ pendingOrdersCount }}</small>
        </article>
        <article class="asset-card success">
          <span>已支付</span>
          <strong>{{ paidOrdersCount }}</strong>
          <small>累计成交订单</small>
        </article>
        <article class="asset-card accent">
          <span>导购会话</span>
          <strong>{{ sessions.length }}</strong>
          <small>最近浏览 {{ recentViews.length }}</small>
        </article>
      </section>

      <div class="overview-grid">
        <section class="panel order-panel">
          <div class="panel-head">
            <div>
              <h2>我的订单</h2>
              <p>按状态快速进入对应订单列表。</p>
            </div>
            <RouterLink to="/orders">查看全部</RouterLink>
          </div>
          <div class="order-entry-grid">
            <RouterLink
              v-for="entry in orderEntryList"
              :key="entry.label"
              class="order-entry"
              :class="entry.tone"
              :to="entry.to"
            >
              <strong>{{ entry.value }}</strong>
              <span>{{ entry.label }}</span>
              <small>{{ entry.hint }}</small>
            </RouterLink>
          </div>
        </section>

        <section class="panel tools-panel">
          <div class="panel-head">
            <div>
              <h2>常用功能</h2>
              <p>保留高频入口，减少层级跳转。</p>
            </div>
          </div>
          <div class="tool-grid">
            <button
              v-for="action in quickActions"
              :key="action.title"
              class="tool-item"
              type="button"
              @click="navigateAction(action)"
            >
              <span class="tool-badge">{{ action.badge }}</span>
              <strong>{{ action.title }}</strong>
              <small>{{ action.subtitle }}</small>
            </button>
          </div>
        </section>
      </div>

      <div class="content-grid">
        <section class="panel" aria-labelledby="recent-orders-title">
          <div class="panel-head">
            <div>
              <h2 id="recent-orders-title">最近订单</h2>
              <p>默认收起，按需展开查看最近成交记录。</p>
            </div>
            <div class="panel-actions">
              <RouterLink to="/orders">全部订单</RouterLink>
              <button class="toggle-btn" type="button" @click="showRecentOrders = !showRecentOrders">
                {{ showRecentOrders ? '收起' : '展开' }}
              </button>
            </div>
          </div>
          <div v-if="!showRecentOrders" class="fold-placeholder">点击右上角按钮后展示最近订单。</div>
          <div v-else-if="!orders.length" class="muted">暂无订单记录。</div>
          <div v-else class="list">
            <RouterLink v-for="order in recentOrders" :key="order.id" class="row-link" :to="`/orders/${order.id}`">
              <div>
                <strong>{{ formatStatus(order.status) }}</strong>
                <span>{{ formatTime(order.createdAt) }}</span>
              </div>
              <strong>{{ formatCurrency(Number(order.totalAmount)) }}</strong>
            </RouterLink>
          </div>
        </section>

        <section class="panel" aria-labelledby="recent-sessions-title">
          <div class="panel-head">
            <div>
              <h2 id="recent-sessions-title">最近会话</h2>
              <p>默认收起，按需展开继续导购上下文。</p>
            </div>
            <div class="panel-actions">
              <RouterLink to="/history">查看全部</RouterLink>
              <button class="toggle-btn" type="button" @click="showRecentSessions = !showRecentSessions">
                {{ showRecentSessions ? '收起' : '展开' }}
              </button>
            </div>
          </div>
          <div v-if="!showRecentSessions" class="fold-placeholder">点击右上角按钮后展示最近会话。</div>
          <div v-else-if="!sessions.length" class="muted">暂无会话记录。</div>
          <div v-else class="list">
            <RouterLink v-for="session in recentSessions" :key="session.id" class="row-link" :to="`/chat?session=${encodeURIComponent(session.id)}`">
              <div>
                <strong>{{ session.title }}</strong>
                <span>{{ formatTime(session.updatedAt) }}</span>
              </div>
              <strong>{{ session.messages.length }} 条</strong>
            </RouterLink>
          </div>
        </section>

        <section id="recent-views" class="panel" aria-labelledby="recent-views-title">
          <div class="panel-head">
            <div>
              <h2 id="recent-views-title">最近浏览</h2>
              <p>默认收起，按需展开查看。</p>
            </div>
            <button class="toggle-btn" type="button" @click="showRecentViews = !showRecentViews">
              {{ showRecentViews ? '收起' : '展开' }}
            </button>
          </div>
          <div v-if="!showRecentViews" class="fold-placeholder">点击右上角按钮后展示最近浏览商品。</div>
          <div v-else-if="!recentViews.length" class="muted">最近还没有浏览记录。</div>
          <div v-else class="list">
            <button v-for="item in recentViews" :key="`${item.productId}-${item.viewedAt}`" class="insight-row" type="button" @click="openInsight(item)">
              <div>
                <strong>{{ item.name }}</strong>
                <span>{{ formatTime(item.viewedAt) }}</span>
                <p>{{ item.reason || '来自聊天或商品浏览行为' }}</p>
              </div>
              <strong>{{ formatCurrency(Number(item.price)) }}</strong>
            </button>
          </div>
        </section>

        <section class="panel" aria-labelledby="recommendation-title">
          <div class="panel-head">
            <div>
              <h2 id="recommendation-title">为你推荐</h2>
              <p>默认收起，按需展开查看。</p>
            </div>
            <button class="toggle-btn" type="button" @click="showRecommendations = !showRecommendations">
              {{ showRecommendations ? '收起' : '展开' }}
            </button>
          </div>
          <div v-if="!showRecommendations" class="fold-placeholder">点击右上角按钮后展示个性化推荐。</div>
          <div v-else-if="!recommendations.length" class="muted">当前还没有可展示的推荐。</div>
          <div v-else class="list">
            <button v-for="item in recommendations" :key="item.productId" class="insight-row recommendation" type="button" @click="openInsight(item)">
              <div>
                <strong>{{ item.name }}</strong>
                <span>{{ item.source }}</span>
                <p>{{ item.reason }}</p>
              </div>
              <strong>{{ formatCurrency(Number(item.price)) }}</strong>
            </button>
          </div>
        </section>

        <section id="profile-edit" class="panel profile-panel" aria-labelledby="profile-title">
          <div class="panel-head">
            <div>
              <h2 id="profile-title">个人资料</h2>
              <p>完善昵称、联系方式和偏好，让推荐更贴近你的购物场景。</p>
            </div>
            <button class="action-btn" :disabled="saving" @click="saveProfile">{{ saving ? '保存中...' : '保存资料' }}</button>
          </div>
          <div class="profile-grid">
            <label>
              <span>昵称</span>
              <input v-model="profileForm.displayName" type="text" placeholder="输入昵称" />
            </label>
            <label>
              <span>手机号</span>
              <input v-model="profileForm.phone" type="text" placeholder="输入手机号" />
            </label>
            <label>
              <span>城市</span>
              <input v-model="profileForm.city" type="text" placeholder="输入城市" />
            </label>
            <label class="full">
              <span>个人简介</span>
              <textarea v-model="profileForm.bio" rows="3" placeholder="介绍你的偏好、预算和常购场景"></textarea>
            </label>
          </div>
          <p v-if="profileMessage" :class="['profile-feedback', profileMessageTone]">{{ profileMessage }}</p>
        </section>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

import { useAuth } from '../composables/useAuth'
import { useCart } from '../composables/useCart'
import { api, type OrderDto, type ProductInsightDto } from '../services/api'
import { ensureImportedHistorySession, listChatSessions, type ChatSessionRecord } from '../services/chatSessions'

const { currentUser, updateProfile, refreshProfile } = useAuth()
const { itemCount, refreshCart } = useCart()
const router = useRouter()

const orders = ref<OrderDto[]>([])
const recentViews = ref<ProductInsightDto[]>([])
const recommendations = ref<ProductInsightDto[]>([])
const sessions = ref<ChatSessionRecord[]>([])
const saving = ref(false)
const profileMessage = ref('')
const profileMessageTone = ref<'success' | 'error'>('success')
const showRecentOrders = ref(false)
const showRecentSessions = ref(false)
const showRecentViews = ref(false)
const showRecommendations = ref(false)
const profileForm = ref({
  displayName: '',
  phone: '',
  city: '',
  bio: ''
})
let pollTimer: number | undefined

type QuickAction = {
  title: string
  subtitle: string
  badge: string
  kind: 'route' | 'section'
  target: string
}

const recentOrders = computed(() => orders.value.slice(0, 4))
const recentSessions = computed(() => sessions.value.slice(0, 4))
const pendingOrdersCount = computed(() => orders.value.filter(order => order.status === 'CREATED').length)
const paidOrdersCount = computed(() => orders.value.filter(order => order.status === 'PAID').length)
const cancelledOrdersCount = computed(() => orders.value.filter(order => order.status === 'CANCELLED').length)
const userInitial = computed(() => {
  const source = currentUser.value?.displayName || currentUser.value?.email || '我'
  return source.trim().slice(0, 1).toUpperCase()
})

const orderEntryList = computed(() => ([
  {
    label: '全部订单',
    value: orders.value.length,
    hint: '查看全部记录',
    to: '/orders',
    tone: 'neutral'
  },
  {
    label: '待支付',
    value: pendingOrdersCount.value,
    hint: '待完成付款',
    to: '/orders?status=CREATED',
    tone: 'pending'
  },
  {
    label: '已支付',
    value: paidOrdersCount.value,
    hint: '查看成交订单',
    to: '/orders?status=PAID',
    tone: 'success'
  },
  {
    label: '已取消',
    value: cancelledOrdersCount.value,
    hint: '已关闭订单',
    to: '/orders?status=CANCELLED',
    tone: 'muted'
  }
]))

const quickActions = computed<QuickAction[]>(() => ([
  {
    title: '全部订单',
    subtitle: '查看订单列表',
    badge: '单',
    kind: 'route',
    target: '/orders'
  },
  {
    title: '会话历史',
    subtitle: '回看导购记录',
    badge: '聊',
    kind: 'route',
    target: '/history'
  },
  {
    title: 'AI 导购',
    subtitle: '继续咨询商品',
    badge: '导',
    kind: 'route',
    target: '/chat'
  },
  {
    title: '购物车',
    subtitle: `${itemCount.value} 件待结算`,
    badge: '车',
    kind: 'route',
    target: '/cart'
  },
  {
    title: '最近浏览',
    subtitle: '回到浏览商品',
    badge: '览',
    kind: 'section',
    target: 'recent-views'
  },
  {
    title: '个人资料',
    subtitle: '编辑偏好信息',
    badge: '资',
    kind: 'section',
    target: 'profile-edit'
  }
]))

const formatTime = (value: string) => {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString('zh-CN')
}

const formatCurrency = (price: number) => new Intl.NumberFormat('zh-CN', {
  style: 'currency',
  currency: 'CNY',
  maximumFractionDigits: 0
}).format(price)

const formatStatus = (value: string) => {
  if (value === 'PAID') return '已支付'
  if (value === 'CREATED') return '待支付'
  if (value === 'CANCELLED') return '已取消'
  return value
}

const formatRole = (value: string) => {
  if (value === 'USER') return '用户'
  if (value === 'ASSISTANT') return '助手'
  if (value === 'ADMIN') return '管理员'
  return value
}

const syncProfileForm = () => {
  profileForm.value = {
    displayName: currentUser.value?.displayName || '',
    phone: currentUser.value?.phone || '',
    city: currentUser.value?.city || '',
    bio: currentUser.value?.bio || ''
  }
}

const normalizeProfilePayload = () => ({
  displayName: profileForm.value.displayName.trim(),
  phone: profileForm.value.phone.trim(),
  city: profileForm.value.city.trim(),
  bio: profileForm.value.bio.trim()
})

const toReadableError = (error: unknown, fallback: string) => {
  if (!(error instanceof Error) || !error.message) {
    return fallback
  }

  const match = error.message.match(/^Request failed:\s*\d+\s*(.*)$/)
  const detail = (match?.[1] || error.message).trim()
  return detail || fallback
}

const handleExpiredSession = (message = '当前登录信息已失效，请重新登录。') => {
  profileMessageTone.value = 'error'
  profileMessage.value = message
  orders.value = []
  recentViews.value = []
  recommendations.value = []
  sessions.value = []
  stopPolling()
  void router.replace('/login')
}

const cacheProductPreview = (item: { id: string, name: string, description?: string, price: number, imageUrl?: string }) => {
  try {
    window.sessionStorage.setItem(`product-preview:${item.id}`, JSON.stringify({
      id: item.id,
      name: item.name,
      description: item.description || '',
      price: item.price,
      imageUrl: item.imageUrl || '',
      tags: ''
    }))
  } catch {
    // ignore session storage failures
  }
}

const navigateAction = (action: QuickAction) => {
  if (action.kind === 'route') {
    router.push(action.target)
    return
  }

  const target = document.getElementById(action.target)
  target?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

const openInsight = (item: ProductInsightDto) => {
  cacheProductPreview({
    id: item.productId,
    name: item.name,
    description: item.description,
    price: Number(item.price),
    imageUrl: item.imageUrl
  })
  router.push(`/products/${item.productId}`)
}

const loadOrders = async () => {
  if (!currentUser.value) return
  try {
    orders.value = await api.getOrders(currentUser.value.id)
  } catch {
    orders.value = []
  } finally {
    restartPolling()
  }
}

const loadAccountContext = async () => {
  if (!currentUser.value) return

  const [historyResult, overviewResult, profileResult, cartResult] = await Promise.allSettled([
    api.getChatHistory(currentUser.value.id),
    api.getAccountOverview(currentUser.value.id),
    refreshProfile(),
    refreshCart()
  ])

  if (historyResult.status === 'fulfilled') {
    ensureImportedHistorySession(currentUser.value.id, historyResult.value)
    sessions.value = listChatSessions(currentUser.value.id)
  } else {
    sessions.value = []
  }

  if (overviewResult.status === 'fulfilled') {
    recentViews.value = overviewResult.value.recentViews
    recommendations.value = overviewResult.value.recommendations
  } else {
    recentViews.value = []
    recommendations.value = []
  }

  if (profileResult.status === 'fulfilled') {
    syncProfileForm()
  } else if (toReadableError(profileResult.reason, '').includes('当前登录信息已失效')) {
    handleExpiredSession('登录信息已失效，请重新登录后再查看或保存资料。')
  }

  if (cartResult.status === 'rejected') {
    // keep existing cart snapshot from composable state
  }
}

const loadData = async () => {
  if (!currentUser.value) return
  await Promise.all([loadOrders(), loadAccountContext()])
}

const saveProfile = async () => {
  if (!currentUser.value || saving.value) return
  saving.value = true
  profileMessage.value = ''
  try {
    await updateProfile(normalizeProfilePayload())
    await refreshProfile()
    syncProfileForm()
    profileMessageTone.value = 'success'
    profileMessage.value = '个人资料已保存。'
  } catch (error) {
    if (toReadableError(error, '').includes('当前登录信息已失效')) {
      handleExpiredSession('登录信息已失效，请重新登录后再保存资料。')
      return
    }
    profileMessageTone.value = 'error'
    profileMessage.value = toReadableError(error, '资料保存失败，请稍后重试。')
  } finally {
    saving.value = false
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
    void loadOrders()
  }, 5000)
}

const handleWindowFocus = () => {
  void loadData()
}

const handleVisibilityChange = () => {
  if (document.visibilityState === 'visible') {
    void loadData()
  }
}

onMounted(() => {
  syncProfileForm()
  void loadData()
  window.addEventListener('focus', handleWindowFocus)
  document.addEventListener('visibilitychange', handleVisibilityChange)
})

onBeforeUnmount(() => {
  stopPolling()
  window.removeEventListener('focus', handleWindowFocus)
  document.removeEventListener('visibilitychange', handleVisibilityChange)
})

watch(() => currentUser.value?.id, () => {
  syncProfileForm()
  void loadData()
})
</script>

<style scoped>
.account-page {
  max-width: 1220px;
  margin: 0 auto;
}

.account-home {
  display: grid;
  gap: 18px;
}

.profile-banner {
  display: grid;
  grid-template-columns: minmax(0, 1.5fr) minmax(260px, 0.7fr);
  gap: 18px;
  padding: 24px;
  border-radius: 30px;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.56) 0%, rgba(255, 255, 255, 0.2) 100%),
    radial-gradient(circle at top right, rgba(255, 77, 77, 0.12), transparent 28%),
    radial-gradient(circle at bottom left, rgba(0, 196, 180, 0.1), transparent 28%);
  color: var(--text-main);
  box-shadow: var(--shadow-lg);
  border: 1px solid rgba(255, 255, 255, 0.74);
  backdrop-filter: blur(14px);
}

.profile-main {
  display: flex;
  gap: 18px;
  align-items: center;
}

.avatar {
  width: 72px;
  height: 72px;
  border-radius: 24px;
  display: grid;
  place-items: center;
  background: linear-gradient(135deg, rgba(255, 77, 77, 0.92) 0%, rgba(0, 196, 180, 0.82) 100%);
  border: 1px solid rgba(255, 255, 255, 0.28);
  font-size: 28px;
  font-weight: 700;
}

.profile-copy {
  display: grid;
  gap: 6px;
}

.eyebrow {
  margin: 0;
  font-size: 12px;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: var(--brand-deep);
}

h1,
h2 {
  margin: 0;
}

.subtitle,
.description {
  margin: 0;
  color: var(--text-soft);
}

.profile-side {
  display: grid;
  gap: 12px;
  align-content: space-between;
}

.member-card {
  display: grid;
  gap: 6px;
  padding: 18px;
  border-radius: 22px;
  background: rgba(255, 255, 255, 0.74);
  border: 1px solid rgba(255, 255, 255, 0.8);
  box-shadow: var(--shadow-soft);
}

.member-label {
  font-size: 12px;
  color: var(--text-faint);
}

.member-card small {
  color: var(--text-soft);
}

.banner-actions {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.banner-actions a,
.primary-link {
  min-height: 42px;
  border-radius: 16px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  text-decoration: none;
}

.banner-actions a {
  background: rgba(255, 255, 255, 0.78);
  color: var(--text-main);
  box-shadow: var(--shadow-soft);
}

.asset-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.asset-card,
.panel,
.empty-state {
  padding: 20px;
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.88);
  border: 1px solid rgba(255, 255, 255, 0.78);
  box-shadow: var(--shadow-lg);
  backdrop-filter: blur(14px);
}

.asset-card {
  display: grid;
  gap: 8px;
  background: linear-gradient(180deg, rgba(255,255,255,0.95) 0%, rgba(255,248,244,0.92) 100%);
}

.asset-card strong {
  font-size: 28px;
}

.asset-card small,
.muted,
.panel-head p,
.tool-item small,
.order-entry small {
  color: #766b5f;
}

.panel-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.profile-feedback {
  margin: 2px 0 0;
  font-size: 13px;
}

.profile-feedback.success {
  color: #0d6b51;
}

.profile-feedback.error {
  color: #a6412e;
}

.asset-card.warm {
  background: linear-gradient(180deg, #fff9ef 0%, #fff1db 100%);
}

.asset-card.success {
  background: linear-gradient(180deg, #f1fbf7 0%, #e6f7ef 100%);
}

.asset-card.accent {
  background: linear-gradient(180deg, #f7f8ff 0%, #eef3ff 100%);
}

.overview-grid,
.content-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
}

.profile-panel {
  grid-column: 1 / -1;
}

.panel-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
  margin-bottom: 14px;
}

.panel-head p {
  margin: 6px 0 0;
  font-size: 13px;
}

.panel-head a {
  color: #1f6f5c;
  text-decoration: none;
}

.toggle-btn {
  border: 1px solid #d8cab8;
  border-radius: 999px;
  min-height: 36px;
  padding: 0 14px;
  background: #fff8ef;
  color: #5d5348;
  cursor: pointer;
}

.order-entry-grid,
.tool-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.tool-grid {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.order-entry,
.tool-item {
  border: none;
  width: 100%;
  text-align: left;
  text-decoration: none;
  cursor: pointer;
}

.order-entry {
  display: grid;
  gap: 6px;
  padding: 16px;
  border-radius: 20px;
  background: #f8f3ec;
  color: inherit;
}

.order-entry strong {
  font-size: 26px;
}

.order-entry.pending {
  background: linear-gradient(180deg, #fff7e4 0%, #fff0cb 100%);
}

.order-entry.success {
  background: linear-gradient(180deg, #ebfaf2 0%, #dff3e8 100%);
}

.order-entry.muted {
  background: linear-gradient(180deg, #f5f4f2 0%, #efede8 100%);
}

.tool-item {
  display: grid;
  gap: 8px;
  align-content: start;
  padding: 16px;
  border-radius: 20px;
  background: linear-gradient(180deg, #fffdf9 0%, #f8f2e8 100%);
}

.tool-badge {
  width: 38px;
  height: 38px;
  border-radius: 14px;
  display: grid;
  place-items: center;
  background: #1f6f5c;
  color: #fff;
  font-size: 15px;
  font-weight: 700;
}

.list {
  display: grid;
  gap: 12px;
}

.row-link,
.insight-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  padding: 14px 16px;
  border-radius: 18px;
  background: #fff8ef;
  color: inherit;
  text-decoration: none;
  border: none;
  width: 100%;
  text-align: left;
  cursor: pointer;
}

.row-link div,
.insight-row div {
  display: grid;
  gap: 4px;
}

.insight-row {
  align-items: flex-start;
  background: #f7f2eb;
}

.insight-row p {
  margin: 0;
  color: #685d50;
}

.recommendation {
  background: linear-gradient(135deg, #f7f4ea 0%, #fff2dd 100%);
}

.fold-placeholder {
  padding: 16px;
  border-radius: 16px;
  background: #f8f3ec;
  color: #766b5f;
}

.profile-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.profile-grid label {
  display: grid;
  gap: 8px;
  color: #5f5246;
}

.profile-grid label.full {
  grid-column: 1 / -1;
}

.profile-grid input,
.profile-grid textarea {
  width: 100%;
  border: 1px solid #ddd0bd;
  border-radius: 14px;
  padding: 12px 14px;
  background: #fffdf8;
  font: inherit;
}

.action-btn {
  border: none;
  padding: 10px 14px;
  border-radius: 999px;
  background: #1f6f5c;
  color: #fff;
  cursor: pointer;
}

.action-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.empty-state {
  text-align: center;
  display: grid;
  gap: 10px;
}

.empty-state p {
  margin: 0;
  color: #6f6458;
}

.primary-link {
  justify-self: center;
  padding: 0 18px;
  background: #1f6f5c;
  color: #fff;
}

@media (max-width: 960px) {
  .profile-banner,
  .overview-grid,
  .content-grid,
  .asset-strip,
  .order-entry-grid,
  .tool-grid,
  .profile-grid,
  .banner-actions {
    grid-template-columns: 1fr;
  }

  .profile-main {
    align-items: flex-start;
  }
}
</style>
