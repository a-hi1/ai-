<template>
  <section class="shopping-shell">
    <header class="hero-panel">
      <div class="hero-copy">
        <p class="eyebrow">Guide</p>
        <h1>AI 导购</h1>
        <p class="hero-lead">
          把预算、使用场景和偏好一次说清楚，我会先帮你缩小商品范围，再给出主推、备选和搭配建议。
        </p>

        <div class="hero-notes">
          <span>先说场景</span>
          <span>再说预算</span>
          <span>最后补品牌或偏好</span>
        </div>

        <div class="prompt-list">
          <button v-for="prompt in quickPrompts" :key="prompt" class="prompt-chip" @click="usePrompt(prompt)">{{ prompt }}</button>
        </div>
      </div>

      <div class="hero-side">
        <article class="provider-panel">
          <div class="provider-head">
            <div>
              <span class="provider-label">DeepSeek 实时状态</span>
              <strong>{{ providerStatusTitle }}</strong>
            </div>
            <span class="provider-state" :class="providerStateClass">{{ providerStateText }}</span>
          </div>

          <div class="provider-grid">
            <label class="provider-field">
              <span>当前 Provider</span>
              <input :value="providerConfig?.provider || 'deepseek'" type="text" class="provider-input" readonly />
            </label>

            <label class="provider-field">
              <span>DeepSeek Key</span>
              <input v-model="providerForm.deepseekApiKey" type="password" class="provider-input" :placeholder="providerConfig?.deepseek.maskedApiKey || '留空则保留已保存 key'" />
            </label>

            <label class="provider-field">
              <span>DeepSeek 模型</span>
              <input v-model="providerForm.deepseekModelName" type="text" class="provider-input" placeholder="deepseek-chat" />
            </label>
          </div>

          <div class="provider-meta">
            <span class="provider-note">重启保留：{{ providerConfig?.persisted ? '已启用' : '保存后启用' }}</span>
            <span class="provider-note">当前模型：{{ providerConfig?.deepseek.modelName || providerConfig?.activeModelName || '未就绪' }}</span>
            <span class="provider-note">最近状态：{{ providerRuntimeReason }}</span>
          </div>

          <div class="provider-actions">
            <button class="secondary-btn slim-btn" :disabled="providerLoading || providerSaving" @click="loadAiProviderOverview">
              {{ providerLoading ? '读取中...' : '刷新状态' }}
            </button>
            <button class="send-btn provider-save-btn" :disabled="providerSaving" @click="saveAiProviderConfig">
              {{ providerSaving ? '保存中...' : '保存 DeepSeek 配置' }}
            </button>
          </div>
        </article>

        <article class="hero-stat emphasis">
          <span>导购模式</span>
          <strong>{{ latestAdvisorMode }}</strong>
          <small>{{ latestAdvisorSource }}</small>
        </article>
        <article class="hero-stat">
          <span>候选商品</span>
          <strong>{{ featuredGoods.length }} 款</strong>
          <small>当前货架</small>
        </article>
        <article class="hero-stat">
          <span>聊天轮次</span>
          <strong>{{ assistantMessageCount }} 轮</strong>
          <small>当前会话</small>
        </article>
        <article class="hero-stat accent">
          <span>购物车金额</span>
          <strong>{{ cartTotalLabel }}</strong>
          <small>{{ itemCount }} 件</small>
        </article>
      </div>
    </header>

    <div v-if="notice" :class="['notice', noticeType]">{{ notice }}</div>

    <div class="shopping-grid">
      <div class="chat-panel" ref="chatPanelRef">
        <div class="chat-header">
          <div>
            <h2>导购对话工作台</h2>
            <p class="session-caption">{{ activeSessionTitle }}</p>
          </div>
          <div class="chat-header-side">
            <div class="status-stack">
              <span class="connection-state" :class="backendReachable ? 'online' : 'offline'">
                {{ backendReachable ? '后端在线' : '本地兜底' }}
              </span>
              <span class="secondary-badge">{{ latestBudgetSummary }}</span>
            </div>
            <div class="session-actions">
              <button class="secondary-btn slim-btn" @click="startNewConversation">新增对话</button>
              <RouterLink to="/history" class="mini-ghost-btn slim-link">会话历史</RouterLink>
            </div>
          </div>
        </div>

        <div v-if="latestTraceSteps.length" class="recent-analysis-panel">
          <div class="recent-analysis-head">
            <strong>最近一次分析轨迹</strong>
            <span>共 {{ latestTraceSteps.length }} 步</span>
          </div>
          <div class="tool-trace-list">
            <span v-for="(trace, traceIndex) in latestTraceSteps" :key="`latest-trace-${traceIndex}`" class="trace-pill">
              {{ trace }}
            </span>
          </div>
        </div>

        <div class="chat-container" ref="chatRef">
          <div v-if="analysisVisible" class="analysis-live-panel">
            <div class="analysis-live-head">
              <strong>{{ sending ? 'AI 正在分析中' : '分析完成' }}</strong>
              <span>{{ analysisElapsedLabel }}</span>
            </div>
            <div class="analysis-live-steps">
              <div
                v-for="(step, stepIndex) in analysisProgressSteps"
                :key="step"
                :class="['analysis-step', stepIndex <= analysisStepIndex ? 'done' : 'pending']"
              >
                <span class="dot"></span>
                <span>{{ step }}</span>
              </div>
            </div>
          </div>

          <div class="message-item system">
            <div class="avatar">导</div>
            <div class="content">
              <div class="meta-row">
                <span class="speaker">AI 导购助手</span>
                <span class="timestamp">{{ formatTime(systemTimestamp) }}</span>
              </div>
              <div class="text assistant-text system-text">
                <div v-for="(block, blockIndex) in formatAssistantBlocks('直接描述你的预算、场景和偏好。我会先判断需求，再把推荐拆成主推款、备选款和搭配加购，方便你直接决策。')" :key="`system-${blockIndex}`" :class="['text-block', block.tone]">
                  <span class="text-emoji">{{ block.emoji }}</span>
                  <span class="text-line" v-html="block.html"></span>
                </div>
              </div>
            </div>
          </div>

          <div v-for="(msg, index) in messageList" :key="`${msg.role}-${index}-${msg.timestamp}`" :class="['message-item', msg.role]">
            <div class="avatar">{{ msg.role === 'user' ? '你' : '导' }}</div>
            <div class="content">
              <div class="meta-row">
                <span class="speaker">{{ msg.role === 'user' ? '你的需求' : 'AI 导购建议' }}</span>
                <span class="timestamp">{{ formatTime(msg.timestamp) }}</span>
              </div>

              <div v-if="msg.role === 'assistant'" class="text assistant-text">
                <div v-for="(block, blockIndex) in formatAssistantBlocks(msg.content)" :key="`${msg.timestamp}-${blockIndex}`" :class="['text-block', block.tone]">
                  <span class="text-emoji">{{ block.emoji }}</span>
                  <span class="text-line" v-html="block.html"></span>
                </div>
              </div>
              <div v-else class="text">{{ msg.content }}</div>

              <div v-if="msg.role === 'assistant' && (msg.detectedIntent || msg.budgetSummary)" class="message-summary">
                <span v-if="msg.detectedIntent" class="summary-pill">{{ msg.detectedIntent }}</span>
                <span v-if="msg.budgetSummary" class="summary-pill">{{ msg.budgetSummary }}</span>
                <span v-if="msg.fallback" class="summary-pill warning">已切换兜底建议</span>
              </div>

              <div v-if="msg.role === 'assistant' && msg.fallback && fallbackReasonText(msg)" class="fallback-reason-banner">
                <strong>兜底原因</strong>
                <span>{{ fallbackReasonText(msg) }}</span>
              </div>

              <div v-if="msg.insights?.length || msg.goodsList?.length" class="advisor-brief">
                <div v-if="firstRecommendedGoods(msg)" class="brief-card spotlight">
                  <span>本轮主推</span>
                  <strong>{{ firstRecommendedGoods(msg)?.name }}</strong>
                  <small>{{ recommendationHeadline(firstRecommendedGoods(msg)!, 0) }}</small>
                </div>
                <div v-for="insight in (msg.insights ?? []).slice(0, 4)" :key="`${insight.label}-${insight.value}`" class="brief-card">
                  <span>{{ insight.label }}</span>
                  <strong>{{ insight.value }}</strong>
                </div>
              </div>

              <div v-if="msg.role === 'assistant' && toolTraceSteps(msg).length" class="tool-trace-panel">
                <strong>分析过程</strong>
                <div class="tool-trace-list">
                  <span v-for="(trace, traceIndex) in toolTraceSteps(msg)" :key="`${msg.timestamp}-trace-${traceIndex}`" class="trace-pill">
                    {{ trace }}
                  </span>
                </div>
              </div>

              <div v-if="msg.goodsList?.length" class="goods-list">
                <div class="goods-card" v-for="(goods, goodsIndex) in msg.goodsList" :key="goods.id">
                  <div class="goods-media">
                    <img :src="goods.image" alt="商品图片" class="goods-img" loading="lazy" decoding="async" />
                    <div class="goods-floating-meta">
                      <span class="rank-badge" :class="goodsIndex === 0 ? 'primary' : 'secondary'">{{ recommendationBadge(goodsIndex) }}</span>
                      <span class="sales-badge">{{ formatSalesLabel(goods.salesCount) }}</span>
                    </div>
                  </div>
                  <div class="goods-info">
                    <div class="goods-kicker">
                      <span class="goods-role">{{ recommendationRole(goodsIndex) }}</span>
                      <span class="goods-fit">{{ recommendationHeadline(goods, goodsIndex) }}</span>
                    </div>
                    <div class="goods-headline">
                      <div class="goods-name">{{ goods.name }}</div>
                      <div class="goods-price">{{ formatCurrency(goods.price) }}</div>
                    </div>
                    <div class="goods-desc">{{ goodsDescription(goods) }}</div>
                    <div class="reason-block">
                      <strong>推荐理由</strong>
                      <p>{{ goods.reason }}</p>
                    </div>
                    <div class="goods-meta">
                      <span class="meta-pill" :class="goods.withinBudget ? 'budget-ok' : 'budget-risk'">
                        {{ goods.withinBudget ? '预算内' : '预算外替代' }}
                      </span>
                      <span class="meta-pill">{{ recommendationSupport(goods, goodsIndex) }}</span>
                    </div>
                    <div v-if="goods.tags?.length" class="tag-list compact">
                      <span v-for="tag in goods.tags" :key="tag" class="tag-pill">{{ tag }}</span>
                    </div>
                  </div>
                  <div class="goods-actions">
                    <button class="secondary-btn" @click="viewProduct(goods)">查看详情</button>
                    <button class="add-cart-btn" @click="addToCart(goods)">
                      {{ isInCart(goods.id) ? '再加一件' : '加入购物车' }}
                    </button>
                  </div>
                </div>
              </div>

              <div v-if="msg.relatedGoods?.length" class="related-deck">
                <div class="related-head">
                  <strong>相关搭配加购</strong>
                  <span>适合和主推商品一起比较或顺手加购</span>
                </div>
                <div class="related-grid">
                  <article v-for="related in msg.relatedGoods" :key="`related-${msg.timestamp}-${related.id}`" class="related-card">
                    <img :src="related.image" :alt="related.name" class="related-img" loading="lazy" decoding="async" />
                    <div class="related-info">
                      <strong>{{ related.name }}</strong>
                      <span>{{ formatCurrency(related.price) }}</span>
                      <p>{{ related.reason }}</p>
                    </div>
                    <div class="related-actions">
                      <button class="mini-ghost-btn" @click="viewProduct(related)">详情</button>
                      <button class="mini-cart-btn" @click="addToCart(related)">
                        {{ isInCart(related.id) ? '继续加购' : '搭配加购' }}
                      </button>
                    </div>
                  </article>
                </div>
              </div>
            </div>
          </div>
        </div>

        <div class="input-container">
          <div class="emoji-bar">
            <button v-for="emoji in emojiSuggestions" :key="emoji" class="emoji-chip" type="button" @click="appendEmoji(emoji)">{{ emoji }}</button>
          </div>
          <input
            v-model="inputValue"
            type="text"
            placeholder="例如：预算 3000 元以内，帮我推荐适合通勤和视频会议的降噪耳机"
            class="chat-input"
            @keyup.enter="sendMessage"
          />
          <button class="voice-btn" :class="{ active: listening }" @click="toggleVoiceInput">
            {{ listening ? '停止语音' : '语音输入' }}
          </button>
          <button class="send-btn" :disabled="sending" @click="sendMessage">
            {{ sending ? '分析中...' : '发送需求' }}
          </button>
        </div>
      </div>

      <aside class="sidebar-panel">
        <section class="cart-panel">
          <div class="cart-header">
            <span>购物车（{{ itemCount }}）</span>
            <button class="clear-cart" @click="clearCart">清空</button>
          </div>
          <div v-if="cartErrorMessage" class="cart-error">{{ cartErrorMessage }}</div>
          <div class="cart-list">
            <div v-if="!cartItems.length" class="empty-cart">购物车还是空的，先从导购结果里挑几件商品吧。</div>
            <div v-else class="cart-item" v-for="item in cartItems" :key="item.id">
              <button class="cart-item-main" @click="viewProduct(item)">
                <div class="cart-item-name">{{ item.name }}</div>
                <div class="cart-item-desc">{{ item.desc }}</div>
              </button>
              <div class="cart-item-meta">
                <div class="cart-stepper">
                  <button class="step-btn" @click="changeQuantity(item.id, item.quantity - 1)">-</button>
                  <span>{{ item.quantity }}</span>
                  <button class="step-btn" @click="changeQuantity(item.id, item.quantity + 1)">+</button>
                </div>
                <div class="cart-item-price">{{ formatCurrency(item.price) }} × {{ item.quantity }}</div>
                <button class="remove-btn" @click="removeFromCart(item.id)">×</button>
              </div>
            </div>
          </div>
          <div v-if="cartItems.length" class="cart-footer">
            <div class="total-price">合计：{{ cartTotalLabel }}</div>
            <button class="pay-btn" @click="toPay">前往结算</button>
          </div>
        </section>
      </aside>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { productCardDescription } from '../catalog'
import { useAuth } from '../composables/useAuth'
import { useCart } from '../composables/useCart'
import { api, type AiProviderOverviewDto, type ChatRecommendationDto, type ProductDto } from '../services/api'
import {
  createChatSession,
  ensureImportedHistorySession,
  getChatSession,
  listChatSessions,
  updateChatSessionMessages,
  type ChatSessionGoods,
  type ChatSessionMessage,
  type ChatSessionRecord
} from '../services/chatSessions'
import {
  emojiSuggestions,
  formatAssistantBlocks,
  localFallbackGoods,
  quickPrompts,
  selectFallbackPack
} from '../utils/chatAdvisor'

const DEMO_GUIDE_USER = {
  id: '11111111-1111-1111-1111-111111111111',
  email: 'demo@aishop.local',
  role: 'USER',
  displayName: '访客导购'
}

type Goods = ChatSessionGoods
type Message = ChatSessionMessage

interface SpeechRecognitionResultLike {
  0: { transcript: string }
}

interface SpeechRecognitionEventLike extends Event {
  results: ArrayLike<SpeechRecognitionResultLike>
}

interface SpeechRecognitionLike extends EventTarget {
  lang: string
  interimResults: boolean
  continuous: boolean
  onresult: ((event: SpeechRecognitionEventLike) => void) | null
  onend: (() => void) | null
  onerror: (() => void) | null
  start(): void
  stop(): void
}

interface SpeechRecognitionConstructor {
  new (): SpeechRecognitionLike
}

const inputValue = ref('')
const messageList = ref<Message[]>([])
const chatRef = ref<HTMLDivElement | null>(null)
const chatPanelRef = ref<HTMLDivElement | null>(null)
const sending = ref(false)
const notice = ref('')
const noticeType = ref<'success' | 'error'>('success')
const backendReachable = ref(true)
const featuredGoods = ref<Goods[]>([])
const listening = ref(false)
const systemTimestamp = new Date().toISOString()
const sessionList = ref<ChatSessionRecord[]>([])
const activeSessionId = ref('')
const pendingAutoSend = ref(false)
const useDemoAdvisor = ref(false)
const providerLoading = ref(false)
const providerSaving = ref(false)
const providerConfig = ref<AiProviderOverviewDto | null>(null)
const providerForm = ref({
  deepseekApiKey: '',
  deepseekModelName: 'deepseek-chat'
})
const analysisStepIndex = ref(0)
const analysisElapsedSeconds = ref(0)
const analysisVisible = ref(false)

const analysisProgressSteps = [
  '识别需求与预算',
  '检索商品候选',
  '匹配场景与偏好',
  '生成推荐理由',
  '整理最终答复'
]

let analysisTimer: number | null = null
let analysisStartedAt = 0

let recognition: SpeechRecognitionLike | null = null

const route = useRoute()
const router = useRouter()
const { currentUser, refreshProfile } = useAuth()
const {
  cartItems,
  addItem,
  updateQuantity,
  removeItem,
  clearCart: clearCartItems,
  totalPrice,
  itemCount,
  errorMessage: cartErrorMessage
} = useCart()

const assistantMessages = computed(() => messageList.value.filter(item => item.role === 'assistant'))
const latestAssistantMessage = computed(() => {
  const messages = assistantMessages.value
  return messages.length ? messages[messages.length - 1] : null
})
const latestBudgetSummary = computed(() => latestAssistantMessage.value?.budgetSummary ?? '未设置预算')
const latestAdvisorMode = computed(() => latestAssistantMessage.value?.fallback ? '规则兜底导购' : '实时 AI 导购')
const latestAdvisorSource = computed(() => {
  if (!latestAssistantMessage.value) {
    return backendReachable.value ? '等待实时推荐' : '当前可回退到本地兜底'
  }

  return latestAssistantMessage.value.fallback ? '来源：规则保底推荐' : '来源：大模型实时生成'
})
const assistantMessageCount = computed(() => assistantMessages.value.length)
const cartTotalLabel = computed(() => formatCurrency(Number(totalPrice.value)))
const advisorUser = computed(() => {
  return useDemoAdvisor.value || !currentUser.value ? DEMO_GUIDE_USER : currentUser.value
})
const advisorUserId = computed(() => advisorUser.value.id)
const advisorUserName = computed(() => advisorUser.value.displayName || advisorUser.value.email || '访客')
const activeSessionTitle = computed(() => {
  const activeSession = sessionList.value.find(session => session.id === activeSessionId.value)
  return activeSession?.title || '新对话'
})
const providerStatusTitle = computed(() => {
  if (!providerConfig.value) {
    return '读取 DeepSeek 状态中'
  }
  return providerConfig.value.fallback ? 'DeepSeek 当前处于兜底保护' : 'DeepSeek 实时可用'
})
const providerStateText = computed(() => {
  if (!providerConfig.value) {
    return '未读取'
  }
  return providerConfig.value.fallback ? '当前兜底' : '实时可用'
})
const providerStateClass = computed(() => {
  return providerConfig.value?.fallback ? 'fallback' : 'ready'
})
const providerRuntimeReason = computed(() => providerConfig.value?.runtimeReason || '未读取')

const isExpiredAuthError = (error: unknown) => {
  return error instanceof Error && /当前登录信息已失效|Request failed:\s*404\b/.test(error.message)
}

const isInCart = (goodsId: string) => cartItems.value.some(item => item.id === goodsId)

const formatCurrency = (price: number) => {
  return new Intl.NumberFormat('zh-CN', {
    style: 'currency',
    currency: 'CNY',
    maximumFractionDigits: 0
  }).format(price)
}

const formatTime = (timestamp?: string) => {
  if (!timestamp) return '--:--'
  const date = new Date(timestamp)
  if (Number.isNaN(date.getTime())) {
    return '--:--'
  }
  return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}

const firstRecommendedGoods = (message: Message) => message.goodsList?.[0]

const recommendationBadge = (index: number) => {
  if (index === 0) return 'TOP 1'
  if (index === 1) return 'TOP 2'
  return `TOP ${index + 1}`
}

const recommendationRole = (index: number) => {
  if (index === 0) return '主推款'
  if (index === 1) return '稳妥备选'
  return '延展选择'
}

const recommendationHeadline = (goods: Goods, index: number) => {
  if (!goods.withinBudget && index === 0) {
    return '核心需求接近，但价格高于当前预算'
  }
  if (goods.withinBudget && index === 0) {
    return '当前场景下最值得优先比较的一款'
  }
  if (goods.withinBudget) {
    return '预算友好，适合和主推款横向对比'
  }
  return '可作为更高配置的替代方案'
}

const formatSalesLabel = (salesCount: number) => {
  if (salesCount <= 0) {
    return '新品观察'
  }
  return `成交 ${salesCount} 件`
}

const recommendationSupport = (goods: Goods, index: number) => {
  if (goods.salesCount > 0) {
    return `热度参考 ${goods.salesCount} 件`
  }
  return index === 0 ? '优先结合场景匹配' : '适合作为备选比较'
}

const goodsDescription = (goods: Goods) => {
  return productCardDescription({
    name: goods.name,
    description: goods.desc,
    tags: goods.tags,
    sellingPoints: goods.reason
  }, goods.reason || '查看商品详情')
}

const analysisElapsedLabel = computed(() => `已分析 ${analysisElapsedSeconds.value}s`)

const latestTraceSteps = computed(() => {
  if (!latestAssistantMessage.value) {
    return []
  }
  return toolTraceSteps(latestAssistantMessage.value)
})

const toolTraceSteps = (message: Message) => {
  const trace = (message.insights ?? []).find(insight => /tool|轨迹/i.test(insight.label))
  if (!trace?.value) {
    return []
  }
  return trace.value
    .split('|')
    .map(item => item.trim())
    .filter(Boolean)
}

const startAnalysisVisualization = () => {
  analysisVisible.value = true
  analysisStartedAt = Date.now()
  analysisStepIndex.value = 0
  analysisElapsedSeconds.value = 0
  if (analysisTimer !== null) {
    window.clearInterval(analysisTimer)
  }
  analysisTimer = window.setInterval(() => {
    analysisElapsedSeconds.value += 1
    if (analysisElapsedSeconds.value % 2 === 0 && analysisStepIndex.value < analysisProgressSteps.length - 1) {
      analysisStepIndex.value += 1
    }
  }, 1000)
}

const sleep = (ms: number) => new Promise<void>(resolve => window.setTimeout(resolve, ms))

const stopAnalysisVisualization = async () => {
  const minVisibleMs = 1800
  const elapsedMs = Date.now() - analysisStartedAt
  if (elapsedMs < minVisibleMs) {
    await sleep(minVisibleMs - elapsedMs)
  }

  analysisStepIndex.value = analysisProgressSteps.length - 1
  await sleep(320)

  if (analysisTimer !== null) {
    window.clearInterval(analysisTimer)
    analysisTimer = null
  }
  analysisStepIndex.value = 0
  analysisElapsedSeconds.value = 0
  analysisVisible.value = false
}

const fallbackReasonText = (message: Message) => {
  if (!message.fallback) {
    return ''
  }

  const reasonInsight = (message.insights ?? []).find(insight => insight.label === '兜底原因')
  if (reasonInsight?.value) {
    return reasonInsight.value
  }

  return '当前实时模型请求失败，已切换为规则兜底建议。'
}

const usePrompt = (prompt: string) => {
  inputValue.value = prompt
}

const appendEmoji = (emoji: string) => {
  inputValue.value = `${inputValue.value}${inputValue.value ? ' ' : ''}${emoji}`
}

const refreshSessionList = () => {
  sessionList.value = listChatSessions(advisorUserId.value)
}

const syncPromptFromRoute = () => {
  const promptFromRoute = typeof route.query.prompt === 'string' ? route.query.prompt.trim() : ''
  if (promptFromRoute) {
    inputValue.value = promptFromRoute
  }

  pendingAutoSend.value = route.query.autoSend === '1' && Boolean(promptFromRoute)
}

const validateActiveUser = async () => {
  if (!currentUser.value) {
    useDemoAdvisor.value = true
    return true
  }

  try {
    await refreshProfile()
    useDemoAdvisor.value = false
    return true
  } catch (error) {
    if (isExpiredAuthError(error)) {
      useDemoAdvisor.value = true
      noticeType.value = 'success'
      notice.value = '当前登录状态已失效，已自动切换为访客导购模式，你仍可继续直接咨询商品。'
      return true
    }

    noticeType.value = 'error'
    notice.value = '用户资料校验暂时不可用，但你仍可继续使用本地会话和导购推荐。'
    return true
  }
}

const persistCurrentSession = () => {
  if (!activeSessionId.value) {
    return
  }

  updateChatSessionMessages(advisorUserId.value, activeSessionId.value, messageList.value)
  refreshSessionList()
}

const loadSession = (sessionId: string) => {
  const session = getChatSession(advisorUserId.value, sessionId)
  if (!session) {
    return
  }

  activeSessionId.value = session.id
  messageList.value = session.messages
  void router.replace({ path: '/chat', query: { session: session.id } })
  focusLatestConversation()
}

const ensureActiveSession = () => {
  if (activeSessionId.value) {
    const existingSession = getChatSession(advisorUserId.value, activeSessionId.value)
    if (existingSession) {
      return existingSession
    }
  }

  const session = createChatSession(advisorUserId.value, inputValue.value)
  refreshSessionList()
  activeSessionId.value = session.id
  void router.replace({ path: '/chat', query: { session: session.id } })
  return session
}

const startNewConversation = () => {
  const session = createChatSession(advisorUserId.value)
  refreshSessionList()
  activeSessionId.value = session.id
  messageList.value = []
  inputValue.value = ''
  noticeType.value = 'success'
  notice.value = '已新建对话，你可以开始新的需求。'
  void router.replace({ path: '/chat', query: { session: session.id } })
  focusLatestConversation()
}

const createRecognition = (): SpeechRecognitionLike | null => {
  const speechCtor = (window as Window & {
    SpeechRecognition?: SpeechRecognitionConstructor
    webkitSpeechRecognition?: SpeechRecognitionConstructor
  }).SpeechRecognition
    || (window as Window & { webkitSpeechRecognition?: SpeechRecognitionConstructor }).webkitSpeechRecognition

  if (!speechCtor) {
    noticeType.value = 'error'
    notice.value = '当前浏览器不支持语音识别，请改用文字输入。'
    return null
  }

  const instance = new speechCtor()
  instance.lang = 'zh-CN'
  instance.interimResults = true
  instance.continuous = false
  instance.onresult = (event) => {
    const transcript = Array.from(event.results)
      .map(result => result[0]?.transcript ?? '')
      .join('')
    inputValue.value = transcript.trim()
  }
  instance.onend = () => {
    listening.value = false
  }
  instance.onerror = () => {
    listening.value = false
    noticeType.value = 'error'
    notice.value = '语音识别失败，请检查麦克风权限后重试。'
  }
  return instance
}

const toggleVoiceInput = () => {
  if (!recognition) {
    recognition = createRecognition()
  }
  if (!recognition) {
    return
  }

  if (listening.value) {
    recognition.stop()
    listening.value = false
    return
  }

  recognition.start()
  listening.value = true
  noticeType.value = 'success'
  notice.value = '正在监听语音，请开始说话。'
}

const mapProducts = (products: ProductDto[]): Goods[] => {
  return products.map((item, index) => ({
    id: item.id,
    name: item.name,
    price: item.price,
    desc: productCardDescription(item, item.sellingPoints || item.specs || item.policy || '查看商品详情'),
    image: item.imageUrl || `https://picsum.photos/seed/product-${index + 1}/480/320`,
    reason: item.sellingPoints || item.specs || item.policy || '来自商品库样本',
    salesCount: 0,
    withinBudget: true,
    tags: item.tags ? item.tags.split(/[,，]/).map(tag => tag.trim()).filter(Boolean).slice(0, 4) : []
  }))
}

const mapRecommendation = (item: ChatRecommendationDto): Goods => ({
  id: item.productId,
  name: item.name,
  price: item.price,
  desc: productCardDescription({
    name: item.name,
    description: item.description,
    tags: item.tags,
    sellingPoints: item.reason
  }, item.reason || '查看商品详情'),
  image: item.imageUrl || `https://picsum.photos/seed/${item.productId}/480/320`,
  reason: item.reason,
  salesCount: item.salesCount,
  withinBudget: item.withinBudget,
  tags: item.tags ?? []
})

type ProductPreview = {
  id: string
  name: string
  desc: string
  image: string
  price: number
  tags?: string[]
}

const cacheProductSnapshot = (goods: ProductPreview) => {
  try {
    window.sessionStorage.setItem(`product-preview:${goods.id}`, JSON.stringify({
      id: goods.id,
      name: goods.name,
      description: goods.desc,
      price: goods.price,
      imageUrl: goods.image,
      tags: (goods.tags ?? []).join(',')
    }))
  } catch {
    // ignore session storage failures
  }
}

const viewProduct = (goods: ProductPreview) => {
  cacheProductSnapshot(goods)
  router.push(`/products/${goods.id}`)
}

const recordView = async (goods: Goods, source: string) => {
  if (!currentUser.value) return
  try {
    await api.recordProductView(currentUser.value.id, goods.id, source, goods.reason || goods.desc)
  } catch {
    // ignore tracking failures to avoid blocking shopping flow
  }
}

const addToCart = async (goods: Goods) => {
  if (!currentUser.value) {
    router.push('/login')
    return
  }

  try {
    await recordView(goods, 'cart-add')
    await addItem({
      id: goods.id,
      name: goods.name,
      price: goods.price,
      desc: goods.desc,
      image: goods.image
    })
    noticeType.value = 'success'
    notice.value = `${goods.name} 已加入购物车。`
  } catch {
    noticeType.value = 'error'
    notice.value = '加入购物车失败，请确认已登录且后端服务可用。'
  }
}

const changeQuantity = async (goodsId: string, quantity: number) => {
  try {
    await updateQuantity(goodsId, quantity)
  } catch {
    noticeType.value = 'error'
    notice.value = '更新商品数量失败。'
  }
}

const removeFromCart = async (goodsId: string) => {
  try {
    await removeItem(goodsId)
  } catch {
    noticeType.value = 'error'
    notice.value = '移除商品失败，请稍后重试。'
  }
}

const clearCart = async () => {
  if (!currentUser.value) return
  try {
    await clearCartItems()
    noticeType.value = 'success'
    notice.value = '购物车已清空。'
  } catch {
    noticeType.value = 'error'
    notice.value = '清空购物车失败，请稍后重试。'
  }
}

const toPay = () => {
  router.push('/cart')
}

const syncProviderForm = (config: AiProviderOverviewDto) => {
  providerForm.value.deepseekApiKey = ''
  providerForm.value.deepseekModelName = config.deepseek.modelName || 'deepseek-chat'
}

const loadAiProviderOverview = async () => {
  providerLoading.value = true
  try {
    const overview = await api.getAiProviderOverview()
    providerConfig.value = overview
    syncProviderForm(overview)
  } catch {
    noticeType.value = 'error'
    notice.value = 'AI Provider 状态读取失败，请确认后端在线。'
  } finally {
    providerLoading.value = false
  }
}

const saveAiProviderConfig = async () => {
  providerSaving.value = true
  try {
    const result = await api.updateAiProviderConfig({
      provider: 'deepseek',
      deepseek: {
        apiKey: providerForm.value.deepseekApiKey.trim(),
        modelName: providerForm.value.deepseekModelName.trim()
      }
    })
    providerConfig.value = result.config
    syncProviderForm(result.config)
    noticeType.value = 'success'
    notice.value = result.message
  } catch (error) {
    noticeType.value = 'error'
    notice.value = error instanceof Error ? error.message : 'AI Provider 保存失败。'
  } finally {
    providerSaving.value = false
  }
}

const loadFeaturedProducts = async () => {
  try {
    const products = await api.listProducts()
    featuredGoods.value = mapProducts(products)
    backendReachable.value = true
  } catch {
    backendReachable.value = false
    featuredGoods.value = localFallbackGoods
  }
}

const loadHistory = async () => {
  try {
    const historyUserId = advisorUserId.value
    const shouldReplaceExisting = useDemoAdvisor.value || !currentUser.value
    const history = await api.getChatHistory(historyUserId)
    ensureImportedHistorySession(historyUserId, history, { replaceExisting: shouldReplaceExisting })
    refreshSessionList()

    const sessionIdFromRoute = typeof route.query.session === 'string' ? route.query.session : ''
    const targetSessionId = sessionIdFromRoute || sessionList.value[0]?.id || createChatSession(historyUserId).id

    refreshSessionList()
    loadSession(targetSessionId)
  } catch {
    noticeType.value = 'error'
    notice.value = '历史会话同步失败，当前先展示本地会话，不影响继续发起 AI 导购。'
    refreshSessionList()

    const existingSession = sessionList.value[0]
    if (existingSession) {
      loadSession(existingSession.id)
      return
    }

    const localSession = createChatSession(advisorUserId.value)
    activeSessionId.value = localSession.id
    messageList.value = []
    void router.replace({ path: '/chat', query: { session: localSession.id } })
  }
}

const maybeAutoSendPrompt = async () => {
  if (!pendingAutoSend.value || !inputValue.value.trim() || sending.value) {
    return
  }

  pendingAutoSend.value = false
  await sendMessage()
}

const scrollToBottom = () => {
  nextTick(() => {
    if (chatRef.value) {
      chatRef.value.scrollTop = chatRef.value.scrollHeight
    }
  })
}

const focusLatestConversation = () => {
  nextTick(() => {
    chatPanelRef.value?.scrollIntoView({ block: 'start' })
    if (chatRef.value) {
      chatRef.value.scrollTop = chatRef.value.scrollHeight
    }
  })
}

const sendMessage = async () => {
  if (!inputValue.value.trim()) return
  if (sending.value) return

  ensureActiveSession()

  sending.value = true
  startAnalysisVisualization()
  const inputVal = inputValue.value.trim()
  messageList.value.push({
    role: 'user',
    content: inputVal,
    timestamp: new Date().toISOString()
  })
  inputValue.value = ''
  persistCurrentSession()
  scrollToBottom()

  try {
    const chatRes = await api.sendChat(advisorUserId.value, inputVal)
    backendReachable.value = true
    const goodsList = (chatRes.recommendations ?? []).map(mapRecommendation)
    const relatedGoods = (chatRes.relatedRecommendations ?? []).map(mapRecommendation)
    messageList.value.push({
      role: 'assistant',
      content: chatRes.reply,
      goodsList,
      relatedGoods,
      insights: chatRes.insights ?? [],
      timestamp: chatRes.timestamp,
      budgetSummary: chatRes.budgetSummary,
      detectedIntent: chatRes.detectedIntent,
      fallback: chatRes.fallback
    })
    persistCurrentSession()

    await Promise.all(goodsList.slice(0, 3).map(item => recordView(item, 'chat-recommendation')))
  } catch {
    const validUser = await validateActiveUser()
    if (!validUser) {
      sending.value = false
      return
    }

    backendReachable.value = false
    noticeType.value = 'error'
    notice.value = '当前 AI 请求超时或失败，已切换到本地推荐模式。'
    const fallbackPack = selectFallbackPack(inputVal, featuredGoods.value)
    messageList.value.push({
      role: 'assistant',
      content: '需求速览：本次 AI 导购请求超时或失败，我已切到本地真实商品库继续给你推荐。你现在看到的商品都能直接走详情、加购和下单链路。',
      goodsList: fallbackPack.recommendations,
      relatedGoods: fallbackPack.relatedGoods,
      insights: [
        { label: 'AI模式', value: '规则兜底' },
        { label: '兜底原因', value: 'AI 导购请求超时或失败，当前已切到本地推荐模式。' },
        ...fallbackPack.insights
      ],
      timestamp: new Date().toISOString(),
      budgetSummary: fallbackPack.budgetSummary,
      detectedIntent: fallbackPack.detectedIntent,
      fallback: true
    })
    persistCurrentSession()
  } finally {
    await stopAnalysisVisualization()
    sending.value = false
  }

  scrollToBottom()
}

const initializeChat = async () => {
  syncPromptFromRoute()

  const validUser = await validateActiveUser()
  if (!validUser) {
    return
  }

  if (!currentUser.value) {
    useDemoAdvisor.value = true
    noticeType.value = 'success'
    notice.value = `当前以${advisorUserName.value}模式提供 AI 导购，无需登录也能先问商品。`
  }

  await Promise.allSettled([loadFeaturedProducts(), loadHistory()])
  await loadAiProviderOverview()
  focusLatestConversation()
  await maybeAutoSendPrompt()
}

onMounted(() => {
  void initializeChat()
})

watch(() => route.query.session, (sessionId) => {
  if (typeof sessionId !== 'string' || !sessionId || sessionId === activeSessionId.value) {
    return
  }

  loadSession(sessionId)
})

watch(() => [route.query.prompt, route.query.autoSend], () => {
  syncPromptFromRoute()
  void maybeAutoSendPrompt()
})
</script>

<style scoped>
.shopping-shell {
  max-width: 1460px;
  margin: 0 auto;
  display: grid;
  gap: 22px;
}

.hero-panel {
  display: grid;
  grid-template-columns: minmax(0, 1.3fr) minmax(260px, 0.9fr);
  gap: 14px;
  padding: 18px 20px;
  border-radius: 20px;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.56) 0%, rgba(255, 255, 255, 0.2) 100%),
    radial-gradient(circle at top left, rgba(255, 77, 77, 0.12), transparent 32%),
    radial-gradient(circle at bottom right, rgba(0, 196, 180, 0.1), transparent 28%);
  color: var(--text-main);
  box-shadow: var(--shadow-lg);
  border: 1px solid rgba(255, 255, 255, 0.7);
  backdrop-filter: blur(14px);
}

.hero-copy {
  display: grid;
  gap: 10px;
}

.eyebrow {
  margin: 0;
  font-size: 12px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--brand-deep);
}

.hero-copy h1 {
  margin: 0;
  font-size: 28px;
  line-height: 1.1;
}

.hero-lead {
  margin: 0;
  max-width: 700px;
  color: var(--text-soft);
  line-height: 1.65;
}

.hero-notes {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.hero-notes span {
  display: inline-flex;
  align-items: center;
  padding: 6px 10px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.7);
  border: 1px solid rgba(255, 255, 255, 0.78);
  font-size: 12px;
  color: var(--text-soft);
}

.prompt-list {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.prompt-chip {
  border: 1px solid rgba(255, 255, 255, 0.76);
  background: rgba(255, 255, 255, 0.74);
  color: var(--text-main);
  padding: 6px 12px;
  border-radius: 999px;
  cursor: pointer;
  font-size: 13px;
}

.hero-side {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.provider-panel {
  grid-column: 1 / -1;
  display: grid;
  gap: 12px;
  padding: 14px;
  border-radius: 16px;
  background: linear-gradient(135deg, rgba(36, 51, 64, 0.9) 0%, rgba(31, 111, 92, 0.88) 100%);
  color: #fff;
  box-shadow: var(--shadow-soft);
}

.provider-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
}

.provider-label {
  display: block;
  font-size: 11px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: rgba(255, 255, 255, 0.72);
}

.provider-state {
  display: inline-flex;
  align-items: center;
  padding: 8px 12px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
}

.provider-state.ready {
  background: rgba(223, 245, 234, 0.18);
  color: #dff5ea;
}

.provider-state.fallback {
  background: rgba(255, 241, 236, 0.2);
  color: #fff1ec;
}

.provider-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.provider-field {
  display: grid;
  gap: 6px;
}

.provider-field span {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.82);
}

.provider-input,
.provider-select {
  width: 100%;
  min-height: 40px;
  border-radius: 12px;
  border: 1px solid rgba(255, 255, 255, 0.24);
  background: rgba(255, 255, 255, 0.12);
  color: #fff;
  padding: 0 12px;
}

.provider-input::placeholder {
  color: rgba(255, 255, 255, 0.55);
}

.provider-select option {
  color: #243340;
}

.provider-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.provider-note {
  display: inline-flex;
  align-items: center;
  padding: 7px 10px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.12);
  font-size: 12px;
  color: rgba(255, 255, 255, 0.86);
}

.provider-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  flex-wrap: wrap;
}

.provider-save-btn {
  min-width: 128px;
}

.fallback-reason-banner {
  display: grid;
  gap: 4px;
  margin-top: 10px;
  padding: 10px 12px;
  border-radius: 14px;
  background: rgba(255, 241, 236, 0.9);
  border: 1px solid rgba(214, 99, 72, 0.18);
  color: #8c3525;
}

.fallback-reason-banner strong {
  font-size: 12px;
}

.fallback-reason-banner span {
  font-size: 13px;
  line-height: 1.5;
}

.hero-stat {
  display: grid;
  gap: 4px;
  padding: 12px 14px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.74);
  border: 1px solid rgba(255, 255, 255, 0.8);
  box-shadow: var(--shadow-soft);
}

.hero-stat.emphasis {
  background: linear-gradient(135deg, rgba(255, 77, 77, 0.1) 0%, rgba(255, 255, 255, 0.82) 100%);
}

.hero-stat.accent {
  background: linear-gradient(135deg, rgba(0, 196, 180, 0.12) 0%, rgba(255, 255, 255, 0.82) 100%);
}

.hero-stat strong {
  font-size: 17px;
}

.hero-stat span {
  font-size: 11px;
  color: var(--text-faint);
}

.hero-stat small {
  font-size: 11px;
  color: var(--text-soft);
}

.notice {
  padding: 14px 16px;
  border-radius: 18px;
}

.notice.success {
  background: #e5f6ed;
  color: #0d6b51;
}

.notice.error {
  background: #fff1ec;
  color: #a6412e;
}

.shopping-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.7fr) minmax(300px, 0.82fr);
  gap: 18px;
  align-items: start;
}

.chat-panel,
.sidebar-panel {
  background: rgba(255, 255, 255, 0.9);
  border: 1px solid rgba(255, 255, 255, 0.78);
  border-radius: 28px;
  box-shadow: var(--shadow-lg);
  backdrop-filter: blur(14px);
}

.chat-panel,
.sidebar-panel {
  padding: 18px;
}

.analysis-live-panel {
  display: grid;
  gap: 8px;
  padding: 10px 12px;
  margin-bottom: 12px;
  border-radius: 14px;
  background: rgba(15, 88, 74, 0.08);
  border: 1px solid rgba(15, 88, 74, 0.18);
}

.recent-analysis-panel {
  margin-bottom: 12px;
  display: grid;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 14px;
  background: rgba(22, 154, 127, 0.08);
  border: 1px solid rgba(22, 154, 127, 0.2);
}

.recent-analysis-head {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #1c5f50;
}

.analysis-live-head {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #205447;
}

.analysis-live-steps {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.analysis-step {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border-radius: 999px;
  padding: 4px 10px;
  font-size: 12px;
}

.analysis-step .dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}

.analysis-step.done {
  color: #155d4d;
  background: rgba(32, 162, 136, 0.12);
}

.analysis-step.done .dot {
  background: #169a7f;
}

.analysis-step.pending {
  color: #6d827b;
  background: rgba(109, 130, 123, 0.12);
}

.analysis-step.pending .dot {
  background: #8da19a;
}

.tool-trace-panel {
  margin-top: 10px;
  display: grid;
  gap: 8px;
}

.tool-trace-panel strong {
  font-size: 12px;
  color: #2b5b4f;
}

.tool-trace-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.trace-pill {
  font-size: 12px;
  padding: 6px 10px;
  border-radius: 999px;
  background: rgba(22, 154, 127, 0.1);
  color: #1f5d50;
  border: 1px solid rgba(22, 154, 127, 0.2);
}

.panel-head,
.chat-header,
.cart-header,
.compact-head {
  display: flex;
  justify-content: space-between;
  gap: 14px;
  align-items: center;
}

.panel-head h2,
.chat-header h2,
.compact-head h2 {
  margin: 0;
  font-size: 22px;
}

.session-caption {
  margin: 6px 0 0;
  color: #6f655a;
  font-size: 13px;
}

.chat-header-side {
  display: grid;
  gap: 10px;
  justify-items: end;
}

.session-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.slim-btn,
.slim-link {
  min-height: 34px;
  padding: 0 12px;
}

.slim-link {
  text-decoration: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.ghost-btn,
.clear-cart {
  border: none;
  background: #eef4f3;
  color: #1f6f5c;
  padding: 9px 12px;
  border-radius: 999px;
  cursor: pointer;
}

.goods-img {
  width: 100%;
  aspect-ratio: 4 / 3;
  object-fit: cover;
  border-radius: 16px;
}

.goods-info {
  display: grid;
  gap: 8px;
}

.goods-headline {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
}

.goods-desc {
  margin: 0;
  color: #6a6258;
  line-height: 1.45;
  font-size: 13px;
}

.featured-price,
.goods-price,
.cart-item-price {
  color: #c05b14;
  font-weight: 700;
}

.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.tag-list.compact {
  gap: 6px;
}

.tag-pill {
  display: inline-flex;
  padding: 6px 10px;
  border-radius: 999px;
  background: #f4efe7;
  color: #5d554d;
  font-size: 12px;
}

.status-stack {
  display: grid;
  gap: 8px;
  justify-items: end;
}

.connection-state,
.secondary-badge,
.summary-pill,
.meta-pill,
.mini-insight {
  display: inline-flex;
  align-items: center;
  padding: 7px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
}

.connection-state.online {
  color: #0d6b51;
  background: #dff5ea;
}

.connection-state.offline {
  color: #a6412e;
  background: #fff1ec;
}

.secondary-badge,
.summary-pill,
.mini-insight,
.meta-pill {
  background: #f4efe7;
  color: #5c554d;
}

.summary-pill.warning,
.meta-pill.budget-risk {
  background: #fff1ec;
  color: #a6412e;
}

.meta-pill.budget-ok {
  background: #dff5ea;
  color: #0d6b51;
}

.insight-strip {
  margin-top: 16px;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 10px;
}

.insight-card {
  display: grid;
  gap: 6px;
  padding: 14px;
  border-radius: 18px;
  background: linear-gradient(180deg, #fffdfa 0%, #f5f7fb 100%);
  border: 1px solid #ece3d6;
}

.insight-card span {
  color: #746a5e;
  font-size: 12px;
}

.insight-card strong {
  color: #273644;
}

.chat-container {
  height: 620px;
  margin-top: 16px;
  padding: 8px 6px 8px 0;
  overflow-y: auto;
  scroll-behavior: smooth;
}

.message-item {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
  max-width: 86%;
}

.message-item.user {
  margin-left: auto;
  flex-direction: row-reverse;
}

.avatar {
  width: 42px;
  height: 42px;
  border-radius: 50%;
  background: #e7ecec;
  color: #305061;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 800;
  flex-shrink: 0;
}

.message-item.user .avatar {
  background: #1f6f5c;
  color: #fff;
}

.content {
  flex: 1;
  display: grid;
  gap: 8px;
}

.meta-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
}

.speaker,
.timestamp {
  font-size: 12px;
  color: #746a5e;
}

.text {
  padding: 14px 16px;
  border-radius: 20px;
  line-height: 1.72;
  word-break: break-word;
  white-space: pre-line;
}

.assistant-text {
  display: grid;
  gap: 8px;
  padding: 14px 16px;
  border-radius: 20px;
  background: linear-gradient(180deg, #f9f7f1 0%, #fffdfa 100%);
  color: #243340;
  border-top-left-radius: 6px;
}

.system-text {
  background: linear-gradient(180deg, #f3f6fb 0%, #faf6ef 100%);
}

.text-block {
  display: grid;
  grid-template-columns: 22px 1fr;
  gap: 8px;
  align-items: start;
  padding: 8px 10px;
  border-radius: 14px;
}

.text-block.normal {
  background: rgba(255, 255, 255, 0.7);
}

.text-block.focus {
  background: #fff2df;
}

.text-block.accent {
  background: #eef8f6;
}

.text-emoji {
  font-size: 16px;
  line-height: 1.5;
}

.text-line {
  line-height: 1.72;
}

.text-line :deep(.inline-emphasis) {
  color: #b45309;
  font-weight: 800;
}

.message-item.system .text,
.message-item.assistant .text {
  background: #f6f3ee;
  color: #243340;
  border-top-left-radius: 6px;
}

.message-item.user .text {
  background: linear-gradient(135deg, rgba(255, 77, 77, 0.92) 0%, rgba(0, 196, 180, 0.7) 100%);
  color: #fff;
  border-top-right-radius: 6px;
}

.message-summary,
.message-insights,
.goods-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.advisor-brief {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 10px;
}

.brief-card {
  display: grid;
  gap: 6px;
  padding: 12px 14px;
  border-radius: 18px;
  background: linear-gradient(180deg, #fffdfa 0%, #f5f1ea 100%);
  border: 1px solid #eadfce;
}

.brief-card span {
  color: #7a6f62;
  font-size: 12px;
}

.brief-card strong {
  color: #273644;
  line-height: 1.4;
}

.brief-card small {
  color: #6f655a;
  line-height: 1.45;
}

.brief-card.spotlight {
  background: linear-gradient(135deg, #fff2df 0%, #fffaf2 55%, #eef6f4 100%);
  border-color: #e8c79d;
}

.goods-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 14px;
}

.related-deck {
  display: grid;
  gap: 12px;
}

.related-head,
.bundle-head {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: center;
}

.related-head strong,
.bundle-head strong {
  color: #263543;
}

.related-head span,
.bundle-head span {
  color: #7a6f62;
  font-size: 12px;
}

.related-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
  gap: 12px;
}

.related-card {
  display: grid;
  gap: 10px;
  padding: 12px;
  border-radius: 18px;
  border: 1px solid #eadfce;
  background: linear-gradient(180deg, #fffdfa 0%, #f9f3e9 100%);
}

.related-img {
  width: 100%;
  aspect-ratio: 4 / 3;
  object-fit: cover;
  border-radius: 14px;
}

.related-info {
  display: grid;
  gap: 4px;
}

.related-info span {
  color: #bf5b15;
  font-weight: 700;
}

.related-info p {
  margin: 0;
  color: #675d51;
  font-size: 13px;
  line-height: 1.45;
}

.goods-card {
  display: grid;
  gap: 12px;
  padding: 14px;
  border-radius: 20px;
  border: 1px solid #eadfce;
  background: linear-gradient(180deg, #ffffff 0%, #fffaf4 100%);
  box-shadow: 0 14px 26px rgba(54, 45, 28, 0.06);
}

.goods-media {
  position: relative;
}

.goods-floating-meta {
  position: absolute;
  inset: 12px 12px auto 12px;
  display: flex;
  justify-content: space-between;
  gap: 8px;
}

.rank-badge,
.sales-badge,
.goods-role,
.goods-fit {
  display: inline-flex;
  align-items: center;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
}

.rank-badge,
.sales-badge {
  padding: 8px 10px;
  backdrop-filter: blur(8px);
}

.rank-badge.primary {
  background: rgba(193, 91, 20, 0.92);
  color: #fff;
}

.rank-badge.secondary {
  background: rgba(36, 51, 64, 0.82);
  color: #fff;
}

.sales-badge {
  background: rgba(255, 255, 255, 0.88);
  color: #3f4e5c;
}

.goods-kicker {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.goods-role {
  padding: 6px 10px;
  background: #edf4f6;
  color: #1d4355;
}

.goods-fit {
  padding: 6px 10px;
  background: #fff3e6;
  color: #a25515;
}

.goods-actions,
.related-actions {
  display: flex;
  gap: 10px;
}

.reason-block {
  display: grid;
  gap: 6px;
  padding: 10px 12px;
  border-radius: 14px;
  background: #fbf7f0;
  color: #5f564e;
  font-size: 13px;
  line-height: 1.5;
}

.reason-block strong {
  color: #253443;
  font-size: 12px;
}

.reason-block p {
  margin: 0;
}

.add-cart-btn,
.secondary-btn,
.mini-cart-btn,
.mini-ghost-btn,
.send-btn,
.voice-btn,
.pay-btn {
  border: none;
  cursor: pointer;
}

.add-cart-btn,
.secondary-btn {
  padding: 10px 0;
  border-radius: 14px;
  font-weight: 700;
}

.add-cart-btn {
  background: #1f6f5c;
  color: #fff;
}

.secondary-btn,
.mini-ghost-btn {
  background: #eef4f3;
  color: #1d4355;
}

.add-cart-btn:hover,
.mini-cart-btn:hover,
.send-btn:hover {
  background: #175644;
}

.secondary-btn:hover,
.mini-ghost-btn:hover {
  background: #dce8e7;
}

.mini-cart-btn {
  padding: 9px 12px;
  border-radius: 12px;
  background: #1f6f5c;
  color: #fff;
  font-size: 13px;
  font-weight: 700;
}

.mini-ghost-btn {
  padding: 9px 12px;
  border-radius: 12px;
  font-size: 13px;
  font-weight: 700;
}

.input-container {
  margin-top: 16px;
  padding: 16px;
  display: grid;
  gap: 12px;
  border-radius: 20px;
  border: 1px solid #e7ddd1;
  background: #fffdf9;
}

.emoji-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.emoji-chip {
  min-width: 38px;
  padding: 6px 10px;
  border-radius: 999px;
  border: 1px solid #e4d8c9;
  background: #fff;
  cursor: pointer;
}

.input-row {
  display: flex;
  gap: 12px;
}

.chat-input {
  flex: 1;
  padding: 12px 16px;
  border: 1px solid #e0d6c7;
  border-radius: 24px;
  font-size: 14px;
  outline: none;
}

.chat-input:focus {
  border-color: #1f6f5c;
}

.voice-btn,
.send-btn {
  padding: 12px 18px;
  border-radius: 24px;
  font-size: 14px;
  font-weight: 700;
}

.voice-btn {
  background: #edf4f6;
  color: #1d4355;
}

.voice-btn.active {
  background: #ffe6db;
  color: #9a4025;
}

.send-btn {
  background: #1f6f5c;
  color: #fff;
}

.send-btn:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.sidebar-panel {
  display: grid;
  gap: 16px;
  position: sticky;
  top: 104px;
}

.summary-panel,
.cart-panel {
  display: grid;
  gap: 14px;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.summary-stat {
  display: grid;
  gap: 6px;
  padding: 14px;
  border-radius: 18px;
  background: linear-gradient(180deg, #fffdfa 0%, #f6f7fb 100%);
  border: 1px solid #ece3d6;
}

.summary-stat span {
  color: #786f64;
  font-size: 12px;
}

.summary-stat strong {
  color: #273644;
}

.latest-list {
  display: grid;
  gap: 10px;
}

.bundle-panel {
  display: grid;
  gap: 12px;
  padding-top: 8px;
}

.bundle-list {
  display: grid;
  gap: 10px;
}

.bundle-item {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
  padding: 12px 14px;
  border-radius: 16px;
  background: #fcf8f1;
  border: 1px solid #ece3d6;
}

.bundle-item p {
  margin: 4px 0 0;
  color: #6f655a;
  font-size: 13px;
  line-height: 1.45;
}

.bundle-action {
  display: grid;
  gap: 8px;
  justify-items: end;
}

.latest-item {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
  padding: 12px 14px;
  border-radius: 16px;
  background: #fcf8f1;
  border: 1px solid #ece3d6;
}

.latest-item p,
.empty-summary,
.cart-item-desc,
.empty-cart {
  margin: 4px 0 0;
  color: #6f655a;
  font-size: 13px;
  line-height: 1.5;
}

.cart-header {
  padding-bottom: 12px;
  border-bottom: 1px solid #ece3d6;
  font-weight: 700;
}

.cart-list {
  max-height: 360px;
  overflow-y: auto;
  padding: 4px 0;
}

.cart-error {
  padding: 10px 12px;
  border-radius: 12px;
  background: #fff1ec;
  color: #a6412e;
  font-size: 12px;
}

.cart-item {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 10px;
  padding: 12px 0;
  border-bottom: 1px solid #f1ebe2;
}

.clickable-card {
  cursor: pointer;
  transition: transform 180ms ease, box-shadow 180ms ease, border-color 180ms ease;
}

.clickable-card:hover {
  transform: translateY(-1px);
  box-shadow: 0 12px 18px rgba(31, 41, 55, 0.06);
  border-color: #e0d4c3;
}

.cart-item-main {
  border: none;
  background: transparent;
  text-align: left;
  padding: 0;
  cursor: pointer;
}

.cart-item-name {
  font-size: 14px;
  font-weight: 700;
}

.cart-item-meta {
  display: grid;
  justify-items: end;
  gap: 8px;
}

.cart-stepper {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 6px 8px;
  border-radius: 999px;
  background: #f3f0ea;
}

.step-btn,
.remove-btn {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  border: none;
  cursor: pointer;
}

.step-btn {
  background: #fff;
}

.remove-btn {
  background: #fef2f2;
  color: #ef4444;
}

.cart-footer {
  padding-top: 12px;
  border-top: 1px solid #ece3d6;
}

.total-price {
  font-weight: 700;
  margin-bottom: 12px;
  text-align: right;
}

.pay-btn {
  width: 100%;
  padding: 12px 0;
  border-radius: 14px;
  background: #f97316;
  color: #fff;
}

.pay-btn:hover {
  background: #ea580c;
}

@media (max-width: 1180px) {
  .hero-panel,
  .shopping-grid {
    grid-template-columns: 1fr;
  }

  .provider-grid {
    grid-template-columns: 1fr;
  }

  .hero-panel {
    padding: 16px;
  }

  .sidebar-panel {
    position: static;
  }
}

@media (max-width: 720px) {
  .hero-panel {
    padding: 14px;
    border-radius: 14px;
  }

  .hero-copy h1 {
    font-size: 22px;
  }

  .hero-lead {
    font-size: 13px;
  }

  .prompt-chip {
    padding: 5px 10px;
    font-size: 12px;
  }

  .hero-side {
    grid-template-columns: 1fr 1fr;
  }

  .hero-stat {
    padding: 10px 12px;
  }

  .hero-stat strong {
    font-size: 15px;
  }

  .summary-grid,
  .insight-strip,
  .advisor-brief,
  .goods-list,
  .related-grid {
    grid-template-columns: 1fr;
  }

  .input-row,
  .panel-head,
  .chat-header,
  .cart-header,
  .related-head,
  .bundle-head,
  .bundle-item,
  .goods-actions,
  .related-actions {
    flex-direction: column;
    align-items: stretch;
  }

  .status-stack {
    justify-items: start;
  }

  .message-item,
  .message-item.user {
    max-width: 100%;
  }
}

@media (max-width: 480px) {
  .hero-panel {
    padding: 12px;
    gap: 10px;
    border-radius: 12px;
  }

  .hero-copy h1 {
    font-size: 18px;
  }

  .prompt-list {
    gap: 6px;
  }

  .hero-notes {
    gap: 6px;
  }

  .prompt-chip {
    padding: 4px 8px;
    font-size: 11px;
  }

  .hero-side {
    gap: 8px;
  }

  .hero-stat {
    padding: 8px 10px;
    border-radius: 10px;
  }

  .hero-stat strong {
    font-size: 13px;
  }
}
</style>
