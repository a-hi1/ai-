import { getPrimaryCategory, productMatchesKeyword, SHOP_CATEGORY_CONFIG } from '../catalog'
import type { ChatSessionGoods, ChatSessionInsight } from '../services/chatSessions'

export type AssistantTextBlock = {
  emoji: string
  html: string
  tone: 'normal' | 'focus' | 'accent'
}

export type AdvisorFallbackPack = {
  recommendations: ChatSessionGoods[]
  relatedGoods: ChatSessionGoods[]
  budgetSummary: string
  detectedIntent: string
  insights: ChatSessionInsight[]
}

export const quickPrompts = [
  '预算 3000 元以内，帮我推荐适合通勤和视频会议的降噪耳机',
  '我想要一台适合出差和写方案的轻薄办公本',
  '预算 800 元以内，推荐打字舒服的办公键盘',
  '租房厨房想补几件省心好用的日常用品，别太贵',
  '我经常跑步，想买能做心率和训练记录的穿戴设备',
  '家里有猫，想补猫粮和外出用品，帮我先列一套',
  '我想买一套提升桌面效率的键盘和穿戴组合'
]

export const emojiSuggestions = ['🎯', '💻', '🎧', '🛒', '☕', '🐱', '✨', '👍']

export const localFallbackGoods: ChatSessionGoods[] = [
  {
    id: '20000000-0000-0000-0000-000000000004',
    name: '华为 FreeBuds Pro 4',
    price: 1499,
    desc: '适合通勤和视频会议的降噪耳机',
    image: 'https://picsum.photos/seed/freebuds/480/320',
    reason: '本地兜底推荐，适合通勤和日常会议场景。',
    salesCount: 12,
    withinBudget: true,
    tags: ['耳机', '降噪', '通勤']
  },
  {
    id: '20000000-0000-0000-0000-000000000019',
    name: '苏泊尔轻量不粘炒锅',
    price: 229,
    desc: '适合租房和家庭快手菜的轻量炒锅',
    image: 'https://picsum.photos/seed/wok/480/320',
    reason: '本地兜底推荐，适合快速补齐厨房常用品。',
    salesCount: 9,
    withinBudget: true,
    tags: ['厨房', '锅具', '家居']
  },
  {
    id: '20000000-0000-0000-0000-000000000006',
    name: 'Garmin Forerunner 265',
    price: 2680,
    desc: '适合跑步训练和健康追踪的智能手表',
    image: 'https://picsum.photos/seed/garmin/480/320',
    reason: '本地兜底推荐，适合高频训练人群。',
    salesCount: 6,
    withinBudget: true,
    tags: ['手表', '运动', '训练']
  },
  {
    id: '20000000-0000-0000-0000-000000000027',
    name: '网易严选冻干双拼猫粮',
    price: 139,
    desc: '高蛋白冻干双拼猫粮，适合成猫日常喂养',
    image: 'https://picsum.photos/seed/catfood/480/320',
    reason: '本地兜底推荐，适合家庭宠物日常补货。',
    salesCount: 15,
    withinBudget: true,
    tags: ['宠物', '猫粮', '家庭']
  },
  {
    id: '20000000-0000-0000-0000-000000000031',
    name: '星巴克家享哥伦比亚咖啡豆',
    price: 98,
    desc: '适合手冲和全自动咖啡机的中度烘焙咖啡豆',
    image: 'https://picsum.photos/seed/coffee/480/320',
    reason: '本地兜底推荐，适合办公室和家庭补充提神饮品。',
    salesCount: 18,
    withinBudget: true,
    tags: ['食品', '咖啡', '生活']
  },
  {
    id: '20000000-0000-0000-0000-000000000001',
    name: '联想小新 Pro 14',
    price: 6299,
    desc: '适合差旅办公和文档处理的轻薄本',
    image: 'https://picsum.photos/seed/laptop/480/320',
    reason: '本地兜底推荐，适合移动办公和高频文档处理。',
    salesCount: 8,
    withinBudget: false,
    tags: ['笔记本', '办公', '轻薄']
  }
]

const formatCurrency = (price: number) => new Intl.NumberFormat('zh-CN', {
  style: 'currency',
  currency: 'CNY',
  maximumFractionDigits: 0
}).format(price)

const escapeHtml = (value: string) => value
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;')
  .replace(/'/g, '&#39;')

const decorateAssistantLine = (value: string) => {
  return escapeHtml(value).replace(/(预算|主推款|主推|备选款|备选|搭配推荐|搭配|加购|需求速览|推荐理由|预算内|预算外替代|实时 AI 导购|规则兜底导购)/g, '<strong class="inline-emphasis">$1</strong>')
}

const normalizeAssistantLine = (value: string) => {
  return value
    .replace(/^#{1,6}\s*/g, '')
    .replace(/^[-*+]\s+/g, '')
    .replace(/\*\*/g, '')
    .replace(/`+/g, '')
    .replace(/\s+/g, ' ')
    .trim()
}

const normalizeAssistantText = (content: string) => {
  return (content || '')
    .replace(/\r/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
    .replace(/[\t ]+/g, ' ')
    .trim()
}

const resolveBlockTone = (value: string): AssistantTextBlock['tone'] => {
  if (/(预算|主推|需求速览|推荐理由)/.test(value)) {
    return 'focus'
  }
  if (/(搭配|加购|备选|替代)/.test(value)) {
    return 'accent'
  }
  return 'normal'
}

const resolveBlockEmoji = (value: string) => {
  if (/(预算|价格)/.test(value)) return '💰'
  if (/(主推|优先|推荐)/.test(value)) return '🎯'
  if (/(搭配|加购|购物车)/.test(value)) return '🛒'
  if (/(备选|对比|替代)/.test(value)) return '📌'
  return '✨'
}

export const formatAssistantBlocks = (content: string): AssistantTextBlock[] => {
  const normalized = normalizeAssistantText(content)
  const lines = normalized
    .split(/\n+/)
    .flatMap(line => line.split(/(?<=[。！？!?；;])/))
    .map(line => normalizeAssistantLine(line))
    .filter(Boolean)

  return (lines.length ? lines : [normalizeAssistantLine(normalized)]).filter(Boolean).map(line => ({
    emoji: resolveBlockEmoji(line),
    html: decorateAssistantLine(line),
    tone: resolveBlockTone(line)
  }))
}

const parseBudget = (message: string) => {
  const matches = [...message.matchAll(/(\d{2,6})(?:\s*)(?:元|块|rmb|人民币)?/gi)]
  if (!matches.length) {
    return null
  }

  return Math.max(...matches.map(match => Number(match[1] || 0)).filter(value => value > 0))
}

const detectCategory = (message: string) => {
  const normalized = message.toLowerCase()
  const ranked = SHOP_CATEGORY_CONFIG
    .map(category => ({
      category,
      score: category.keywords.reduce((total, keyword) => total + (normalized.includes(keyword.toLowerCase()) ? 1 : 0), 0)
    }))
    .sort((left, right) => right.score - left.score)

  return ranked[0]?.score ? ranked[0].category : null
}

const extractSearchTokens = (message: string, categoryKeywords: string[] = []) => {
  return Array.from(new Set([
    ...message.split(/[\s,，。！？!?:：/]+/).map(item => item.trim()).filter(item => item.length >= 2),
    ...categoryKeywords
  ]))
}

const scoreFallbackGoods = (goods: ChatSessionGoods, budget: number | null, tokens: string[], categoryId: string | null) => {
  let score = 0
  const primaryCategory = getPrimaryCategory({ name: goods.name, description: goods.desc, tags: goods.tags })

  if (categoryId && primaryCategory?.id === categoryId) {
    score += 8
  }

  score += tokens.reduce((total, token) => total + (productMatchesKeyword({
    name: goods.name,
    description: goods.desc,
    tags: goods.tags
  }, token) ? 3 : 0), 0)

  if (budget !== null) {
    if (goods.price <= budget) {
      score += 4
    } else {
      score -= 2
    }
  }

  score += Math.min(goods.salesCount, 20) / 10
  return score
}

const buildFallbackRecommendation = (goods: ChatSessionGoods, budget: number | null, categoryLabel: string | null) => ({
  ...goods,
  withinBudget: budget === null ? goods.withinBudget : goods.price <= budget,
  reason: categoryLabel
    ? `本地兜底已切到真实商品库，优先按“${categoryLabel}”分区为你挑选。${goods.reason}`
    : `本地兜底已切到真实商品库。${goods.reason}`
})

export const selectFallbackPack = (message: string, sourceGoods: ChatSessionGoods[]): AdvisorFallbackPack => {
  const budget = parseBudget(message)
  const detectedCategory = detectCategory(message)
  const categoryKeywords = detectedCategory?.keywords ?? []
  const tokens = extractSearchTokens(message, categoryKeywords)
  const candidateGoods = sourceGoods.length ? sourceGoods : localFallbackGoods
  const rankedGoods = candidateGoods
    .map(goods => ({ goods, score: scoreFallbackGoods(goods, budget, tokens, detectedCategory?.id ?? null) }))
    .sort((left, right) => right.score - left.score)

  const preferredGoods = rankedGoods.filter(item => item.score > 0).map(item => item.goods)
  const recommendations = (preferredGoods.length ? preferredGoods : rankedGoods.map(item => item.goods))
    .slice(0, 3)
    .map(goods => buildFallbackRecommendation(goods, budget, detectedCategory?.label ?? null))

  const recommendationIds = new Set(recommendations.map(item => item.id))
  const relatedGoods = rankedGoods
    .map(item => item.goods)
    .filter(goods => !recommendationIds.has(goods.id))
    .slice(0, 3)
    .map(goods => buildFallbackRecommendation(goods, budget, detectedCategory?.label ?? null))

  const budgetSummary = budget === null ? '未限制预算' : `预算约 ${formatCurrency(budget)}`
  const detectedIntent = detectedCategory ? `${detectedCategory.label}采购` : '综合选品'
  const fallbackPrimaryCategory = recommendations[0]
    ? getPrimaryCategory(recommendations[0])
    : null
  const coverageLabel = detectedCategory?.label || fallbackPrimaryCategory?.label || SHOP_CATEGORY_CONFIG[0]?.label || '综合选品'

  return {
    recommendations,
    relatedGoods,
    budgetSummary,
    detectedIntent,
    insights: [
      { label: '需求类型', value: detectedIntent },
      { label: '预算范围', value: budgetSummary },
      { label: '推荐来源', value: '本地真实商品兜底' },
      { label: '覆盖分区', value: coverageLabel }
    ]
  }
}