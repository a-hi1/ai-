<template>
  <section v-if="product" class="detail-page">
    <div class="detail-grid">
      <article class="gallery-card">
        <span class="gallery-badge">Lifestyle Select</span>
        <img :src="product.imageUrl || fallbackImage(product.id)" :alt="product.name" class="hero-image" loading="lazy" decoding="async" />
      </article>

      <article class="info-card">
        <div class="info-top">
          <span class="detail-label">商品详情</span>
          <h1>{{ product.name }}</h1>
        </div>

        <div class="detail-metrics">
          <div class="detail-metric">
            <span>价格带</span>
            <strong>{{ formatCurrency(product.price) }}</strong>
          </div>
          <div class="detail-metric">
            <span>商品大类</span>
            <strong>{{ product.category || '未分类' }}</strong>
          </div>
          <div class="detail-metric">
            <span>标签数量</span>
            <strong>{{ splitTags(product.tags).length || 1 }}</strong>
          </div>
          <div class="detail-metric">
            <span>关联搭配</span>
            <strong>{{ relatedProducts.length }}</strong>
          </div>
        </div>

        <div class="detail-fields">
          <article v-if="cleanSellingPoints" class="field-card">
            <span>卖点</span>
            <p>{{ cleanSellingPoints }}</p>
          </article>
          <article v-if="cleanSpecs" class="field-card">
            <span>规格</span>
            <p>{{ cleanSpecs }}</p>
          </article>
          <article v-if="cleanPolicy" class="field-card">
            <span>服务保障</span>
            <p>{{ cleanPolicy }}</p>
          </article>
        </div>

        <div v-if="splitTags(product.tags).length" class="tag-row">
          <span v-for="tag in splitTags(product.tags)" :key="tag" class="tag-pill">{{ tag }}</span>
        </div>

        <div class="price-panel">
          <strong>{{ formatCurrency(product.price) }}</strong>
        </div>

        <p v-if="actionMessage" :class="['action-feedback', actionTone]">{{ actionMessage }}</p>

        <div class="action-row">
          <button class="primary-btn" @click="addToCart">加入购物车</button>
          <button class="secondary-btn" type="button" @click="toggleReviews">{{ showReviews ? '收起评价' : `查看评价（${reviewItems.length}）` }}</button>
          <button class="secondary-btn" @click="askAi">让 AI 分析这款</button>
        </div>

        <section v-if="showReviews" class="review-panel">
          <div class="review-head">
            <span class="detail-label">用户评价</span>
            <strong>逐条展示</strong>
          </div>
          <ol v-if="reviewItems.length" class="review-list">
            <li v-for="(item, index) in reviewItems" :key="`${product.id}-review-${index}`" class="review-item">
              {{ item }}
            </li>
          </ol>
          <p v-else class="review-empty">暂无可展示评价。</p>
        </section>
      </article>
    </div>

    <section class="detail-section">
      <div class="section-head">
        <div>
          <span class="detail-label">关联推荐</span>
          <h2>搭配购买</h2>
        </div>
      </div>

      <div v-if="relatedProducts.length" class="related-grid">
        <article
          v-for="item in relatedProducts"
          :key="item.productId"
          class="related-card clickable-card"
          role="link"
          tabindex="0"
          @click="openRelated(item)"
          @keyup.enter="openRelated(item)"
        >
          <img :src="item.imageUrl || fallbackImage(item.productId)" :alt="item.name" class="related-image" loading="lazy" decoding="async" />
          <div class="related-copy">
            <strong>{{ item.name }}</strong>
          </div>
          <div class="related-foot">
            <span>{{ formatCurrency(item.price) }}</span>
            <button class="detail-link button-link" type="button" @click.stop="openRelated(item)">查看</button>
          </div>
        </article>
      </div>
      <div v-else class="loading-state">当前没有可展示的搭配推荐。</div>
    </section>
  </section>

  <section v-else-if="loading" class="loading-state">商品加载中...</section>
  <section v-else class="loading-state error-state">
    <strong>商品详情暂时无法展示</strong>
    <p>{{ loadError || '该商品可能已下架，或当前推荐来自本地兜底数据。' }}</p>
    <RouterLink to="/shop" class="detail-link">返回商城</RouterLink>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { parseProductReviews, sanitizeProductTextField } from '../catalog'
import { useAuth } from '../composables/useAuth'
import { useCart } from '../composables/useCart'
import { api, type ChatRecommendationDto, type ProductDto } from '../services/api'

const route = useRoute()
const router = useRouter()
const { currentUser } = useAuth()
const { addItem } = useCart()

const product = ref<ProductDto | null>(null)
const relatedProducts = ref<ChatRecommendationDto[]>([])
const loading = ref(true)
const loadError = ref('')
const actionMessage = ref('')
const actionTone = ref<'success' | 'error'>('success')
const showReviews = ref(false)
const resolvedProductId = ref('')

const splitTags = (tags?: string) => (tags || '').split(/[,，]/).map(tag => tag.trim()).filter(Boolean).slice(0, 6)

const formatCurrency = (price: number) => new Intl.NumberFormat('zh-CN', {
  style: 'currency',
  currency: 'CNY',
  maximumFractionDigits: 0
}).format(price)

const fallbackImage = (id: string) => `https://picsum.photos/seed/detail-${id}/960/720`

const reviewItems = computed(() => {
  if (!product.value) {
    return []
  }
  return parseProductReviews(product.value)
})

const cleanSellingPoints = computed(() => sanitizeProductTextField(product.value?.sellingPoints))
const cleanSpecs = computed(() => sanitizeProductTextField(product.value?.specs))
const cleanPolicy = computed(() => sanitizeProductTextField(product.value?.policy))

const toggleReviews = () => {
  showReviews.value = !showReviews.value
}

const resolveCartProductId = async () => {
  if (!product.value) {
    return ''
  }
  if (canPersistProduct(product.value.id)) {
    return product.value.id
  }
  if (resolvedProductId.value) {
    return resolvedProductId.value
  }

  const keyword = product.value.name.trim()
  if (!keyword) {
    return ''
  }

  try {
    const candidates = await api.searchProducts(keyword)
    if (!candidates.length) {
      return ''
    }

    const exact = candidates.find(item => item.name.trim() === product.value?.name.trim())
    const priceNear = candidates.find(item => Math.abs(item.price - product.value!.price) <= 1)
    const selected = exact || priceNear || candidates[0]
    if (!selected?.id) {
      return ''
    }

    resolvedProductId.value = selected.id
    return selected.id
  } catch {
    return ''
  }
}

const cacheProductPreview = (payload: {
  id: string
  name: string
  description?: string
  price: number
  imageUrl?: string
  tags?: string[] | string
}) => {
  try {
    const normalizedTags = Array.isArray(payload.tags) ? payload.tags.join(',') : (payload.tags || '')
    window.sessionStorage.setItem(`product-preview:${payload.id}`, JSON.stringify({
      id: payload.id,
      name: payload.name,
      description: payload.description || '',
      price: payload.price,
      imageUrl: payload.imageUrl || fallbackImage(payload.id),
      tags: normalizedTags
    }))
  } catch {
    // ignore session storage failures
  }
}

const openRelated = (item: ChatRecommendationDto) => {
  cacheProductPreview({
    id: item.productId,
    name: item.name,
    description: item.description,
    price: item.price,
    imageUrl: item.imageUrl,
    tags: item.tags
  })
  router.push(`/products/${item.productId}`)
}

const isUuidLike = (value: string) => /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value)

const canPersistProduct = (productId: string) => isUuidLike(productId)

const readCachedProduct = (productId: string): ProductDto | null => {
  try {
    const cached = window.sessionStorage.getItem(`product-preview:${productId}`)
    if (!cached) {
      return null
    }

    const parsed = JSON.parse(cached) as ProductDto
    return parsed?.id ? parsed : null
  } catch {
    return null
  }
}

const loadDetail = async () => {
  const productId = String(route.params.id || '')
  loading.value = true
  loadError.value = ''
  actionMessage.value = ''
  product.value = null
  relatedProducts.value = []
  showReviews.value = false
  resolvedProductId.value = ''

  if (!productId) {
    loadError.value = '缺少商品编号。'
    loading.value = false
    return
  }

  try {
    product.value = await api.getProduct(productId)

    if (isUuidLike(productId)) {
      try {
        relatedProducts.value = await api.getRelatedProducts(productId, 4)
      } catch {
        relatedProducts.value = []
      }
    }
  } catch {
    const cachedProduct = readCachedProduct(productId)
    if (cachedProduct) {
      product.value = cachedProduct
      loadError.value = '当前展示的是最近一次推荐时缓存的商品快照。'
    } else {
      loadError.value = '商品不存在，或详情接口暂时不可用。'
    }
  }

  if (!product.value) {
    loading.value = false
    return
  }

  if (currentUser.value && canPersistProduct(productId)) {
    try {
      await api.recordProductView(currentUser.value.id, productId, 'product-detail', product.value.description || product.value.name)
    } catch {
      // ignore tracking failures
    }
  }

  if (!canPersistProduct(productId)) {
    void resolveCartProductId()
  }

  loading.value = false
}

const addToCart = async () => {
  if (!product.value) {
    return
  }
  if (!currentUser.value) {
    router.push('/login')
    return
  }

  actionMessage.value = ''

  const cartProductId = await resolveCartProductId()
  if (!cartProductId) {
    actionTone.value = 'error'
    actionMessage.value = '暂未匹配到可下单商品，请返回商城重新选择，或在导购中继续细化需求。'
    return
  }

  await addItem({
    id: cartProductId,
    name: product.value.name,
    price: product.value.price,
    desc: product.value.description || '',
    image: product.value.imageUrl || fallbackImage(cartProductId)
  })
  actionTone.value = 'success'
  actionMessage.value = '已加入购物车。'
}

const askAi = () => {
  if (!product.value) {
    return
  }
  router.push({
    path: '/chat',
    query: {
      prompt: `帮我分析 ${product.value?.name} 是否适合购买，并推荐相关搭配`,
      autoSend: '1'
    }
  })
}

watch(() => route.params.id, () => {
  void loadDetail()
}, { immediate: true })
</script>

<style scoped>
.detail-page,
.detail-section {
  display: grid;
  gap: 22px;
}

.detail-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(320px, 0.9fr);
  gap: 22px;
}

.gallery-card,
.info-card,
.related-card,
.loading-state {
  padding: 22px;
  border-radius: 30px;
  background: rgba(255, 255, 255, 0.9);
  border: 1px solid rgba(255, 255, 255, 0.82);
  box-shadow: var(--shadow-lg);
  backdrop-filter: blur(16px);
}

.gallery-card {
  position: relative;
  overflow: hidden;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.56) 0%, rgba(255, 255, 255, 0.22) 100%),
    radial-gradient(circle at top left, rgba(255, 77, 77, 0.1), transparent 34%),
    radial-gradient(circle at bottom right, rgba(0, 196, 180, 0.08), transparent 30%);
}

.gallery-badge {
  position: absolute;
  top: 18px;
  left: 18px;
  z-index: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 34px;
  padding: 0 14px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.7);
  color: var(--brand-deep);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  backdrop-filter: blur(12px);
}

.hero-image,
.related-image {
  width: 100%;
  object-fit: cover;
  border-radius: 24px;
}

.hero-image {
  aspect-ratio: 4 / 3;
  box-shadow: 0 26px 44px rgba(120, 105, 101, 0.14);
}

.related-image {
  aspect-ratio: 1;
}

.info-card,
.info-top,
.related-copy {
  display: grid;
  gap: 12px;
}

.detail-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.detail-fields {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.field-card {
  padding: 12px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.84);
  border: 1px solid rgba(255, 255, 255, 0.78);
  display: grid;
  gap: 6px;
}

.field-card span {
  font-size: 11px;
  color: var(--text-faint);
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.field-card p {
  margin: 0;
  color: var(--text-soft);
  line-height: 1.6;
}

.detail-metric {
  display: grid;
  gap: 6px;
  padding: 14px;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.82);
  border: 1px solid rgba(255, 255, 255, 0.82);
  box-shadow: var(--shadow-soft);
}

.detail-metric span {
  font-size: 11px;
  color: var(--text-faint);
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.detail-label {
  font-size: 11px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--text-faint);
}

.info-top h1,
.section-head h2 {
  margin: 0;
  font-size: clamp(2.1rem, 3vw, 3.4rem);
  line-height: 1.02;
  letter-spacing: -0.04em;
}

.info-top p,
.related-copy p {
  margin: 0;
  color: var(--text-soft);
}

.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.tag-pill {
  padding: 7px 10px;
  border-radius: 999px;
  background: rgba(0, 196, 180, 0.1);
  color: #215462;
  font-size: 12px;
}

.price-panel {
  display: grid;
  gap: 6px;
  padding: 20px;
  border-radius: 24px;
  background: linear-gradient(135deg, rgba(255, 77, 77, 0.1) 0%, rgba(255, 255, 255, 0.86) 100%);
  border: 1px solid rgba(255, 77, 77, 0.12);
}

.action-feedback {
  margin: 0;
  padding: 12px 14px;
  border-radius: 16px;
  font-size: 13px;
  line-height: 1.6;
}

.action-feedback.success {
  background: #e5f6ef;
  color: #106b53;
}

.action-feedback.error {
  background: #fff1ec;
  color: #a53f2d;
}

.price-panel strong {
  font-size: 30px;
}

.action-row,
.related-foot {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
}

.review-panel {
  display: grid;
  gap: 10px;
  padding: 14px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.88);
  border: 1px solid rgba(255, 255, 255, 0.76);
}

.review-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.review-list {
  margin: 0;
  padding-left: 18px;
  display: grid;
  gap: 8px;
  color: var(--text-soft);
}

.review-item {
  line-height: 1.7;
}

.review-empty {
  margin: 0;
  color: var(--text-faint);
}

.primary-btn,
.secondary-btn,
.detail-link {
  min-height: 44px;
  padding: 0 16px;
  border-radius: 999px;
  font-weight: 700;
}

.primary-btn {
  border: none;
  background: linear-gradient(135deg, rgba(255, 77, 77, 0.96) 0%, rgba(255, 109, 109, 0.88) 100%);
  color: #fff;
  cursor: pointer;
  box-shadow: 0 16px 28px rgba(255, 77, 77, 0.18);
}

.primary-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.secondary-btn,
.detail-link {
  border: 1px solid rgba(255, 255, 255, 0.82);
  background: rgba(255, 255, 255, 0.9);
  color: var(--text-main);
  cursor: pointer;
  text-decoration: none;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  box-shadow: var(--shadow-soft);
}

.button-link {
  cursor: pointer;
}

.related-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 18px;
}

.related-card {
  display: grid;
  gap: 12px;
}

.clickable-card {
  cursor: pointer;
  transition: transform 300ms ease, box-shadow 300ms ease;
}

.clickable-card:hover {
  transform: translateY(-4px) scale(1.015);
  box-shadow: 0 22px 40px rgba(120, 105, 101, 0.12);
}

.loading-state {
  display: grid;
  gap: 10px;
  text-align: center;
  color: var(--text-soft);
}

.error-state {
  justify-items: center;
}

.error-state p {
  margin: 0;
}

@media (max-width: 1120px) {
  .detail-grid,
  .related-grid {
    grid-template-columns: 1fr 1fr;
  }

  .detail-grid {
    grid-template-columns: 1fr;
  }

  .detail-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .detail-fields {
    grid-template-columns: 1fr 1fr;
  }
}

@media (max-width: 720px) {
  .action-row,
  .related-foot {
    flex-direction: column;
    align-items: stretch;
  }

  .related-grid {
    grid-template-columns: 1fr;
  }

  .detail-metrics {
    grid-template-columns: 1fr;
  }

  .detail-fields {
    grid-template-columns: 1fr;
  }

  .gallery-card,
  .info-card,
  .related-card,
  .loading-state {
    padding: 18px;
    border-radius: 24px;
  }
}
</style>