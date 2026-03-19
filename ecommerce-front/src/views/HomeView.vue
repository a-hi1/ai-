<template>
  <section class="home-page">
    <header class="showcase-head">
      <aside class="showcase-menu">
        <div class="menu-title">全部分类</div>
        <button
          v-for="group in menuGroups"
          :key="group.category.id"
          class="menu-item"
          type="button"
          @click="openCategory(group.category.id)"
          :style="{ '--menu-accent': categoryAccent(group.category.id) }"
        >
          <div class="menu-item-copy">
            <span class="menu-icon"></span>
            <div>
              <span>{{ group.category.label }}</span>
              <small>{{ group.category.headline }}</small>
            </div>
          </div>
          <strong class="menu-count"><i></i>{{ group.products.length }}</strong>
        </button>
      </aside>

      <div
        class="showcase-banner"
        :style="bannerStyle"
        @mouseenter="pauseCarousel"
        @mouseleave="resumeCarousel"
        @focusin="pauseCarousel"
        @focusout="resumeCarousel"
        @touchstart="handleTouchStart"
        @touchend="handleTouchEnd"
      >
        <span class="hero-label">2026 Lifestyle Edit</span>
        <h1>{{ activeSlide.title }}</h1>
        <p class="hero-lead">{{ activeSlide.description }}</p>
        <div class="hero-actions">
          <RouterLink :to="activeSlide.primaryTo" class="primary-link">{{ activeSlide.primaryLabel }}</RouterLink>
          <RouterLink :to="activeSlide.secondaryTo" class="secondary-link">{{ activeSlide.secondaryLabel }}</RouterLink>
        </div>
        <div class="hero-metrics">
          <div class="hero-metric">
            <span>在售商品</span>
            <strong>{{ animatedProductCount }}</strong>
          </div>
          <div class="hero-metric">
            <span>场景分区</span>
            <strong>{{ categoryGroups.length }}</strong>
          </div>
          <div class="hero-metric">
            <span>今日推荐</span>
            <strong>{{ featuredGroup?.products.length || 0 }}</strong>
          </div>
        </div>
        <div class="banner-feature">
          <div class="feature-tag">{{ activeSlide.badge }}</div>
          <strong>{{ activeSlide.featureTitle }}</strong>
          <p>{{ activeSlide.featureText }}</p>
        </div>
        <div class="carousel-controls">
          <button class="carousel-btn" type="button" @click="prevSlide">上一张</button>
          <div class="carousel-dots">
            <button
              v-for="(slide, index) in heroSlides"
              :key="slide.id"
              class="carousel-dot"
              :class="{ active: index === activeSlideIndex }"
              type="button"
              @click="setSlide(index)"
              :aria-label="`切换到第 ${index + 1} 张`"
            ></button>
          </div>
          <button class="carousel-btn" type="button" @click="nextSlide">下一张</button>
        </div>
      </div>

      <aside class="showcase-side">
        <article class="showcase-card metric-card">
          <span class="side-kicker">在售商品</span>
          <strong class="mega-number">{{ animatedProductCount }}</strong>
          <small>{{ categorySummary }}</small>
        </article>
        <article class="showcase-card trend-card">
          <span class="side-kicker">今日热区</span>
          <strong>{{ featuredGroup?.category.label || '生活分区' }}</strong>
          <div class="trend-tags">
            <span v-for="tag in featuredGroupTags" :key="tag" class="trend-tag">{{ tag }}</span>
          </div>
        </article>
        <article class="showcase-card floating-hint-card">
          <span class="side-kicker">AI 导购入口</span>
          <strong>已收进右侧悬浮气泡</strong>
          <small>点击右下侧在线导购气泡，直接输入一句话需求。推荐词会轮播，示例句和输入要求都在展开层里。</small>
        </article>
      </aside>
    </header>

    <section v-for="group in spotlightGroups" :key="group.category.id" class="section-block">
      <div class="section-head">
        <div>
          <span class="section-label">{{ group.category.label }}</span>
          <h2>{{ group.category.headline }}</h2>
        </div>
        <button class="section-link button-link" type="button" @click="openCategory(group.category.id)">进入分区</button>
      </div>

        <div class="product-grid">
        <article
          v-for="(product, index) in group.products.slice(0, 4)"
          :key="product.id"
          class="product-card clickable-card"
          role="link"
          tabindex="0"
          @click="openProduct(product)"
          @keyup.enter="openProduct(product)"
        >
          <div class="product-badges">
            <span v-if="index === 0" class="hot-flag">今日热卖</span>
            <span class="scene-pill">{{ group.category.label }}</span>
          </div>
          <img :src="product.imageUrl || fallbackImage(product.id)" :alt="product.name" class="product-image" loading="lazy" decoding="async" />
          <div class="product-copy">
            <strong>{{ product.name }}</strong>
          </div>
          <div v-if="splitProductTags(product.tags).length" class="tag-row micro-cloud">
            <span v-for="tag in splitProductTags(product.tags).slice(0, 3)" :key="`${product.id}-${tag}`" class="tag-pill">{{ tag }}</span>
          </div>
          <div class="product-foot">
            <span>{{ formatCurrency(product.price) }}</span>
            <button class="mini-link button-link" type="button" @click.stop="openProduct(product)">查看</button>
          </div>
        </article>
      </div>
    </section>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import { groupProductsByCategory, splitProductTags } from '../catalog'
import { api, type ProductDto } from '../services/api'

const CATEGORY_ACCENTS: Record<string, string> = {
  'home-essentials': '#00b59f',
  featured: '#ff7f50',
  'food-fresh': '#7cb342',
  digital: '#2f6df6',
  fashion: '#d25e99',
  'home-furniture': '#8d6e63',
  'personal-care': '#ff8a65',
  'bags-accessories': '#6a5acd'
}

const products = ref<ProductDto[]>([])
const router = useRouter()
const activeSlideIndex = ref(0)
const isCarouselPaused = ref(false)
const touchStartX = ref(0)
let slideTimer: number | undefined

const categoryGroups = computed(() => {
  return groupProductsByCategory(products.value).map(group => ({
    ...group,
    previewTags: Array.from(new Set(group.products.flatMap(product => splitProductTags(product.tags)))).slice(0, 3),
    previewProducts: group.products.slice(0, 3).map(product => product.name)
  }))
})

const spotlightGroups = computed(() => {
  return categoryGroups.value.slice(0, 3)
})

const menuGroups = computed(() => categoryGroups.value.slice(0, 5))

const featuredGroup = computed(() => categoryGroups.value[0] ?? null)

const featuredGroupTags = computed(() => {
  if (!featuredGroup.value) {
    return ['热卖', '高频购买', '生活方式']
  }

  return Array.from(new Set(featuredGroup.value.products.flatMap(product => splitProductTags(product.tags)))).slice(0, 4)
})

const animatedProductCount = computed(() => products.value.length.toLocaleString('zh-CN'))

const heroSlides = computed(() => {
  const categorySlides = spotlightGroups.value.map(group => ({
    id: group.category.id,
    title: `${group.category.label}主会场，先看热卖再下单`,
    description: `${group.category.description} 首页先帮你展示主推商品和代表品类，点进去就能继续筛选。`,
    primaryLabel: '进入该分区',
    primaryTo: `/shop?category=${encodeURIComponent(group.category.id)}`,
    secondaryLabel: '查看主推商品',
    secondaryTo: group.products[0] ? `/products/${group.products[0].id}` : '/shop',
    badge: '热门专区',
    featureTitle: group.category.label,
    featureText: group.category.examples.join(' / '),
    imageUrl: group.products[0]?.imageUrl || fallbackImage(group.category.id)
  }))

  return [
    {
      id: 'mall-showcase',
      title: '像逛淘宝一样先看橱窗，再决定怎么买',
      description: '首页只做展示和分流，热门分区、主推商品都在首屏，AI 导购已经收进右侧悬浮气泡，随时可直接开问。',
      primaryLabel: '去商城逛货架',
      primaryTo: '/shop',
      secondaryLabel: '让 AI 帮我挑',
      secondaryTo: '/chat',
      badge: '首页主推',
      featureTitle: featuredGroup.value?.category.label || '热门货架',
      featureText: featuredGroup.value?.category.examples.join(' / ') || categorySummary.value,
      imageUrl: featuredGroup.value?.products[0]?.imageUrl || fallbackImage('mall-showcase')
    },
    ...categorySlides,
    {
      id: 'ai-guide',
      title: '拿不准怎么选时，直接交给 AI 导购',
      description: '告诉 AI 你想买什么，系统会通过几个问题缩小范围，再给出主推和备选推荐。',
      primaryLabel: '去 AI 导购',
      primaryTo: '/chat',
      secondaryLabel: '先看商城',
      secondaryTo: '/shop',
      badge: '智能导购',
      featureTitle: '预算 + 场景 + 偏好',
      featureText: '一句话缩小选择范围，保留详情、加购和下单链路',
      imageUrl: featuredGroup.value?.products[1]?.imageUrl || fallbackImage('ai-guide')
    }
  ]
})

const activeSlide = computed(() => {
  return heroSlides.value[activeSlideIndex.value] ?? heroSlides.value[0] ?? {
    id: 'fallback',
    title: '欢迎来到首页',
    description: '浏览商品与导购入口。',
    primaryLabel: '去商城',
    primaryTo: '/shop',
    secondaryLabel: '去导购',
    secondaryTo: '/chat',
    badge: '首页',
    featureTitle: categorySummary.value,
    featureText: categorySummary.value,
    imageUrl: fallbackImage('fallback')
  }
})

const bannerStyle = computed(() => ({
  '--hero-image': `url(${activeSlide.value.imageUrl})`
}))

const categorySummary = computed(() => {
  const labels = categoryGroups.value.slice(0, 4).map(group => group.category.label)
  return labels.length ? labels.join(' / ') : '生活用品 / 食品生鲜 / 电子数码 / 个护美妆'
})

const categoryAccent = (categoryId: string) => CATEGORY_ACCENTS[categoryId] || 'var(--brand)'

const formatCurrency = (price: number) => new Intl.NumberFormat('zh-CN', {
  style: 'currency',
  currency: 'CNY',
  maximumFractionDigits: 0
}).format(price)

const fallbackImage = (id: string) => `https://picsum.photos/seed/home-${id}/640/420`

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

const openCategory = (categoryId: string) => {
  router.push(`/shop?category=${encodeURIComponent(categoryId)}`)
}

const setSlide = (index: number) => {
  activeSlideIndex.value = index
  restartSlideTimer()
}

const nextSlide = () => {
  if (!heroSlides.value.length) {
    return
  }
  activeSlideIndex.value = (activeSlideIndex.value + 1) % heroSlides.value.length
}

const prevSlide = () => {
  if (!heroSlides.value.length) {
    return
  }
  activeSlideIndex.value = (activeSlideIndex.value - 1 + heroSlides.value.length) % heroSlides.value.length
}

const pauseCarousel = () => {
  isCarouselPaused.value = true
  if (slideTimer) {
    window.clearInterval(slideTimer)
    slideTimer = undefined
  }
}

const resumeCarousel = () => {
  isCarouselPaused.value = false
  restartSlideTimer()
}

const handleTouchStart = (event: TouchEvent) => {
  touchStartX.value = event.changedTouches[0]?.clientX || 0
  pauseCarousel()
}

const handleTouchEnd = (event: TouchEvent) => {
  const deltaX = (event.changedTouches[0]?.clientX || 0) - touchStartX.value
  if (Math.abs(deltaX) > 36) {
    if (deltaX < 0) {
      nextSlide()
    } else {
      prevSlide()
    }
  }
  resumeCarousel()
}

const restartSlideTimer = () => {
  if (slideTimer) {
    window.clearInterval(slideTimer)
  }

  if (heroSlides.value.length <= 1 || isCarouselPaused.value) {
    return
  }

  slideTimer = window.setInterval(() => {
    nextSlide()
  }, 5000)
}

onMounted(async () => {
  try {
    products.value = await api.listProducts()
  } catch {
    products.value = []
  }
  restartSlideTimer()
})

watch(heroSlides, slides => {
  if (!slides.length) {
    activeSlideIndex.value = 0
    return
  }

  if (activeSlideIndex.value >= slides.length) {
    activeSlideIndex.value = 0
  }

  restartSlideTimer()
})

onBeforeUnmount(() => {
  if (slideTimer) {
    window.clearInterval(slideTimer)
  }
})
</script>

<style scoped>
.home-page {
  display: grid;
  gap: 28px;
}

.showcase-head {
  display: grid;
  grid-template-columns: 220px minmax(0, 1.45fr) 260px;
  gap: 16px;
  padding: 18px;
  border-radius: 28px;
  background:
    linear-gradient(135deg, rgba(255, 255, 255, 0.58) 0%, rgba(255, 255, 255, 0.22) 100%),
    radial-gradient(circle at top right, rgba(255, 77, 77, 0.1), transparent 32%),
    radial-gradient(circle at 100% 100%, rgba(0, 196, 180, 0.08), transparent 26%);
  border: 1px solid rgba(255, 255, 255, 0.6);
  box-shadow: var(--shadow-lg);
  backdrop-filter: blur(14px);
}

.showcase-menu,
.showcase-side {
  display: grid;
  gap: 12px;
}

.menu-title {
  padding: 10px 16px;
  border-radius: 16px;
  background: linear-gradient(135deg, rgba(255, 77, 77, 0.96) 0%, rgba(255, 77, 77, 0.84) 100%);
  color: #fff9ef;
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.14em;
  text-transform: uppercase;
}

.menu-item {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
  padding: 14px 16px;
  border-radius: 18px;
  border: 1px solid rgba(255, 255, 255, 0.72);
  background: rgba(255, 255, 255, 0.96);
  color: var(--text-main);
  font-weight: 700;
  cursor: pointer;
  box-shadow: var(--shadow-soft);
}

.menu-item:hover {
  transform: translateY(-4px);
  border-color: rgba(255, 77, 77, 0.18);
  box-shadow: 0 22px 36px rgba(120, 105, 101, 0.12);
}

.menu-item-copy {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.menu-item-copy div {
  display: grid;
  gap: 4px;
}

.menu-item-copy span {
  font-size: 14px;
}

.menu-item-copy small {
  color: var(--text-faint);
  font-size: 11px;
}

.menu-icon {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: var(--menu-accent);
  box-shadow: 0 0 0 8px color-mix(in srgb, var(--menu-accent) 16%, white);
  transition: transform 300ms ease, box-shadow 300ms ease;
}

.menu-item:hover .menu-icon {
  transform: scale(1.08);
  box-shadow: 0 0 0 9px color-mix(in srgb, var(--menu-accent) 22%, white);
}

.menu-count {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--brand-deep);
  font-size: 13px;
}

.menu-count i {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--menu-accent);
}

.showcase-banner {
  display: grid;
  gap: 20px;
  align-content: center;
  min-height: 456px;
  padding: 28px 32px;
  border-radius: 28px;
  background:
    linear-gradient(135deg, rgba(255, 77, 77, 0.86) 0%, rgba(255, 111, 105, 0.74) 38%, rgba(0, 196, 180, 0.58) 100%),
    linear-gradient(rgba(255, 255, 255, 0.08) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.08) 1px, transparent 1px),
    radial-gradient(circle at top right, rgba(255, 255, 255, 0.18), transparent 34%),
    var(--hero-image) center / cover no-repeat;
  background-size: auto, 24px 24px, 24px 24px, auto, cover;
  color: #fff9ef;
  box-shadow: 0 22px 40px rgba(255, 77, 77, 0.14);
  transition: background 300ms ease, transform 300ms ease;
  overflow: hidden;
  contain: layout paint;
}

.hero-label,
.section-label {
  font-size: 11px;
  letter-spacing: 0.26em;
  text-transform: uppercase;
  color: rgba(255, 249, 239, 0.76);
}

.hero-copy h1,
.section-head h2,
.category-card h3 {
  margin: 0;
}

.showcase-banner h1 {
  margin: 0;
  max-width: 10ch;
  font-size: 46px;
  line-height: 1.02;
  letter-spacing: -0.04em;
}

.hero-lead,
.product-copy p {
  margin: 0;
  color: rgba(255, 249, 239, 0.88);
  line-height: 1.55;
  max-width: 56ch;
}

.hero-metrics {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.hero-metric {
  min-width: 110px;
  display: grid;
  gap: 6px;
  padding: 12px 14px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.12);
  border: 1px solid rgba(255, 255, 255, 0.16);
  backdrop-filter: blur(14px);
}

.hero-metric span {
  font-size: 11px;
  color: rgba(255, 249, 239, 0.72);
}

.hero-metric strong {
  font-size: 28px;
  line-height: 1;
}

.hero-actions,
.section-head,
.product-foot,
.category-card-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
}

.hero-actions {
  justify-content: flex-start;
  flex-wrap: wrap;
}

.primary-link,
.secondary-link,
.section-link,
.mini-link {
  text-decoration: none;
}

.button-link {
  border: none;
  background: transparent;
  cursor: pointer;
}

.primary-link,
.secondary-link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 44px;
  padding: 0 22px;
  border-radius: 999px;
  font-size: 13px;
  font-weight: 700;
  backdrop-filter: blur(16px);
  box-shadow: 0 16px 28px rgba(37, 31, 31, 0.14);
}

.primary-link {
  background: rgba(255, 255, 255, 0.22);
  color: #fff;
  border: 1px solid rgba(255, 255, 255, 0.34);
  box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.14), 0 18px 30px rgba(255, 77, 77, 0.24);
}

.secondary-link {
  border: 1px solid rgba(255, 255, 255, 0.24);
  background: rgba(255, 255, 255, 0.1);
  color: #fff9ef;
}

.banner-feature {
  display: grid;
  gap: 8px;
  max-width: 320px;
  padding: 16px 18px;
  border-radius: 22px;
  background: rgba(255, 255, 255, 0.14);
  border: 1px solid rgba(255, 255, 255, 0.18);
  backdrop-filter: blur(16px);
}

.feature-tag {
  display: inline-flex;
  width: fit-content;
  padding: 5px 10px;
  border-radius: 999px;
  background: rgba(255, 245, 230, 0.18);
  font-size: 12px;
  font-weight: 700;
}

.banner-feature strong {
  font-size: 22px;
}

.banner-feature p {
  margin: 0;
  color: rgba(255, 249, 239, 0.82);
}

.carousel-controls {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
}

.carousel-btn {
  min-height: 38px;
  padding: 0 14px;
  border-radius: 999px;
  border: 1px solid rgba(255, 255, 255, 0.24);
  background: rgba(255, 255, 255, 0.08);
  color: #fff9ef;
  cursor: pointer;
}

.carousel-dots {
  display: flex;
  gap: 8px;
  align-items: center;
}

.carousel-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  border: none;
  background: rgba(255, 255, 255, 0.3);
  cursor: pointer;
}

.carousel-dot.active {
  width: 26px;
  border-radius: 999px;
  background: #fff4e3;
}

.showcase-card {
  display: grid;
  gap: 8px;
  padding: 18px;
  border-radius: 24px;
  background: rgba(255, 255, 255, 0.88);
  border: 1px solid rgba(255, 255, 255, 0.7);
  box-shadow: var(--shadow-soft);
  backdrop-filter: blur(18px);
}

.showcase-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 22px 38px rgba(120, 105, 101, 0.12);
}

.side-kicker {
  font-size: 11px;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--text-faint);
}

.showcase-card strong {
  font-size: 24px;
  color: var(--text-main);
}

.showcase-card small {
  color: var(--text-soft);
  line-height: 1.6;
}

.metric-card {
  align-content: center;
}

.floating-hint-card {
  background: linear-gradient(135deg, rgba(255, 255, 255, 0.92) 0%, rgba(255, 250, 246, 0.88) 100%);
}

.mega-number {
  font-size: 54px;
  line-height: 0.94;
  color: var(--brand-deep);
  animation: metric-breathe 3.2s ease-in-out infinite;
}

.trend-card {
  background: linear-gradient(135deg, rgba(255, 255, 255, 0.92) 0%, rgba(247, 255, 253, 0.9) 100%);
}

.trend-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.trend-tag {
  padding: 6px 10px;
  border-radius: 999px;
  background: var(--accent-soft);
  color: var(--accent-deep);
  font-size: 11px;
}

.section-block {
  display: grid;
  gap: 18px;
  content-visibility: auto;
  contain-intrinsic-size: 720px;
}

.section-head {
  align-items: end;
}

.section-link,
.mini-link {
  color: var(--brand);
  font-weight: 700;
}

.product-card {
  display: grid;
  gap: 12px;
  padding: 16px;
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(255, 255, 255, 0.76);
  box-shadow: var(--shadow-soft);
}

.clickable-card {
  cursor: pointer;
  transition: transform 300ms ease, box-shadow 300ms ease;
}

.clickable-card:hover {
  transform: translateY(-3px) scale(1.015);
  box-shadow: 0 18px 32px rgba(120, 105, 101, 0.12);
}

.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.tag-pill {
  padding: 5px 9px;
  border-radius: 999px;
  background: rgba(0, 196, 180, 0.1);
  color: #215462;
  font-size: 11px;
}

.product-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 18px;
}

.product-badges {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  align-items: center;
}

.hot-flag,
.scene-pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 28px;
  padding: 0 10px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
}

.hot-flag {
  background: rgba(255, 77, 77, 0.1);
  color: var(--brand-deep);
}

.scene-pill {
  background: rgba(0, 196, 180, 0.1);
  color: var(--accent-deep);
}

.product-image {
  width: 100%;
  aspect-ratio: 4 / 3;
  object-fit: cover;
  border-radius: 18px;
  transition: transform 300ms ease;
}

.product-card:hover .product-image {
  transform: scale(1.03);
}

.product-copy {
  display: grid;
  gap: 8px;
  min-height: 92px;
}

.product-copy p {
  color: var(--text-soft);
}

.micro-cloud {
  gap: 8px;
}

.product-foot {
  font-weight: 700;
}

@media (max-width: 1120px) {
  .product-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .showcase-head {
    grid-template-columns: 1fr;
    padding: 16px;
  }
}

@media (max-width: 720px) {
  .showcase-head,
  .product-grid,
  .showcase-side {
    grid-template-columns: 1fr;
  }

  .showcase-head,
  .product-card {
    border-radius: 20px;
  }

  .showcase-banner h1 {
    font-size: 30px;
  }

  .section-head,
  .product-foot,
  .carousel-controls {
    flex-direction: column;
    align-items: stretch;
  }

  .showcase-banner {
    min-height: 360px;
    padding: 20px;
  }

  .showcase-menu {
    order: 3;
    grid-auto-flow: column;
    grid-auto-columns: minmax(148px, 1fr);
    overflow-x: auto;
    padding: 8px;
    margin: 2px -2px 0;
    border-radius: 20px;
    background: rgba(255, 255, 255, 0.78);
    box-shadow: var(--shadow-soft);
    position: sticky;
    bottom: 74px;
    z-index: 4;
    backdrop-filter: blur(18px);
  }

  .showcase-menu::-webkit-scrollbar {
    display: none;
  }

  .menu-title {
    display: none;
  }

  .menu-item {
    min-width: 148px;
  }

  .hero-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .mega-number {
    font-size: 44px;
  }
}

@keyframes metric-breathe {
  0%,
  100% {
    transform: translateY(0);
  }

  50% {
    transform: translateY(-2px);
  }
}
</style>