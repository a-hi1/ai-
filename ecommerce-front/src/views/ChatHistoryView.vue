<template>
  <section class="history-page">
    <header class="history-hero">
      <div>
        <p class="eyebrow">对话归档</p>
        <h1>会话历史</h1>
        <p>每次新增对话都会单独存档，可以回看当时的消息与商品卡片。</p>
      </div>
      <div class="hero-actions">
        <RouterLink class="ghost-link" to="/account">返回账户</RouterLink>
        <RouterLink class="primary-link" to="/chat">去导购页</RouterLink>
      </div>
    </header>

    <div v-if="!currentUser" class="empty-state">
      <h2>请先登录</h2>
      <RouterLink class="primary-link" to="/login">前往登录</RouterLink>
    </div>

    <div v-else class="history-layout">
      <aside class="session-pane">
        <div v-if="!categorizedSessions.length" class="empty-state compact">
          <strong>暂无会话</strong>
          <p>去导购页发起一次新对话后，这里会按会话自动归档。</p>
        </div>

        <section v-for="group in categorizedSessions" :key="group.label" class="session-group">
          <h2>{{ group.label }}</h2>
          <article
            v-for="session in group.items"
            :key="session.id"
            class="session-card"
            :class="{ active: session.id === activeSessionId }"
          >
            <button class="session-card-main" type="button" @click="selectSession(session.id)">
              <strong>{{ session.title }}</strong>
              <span>{{ session.messages.length }} 条消息</span>
              <small>{{ formatTime(session.updatedAt) }}</small>
            </button>
            <button class="session-delete" type="button" @click="removeSession(session.id)">删除</button>
          </article>
        </section>
      </aside>

      <section class="detail-pane">
        <div v-if="activeSession" class="detail-head">
          <div>
            <p class="eyebrow warm">会话详情</p>
            <h2>{{ activeSession.title }}</h2>
            <span>{{ formatTime(activeSession.updatedAt) }}</span>
          </div>
          <div class="detail-actions">
            <button class="danger-btn" type="button" @click="removeSession(activeSession.id)">删除会话</button>
            <RouterLink class="ghost-link" :to="`/chat?session=${encodeURIComponent(activeSession.id)}`">继续该对话</RouterLink>
          </div>
        </div>

        <div v-if="!activeSession" class="empty-state compact">
          <strong>请选择左侧会话</strong>
        </div>

        <div v-else class="message-list">
          <article v-for="(message, index) in activeSession.messages" :key="`${activeSession.id}-${index}-${message.timestamp}`" class="message-card" :class="message.role">
            <div class="meta-row">
              <strong>{{ message.role === 'user' ? '你的需求' : 'AI 导购建议' }}</strong>
              <span>{{ formatTime(message.timestamp) }}</span>
            </div>
            <p class="message-text">{{ message.content }}</p>

            <div v-if="message.goodsList?.length" class="goods-grid">
              <button v-for="goods in message.goodsList" :key="goods.id" class="goods-card" @click="openProduct(goods)">
                <img :src="goods.image" :alt="goods.name" loading="lazy" decoding="async" />
                <div>
                  <strong>{{ goods.name }}</strong>
                  <span>{{ formatCurrency(goods.price) }}</span>
                  <p>{{ goods.reason || goods.desc }}</p>
                </div>
              </button>
            </div>

            <div v-if="message.relatedGoods?.length" class="goods-grid related-grid">
              <button v-for="goods in message.relatedGoods" :key="`related-${goods.id}`" class="goods-card related" @click="openProduct(goods)">
                <img :src="goods.image" :alt="goods.name" loading="lazy" decoding="async" />
                <div>
                  <strong>{{ goods.name }}</strong>
                  <span>{{ formatCurrency(goods.price) }}</span>
                  <p>{{ goods.reason || goods.desc }}</p>
                </div>
              </button>
            </div>
          </article>
        </div>
      </section>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

import { useAuth } from '../composables/useAuth'
import { api } from '../services/api'
import {
  deleteChatSession,
  ensureImportedHistorySession,
  listChatSessions,
  type ChatSessionGoods,
  type ChatSessionRecord
} from '../services/chatSessions'

const { currentUser } = useAuth()
const router = useRouter()
const sessions = ref<ChatSessionRecord[]>([])
const activeSessionId = ref('')

const formatTime = (value: string) => {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString('zh-CN')
}

const formatCurrency = (price: number) => new Intl.NumberFormat('zh-CN', {
  style: 'currency',
  currency: 'CNY',
  maximumFractionDigits: 0
}).format(price)

const categorizedSessions = computed(() => {
  const now = Date.now()
  const todayBoundary = new Date()
  todayBoundary.setHours(0, 0, 0, 0)
  const weekBoundary = new Date(todayBoundary)
  weekBoundary.setDate(weekBoundary.getDate() - 7)

  const todayGroup = { label: '今天', items: [] as ChatSessionRecord[] }
  const weekGroup = { label: '近 7 天', items: [] as ChatSessionRecord[] }
  const earlierGroup = { label: '更早', items: [] as ChatSessionRecord[] }
  const groups = [todayGroup, weekGroup, earlierGroup]

  sessions.value.forEach((session) => {
    const updatedAt = new Date(session.updatedAt).getTime()
    if (updatedAt >= todayBoundary.getTime() && updatedAt <= now) {
      todayGroup.items.push(session)
      return
    }
    if (updatedAt >= weekBoundary.getTime()) {
      weekGroup.items.push(session)
      return
    }
    earlierGroup.items.push(session)
  })

  return groups.filter(group => group.items.length)
})

const activeSession = computed(() => {
  return sessions.value.find(session => session.id === activeSessionId.value) ?? null
})

const syncSessions = () => {
  if (!currentUser.value) {
    sessions.value = []
    activeSessionId.value = ''
    return
  }

  sessions.value = listChatSessions(currentUser.value.id)
  if (sessions.value.some(session => session.id === activeSessionId.value)) {
    return
  }

  activeSessionId.value = sessions.value[0]?.id || ''
}

const selectSession = (sessionId: string) => {
  activeSessionId.value = sessionId
}

const removeSession = (sessionId: string) => {
  if (!currentUser.value) {
    return
  }
  const target = sessions.value.find(session => session.id === sessionId)
  if (!target) {
    return
  }
  const confirmed = window.confirm(`确认删除会话“${target.title}”吗？删除后无法恢复。`)
  if (!confirmed) {
    return
  }
  deleteChatSession(currentUser.value.id, sessionId)
  syncSessions()
}

const openProduct = (goods: ChatSessionGoods) => {
  try {
    window.sessionStorage.setItem(`product-preview:${goods.id}`, JSON.stringify({
      id: goods.id,
      name: goods.name,
      description: goods.desc,
      price: goods.price,
      imageUrl: goods.image,
      tags: goods.tags.join(',')
    }))
  } catch {
    // ignore session storage failures
  }

  router.push(`/products/${goods.id}`)
}

onMounted(async () => {
  if (!currentUser.value) return
  try {
    const history = await api.getChatHistory(currentUser.value.id)
    ensureImportedHistorySession(currentUser.value.id, history)
  } catch {
    // ignore backend history failures and use local session store only
  }

  syncSessions()
})
</script>

<style scoped>
.history-page {
  max-width: 1240px;
  margin: 0 auto;
  display: grid;
  gap: 18px;
}

.history-hero {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: center;
  padding: 22px 24px;
  border-radius: 24px;
  background:
    linear-gradient(135deg, rgba(255,255,255,0.62) 0%, rgba(255,255,255,0.28) 100%),
    radial-gradient(circle at top right, rgba(255,77,77,0.12), transparent 30%),
    radial-gradient(circle at bottom left, rgba(0,196,180,0.12), transparent 26%);
  border: 1px solid rgba(255,255,255,0.76);
  box-shadow: var(--shadow-lg);
  backdrop-filter: blur(14px);
}

.eyebrow {
  margin: 0 0 8px;
  font-size: 12px;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: #8a5a24;
}

.eyebrow.warm {
  color: #a4652d;
}

h1,
h2 {
  margin: 0;
}

.history-hero p {
  margin: 8px 0 0;
  color: #6f655a;
}

.hero-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.primary-link,
.ghost-link {
  min-height: 40px;
  padding: 0 14px;
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  text-decoration: none;
}

.primary-link {
  background: linear-gradient(135deg, rgba(255,77,77,0.96) 0%, rgba(255,111,105,0.88) 100%);
  color: #fff;
}

.ghost-link {
  background: rgba(255, 255, 255, 0.82);
  color: #6b5844;
}

.history-layout {
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  gap: 18px;
}

.session-pane,
.detail-pane,
.empty-state {
  padding: 20px;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.9);
  border: 1px solid rgba(255,255,255,0.78);
  box-shadow: var(--shadow-lg);
  backdrop-filter: blur(14px);
}

.empty-state.compact {
  display: grid;
  gap: 8px;
}

.empty-state.compact p {
  margin: 0;
  color: #6f655a;
}

.session-pane {
  display: grid;
  gap: 16px;
  align-content: start;
}

.session-group {
  display: grid;
  gap: 10px;
}

.session-group h2 {
  font-size: 14px;
  color: #7d6c5c;
}

.session-card {
  display: grid;
  gap: 10px;
  border: 1px solid rgba(255,255,255,0.82);
  background: rgba(255,255,255,0.94);
  border-radius: 16px;
  padding: 12px;
  box-shadow: var(--shadow-soft);
}

.session-card.active {
  border-color: rgba(255,77,77,0.18);
  background: linear-gradient(135deg, rgba(255,77,77,0.08) 0%, rgba(255,255,255,0.94) 100%);
}

.session-card-main {
  border: none;
  background: transparent;
  padding: 0;
  text-align: left;
  display: grid;
  gap: 6px;
  cursor: pointer;
}

.session-card span,
.session-card small,
.detail-head span {
  color: #6f655a;
}

.detail-pane {
  display: grid;
  gap: 16px;
}

.detail-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
}

.detail-actions {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
}

.session-delete,
.danger-btn {
  border: none;
  border-radius: 999px;
  min-height: 34px;
  padding: 0 12px;
  background: #fff1ec;
  color: #a53f2d;
  cursor: pointer;
}

.message-list {
  display: grid;
  gap: 14px;
}

.message-card {
  display: grid;
  gap: 12px;
  padding: 16px;
  border-radius: 18px;
}

.message-card.user {
  background: rgba(255,245,242,0.9);
}

.message-card.assistant {
  background: rgba(240,251,249,0.92);
}

.meta-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 12px;
  color: #736759;
}

.message-text {
  margin: 0;
  line-height: 1.6;
}

.goods-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.goods-card {
  border: 1px solid #e3d8ca;
  background: #fff;
  border-radius: 16px;
  padding: 12px;
  display: grid;
  grid-template-columns: 88px minmax(0, 1fr);
  gap: 12px;
  align-items: center;
  text-align: left;
  cursor: pointer;
}

.goods-card.related {
  background: #fffbf6;
}

.goods-card img {
  width: 88px;
  height: 88px;
  border-radius: 12px;
  object-fit: cover;
}

.goods-card div {
  display: grid;
  gap: 6px;
}

.goods-card span,
.goods-card p {
  color: #6f655a;
}

.goods-card p {
  margin: 0;
  font-size: 13px;
  line-height: 1.5;
}

@media (max-width: 960px) {
  .history-hero,
  .history-layout {
    grid-template-columns: 1fr;
    flex-direction: column;
    align-items: stretch;
  }
}

@media (max-width: 720px) {
  .goods-grid {
    grid-template-columns: 1fr;
  }

  .detail-actions,
  .history-hero,
  .detail-head {
    flex-direction: column;
    align-items: stretch;
  }

  .goods-card {
    grid-template-columns: 72px minmax(0, 1fr);
  }

  .goods-card img {
    width: 72px;
    height: 72px;
  }
}
</style>
