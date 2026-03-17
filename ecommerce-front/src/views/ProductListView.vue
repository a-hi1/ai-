<template>
  <section class="shop-page">
    <header class="shop-head">
      <div class="shop-head-main">
        <span class="shop-label">商城货架</span>
        <h1>{{ selectedCategory ? `${selectedCategory.label}分区` : '分区式浏览商城' }}</h1>
        <p class="shop-lead">
          {{ selectedCategory ? selectedCategory.description : '先按场景逛，再用搜索收窄范围。没有关键词时，页面会自动按分区整理货架。' }}
        </p>
        <div v-if="selectedCategory" class="selected-examples">
          {{ selectedCategory.examples.join(' / ') }}
        </div>
        <div class="shop-actions">
        <input v-model="keyword" type="search" class="search-input" placeholder="搜索咖啡、耳机、猫粮、锅具、纸尿裤" @keyup.enter="search" />
        <button class="search-btn" @click="search">搜索</button>
        </div>
      </div>
      <div class="shop-head-side">
        <article class="shop-stat-card emphasis">
          <span>在售单品</span>
          <strong>{{ displayProductCount }}</strong>
          <small>{{ hasActiveFilter ? '当前筛选结果' : '全站精选货架' }}</small>
        </article>
        <article class="shop-stat-card">
          <span>场景分区</span>
          <strong>{{ categoryGroups.length }}</strong>
          <small>按生活方式组织</small>
        </article>
        <article class="shop-stat-card accent">
          <span>均价参考</span>
          <strong>{{ averagePriceLabel }}</strong>
          <small>帮助快速判断预算带</small>
        </article>
      </div>
    </header>

    <div class="category-grid">
      <button
        v-for="group in categoryGroups"
        :key="group.category.id"
        class="category-chip"
        :class="{ active: selectedCategoryId === group.category.id }"
        @click="applyCategory(group.category.id)"
      >
        <span>{{ group.category.label }}</span>
        <strong>{{ group.products.length }} 款</strong>
      </button>
      <button class="category-chip ghost" :class="{ active: !selectedCategoryId && !keyword.trim() }" @click="resetFilter">全部分区</button>
    </div>

    <section class="filter-toolbar">
      <label class="filter-field wide">
        <span>排序</span>
        <select v-model="sortBy" @change="search">
          <option value="default">综合推荐</option>
          <option value="price-asc">价格从低到高</option>
          <option value="price-desc">价格从高到低</option>
          <option value="name">名称排序</option>
        </select>
      </label>
      <label class="filter-field">
        <span>最低价</span>
        <input v-model="minPrice" type="number" min="0" placeholder="0" @keyup.enter="search" />
      </label>
      <label class="filter-field">
        <span>最高价</span>
        <input v-model="maxPrice" type="number" min="0" placeholder="不限" @keyup.enter="search" />
      </label>
      <button class="filter-apply" type="button" @click="search">应用筛选</button>
    </section>

    <div v-if="activeFilterChips.length" class="active-filters">
      <button v-for="chip in activeFilterChips" :key="chip.label" class="active-filter-chip" type="button" @click="chip.remove">
        {{ chip.label }}
      </button>
    </div>

    <section v-if="hasActiveFilter" class="result-panel">
      <div class="section-head">
        <div>
          <span class="section-label">筛选结果</span>
          <h2>{{ resultTitle }}</h2>
        </div>
        <button class="section-link button-link" type="button" @click="resetFilter">清空筛选</button>
      </div>

      <div v-if="filteredProducts.length" class="result-grid">
        <article
          v-for="product in filteredProducts"
          :key="product.id"
          class="product-card clickable-card"
          role="link"
          tabindex="0"
          @click="openProduct(product)"
          @keyup.enter="openProduct(product)"
        >
          <div class="card-kicker">
            <span class="shelf-pill">{{ selectedCategory?.label || '生活优选' }}</span>
            <span class="heat-pill">推荐货架</span>
          </div>
          <img :src="product.imageUrl || fallbackImage(product.id)" :alt="product.name" class="product-image" loading="lazy" decoding="async" />
          <div class="product-body">
            <div class="product-headline">
              <strong>{{ product.name }}</strong>
              <span>{{ formatCurrency(product.price) }}</span>
            </div>
            <p>{{ productCardDescription(product, selectedCategory?.description || '商品详情') }}</p>
            <div v-if="splitTags(product.tags).length" class="tag-row">
              <span v-for="tag in splitTags(product.tags)" :key="tag" class="tag-pill">{{ tag }}</span>
            </div>
          </div>
          <div class="card-actions">
            <button class="detail-link button-link" type="button" @click.stop="openProduct(product)">查看详情</button>
            <button class="cart-btn" @click.stop="addToCart(product)">加入购物车</button>
          </div>
        </article>
      </div>

      <div v-else class="empty-state">{{ emptyMessage }}</div>
    </section>

    <section v-else class="section-stack">
      <div v-if="!categoryGroups.length" class="empty-state">{{ emptyMessage }}</div>
      <article v-for="group in categoryGroups" :key="group.category.id" class="zone-block">
        <div class="section-head">
          <div>
            <span class="section-label">{{ group.category.label }}</span>
            <h2>{{ group.category.headline }}</h2>
          </div>
          <button class="section-link button-link" type="button" @click="applyCategory(group.category.id)">只看该分区</button>
        </div>
        <p class="zone-description">{{ group.category.description }}</p>
        <div class="zone-examples">{{ group.category.examples.join(' / ') }}</div>

        <div class="result-grid compact-grid">
          <article
            v-for="product in group.products.slice(0, 4)"
            :key="product.id"
            class="product-card clickable-card"
            role="link"
            tabindex="0"
            @click="openProduct(product)"
            @keyup.enter="openProduct(product)"
          >
            <div class="card-kicker">
              <span class="shelf-pill">{{ group.category.label }}</span>
              <span class="heat-pill">场景热卖</span>
            </div>
            <img :src="product.imageUrl || fallbackImage(product.id)" :alt="product.name" class="product-image" loading="lazy" decoding="async" />
            <div class="product-body">
              <div class="product-headline">
                <strong>{{ product.name }}</strong>
                <span>{{ formatCurrency(product.price) }}</span>
              </div>
              <p>{{ productCardDescription(product, group.category.description) }}</p>
            </div>
            <div class="card-actions">
              <button class="detail-link button-link" type="button" @click.stop="openProduct(product)">查看详情</button>
              <button class="cart-btn" @click.stop="addToCart(product)">加入购物车</button>
            </div>
          </article>
        </div>
      </article>
    </section>

    <transition name="cart-toast">
      <div v-if="cartNotice" :class="['cart-toast', cartNoticeTone]" role="status" aria-live="polite">
        {{ cartNotice }}
      </div>
    </transition>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { groupProductsByCategory, productCardDescription, productMatchesKeyword, SHOP_CATEGORY_CONFIG, splitProductTags } from '../catalog'
import { useAuth } from '../composables/useAuth'
import { useCart } from '../composables/useCart'
import { api, type ProductDto } from '../services/api'

const route = useRoute()
const router = useRouter()
const { currentUser } = useAuth()
const { addItem } = useCart()

const allProducts = ref<ProductDto[]>([])
const keyword = ref('')
const selectedCategoryId = ref('')
const sortBy = ref<'default' | 'price-asc' | 'price-desc' | 'name'>('default')
const minPrice = ref('')
const maxPrice = ref('')
const loadFailed = ref(false)
const cartNotice = ref('')
const cartNoticeTone = ref<'success' | 'error'>('success')
let cartNoticeTimer: number | null = null

const splitTags = (tags?: string) => splitProductTags(tags).slice(0, 4)

const categoryGroups = computed(() => {
  return groupProductsByCategory(allProducts.value)
})

const selectedCategory = computed(() => {
  return SHOP_CATEGORY_CONFIG.find(item => item.id === selectedCategoryId.value) ?? null
})

const filteredProducts = computed(() => {
  const searchKeyword = keyword.value.trim()
  const min = Number(minPrice.value)
  const max = Number(maxPrice.value)

  const matchedProducts = allProducts.value.filter(product => {
    const matchesSelectedCategory = !selectedCategoryId.value || categoryGroups.value
      .find(group => group.category.id === selectedCategoryId.value)
      ?.products.some(item => item.id === product.id)

    const matchesKeywordSearch = !searchKeyword || productMatchesKeyword(product, searchKeyword)
    const matchesMinPrice = !minPrice.value || (!Number.isNaN(min) && product.price >= min)
    const matchesMaxPrice = !maxPrice.value || (!Number.isNaN(max) && product.price <= max)
    return Boolean(matchesSelectedCategory) && matchesKeywordSearch && matchesMinPrice && matchesMaxPrice
  })

  return [...matchedProducts].sort((left, right) => {
    if (sortBy.value === 'price-asc') {
      return left.price - right.price
    }
    if (sortBy.value === 'price-desc') {
      return right.price - left.price
    }
    if (sortBy.value === 'name') {
      return left.name.localeCompare(right.name, 'zh-CN')
    }
    return 0
  })
})

const hasActiveFilter = computed(() => Boolean(
  selectedCategoryId.value || keyword.value.trim() || minPrice.value || maxPrice.value || sortBy.value !== 'default'
))

const activeFilterChips = computed(() => {
  const chips: Array<{ label: string, remove: () => void }> = []
  if (selectedCategory.value) {
    chips.push({ label: `分区：${selectedCategory.value.label}`, remove: () => { selectedCategoryId.value = ''; syncQuery() } })
  }
  if (keyword.value.trim()) {
    chips.push({ label: `关键词：${keyword.value.trim()}`, remove: () => { keyword.value = ''; syncQuery() } })
  }
  if (minPrice.value) {
    chips.push({ label: `最低价：${minPrice.value}`, remove: () => { minPrice.value = ''; syncQuery() } })
  }
  if (maxPrice.value) {
    chips.push({ label: `最高价：${maxPrice.value}`, remove: () => { maxPrice.value = ''; syncQuery() } })
  }
  if (sortBy.value !== 'default') {
    const labelMap = {
      'price-asc': '价格升序',
      'price-desc': '价格降序',
      name: '名称排序',
      default: '综合推荐'
    }
    chips.push({ label: `排序：${labelMap[sortBy.value]}`, remove: () => { sortBy.value = 'default'; syncQuery() } })
  }
  return chips
})

const resultTitle = computed(() => {
  if (selectedCategory.value && keyword.value.trim()) {
    return `${selectedCategory.value.label} · ${keyword.value.trim()} · ${filteredProducts.value.length} 款`
  }
  if (selectedCategory.value) {
    return `${selectedCategory.value.label} · ${filteredProducts.value.length} 款`
  }
  return `搜索“${keyword.value.trim()}” · ${filteredProducts.value.length} 款`
})

const emptyMessage = computed(() => {
  if (loadFailed.value) {
    return '商品接口暂时不可用，分区货架还没加载出来。请稍后刷新，或先去 AI 导购页继续浏览。'
  }
  return hasActiveFilter.value
    ? '当前没有匹配的商品，换个关键词或切换分区试试。'
    : '当前没有可展示的商品分区，请稍后重试。'
})

const displayProductCount = computed(() => {
  const count = hasActiveFilter.value ? filteredProducts.value.length : allProducts.value.length
  return count.toLocaleString('zh-CN')
})

const formatCurrency = (price: number) => new Intl.NumberFormat('zh-CN', {
  style: 'currency',
  currency: 'CNY',
  maximumFractionDigits: 0
}).format(price)

const averagePriceLabel = computed(() => {
  const source = hasActiveFilter.value ? filteredProducts.value : allProducts.value
  if (!source.length) {
    return '--'
  }

  const total = source.reduce((sum, product) => sum + product.price, 0)
  return formatCurrency(total / source.length)
})

const fallbackImage = (id: string) => `https://picsum.photos/seed/list-${id}/640/420`

const cacheProductPreview = (product: ProductDto) => {
  try {
    window.sessionStorage.setItem(`product-preview:${product.id}`, JSON.stringify({
      id: product.id,
      name: product.name,
      description: product.description || '',
      price: product.price,
      imageUrl: product.imageUrl || fallbackImage(product.id),
      tags: product.tags || ''
    }))
  } catch {
    // ignore session storage failures
  }
}

const openProduct = (product: ProductDto) => {
  cacheProductPreview(product)
  router.push(`/products/${product.id}`)
}

const syncQuery = () => {
  const nextQuery: Record<string, string> = {}
  if (selectedCategoryId.value) {
    nextQuery.category = selectedCategoryId.value
  }
  if (keyword.value.trim()) {
    nextQuery.q = keyword.value.trim()
  }
  if (sortBy.value !== 'default') {
    nextQuery.sort = sortBy.value
  }
  if (minPrice.value) {
    nextQuery.min = minPrice.value
  }
  if (maxPrice.value) {
    nextQuery.max = maxPrice.value
  }
  void router.replace({ path: '/shop', query: nextQuery })
}

const search = () => {
  syncQuery()
}

const applyCategory = (categoryId: string) => {
  selectedCategoryId.value = categoryId
  syncQuery()
}

const resetFilter = () => {
  selectedCategoryId.value = ''
  keyword.value = ''
  sortBy.value = 'default'
  minPrice.value = ''
  maxPrice.value = ''
  syncQuery()
}

const addToCart = async (product: ProductDto) => {
  if (!currentUser.value) {
    router.push('/login')
    return
  }

  try {
    await addItem({
      id: product.id,
      name: product.name,
      price: product.price,
      desc: product.description || '',
      image: product.imageUrl || fallbackImage(product.id)
    })
    showCartNotice(`已加入购物车：${product.name}`)
  } catch {
    showCartNotice('加入购物车失败，请稍后重试。', 'error')
  }
}

const showCartNotice = (message: string, tone: 'success' | 'error' = 'success') => {
  cartNoticeTone.value = tone
  cartNotice.value = message
  if (cartNoticeTimer) {
    window.clearTimeout(cartNoticeTimer)
  }
  cartNoticeTimer = window.setTimeout(() => {
    cartNotice.value = ''
  }, 2200)
}

const applyRouteQuery = () => {
  selectedCategoryId.value = typeof route.query.category === 'string' ? route.query.category : ''
  keyword.value = typeof route.query.q === 'string' ? route.query.q : ''
  sortBy.value = route.query.sort === 'price-asc' || route.query.sort === 'price-desc' || route.query.sort === 'name'
    ? route.query.sort
    : 'default'
  minPrice.value = typeof route.query.min === 'string' ? route.query.min : ''
  maxPrice.value = typeof route.query.max === 'string' ? route.query.max : ''
}

onMounted(async () => {
  applyRouteQuery()
  try {
    allProducts.value = await api.listProducts()
    loadFailed.value = false
  } catch {
    allProducts.value = []
    loadFailed.value = true
  }
})

watch(() => route.query, () => {
  applyRouteQuery()
})

onBeforeUnmount(() => {
  if (cartNoticeTimer) {
    window.clearTimeout(cartNoticeTimer)
  }
})
</script>

<style scoped>
.shop-page {
  display: grid;
  gap: 22px;
  position: relative;
}

.cart-toast {
  position: fixed;
  right: 26px;
  bottom: 30px;
  z-index: 40;
  min-width: min(360px, calc(100vw - 32px));
  max-width: min(420px, calc(100vw - 32px));
  padding: 12px 16px;
  border-radius: 14px;
  border: 1px solid rgba(16, 185, 129, 0.36);
  background: rgba(6, 95, 70, 0.92);
  color: #ecfdf5;
  font-weight: 600;
  box-shadow: 0 18px 40px rgba(4, 120, 87, 0.28);
  backdrop-filter: blur(8px);
}

.cart-toast.error {
  border-color: rgba(248, 113, 113, 0.34);
  background: rgba(153, 27, 27, 0.9);
  color: #fef2f2;
  box-shadow: 0 18px 40px rgba(153, 27, 27, 0.28);
}

.cart-toast-enter-active,
.cart-toast-leave-active {
  transition: opacity 0.22s ease, transform 0.22s ease;
}

.cart-toast-enter-from,
.cart-toast-leave-to {
  opacity: 0;
  transform: translateY(10px);
}

.shop-head {
  display: grid;
  grid-template-columns: minmax(0, 1.55fr) minmax(280px, 0.9fr);
  gap: 18px;
  padding: 24px;
  border-radius: 30px;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.62) 0%, rgba(255, 255, 255, 0.28) 100%),
    radial-gradient(circle at top left, rgba(255, 77, 77, 0.1), transparent 32%),
    radial-gradient(circle at bottom right, rgba(0, 196, 180, 0.08), transparent 30%);
  border: 1px solid rgba(255, 255, 255, 0.72);
  box-shadow: var(--shadow-lg);
  backdrop-filter: blur(18px);
}

.shop-head-main {
  display: grid;
  gap: 14px;
}

.shop-head-side {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  align-content: start;
}

.shop-label,
.section-label {
  font-size: 11px;
  letter-spacing: 0.2em;
  text-transform: uppercase;
  color: var(--text-faint);
}

.shop-head h1,
.section-head h2 {
  margin: 4px 0 0;
  font-size: clamp(2rem, 3.1vw, 3.2rem);
  line-height: 1.02;
  letter-spacing: -0.04em;
}

.shop-lead,
.zone-description,
.product-body p {
  margin: 0;
  color: var(--text-soft);
  line-height: 1.6;
}

.selected-examples,
.zone-examples {
  color: #6b5844;
  line-height: 1.4;
  font-size: 12px;
}

.shop-actions,
.section-head,
.product-headline,
.card-actions {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: center;
}

.shop-actions {
  align-items: stretch;
  flex-wrap: wrap;
}

.shop-stat-card {
  display: grid;
  gap: 6px;
  padding: 16px;
  border-radius: 22px;
  background: rgba(255, 255, 255, 0.84);
  border: 1px solid rgba(255, 255, 255, 0.78);
  box-shadow: var(--shadow-soft);
}

.shop-stat-card.emphasis {
  grid-column: span 2;
  background: linear-gradient(135deg, rgba(255, 77, 77, 0.12) 0%, rgba(255, 255, 255, 0.88) 100%);
}

.shop-stat-card.accent {
  background: linear-gradient(135deg, rgba(0, 196, 180, 0.12) 0%, rgba(255, 255, 255, 0.88) 100%);
}

.shop-stat-card span {
  font-size: 11px;
  color: var(--text-faint);
  letter-spacing: 0.16em;
  text-transform: uppercase;
}

.shop-stat-card strong {
  font-size: 30px;
  line-height: 1;
}

.shop-stat-card small {
  color: var(--text-soft);
}

.search-input {
  width: min(420px, 100%);
  min-height: 50px;
  padding: 0 18px;
  border-radius: 999px;
  border: 1px solid rgba(255, 77, 77, 0.14);
  background: rgba(255, 255, 255, 0.94);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.72), 0 10px 18px rgba(120, 105, 101, 0.06);
}

.search-btn,
.cart-btn,
.detail-link,
.section-link,
.category-chip {
  min-height: 44px;
  padding: 0 16px;
  border-radius: 999px;
  font-weight: 700;
}

.search-btn,
.cart-btn,
.section-link,
.category-chip.active {
  border: none;
  background: linear-gradient(135deg, rgba(255, 77, 77, 0.96) 0%, rgba(255, 109, 109, 0.88) 100%);
  color: #fff;
  cursor: pointer;
  box-shadow: 0 16px 28px rgba(255, 77, 77, 0.18);
}

.detail-link,
.category-chip,
.category-chip.ghost {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  text-decoration: none;
  border: 1px solid rgba(255, 255, 255, 0.82);
  color: var(--text-main);
  background: rgba(255, 255, 255, 0.9);
  cursor: pointer;
  box-shadow: var(--shadow-soft);
}

.button-link {
  cursor: pointer;
}

.category-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.filter-toolbar {
  display: grid;
  grid-template-columns: minmax(180px, 1.4fr) repeat(2, minmax(120px, 0.8fr)) auto;
  gap: 12px;
  align-items: end;
  padding: 18px;
  border-radius: 26px;
  background: rgba(255, 255, 255, 0.86);
  border: 1px solid rgba(255, 255, 255, 0.78);
  box-shadow: var(--shadow-soft);
  backdrop-filter: blur(16px);
}

.filter-field {
  display: grid;
  gap: 6px;
}

.filter-field span {
  font-size: 12px;
  color: var(--text-faint);
}

.filter-field input,
.filter-field select {
  min-height: 42px;
  padding: 0 14px;
  border-radius: 16px;
  border: 1px solid rgba(255, 77, 77, 0.12);
  background: rgba(255, 255, 255, 0.96);
}

.filter-apply,
.active-filter-chip {
  min-height: 42px;
  padding: 0 16px;
  border-radius: 999px;
  border: 1px solid rgba(255, 255, 255, 0.82);
  background: rgba(255, 255, 255, 0.9);
  cursor: pointer;
}

.filter-apply {
  background: linear-gradient(135deg, rgba(255, 77, 77, 0.96) 0%, rgba(255, 109, 109, 0.88) 100%);
  color: #fff;
  border: none;
}

.active-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.active-filter-chip {
  color: var(--text-main);
}

.category-chip {
  flex-direction: column;
  gap: 4px;
  align-items: flex-start;
  min-height: 64px;
  padding: 12px 16px;
}

.category-chip span {
  color: inherit;
  font-size: 12px;
}

.category-chip strong {
  font-size: 11px;
  opacity: 0.82;
}

.result-panel,
.section-stack,
.zone-block {
  display: grid;
  gap: 18px;
}

.zone-block,
.result-panel {
  padding: 22px;
  border-radius: 28px;
  background: rgba(255, 255, 255, 0.86);
  border: 1px solid rgba(255, 255, 255, 0.8);
  box-shadow: var(--shadow-soft);
  backdrop-filter: blur(16px);
}

.result-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 18px;
}

.compact-grid {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.product-card {
  display: grid;
  gap: 12px;
  padding: 16px;
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.94);
  border: 1px solid rgba(255, 255, 255, 0.84);
  box-shadow: var(--shadow-soft);
}

.clickable-card {
  cursor: pointer;
  transition: transform 300ms ease, box-shadow 300ms ease;
}

.clickable-card:hover {
  transform: translateY(-4px) scale(1.015);
  box-shadow: 0 22px 40px rgba(120, 105, 101, 0.12);
}

.card-kicker {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  align-items: center;
}

.shelf-pill,
.heat-pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 28px;
  padding: 0 10px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
}

.shelf-pill {
  background: rgba(0, 196, 180, 0.1);
  color: var(--accent-deep);
}

.heat-pill {
  background: rgba(255, 77, 77, 0.1);
  color: var(--brand-deep);
}

.product-image {
  width: 100%;
  aspect-ratio: 1;
  object-fit: cover;
  border-radius: 20px;
  transition: transform 300ms ease;
}

.product-card:hover .product-image {
  transform: scale(1.035);
}

.product-body {
  display: grid;
  gap: 8px;
}

.product-body p {
  margin: 0;
}

.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.tag-pill {
  padding: 6px 10px;
  border-radius: 999px;
  background: rgba(0, 196, 180, 0.1);
  color: #215462;
  font-size: 12px;
}

.empty-state {
  padding: 32px;
  border-radius: 28px;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(255, 255, 255, 0.82);
  text-align: center;
  color: var(--text-soft);
  box-shadow: var(--shadow-soft);
}

@media (max-width: 1120px) {
  .shop-head {
    grid-template-columns: 1fr;
  }

  .result-grid,
  .compact-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .filter-toolbar {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .shop-head,
  .shop-head-side,
  .shop-actions,
  .card-actions,
  .section-head,
  .product-headline {
    grid-template-columns: 1fr;
    flex-direction: column;
    align-items: stretch;
  }

  .result-grid,
  .compact-grid {
    grid-template-columns: 1fr;
  }

  .filter-toolbar {
    grid-template-columns: 1fr;
  }

  .search-input {
    width: 100%;
  }

  .shop-head,
  .filter-toolbar,
  .zone-block,
  .result-panel,
  .empty-state {
    padding: 18px;
    border-radius: 24px;
  }
}
</style>