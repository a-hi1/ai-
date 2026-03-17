export interface CatalogProductLike {
  name: string
  description?: string
  tags?: string | string[]
  category?: string
  specs?: string
  sellingPoints?: string
  policy?: string
}

export interface ShopCategoryConfig {
  id: string
  label: string
  headline: string
  description: string
  query: string
  keywords: string[]
  examples: string[]
}

export interface ShopCategoryGroup<T extends CatalogProductLike> {
  category: ShopCategoryConfig
  products: T[]
}

export const SHOP_CATEGORY_CONFIG: ShopCategoryConfig[] = [
  {
    id: 'home-essentials',
    label: '生活用品',
    headline: '清洁补货与厨房居家必需品',
    description: '垃圾袋、抽纸、清洁湿巾、厨房耗材等高频消耗品。',
    query: '生活用品',
    keywords: ['生活用品', '家居日用', '垃圾袋', '抽纸', '纸巾', '清洁', '洗衣', '湿巾', '厨房', '收纳'],
    examples: ['抽纸', '垃圾袋', '清洁湿巾']
  },
  {
    id: 'featured',
    label: '精选商品',
    headline: '综合热卖与高口碑优选',
    description: '平台综合评分高、销量与评价表现稳定的热门单品。',
    query: '精选',
    keywords: ['精选商品', '精选', '热卖', '爆款', '推荐', '优选'],
    examples: ['平台热卖', '口碑优选', '高性价比']
  },
  {
    id: 'food-fresh',
    label: '食品生鲜',
    headline: '零食粮油与日常饮食补给',
    description: '零食、饮料、粮油、冲调和便捷食品。',
    query: '食品生鲜',
    keywords: ['食品生鲜', '食品', '生鲜', '零食', '饮料', '牛奶', '咖啡', '坚果', '粮油', '米面'],
    examples: ['坚果零食', '饮品冲调', '粮油补给']
  },
  {
    id: 'digital',
    label: '电子数码',
    headline: '手机外设与数码办公设备',
    description: '手机、耳机、充电、电脑外设和影音设备。',
    query: '电子数码',
    keywords: ['电子数码', '手机', '耳机', '笔记本', '电脑', '充电器', '平板', '相机', '键盘', '鼠标'],
    examples: ['蓝牙耳机', '快充设备', '办公数码']
  },
  {
    id: 'fashion',
    label: '服饰',
    headline: '日常穿搭与季节服装',
    description: 'T恤、卫衣、外套、裤装等基础与潮流服饰。',
    query: '服饰',
    keywords: ['服饰', '服装', 'T恤', '卫衣', '外套', '裤', '裙', '衬衫', '毛衣'],
    examples: ['基础款 T 恤', '通勤外套', '休闲裤装']
  },
  {
    id: 'home-furniture',
    label: '家居家具',
    headline: '家装软饰与家具补充',
    description: '家具、家纺和空间布置用品。',
    query: '家居家具',
    keywords: ['家居家具', '家居', '家具', '沙发', '桌', '椅', '床', '床垫', '家纺'],
    examples: ['小户型家具', '家纺软装', '收纳家居']
  },
  {
    id: 'personal-care',
    label: '个护美妆',
    headline: '护肤洗护与个人护理',
    description: '洁面、护肤、洗护与个人日用护理。',
    query: '个护美妆',
    keywords: ['个护美妆', '个护', '美妆', '洗护', '护肤', '洁面', '香氛', '防晒'],
    examples: ['面部护理', '洗护套装', '日常个护']
  },
  {
    id: 'bags-accessories',
    label: '箱包配饰',
    headline: '出行箱包与穿搭配件',
    description: '背包、手提包、行李箱和日常穿搭配饰。',
    query: '箱包配饰',
    keywords: ['箱包配饰', '箱包', '背包', '手提包', '行李箱', '配饰', '帽', '围巾', '腰带'],
    examples: ['通勤背包', '轻便行李箱', '日常配饰']
  }
]

const CATEGORY_LABEL_TO_ID = new Map<string, string>(SHOP_CATEGORY_CONFIG.map(item => [item.label, item.id]))

export const splitProductTags = (tags?: string | string[]) => {
  const hiddenTags = new Set(['crawler', 'escuelajs', 'jsonfile', 'xlsx'])
  if (Array.isArray(tags)) {
    return tags
      .map(tag => tag.trim())
      .filter(Boolean)
      .filter(tag => !hiddenTags.has(tag.toLowerCase()))
  }

  return (tags || '')
    .split(/[,，]/)
    .map(tag => tag.trim())
    .filter(Boolean)
    .filter(tag => !hiddenTags.has(tag.toLowerCase()))
}

const normalizeText = (value: string) => value
  .toLowerCase()
  .replace(/[\-_/,，]/g, ' ')
  .replace(/\s+/g, ' ')
  .trim()

const looksLikeReviewJson = (text: string) => {
  const normalized = text.trim()
  if (!normalized) {
    return false
  }
  if (normalized.startsWith('[') && normalized.includes('"content"')) {
    return true
  }
  return /"content"\s*:\s*"[\s\S]*?"/.test(normalized)
}

const decodeEscapedText = (text: string) => {
  try {
    return JSON.parse(`"${text.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`) as string
  } catch {
    return text
  }
}

const extractReviewsFromText = (source: string) => {
  const text = source.trim()
  if (!text) {
    return [] as string[]
  }

  if (text.startsWith('[') && text.includes('"content"')) {
    try {
      const payload = JSON.parse(text) as Array<{ content?: string }>
      return payload
        .map(item => (item?.content || '').trim())
        .filter(Boolean)
        .slice(0, 10)
    } catch {
      // fallback to regex parse below
    }
  }

  const matches = [...text.matchAll(/"content"\s*:\s*"([\s\S]*?)"/g)]
  if (!matches.length) {
    return [] as string[]
  }

  return matches
    .map(match => decodeEscapedText((match[1] || '').trim()))
    .filter(Boolean)
    .slice(0, 10)
}

export const parseProductReviews = (product: CatalogProductLike): string[] => {
  const reviewSources = [
    (product.description || '').trim(),
    (product.sellingPoints || '').trim(),
    (product.policy || '').trim()
  ]

  const merged: string[] = []
  for (const source of reviewSources) {
    const parsed = extractReviewsFromText(source)
    for (const item of parsed) {
      if (!merged.includes(item)) {
        merged.push(item)
      }
      if (merged.length >= 10) {
        return merged
      }
    }
  }
  return merged
}

export const sanitizeProductTextField = (value?: string) => {
  const text = (value || '').trim()
  if (!text) {
    return ''
  }
  if (looksLikeReviewJson(text)) {
    return ''
  }
  return text
}

export const productCardDescription = (product: CatalogProductLike, fallback = '点击进入详情查看商品信息') => {
  const reviews = parseProductReviews(product)
  const sellingPoints = sanitizeProductTextField(product.sellingPoints)
  const specs = sanitizeProductTextField(product.specs)
  const policy = sanitizeProductTextField(product.policy)
  if (reviews.length) {
    return sellingPoints || specs || policy || fallback
  }

  const text = sanitizeProductTextField(product.description)
  if (!text) {
    return sellingPoints || specs || policy || fallback
  }

  return text.length > 72 ? `${text.slice(0, 72)}...` : text
}

const categoryMatchScore = (product: CatalogProductLike, category: ShopCategoryConfig) => {
  if ((product.category || '').trim() === category.label) {
    return 1000
  }
  return 0
}

export const productSearchText = (product: CatalogProductLike) => normalizeText([
  product.name,
  product.description || '',
  splitProductTags(product.tags).join(' ')
].join(' '))

export const matchesCategory = (product: CatalogProductLike, categoryId: string) => {
  const category = SHOP_CATEGORY_CONFIG.find(item => item.id === categoryId)
  if (!category) {
    return false
  }

  return categoryMatchScore(product, category) > 0
}

export const getPrimaryCategory = (product: CatalogProductLike) => {
  const directCategory = (product.category || '').trim()
  if (directCategory) {
    const categoryId = CATEGORY_LABEL_TO_ID.get(directCategory)
    if (categoryId) {
      return SHOP_CATEGORY_CONFIG.find(item => item.id === categoryId) ?? null
    }
  }

  return SHOP_CATEGORY_CONFIG
    .map(category => ({ category, score: categoryMatchScore(product, category) }))
    .sort((left, right) => right.score - left.score)
    .find(item => item.score > 0)
    ?.category ?? null
}

export const productMatchesKeyword = (product: CatalogProductLike, keyword: string) => {
  const normalizedKeyword = normalizeText(keyword)
  if (!normalizedKeyword) {
    return true
  }

  return productSearchText(product).includes(normalizedKeyword)
}

export const getCategoryProducts = <T extends CatalogProductLike>(products: T[], categoryId: string) => {
  return products.filter(product => getPrimaryCategory(product)?.id === categoryId)
}

export const groupProductsByCategory = <T extends CatalogProductLike>(products: T[]): ShopCategoryGroup<T>[] => {
  return SHOP_CATEGORY_CONFIG
    .map(category => ({
      category,
      products: getCategoryProducts(products, category.id)
    }))
    .filter(group => group.products.length > 0)
}