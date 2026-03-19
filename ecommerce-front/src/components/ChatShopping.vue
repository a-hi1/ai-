<template>
  <section class="shopping-shell">
    <div class="hero-toggle-bar">
      <div class="hero-toggle-left">
        <span class="hero-mini-brand">AI 导购</span>
        <span class="connection-state" :class="connectionStateClass">{{ connectionStateText }}</span>
        <span class="secondary-badge">{{ latestBudgetSummary }}</span>
      </div>
      <div class="hero-toggle-right">
        <span class="hero-stat-mini"><b>{{ assistantMessageCount }}</b> 轮对话</span>
        <span class="hero-stat-mini accent"><b>{{ itemCount }}</b> 件购物车</span>
        <button class="hero-collapse-btn" @click="heroExpanded = !heroExpanded">{{ heroExpanded ? '收起' : '配置 & 快捷提问' }}</button>
      </div>
    </div>
    <header class="hero-panel" v-show="heroExpanded">
      <div class="hero-copy">
        <p class="eyebrow">Guide</p>
        <h1>AI 导购</h1>
        <p class="hero-lead">
          不管买什么商品，跟我说一声，我会按品类追问关键需求，再给出主推款、备选款和逻辑成立的搭配建议。
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

    <div class="chat-panel" ref="chatPanelRef">
        <div class="chat-header">
          <div>
            <h2>导购对话工作台</h2>
            <p class="session-caption">{{ activeSessionTitle }}</p>
          </div>
          <div class="chat-header-side">
            <div class="status-stack">
              <div class="mode-toggle">
                <button :class="['mode-btn', answerMode === 'brief' ? 'active' : '']" @click="answerMode = 'brief'">简略</button>
                <button :class="['mode-btn', answerMode === 'detailed' ? 'active' : '']" @click="answerMode = 'detailed'">详细</button>
              </div>
              <span class="connection-state" :class="connectionStateClass">{{ connectionStateText }}</span>
            </div>
            <div class="session-actions">
              <button class="secondary-btn slim-btn" @click="startNewConversation">新增对话</button>
              <RouterLink to="/history" class="mini-ghost-btn slim-link">会话历史</RouterLink>
            </div>
          </div>
        </div>

        <div class="chat-container" ref="chatRef">
          <div class="message-item system">
            <div class="avatar">😊</div>
            <div class="content">
              <div class="meta-row">
                <span class="speaker">AI 导购助手</span>
                <span class="timestamp">{{ formatTime(systemTimestamp) }}</span>
              </div>
              <div class="text assistant-text system-text">
                <div v-for="(block, blockIndex) in formatAssistantBlocks('告诉我你想买什么，我会按品类追问预算、场景和细节偏好，再给出最合适的主推款、备选款，以及真正用得上的搭配推荐。')" :key="`system-${blockIndex}`" :class="['text-block', block.tone]">
                  <span class="text-emoji">{{ block.emoji }}</span>
                  <span class="text-line" v-html="block.html"></span>
                </div>
              </div>
            </div>
          </div>

          <div v-for="(msg, index) in visibleMessageList" :key="`${msg.role}-${index}-${msg.timestamp}`" :class="['message-item', msg.role]">
            <div class="avatar">{{ msg.role === 'user' ? '👤' : '😊' }}</div>
            <div class="content">
              <div class="meta-row">
                <span class="speaker">{{ msg.role === 'user' ? '你的需求' : 'AI 导购建议' }}</span>
                <span class="timestamp">{{ formatTime(msg.timestamp) }}</span>
              </div>

              <div v-if="msg.role === 'assistant' && answerMode === 'detailed'" class="text assistant-text">
                <div v-for="(block, blockIndex) in formatAssistantBlocks(compactAssistantDetail(msg.content))" :key="`${msg.timestamp}-${blockIndex}`" :class="['text-block', block.tone]">
                  <span class="text-emoji">{{ block.emoji }}</span>
                  <span class="text-line" v-html="block.html"></span>
                </div>
              </div>
              <div v-if="msg.role === 'assistant' && answerMode === 'brief'" class="brief-summary-row">
                <span class="brief-one-line">{{ briefAssistantSentence(msg) }}</span>
              </div>
              <div v-if="msg.role === 'user'" class="text">{{ msg.content }}</div>

              <div v-if="msg.goodsList?.length" class="result-showcase">
                <div class="result-showcase-head">
                  <strong>推荐结果展示</strong>
                </div>
              <div class="goods-list">
                <div class="goods-card" v-for="(goods, goodsIndex) in msg.goodsList" :key="goods.id">
                  <div class="goods-media">
                    <img :src="goods.image" alt="商品图片" class="goods-img" loading="lazy" decoding="async" />
                    <div class="goods-floating-meta">
                      <span class="rank-badge" :class="goodsIndex === 0 ? 'primary' : 'secondary'">{{ recommendationBadge(goodsIndex) }}</span>
                      <span class="sales-badge">{{ formatSalesLabel(goods.salesCount) }}</span>
                    </div>
                  </div>
                  <div class="goods-info">
                    <div class="goods-headline">
                      <div class="goods-name">{{ goods.name }}</div>
                      <div class="goods-price">{{ formatCurrency(goods.price) }}</div>
                    </div>
                    <div v-if="goods.tags?.length" class="tag-list compact">
                      <span v-for="tag in goods.tags" :key="tag" class="tag-pill">{{ tag }}</span>
                    </div>
                    <div v-if="formatReasonLines(goods).length" class="reason-panel">
                      <div class="reason-title">推荐理由</div>
                      <ul class="reason-list">
                        <li v-for="(line, reasonIndex) in formatReasonLines(goods)" :key="`reason-${goods.id}-${reasonIndex}`">
                          {{ line }}
                        </li>
                      </ul>
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
              </div>

              <div v-if="msg.relatedGoods?.length" class="related-deck">
                <div class="related-head">
                  <strong>{{ relatedDeckTitle(msg) }}</strong>
                  <span>{{ relatedDeckSubtitle(msg) }}</span>
                </div>
                <div class="related-grid">
                  <article v-for="related in msg.relatedGoods" :key="`related-${msg.timestamp}-${related.id}`" class="related-card">
                    <img :src="related.image" :alt="related.name" class="related-img" loading="lazy" decoding="async" />
                    <div class="related-info">
                      <strong>{{ related.name }}</strong>
                      <span>{{ formatCurrency(related.price) }}</span>
                    </div>
                    <div class="related-actions">
                      <button class="mini-ghost-btn" @click="viewProduct(related)">详情</button>
                      <button class="mini-cart-btn" @click="addToCart(related)">
                        {{ isInCart(related.id) ? '再加一件' : '加入搭配' }}
                      </button>
                    </div>
                  </article>
                </div>
              </div>
            </div>
          </div>
        </div>

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

        <div class="input-container">
          <div class="advice-mode-selector">
            <span class="mode-label">推荐模式</span>
            <button 
              :class="['mode-btn', { active: adviceMode === 'deep' }]" 
              @click="adviceMode = 'deep'"
              title="利用知识库进行多轮智能澄清，推荐精准度高"
            >
              💡 深度模式
            </button>
            <button 
              :class="['mode-btn', { active: adviceMode === 'quick' }]" 
              @click="adviceMode = 'quick'"
              title="快速提取核心信息，2-3句话给出方案"
            >
              ⚡ 快速模式
            </button>
          </div>

          <div class="emoji-bar">
            <button v-for="emoji in emojiSuggestions" :key="emoji" class="emoji-chip" type="button" @click="appendEmoji(emoji)">{{ emoji }}</button>
          </div>
          <div class="input-main-row">
            <input
              v-model="inputValue"
              type="text"
              placeholder="例如：预算 3000 元以内，帮我推荐适合通勤和视频会议的降噪耳机"
              class="chat-input"
              @keyup.enter="sendMessage"
            />
            <div class="input-actions">
              <button class="voice-btn compact" :class="{ active: listening }" @click="toggleVoiceInput">
                {{ listening ? '停止' : '语音' }}
              </button>
              <button class="send-btn compact" :disabled="sending" @click="sendMessage">
                {{ sending ? '分析中' : '发送' }}
              </button>
            </div>
          </div>
        </div>
    </div>

    <!-- Floating Cart Bubble -->
    <div class="cart-bubble-wrapper">
      <transition name="cart-slide">
        <div class="cart-drawer" v-if="cartOpen">
          <div class="cart-drawer-header">
            <span class="cart-drawer-title">购物车（{{ itemCount }}）</span>
            <div class="cart-drawer-actions">
              <button class="clear-cart" @click="clearCart">清空</button>
              <button class="cart-drawer-close" @click="cartOpen = false">×</button>
            </div>
          </div>
          <div v-if="cartErrorMessage" class="cart-error">{{ cartErrorMessage }}</div>
          <div class="cart-list">
            <div v-if="!cartItems.length" class="empty-cart">购物车还是空的，先从导购结果里挑几件商品吧。</div>
            <div v-else class="cart-item" v-for="item in cartItems" :key="item.id">
              <button class="cart-item-main" @click="viewProduct(item)">
                <div class="cart-item-name">{{ item.name }}</div>
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
          <div v-if="cartItems.length" class="cart-drawer-footer">
            <div class="total-price">合计：{{ cartTotalLabel }}</div>
            <button class="pay-btn" @click="toPay">前往结算</button>
          </div>
        </div>
      </transition>
      <button class="cart-bubble-btn" @click="cartOpen = !cartOpen" :class="{ open: cartOpen }">
        <span class="bubble-icon">🛒</span>
        <span v-if="itemCount > 0" class="bubble-badge">{{ itemCount }}</span>
        <span class="bubble-label">{{ cartOpen ? '收起' : (itemCount > 0 ? cartTotalLabel : '购物车') }}</span>
      </button>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useAuth } from '../composables/useAuth'
import { useCart } from '../composables/useCart'
import { api, type AiProviderOverviewDto, type ChatRecommendationDto, type ChatStreamProgress, type ProductDto } from '../services/api'
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

type GuidedRequirementProfile = {
  category: string
  budget: number | null
  usage: string
  brand: string
  detailA: string
  detailB: string
}

type GuidedStep = {
  key: keyof GuidedRequirementProfile
  question: string
  required?: boolean
}

type CategoryGuideStrategy = {
  id: string
  matchKeywords: string[]
  detailALabel: string
  detailBLabel: string
  steps: GuidedStep[]
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
const analysisLiveSteps = ref<string[]>([])
const cartOpen = ref(false)
const answerMode = ref<'brief' | 'detailed'>('detailed')
const adviceMode = ref<'deep' | 'quick'>('deep')
const heroExpanded = ref(false)
const guidedModeEnabled = ref(false)
const guidedActive = ref(false)
const guidedStep = ref(0)
const guidedQuestionCount = ref(0)
const guidedSkippedKeys = ref<Array<keyof GuidedRequirementProfile>>([])
const guidedProfile = ref<GuidedRequirementProfile>({
  category: '',
  budget: null,
  usage: '',
  brand: '',
  detailA: '',
  detailB: ''
})

const BASE_GUIDED_STEPS: GuidedStep[] = [
  { key: 'category', question: '你这次想买什么商品？可以直接说品类，比如手机、耳机、电脑、零食、护肤品。', required: true },
  { key: 'budget', question: '预算大概是多少？例如 300、1500、5000 以内，或者说预算不限。', required: true }
]

const CATEGORY_GUIDE_STRATEGIES: CategoryGuideStrategy[] = [
  {
    id: 'audio',
    matchKeywords: ['耳机', 'headphone', 'audio', 'buds', 'airpods', 'freebuds'],
    detailALabel: '核心功能偏好',
    detailBLabel: '佩戴与舒适度',
    steps: [
      { key: 'usage', question: '耳机主要用在什么场景？比如通勤降噪、视频会议、运动、睡前听歌。', required: true },
      { key: 'brand', question: '有品牌偏好吗？例如索尼、华为、苹果，或者都可以。', required: true },
      { key: 'detailA', question: '更看重哪一点？降噪强度、音质表现、通话清晰度还是续航。', required: true },
      { key: 'detailB', question: '佩戴方式和舒适度有没有要求？比如入耳、头戴、久戴不胀耳、适合运动。'}
    ]
  },
  {
    id: 'skincare',
    matchKeywords: ['护肤', '面膜', '面霜', '精华', '乳液', '洁面', '护肤品'],
    detailALabel: '肤质情况',
    detailBLabel: '核心功效',
    steps: [
      { key: 'usage', question: '准备在什么场景下用？比如日常维稳、换季修护、熬夜急救、送礼。', required: true },
      { key: 'brand', question: '有品牌偏好吗？比如理肤泉、CeraVe、欧莱雅，或者都可以。', required: true },
      { key: 'detailA', question: '你的肤质更偏哪种？干皮、油皮、混干、混油还是敏感肌。', required: true },
      { key: 'detailB', question: '最想解决什么问题？保湿、修护、控油、提亮、抗老、清洁。', required: true }
    ]
  },
  {
    id: 'laptop',
    matchKeywords: ['电脑', '笔记本', 'laptop', 'notebook', '轻薄本', '办公本', '游戏本', '平板'],
    detailALabel: '配置取向',
    detailBLabel: '形态细节',
    steps: [
      { key: 'usage', question: '主要做什么？办公、上课、设计、编程、剪辑还是游戏。', required: true },
      { key: 'brand', question: '有品牌偏好吗？例如联想、华硕、苹果、戴尔，或者都可以。', required: true },
      { key: 'detailA', question: '更偏向轻薄续航还是性能释放？也可以直接说想要的内存、屏幕或芯片档位。', required: true },
      { key: 'detailB', question: '还有什么细节要求？比如接口丰富、屏幕素质、静音、重量、便携。'}
    ]
  },
  {
    id: 'food',
    matchKeywords: ['零食', '咖啡', '饮料', '牛奶', '茶叶', '坚果', '食品'],
    detailALabel: '口味偏好',
    detailBLabel: '限制条件',
    steps: [
      { key: 'usage', question: '主要是自己吃、办公室囤货、早餐补给，还是送礼分享？', required: true },
      { key: 'brand', question: '有品牌偏好吗？没有的话也可以说都可以。', required: true },
      { key: 'detailA', question: '更偏什么口味或方向？比如低糖、提神、香脆、浓郁、耐吃。', required: true },
      { key: 'detailB', question: '有没有需要避开的点？比如太甜、咖啡因、过敏原、独立包装。'}
    ]
  },
  {
    id: 'pet',
    matchKeywords: ['宠物', '猫粮', '狗粮', '猫咪', '狗狗', '猫砂'],
    detailALabel: '宠物情况',
    detailBLabel: '功能偏好',
    steps: [
      { key: 'usage', question: '是日常喂养、外出出行、看诊，还是补常备用品？', required: true },
      { key: 'brand', question: '有品牌偏好吗？比如网易严选、皇家、霍尼韦尔，或者都可以。', required: true },
      { key: 'detailA', question: '宠物目前是什么情况？比如成猫、幼猫、挑食、肠胃敏感、需要外出。', required: true },
      { key: 'detailB', question: '更看重什么？适口性、成分、安全感、透气性、便携性。'}
    ]
  },
  {
    id: 'default',
    matchKeywords: [],
    detailALabel: '关键偏好',
    detailBLabel: '补充限制',
    steps: [
      { key: 'usage', question: '主要用在什么场景？比如通勤、办公、送礼、家用、学生使用。', required: true },
      { key: 'brand', question: '有品牌偏好吗？如果没有也可以直接说都可以。', required: true },
      { key: 'detailA', question: '你最在意的是什么？比如性能、颜值、尺寸、续航、材质、口味、功效。', required: true },
      { key: 'detailB', question: '还有没有必须满足或必须避开的条件？没有可以直接说没要求。'}
    ]
  }
]

const CATEGORY_KEYWORDS = [
  '手机', '耳机', '电脑', '笔记本', '平板', '显示器', '键盘', '鼠标', '相机', '手表', '手环',
  '电视', '冰箱', '空调', '洗衣机', '风扇', '路由器', '音箱', '麦克风', '充电器', '充电宝',
  '零食', '牛奶', '咖啡', '饮料', '白酒', '红酒', '茶叶', '水果', '大米', '食用油',
  '护肤品', '面膜', '面霜', '精华', '口红', '粉底', '香水', '洗发水', '沐浴露',
  '衣服', '外套', '羽绒服', '裤子', '鞋', '跑鞋', '球鞋', '背包', '行李箱'
]

const HIDDEN_GOODS_TAGS = new Set(['xlsx', 'csv', '精选商品', '精选'])

const FUZZY_UNKNOWN_WORDS = ['都可以', '都行', '无所谓', '随便', '不太清楚', '不清楚', '不确定', '你决定', '没要求']
const DEFAULT_ANALYSIS_PROGRESS_STEPS = [
  '识别需求与预算',
  '检索商品候选',
  '匹配场景与偏好',
  '生成推荐理由',
  '整理最终答复'
]
const MAX_VISIBLE_MESSAGES = 40

let analysisTimer: number | null = null
let analysisStartedAt = 0
let scrollFrameId: number | null = null

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
const visibleMessageList = computed(() => {
  if (messageList.value.length <= MAX_VISIBLE_MESSAGES) {
    return messageList.value
  }
  return messageList.value.slice(-MAX_VISIBLE_MESSAGES)
})
const latestAssistantMessage = computed(() => {
  const messages = assistantMessages.value
  return messages.length ? messages[messages.length - 1] : null
})
const latestBudgetSummary = computed(() => latestAssistantMessage.value?.budgetSummary ?? '未设置预算')
const latestAdvisorMode = computed(() => {
  if (!currentUser.value) {
    return '未登录'
  }
  return latestAssistantMessage.value?.fallback ? '规则兜底导购' : '实时 AI 导购'
})
const latestAdvisorSource = computed(() => {
  if (!currentUser.value) {
    return '登录后可开启实时导购'
  }

  if (!latestAssistantMessage.value) {
    return backendReachable.value ? '等待实时推荐' : '当前可回退到本地兜底'
  }

  return latestAssistantMessage.value.fallback ? '来源：规则保底推荐' : '来源：大模型实时生成'
})
const assistantMessageCount = computed(() => assistantMessages.value.length)
const cartTotalLabel = computed(() => formatCurrency(Number(totalPrice.value)))
const connectionStateClass = computed(() => {
  if (!currentUser.value) {
    return 'offline'
  }
  return backendReachable.value ? 'online' : 'offline'
})
const connectionStateText = computed(() => {
  if (!currentUser.value) {
    return '离线(未登录)'
  }
  return backendReachable.value ? '后端在线' : '本地兜底'
})
const advisorUser = computed(() => {
  if (useDemoAdvisor.value || !currentUser.value) {
    return DEMO_GUIDE_USER
  }
  return currentUser.value
})
const advisorUserId = computed(() => advisorUser.value.id)
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

const parseBudgetFromText = (text: string) => {
  const matches = [...text.matchAll(/(\d{2,6})(?:\s*)(?:元|块|rmb|人民币|预算)?/gi)]
  if (!matches.length) {
    return null
  }

  const values = matches.map(item => Number(item[1] || 0)).filter(value => value > 0)
  if (!values.length) {
    return null
  }

  return Math.max(...values)
}

const includesAny = (text: string, keywords: string[]) => keywords.some(keyword => text.includes(keyword))
const isFuzzyUnknownAnswer = (text: string) => includesAny(text.toLowerCase(), FUZZY_UNKNOWN_WORDS)
const wantsDirectRecommendation = (text: string) => includesAny(text.toLowerCase(), ['直接推荐', '直接给推荐', '你直接推荐', '你帮我定', '不用问了', '先推荐'])
const hasDirectCommodityDemand = (text: string) => {
  return includesAny(text.toLowerCase(), CATEGORY_KEYWORDS)
    || includesAny(text.toLowerCase(), ['mp3', '播放器', '随身听', '鼠标', 'keyboard', 'mouse', '耳机', '笔记本', '充电器'])
}

const markGuidedStepSkipped = (key: keyof GuidedRequirementProfile) => {
  if (!guidedSkippedKeys.value.includes(key)) {
    guidedSkippedKeys.value.push(key)
  }
}

const detectCategoryFromText = (rawMessage: string) => {
  const text = rawMessage.toLowerCase().trim()
  const matched = CATEGORY_KEYWORDS.find(keyword => text.includes(keyword))
  if (matched) {
    return matched
  }

  const extracted = rawMessage.match(/(?:买|购|要|想要|想买|需要)([^，。！？,.]{1,12})/)
  if (extracted?.[1]) {
    return extracted[1].trim()
  }

  if (rawMessage.length <= 12 && !isFuzzyUnknownAnswer(rawMessage)) {
    return rawMessage.trim()
  }

  return ''
}

const getGuideStrategy = (category: string) => {
  const normalized = (category || '').toLowerCase()
  return CATEGORY_GUIDE_STRATEGIES.find(strategy => strategy.matchKeywords.some(keyword => normalized.includes(keyword.toLowerCase())))
    || CATEGORY_GUIDE_STRATEGIES.find(strategy => strategy.id === 'default')!
}

const getGuidedSteps = () => {
  const strategy = getGuideStrategy(guidedProfile.value.category)
  return [...BASE_GUIDED_STEPS, ...strategy.steps]
}

const fillCurrentGuidedAnswer = (step: GuidedStep | undefined, rawMessage: string) => {
  if (!step || isGuidedStepDone(step.key) || isFuzzyUnknownAnswer(rawMessage)) {
    return
  }

  if (step.key === 'category') {
    guidedProfile.value.category = guidedProfile.value.category || detectCategoryFromText(rawMessage) || rawMessage.trim()
    return
  }

  if (step.key === 'budget') {
    const budget = parseBudgetFromText(rawMessage)
    if (budget !== null) {
      guidedProfile.value.budget = budget
    }
    return
  }

  const value = rawMessage.trim()
  if (!value) {
    return
  }
  guidedProfile.value[step.key] = value as never
}

const updateGuidedProfileFromMessage = (rawMessage: string) => {
  const text = rawMessage.toLowerCase()
  const profile = guidedProfile.value

  if (!profile.category) {
    const category = detectCategoryFromText(rawMessage)
    if (category) {
      profile.category = category
    }
  }

  const budget = parseBudgetFromText(text)
  if (budget !== null) {
    profile.budget = budget
  }

  if (!profile.usage && includesAny(text, ['拍照', '摄影', '游戏', '办公', '通勤', '商务', '老人', '长辈', '日常', '送礼', '学习', '家用', '运动'])) {
    profile.usage = rawMessage.trim()
  }

  if (!profile.brand && includesAny(text, ['华为', '荣耀', '小米', '红米', 'oppo', 'vivo', '苹果', 'iphone', '三星', '联想', '戴尔', '惠普', '华硕', '耐克', '阿迪', '都可以', '无所谓'])) {
    profile.brand = rawMessage.trim()
  }

  if (!profile.detailA && includesAny(text, ['降噪', '音质', '通话', '续航', '肤质', '干皮', '油皮', '敏感肌', '保湿', '修护', '控油', '抗老', '性能', '轻薄', '口味', '成分', '适口性'])) {
    profile.detailA = rawMessage.trim()
  }

  if (!profile.detailB && includesAny(text, ['佩戴', '入耳', '头戴', '舒适', '重量', '接口', '尺寸', '便携', '限制', '避开', '过敏', '独立包装', '透气', '外出'])) {
    profile.detailB = rawMessage.trim()
  }
}

const isGuidedStepDone = (key: keyof GuidedRequirementProfile) => {
  if (guidedSkippedKeys.value.includes(key)) {
    return true
  }

  const value = guidedProfile.value[key]
  if (typeof value === 'number') {
    return Number.isFinite(value)
  }
  return Boolean((value || '').toString().trim())
}

const getGuidedCompletionScore = () => {
  const profile = guidedProfile.value
  const coreFilled = [
    Boolean(profile.category) || guidedSkippedKeys.value.includes('category'),
    profile.budget !== null || guidedSkippedKeys.value.includes('budget'),
    Boolean(profile.usage) || guidedSkippedKeys.value.includes('usage'),
    Boolean(profile.brand) || guidedSkippedKeys.value.includes('brand')
  ].filter(Boolean).length

  const extraFilled = [
    Boolean(profile.detailA) || guidedSkippedKeys.value.includes('detailA'),
    Boolean(profile.detailB) || guidedSkippedKeys.value.includes('detailB')
  ].filter(Boolean).length

  return { coreFilled, extraFilled }
}

const shouldFinalizeGuidedFlow = (latestMessage: string) => {
  const { coreFilled, extraFilled } = getGuidedCompletionScore()

  if (coreFilled >= 3) {
    return true
  }

  if (coreFilled >= 2 && guidedQuestionCount.value >= 2) {
    return true
  }

  if (guidedQuestionCount.value >= 4) {
    return true
  }

  if (wantsDirectRecommendation(latestMessage) && coreFilled >= 1) {
    return true
  }

  return extraFilled >= 2 && coreFilled >= 1
}

const getNextGuidedStep = (): ({ key: keyof GuidedRequirementProfile, question: string, index: number }) | null => {
  for (const [index, step] of getGuidedSteps().entries()) {
    if (!isGuidedStepDone(step.key)) {
      return { key: step.key, question: step.question, index }
    }
  }
  return null
}

const shouldEnterGuidedFlow = (userMessage: string) => {
  if (!guidedModeEnabled.value) {
    return false
  }

  const text = userMessage.trim().toLowerCase()
  if (!text) {
    return false
  }

  if (wantsDirectRecommendation(text)) {
    return false
  }

  const hasCategory = Boolean(detectCategoryFromText(userMessage))
  const hasBudget = parseBudgetFromText(userMessage) !== null
  const hasUsage = includesAny(text, ['学习', '办公', '通勤', '游戏', '送礼', '家用', '运动', '外出'])
  const hasBrand = includesAny(text, ['华为', '荣耀', '小米', '苹果', '索尼', '联想', '戴尔', '惠普', '华硕', '都可以'])
  const signalCount = [hasCategory, hasBudget, hasUsage, hasBrand].filter(Boolean).length

  if (hasCategory && (!hasBudget || !hasUsage)) {
    return true
  }

  if (hasDirectCommodityDemand(text) && signalCount <= 2) {
    return true
  }

  return signalCount <= 1
}

const buildGuidedPrompt = (userMessage: string) => {
  const profile = guidedProfile.value
  const strategy = getGuideStrategy(profile.category)
  const budgetText = profile.budget ? `${profile.budget} 元` : (guidedSkippedKeys.value.includes('budget') ? '预算不设限' : '未明确')
  const asLabel = (key: keyof GuidedRequirementProfile, value: string) => value || (guidedSkippedKeys.value.includes(key) ? '不限定' : '未明确')

  return [
    '你是资深电商导购专家，请基于以下信息给出最合理推荐。',
    '如果用户条件不完整，请按常见主流偏好补齐合理默认值，并说明你如何取舍。',
    `用户原始需求：${userMessage}`,
    `品类：${asLabel('category', profile.category)}`,
    `预算：${budgetText}`,
    `使用场景：${asLabel('usage', profile.usage)}`,
    `品牌偏好：${asLabel('brand', profile.brand)}`,
    `${strategy.detailALabel}：${asLabel('detailA', profile.detailA)}`,
    `${strategy.detailBLabel}：${asLabel('detailB', profile.detailB)}`,
    '请输出 3 款主推和 2 款备选，优先满足预算和关键刚需。',
    '如果该品类存在自然成立的搭配链路，再额外给出 1 到 2 个合理搭配推荐。',
    '搭配推荐必须满足以下条件：服务主商品使用场景、不是同类替代品、不是硬凑、理由要具体。'
  ].join('\n')
}

const pushAssistantQuestion = (question: string) => {
  messageList.value.push({
    role: 'assistant',
    content: question,
    timestamp: new Date().toISOString(),
    detectedIntent: `${guidedProfile.value.category || '商品'}导购需求采集中`,
    budgetSummary: guidedProfile.value.budget ? `预算约 ${formatCurrency(guidedProfile.value.budget)}` : '预算待确认'
  })
  persistCurrentSession()
}

const compactAssistantDetail = (content: string) => {
  const text = (content || '').replace(/\s+/g, ' ').trim()
  if (!text) {
    return '已为你整理好推荐结果，请直接查看下方商品卡片。'
  }

  if (text.length <= 260) {
    return text
  }

  const sentenceMatches = text.match(/[^。！？!?；;]+[。！？!?；;]?/g) ?? []
  const conciseSentences = sentenceMatches
    .map(item => item.trim())
    .filter(Boolean)
    .slice(0, 4)

  return conciseSentences.length ? conciseSentences.join(' ') : text
}

const briefAssistantSentence = (msg: Message) => {
  const count = msg.goodsList?.length ?? 0
  const intent = msg.detectedIntent?.trim()

  if (count > 0 && intent) {
    return `已按「${intent}」为你筛选 ${count} 款商品，直接看下方卡片即可。`
  }

  if (count > 0) {
    return `已为你筛选 ${count} 款商品，直接看下方卡片即可。`
  }

  return '已根据你的需求整理建议，请查看下方内容。'
}

const relatedDeckTitle = (message: Message) => {
  return `${message.detectedIntent || '本轮推荐'}合理搭配`
}

const relatedDeckSubtitle = (message: Message) => {
  const mainGoods = firstRecommendedGoods(message)
  if (!mainGoods) {
    return '这些商品是围绕主推结果补齐使用链路的补充选择。'
  }
  return `围绕 ${mainGoods.name} 补齐使用链路，只展示逻辑成立的补充商品。`
}

const firstRecommendedGoods = (message: Message) => message.goodsList?.[0]

const recommendationBadge = (index: number) => {
  if (index === 0) return 'TOP 1'
  if (index === 1) return 'TOP 2'
  return `TOP ${index + 1}`
}

const formatSalesLabel = (salesCount: number) => {
  if (salesCount <= 0) {
    return '新品观察'
  }
  return `成交 ${salesCount} 件`
}

const formatReasonLines = (goods: Goods) => {
  const raw = (goods.reason || '').trim()
  if (!raw) {
    return []
  }

  return raw
    .split(/\n|；|;|。/)
    .map(item => item.trim())
    .filter(Boolean)
    .slice(0, 3)
}

const sanitizeGoodsTags = (tags?: string[]) => {
  return (tags ?? [])
    .map(tag => (tag || '').trim())
    .filter(Boolean)
    .filter(tag => !HIDDEN_GOODS_TAGS.has(tag.toLowerCase()))
    .slice(0, 4)
}

const analysisElapsedLabel = computed(() => `已分析 ${analysisElapsedSeconds.value}s`)
const analysisProgressSteps = computed(() => {
  return analysisLiveSteps.value.length ? analysisLiveSteps.value : DEFAULT_ANALYSIS_PROGRESS_STEPS
})

const startAnalysisVisualization = () => {
  analysisVisible.value = true
  analysisStartedAt = Date.now()
  analysisStepIndex.value = 0
  analysisElapsedSeconds.value = 0
  analysisLiveSteps.value = [...DEFAULT_ANALYSIS_PROGRESS_STEPS]
  if (analysisTimer !== null) {
    window.clearInterval(analysisTimer)
  }
  analysisTimer = window.setInterval(() => {
    analysisElapsedSeconds.value += 1
    if (analysisElapsedSeconds.value % 2 === 0 && analysisStepIndex.value < analysisProgressSteps.value.length - 1) {
      analysisStepIndex.value += 1
    }
  }, 1000)
}

const syncAnalysisProgress = (progress: ChatStreamProgress) => {
  const step = (progress.step || '').trim()
  if (!step) {
    return
  }

  if (!analysisLiveSteps.value.includes(step)) {
    analysisLiveSteps.value.push(step)
  }

  if (progress.total > 0 && analysisLiveSteps.value.length < progress.total) {
    while (analysisLiveSteps.value.length < progress.total) {
      analysisLiveSteps.value.push(`处理中步骤 ${analysisLiveSteps.value.length + 1}`)
    }
  }

  const targetIndex = progress.index > 0 ? progress.index - 1 : analysisLiveSteps.value.indexOf(step)
  if (targetIndex >= 0) {
    analysisStepIndex.value = Math.max(analysisStepIndex.value, Math.min(targetIndex, analysisLiveSteps.value.length - 1))
  }
}

const sleep = (ms: number) => new Promise<void>(resolve => window.setTimeout(resolve, ms))

const stopAnalysisVisualization = async () => {
  const minVisibleMs = 1800
  const elapsedMs = Date.now() - analysisStartedAt
  if (elapsedMs < minVisibleMs) {
    await sleep(minVisibleMs - elapsedMs)
  }

  analysisStepIndex.value = analysisProgressSteps.value.length - 1
  await sleep(320)

  if (analysisTimer !== null) {
    window.clearInterval(analysisTimer)
    analysisTimer = null
  }
  analysisStepIndex.value = 0
  analysisElapsedSeconds.value = 0
  analysisLiveSteps.value = []
  analysisVisible.value = false
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
    useDemoAdvisor.value = false
    noticeType.value = 'error'
    notice.value = '当前未登录，状态为离线。请先登录或注册后再进行实时导购。'
    return false
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

const ensureAiOpeningMessage = () => {
  if (!activeSessionId.value) {
    return
  }

  const hasAnyMessage = messageList.value.length > 0
  if (hasAnyMessage) {
    return
  }

  messageList.value.push({
    role: 'assistant',
    content: '你好呀，我是小选。我们一步一步来，我先了解你的需求再精准推荐。你这次想买什么品类？',
    timestamp: new Date().toISOString(),
    detectedIntent: '需求采集中',
    budgetSummary: '预算待确认'
  })
  persistCurrentSession()
}

const resetGuidedFlow = () => {
  guidedActive.value = false
  guidedStep.value = 0
  guidedQuestionCount.value = 0
  guidedSkippedKeys.value = []
  guidedProfile.value = {
    category: '',
    budget: null,
    usage: '',
    brand: '',
    detailA: '',
    detailB: ''
  }
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
  if (!currentUser.value) {
    noticeType.value = 'error'
    notice.value = '请先登录或注册后再新建导购对话。'
    void router.push('/login')
    return
  }

  const session = createChatSession(advisorUserId.value)
  refreshSessionList()
  resetGuidedFlow()
  activeSessionId.value = session.id
  messageList.value = []
  ensureAiOpeningMessage()
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
    desc: item.description || item.sellingPoints || item.specs || item.policy || '查看商品详情',
    image: item.imageUrl || `https://picsum.photos/seed/product-${index + 1}/480/320`,
    reason: item.sellingPoints || item.specs || item.policy || '来自商品库样本',
    salesCount: 0,
    withinBudget: true,
    tags: sanitizeGoodsTags(item.tags ? item.tags.split(/[,，]/).map(tag => tag.trim()) : [])
  }))
}

const mapRecommendation = (item: ChatRecommendationDto): Goods => ({
  id: item.productId,
  name: item.name,
  price: item.price,
  desc: item.description || item.reason || '查看商品详情',
  image: item.imageUrl || `https://picsum.photos/seed/${item.productId}/480/320`,
  reason: item.reason,
  salesCount: item.salesCount,
  withinBudget: item.withinBudget,
  tags: sanitizeGoodsTags(item.tags ?? [])
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
    if (scrollFrameId !== null) {
      window.cancelAnimationFrame(scrollFrameId)
    }
    scrollFrameId = window.requestAnimationFrame(() => {
      if (chatRef.value) {
        chatRef.value.scrollTop = chatRef.value.scrollHeight
      }
      scrollFrameId = null
    })
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
  if (!currentUser.value) {
    noticeType.value = 'error'
    notice.value = '当前未登录，无法发送导购需求。请先登录或注册账号。'
    void router.push('/login')
    return
  }

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

  const currentCategory = detectCategoryFromText(inputVal)
  if (currentCategory && guidedProfile.value.category && currentCategory !== guidedProfile.value.category) {
    resetGuidedFlow()
  }

  const enteringGuidedFlow = !guidedActive.value && shouldEnterGuidedFlow(inputVal)
  if (enteringGuidedFlow) {
    guidedActive.value = true
    guidedQuestionCount.value = 0
    const detectedCategory = detectCategoryFromText(inputVal)
    if (detectedCategory) {
      guidedProfile.value.category = detectedCategory
    }
  }

  if (guidedActive.value) {
    const currentStep = getGuidedSteps()[guidedStep.value]

    if (currentStep && isFuzzyUnknownAnswer(inputVal)) {
      markGuidedStepSkipped(currentStep.key)
      if (currentStep.key === 'brand' && !guidedProfile.value.brand) {
        guidedProfile.value.brand = '主流品牌均可'
      }
      if (currentStep.key === 'usage' && !guidedProfile.value.usage) {
        guidedProfile.value.usage = '日常综合使用'
      }
      if (currentStep.key === 'category' && !guidedProfile.value.category) {
        guidedProfile.value.category = '商品'
      }
    }

    fillCurrentGuidedAnswer(currentStep, inputVal)
    updateGuidedProfileFromMessage(inputVal)

    const pendingStep = getNextGuidedStep()
    if (pendingStep && !shouldFinalizeGuidedFlow(inputVal)) {
      guidedStep.value = pendingStep.index
      guidedQuestionCount.value += 1
      pushAssistantQuestion(pendingStep.question)
      await stopAnalysisVisualization()
      sending.value = false
      scrollToBottom()
      return
    }
  }

  const requestMessage = guidedActive.value
    ? buildGuidedPrompt(inputVal)
    : inputVal

  const draftAssistantMessage: Message = {
    role: 'assistant',
    content: '我在理解你的需求，马上开始一步步确认。',
    timestamp: new Date().toISOString()
  }
  messageList.value.push(draftAssistantMessage)
  scrollToBottom()

  try {
    let receivedFirstDelta = false
    let streamedFinalPayload = null as Awaited<ReturnType<typeof api.sendChatStream>>
    
    // 根据模式选择API
    const chatRes = adviceMode.value === 'quick'
      ? await api.sendChatQuick(advisorUserId.value, requestMessage, activeSessionId.value)
      : await api.sendChatStream(advisorUserId.value, requestMessage, activeSessionId.value, {
          onStart: () => {
            // keep placeholder visible while waiting first delta
          },
          onProgress: (progress) => {
            syncAnalysisProgress(progress)
          },
          onDelta: (chunk) => {
            if (!receivedFirstDelta) {
              draftAssistantMessage.content = chunk
              receivedFirstDelta = true
            } else {
              draftAssistantMessage.content += chunk
            }
            scrollToBottom()
          },
          onFinal: (payload) => {
            streamedFinalPayload = payload
          }
        })

    const finalPayload = streamedFinalPayload || chatRes
    if (!finalPayload) {
      throw new Error('stream_final_payload_missing')
    }

    backendReachable.value = true
    const goodsList = (finalPayload.recommendations ?? []).map(mapRecommendation)
    const relatedGoods = (finalPayload.relatedRecommendations ?? []).map(mapRecommendation)
    draftAssistantMessage.content = finalPayload.reply || draftAssistantMessage.content || '已为你整理好推荐结果，请查看下方商品卡片。'
    draftAssistantMessage.goodsList = goodsList
    draftAssistantMessage.relatedGoods = relatedGoods
    draftAssistantMessage.insights = finalPayload.insights ?? []
    draftAssistantMessage.timestamp = finalPayload.timestamp
    draftAssistantMessage.budgetSummary = finalPayload.budgetSummary
    draftAssistantMessage.detectedIntent = finalPayload.detectedIntent
    draftAssistantMessage.fallback = finalPayload.fallback
    persistCurrentSession()

    await Promise.all(goodsList.slice(0, 3).map(item => recordView(item, 'chat-recommendation')))
    resetGuidedFlow()
  } catch {
    const validUser = await validateActiveUser()
    if (!validUser) {
      sending.value = false
      return
    }

    backendReachable.value = false
    const draftIndex = messageList.value.lastIndexOf(draftAssistantMessage)
    if (draftIndex >= 0 && !draftAssistantMessage.content.trim()) {
      messageList.value.splice(draftIndex, 1)
    }
    noticeType.value = 'error'
    notice.value = '当前 AI 请求超时或失败，已切换到本地推荐模式。'
    const fallbackPack = selectFallbackPack(inputVal, featuredGoods.value)
    const fallbackRecommendations = fallbackPack.recommendations.map(goods => ({
      ...goods,
      tags: sanitizeGoodsTags(goods.tags)
    }))
    const fallbackRelatedGoods = fallbackPack.relatedGoods.map(goods => ({
      ...goods,
      tags: sanitizeGoodsTags(goods.tags)
    }))
    messageList.value.push({
      role: 'assistant',
      content: '需求速览：本次 AI 导购请求超时或失败，我已切到本地真实商品库继续给你推荐。你现在看到的是本轮最值得优先比较的商品。',
      goodsList: fallbackRecommendations,
      relatedGoods: fallbackRelatedGoods,
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
    resetGuidedFlow()
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

  useDemoAdvisor.value = false

  await Promise.allSettled([loadFeaturedProducts(), loadHistory()])
  ensureAiOpeningMessage()
  await loadAiProviderOverview()
  focusLatestConversation()
  await maybeAutoSendPrompt()
}

onMounted(() => {
  void initializeChat()
})

onBeforeUnmount(() => {
  if (analysisTimer !== null) {
    window.clearInterval(analysisTimer)
    analysisTimer = null
  }
  if (scrollFrameId !== null) {
    window.cancelAnimationFrame(scrollFrameId)
    scrollFrameId = null
  }
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
  width: 100%;
  max-width: 100%;
  padding: 0 20px;
  margin: 0 auto;
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  gap: 14px;
  box-sizing: border-box;
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
  display: block;
}

.chat-panel {
  background: rgba(255, 255, 255, 0.9);
  border: 1px solid rgba(255, 255, 255, 0.78);
  border-radius: 28px;
  box-shadow: var(--shadow-lg);
  backdrop-filter: blur(14px);
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 400px;
  gap: 12px;
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
  font-size: 11px;
  padding: 4px 8px;
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

.reason-panel {
  display: grid;
  gap: 6px;
  padding: 8px 10px;
  border-radius: 10px;
  background: linear-gradient(180deg, #fff8ee 0%, #fffdf7 100%);
  border: 1px solid #efd9bb;
}

.reason-title {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.02em;
  color: #9a4f10;
}

.reason-list {
  margin: 0;
  padding-left: 16px;
  display: grid;
  gap: 3px;
}

.reason-list li {
  color: #6b4f34;
  font-size: 12px;
  line-height: 1.45;
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
  padding: 5px 8px;
  border-radius: 999px;
  font-size: 11px;
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
  flex: 1;
  min-height: 300px;
  padding: 8px 6px 12px 0;
  overflow-y: auto;
  scroll-behavior: smooth;
}

.message-item {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
  width: fit-content;
  max-width: min(86%, 78ch);
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
  flex: 0 1 auto;
  display: grid;
  gap: 8px;
  width: fit-content;
  max-width: min(100%, 72ch);
}

.meta-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  width: min(100%, 72ch);
}

.speaker,
.timestamp {
  font-size: 12px;
  color: #746a5e;
}

.text {
  width: fit-content;
  max-width: 100%;
  min-width: min(14ch, 100%);
  padding: 14px 16px;
  border-radius: 20px;
  line-height: 1.72;
  word-break: break-word;
  white-space: pre-line;
}

.assistant-text {
  display: grid;
  gap: 6px;
  width: fit-content;
  max-width: 100%;
  min-width: min(18ch, 100%);
  padding: 12px 14px;
  border-radius: 16px;
  background: linear-gradient(180deg, #f9f7f1 0%, #fffdfa 100%);
  color: #243340;
  border-top-left-radius: 6px;
}

.system-text {
  background: linear-gradient(180deg, #f3f6fb 0%, #faf6ef 100%);
}

.text-block {
  display: grid;
  grid-template-columns: 18px 1fr;
  gap: 6px;
  align-items: start;
  padding: 6px 8px;
  border-radius: 10px;
}

.text-block.normal {
  background: rgba(255, 255, 255, 0.7);
}

.text-block.focus {
  background: #fff2df;
  width: fit-content;
  max-width: 100%;
  min-width: min(18ch, 100%);
}

.text-block.accent {
  background: #eef8f6;
}

.text-emoji {
  font-size: 13px;
  line-height: 1.45;
}

.text-line {
  line-height: 1.62;
  font-size: 13px;
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
  grid-template-columns: repeat(auto-fit, minmax(108px, 1fr));
  gap: 6px;
}

.brief-card {
  display: grid;
  gap: 4px;
  padding: 7px 8px;
  border-radius: 10px;
  background: linear-gradient(180deg, #fffdfa 0%, #f5f1ea 100%);
  border: 1px solid #eadfce;
}

.brief-card span {
  color: #7a6f62;
  font-size: 11px;
}

.brief-card strong {
  color: #273644;
  line-height: 1.32;
  font-size: 13px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.brief-card small {
  color: #6f655a;
  line-height: 1.4;
  font-size: 11px;
  display: -webkit-box;
  -webkit-line-clamp: 1;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.brief-card.spotlight {
  background: linear-gradient(135deg, #fff2df 0%, #fffaf2 55%, #eef6f4 100%);
  border-color: #e8c79d;
}

.result-showcase {
  display: grid;
  gap: 8px;
  padding: 8px;
  border-radius: 12px;
  background: linear-gradient(180deg, rgba(255, 252, 246, 0.96) 0%, rgba(245, 250, 248, 0.94) 100%);
  border: 1px solid #e6d9c8;
}

.result-showcase-head {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: center;
}

.result-showcase-head strong {
  font-size: 12px;
  color: #1f5d50;
  letter-spacing: 0.02em;
}

.result-showcase-head span {
  font-size: 11px;
  color: #7a6f62;
}

.goods-list {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(188px, 1fr));
  gap: 10px;
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
  grid-template-columns: repeat(auto-fit, minmax(168px, 1fr));
  gap: 10px;
}

.related-card {
  display: grid;
  gap: 8px;
  padding: 10px;
  border-radius: 12px;
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
  gap: 8px;
  padding: 8px;
  border-radius: 12px;
  border: 1px solid #eadfce;
  background: linear-gradient(180deg, #ffffff 0%, #fffaf4 100%);
  box-shadow: 0 10px 18px rgba(54, 45, 28, 0.05);
}

.goods-media {
  position: relative;
}

.goods-media .goods-img {
  border-radius: 12px;
}

.goods-floating-meta {
  position: absolute;
  inset: 8px 8px auto 8px;
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
  font-size: 11px;
  font-weight: 700;
}

.rank-badge,
.sales-badge {
  padding: 5px 8px;
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
  padding: 4px 8px;
  background: #edf4f6;
  color: #1d4355;
}

.goods-fit {
  padding: 4px 8px;
  background: #fff3e6;
  color: #a25515;
}

.goods-actions,
.related-actions {
  display: flex;
  gap: 8px;
}

.reason-block {
  display: grid;
  gap: 4px;
  padding: 8px 10px;
  border-radius: 12px;
  background: #fbf7f0;
  color: #5f564e;
  font-size: 12px;
  line-height: 1.45;
}

.reason-block strong {
  color: #253443;
  font-size: 11px;
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
  padding: 8px 0;
  border-radius: 12px;
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
  padding: 7px 10px;
  border-radius: 10px;
  background: #1f6f5c;
  color: #fff;
  font-size: 12px;
  font-weight: 700;
}

.mini-ghost-btn {
  padding: 7px 10px;
  border-radius: 10px;
  font-size: 12px;
  font-weight: 700;
}

.advice-mode-selector {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0;
}

.advice-mode-selector .mode-label {
  font-size: 12px;
  color: #6b7280;
  font-weight: 500;
  margin-right: 6px;
}

.advice-mode-selector .mode-btn {
  padding: 6px 12px;
  border-radius: 6px;
  border: 1px solid #d1d5db;
  background: #fff;
  color: #374151;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: all 140ms;
}

.advice-mode-selector .mode-btn:hover {
  border-color: #1f6f5c;
  color: #1f6f5c;
}

.advice-mode-selector .mode-btn.active {
  background: #1f6f5c;
  color: #fff;
  border-color: #1f6f5c;
}

.input-container {
  position: relative;
  width: 100%;
  margin-top: 0;
  padding: 10px;
  display: grid;
  gap: 8px;
  border-radius: 14px;
  border: 1px solid #e7ddd1;
  background: rgba(255, 253, 249, 0.98);
  box-shadow: 0 4px 12px rgba(36, 51, 64, 0.08);
  backdrop-filter: blur(12px);
  flex-shrink: 0;
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

.input-main-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.input-actions {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.chat-input {
  flex: 1;
  min-height: 42px;
  padding: 10px 14px;
  border: 1px solid #e0d6c7;
  border-radius: 14px;
  font-size: 14px;
  outline: none;
}

.chat-input:focus {
  border-color: #1f6f5c;
}

.voice-btn,
.send-btn {
  padding: 9px 14px;
  border-radius: 12px;
  font-size: 14px;
  font-weight: 700;
}

.voice-btn.compact,
.send-btn.compact {
  min-width: 66px;
  padding: 9px 12px;
  font-size: 12px;
  line-height: 1;
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
  display: none;
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
  .hero-panel {
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
  .input-main-row,
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

  .input-container {
    width: calc(100vw - 22px);
    bottom: 8px;
    padding: 10px;
  }

  .input-actions {
    width: 100%;
    justify-content: flex-end;
  }

  .cart-bubble-wrapper {
    right: 10px;
    bottom: 92px;
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

/* ── Hero toggle bar ─────────────────────────────────── */
.hero-toggle-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 14px;
  padding: 10px 16px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.88);
  border: 1px solid rgba(255, 255, 255, 0.78);
  box-shadow: var(--shadow-soft);
  backdrop-filter: blur(10px);
  flex-wrap: wrap;
}

.hero-toggle-left,
.hero-toggle-right {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.hero-mini-brand {
  font-weight: 800;
  font-size: 15px;
  color: var(--brand-deep, #1f6f5c);
}

.hero-stat-mini {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 10px;
  border-radius: 999px;
  background: #f4efe7;
  color: #5c554d;
  font-size: 12px;
}

.hero-stat-mini.accent {
  background: #dff5ea;
  color: #0d6b51;
}

.hero-collapse-btn {
  padding: 7px 14px;
  border-radius: 999px;
  border: 1px solid #d4c9b9;
  background: #fff;
  color: #4a5568;
  font-size: 12px;
  cursor: pointer;
  white-space: nowrap;
}

.hero-collapse-btn:hover {
  background: #f0ebe3;
}

/* ── Mode toggle (简略 / 详细) ───────────────────────── */
.mode-toggle {
  display: inline-flex;
  align-items: center;
  border-radius: 999px;
  background: #f0ebe3;
  padding: 3px;
  gap: 2px;
}

.mode-btn {
  padding: 6px 14px;
  border-radius: 999px;
  border: none;
  background: transparent;
  color: #6a625a;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background 160ms, color 160ms;
}

.mode-btn.active {
  background: #1f6f5c;
  color: #fff;
}

.guided-switch-btn {
  border: none;
  border-radius: 999px;
  padding: 7px 12px;
  font-size: 12px;
  font-weight: 700;
  background: #f4efe7;
  color: #5c554d;
  cursor: pointer;
}

.guided-switch-btn.active {
  background: #dff5ea;
  color: #0d6b51;
}

/* ── Brief mode summary row ──────────────────────────── */
.brief-summary-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 4px 0 8px;
}

.brief-one-line {
  display: inline-flex;
  align-items: center;
  width: fit-content;
  max-width: min(100%, 56ch);
  padding: 8px 12px;
  border-radius: 12px;
  background: #f4efe7;
  color: #4e463e;
  font-size: 13px;
  line-height: 1.5;
}

.brief-intent-tag,
.brief-count-tag,
.brief-budget-tag {
  display: inline-flex;
  align-items: center;
  width: fit-content;
  max-width: min(100%, 56ch);
  padding: 6px 12px;
  border-radius: 999px;
  font-size: 13px;
  font-weight: 600;
}

.brief-intent-tag {
  background: #edf4f6;
  color: #1d4355;
}

  .content,
  .meta-row {
    max-width: 100%;
  }

.brief-count-tag {
  background: #dff5ea;
  color: #0d6b51;
}

.brief-budget-tag {
  background: #fff3e6;
  color: #a25515;
}

/* ── Floating cart bubble ────────────────────────────── */
.cart-bubble-wrapper {
  position: fixed;
  bottom: 76px;
  right: 18px;
  z-index: 200;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 10px;
}

.cart-bubble-btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 12px 20px;
  border-radius: 999px;
  border: none;
  background: linear-gradient(135deg, #1f6f5c 0%, #169a7f 100%);
  color: #fff;
  font-size: 14px;
  font-weight: 700;
  cursor: pointer;
  box-shadow: 0 6px 24px rgba(31, 111, 92, 0.38);
  transition: transform 160ms, box-shadow 160ms;
}

.cart-bubble-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 10px 30px rgba(31, 111, 92, 0.45);
}

.cart-bubble-btn.open {
  background: linear-gradient(135deg, #243340 0%, #3a5068 100%);
}

.bubble-icon {
  font-size: 18px;
  line-height: 1;
}

.bubble-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #f97316;
  color: #fff;
  font-size: 11px;
  font-weight: 800;
  padding: 0 4px;
}

.bubble-label {
  font-size: 13px;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* Cart drawer (floating panel) */
.cart-drawer {
  width: min(300px, calc(100vw - 26px));
  max-height: min(62vh, 520px);
  display: flex;
  flex-direction: column;
  background: rgba(255, 255, 255, 0.97);
  border: 1px solid rgba(255, 255, 255, 0.8);
  border-radius: 22px;
  box-shadow: 0 20px 48px rgba(31, 41, 55, 0.18);
  overflow: hidden;
  backdrop-filter: blur(14px);
}

.cart-drawer-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 16px 12px;
  border-bottom: 1px solid #ece3d6;
  flex-shrink: 0;
}

.cart-drawer-title {
  font-weight: 700;
  font-size: 15px;
  color: #243340;
}

.cart-drawer-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.cart-drawer-close {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  border: none;
  background: #f3f0ea;
  color: #6a625a;
  font-size: 16px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

.cart-drawer .cart-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px 16px;
}

.cart-drawer-footer {
  padding: 12px 16px 14px;
  border-top: 1px solid #ece3d6;
  flex-shrink: 0;
}

/* Cart slide transition */
.cart-slide-enter-active,
.cart-slide-leave-active {
  transition: opacity 200ms ease, transform 200ms ease;
}

.cart-slide-enter-from,
.cart-slide-leave-to {
  opacity: 0;
  transform: translateY(16px) scale(0.96);
}
</style>
