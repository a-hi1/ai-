<template>
  <section class="data-admin-page">
    <header class="hero">
      <div>
        <p class="eyebrow">Data Admin</p>
        <h1>后台数据管理</h1>
        <p class="copy">查看当前数据库状态，清空或重建示例数据，并为后续爬虫数据预留清理入口。</p>
      </div>
      <div class="hero-actions">
        <button class="ghost-btn" :disabled="loading" @click="loadOverview">{{ loading ? '刷新中...' : '刷新概览' }}</button>
        <RouterLink class="primary-link" to="/shop">去商城验证</RouterLink>
      </div>
    </header>

    <div v-if="notice" :class="['notice', noticeType]">{{ notice }}</div>

    <div v-if="!currentUser" class="panel empty-state">
      <h2>请先登录</h2>
      <p>登录后可查看和管理当前数据库中的示例数据与后续爬虫数据。</p>
      <RouterLink class="primary-link" to="/login">前往登录</RouterLink>
    </div>

    <template v-else>
      <section class="stats-grid" v-if="overview">
        <article class="stat-card"><span>商品总数</span><strong>{{ overview.stats.totalProducts }}</strong></article>
        <article class="stat-card accent"><span>示例商品</span><strong>{{ overview.stats.sampleProducts }}</strong></article>
        <article class="stat-card"><span>爬虫商品</span><strong>{{ overview.stats.crawlerProducts }}</strong></article>
        <article class="stat-card"><span>手动商品</span><strong>{{ overview.stats.manualProducts }}</strong></article>
        <article class="stat-card"><span>订单数</span><strong>{{ overview.stats.orderCount }}</strong></article>
        <article class="stat-card"><span>购物车项</span><strong>{{ overview.stats.cartItemCount }}</strong></article>
        <article class="stat-card"><span>聊天记录</span><strong>{{ overview.stats.chatMessageCount }}</strong></article>
        <article class="stat-card"><span>浏览记录</span><strong>{{ overview.stats.productViewCount }}</strong></article>
      </section>

      <section class="panel action-panel">
        <h2>数据操作</h2>
        <div class="action-grid">
          <button class="primary-action" :disabled="runningAction === 'rebuild'" @click="runRebuild">重建示例数据</button>
          <button class="soft-action" :disabled="runningAction === 'clear-sample'" @click="runClear('SAMPLE')">清空示例数据</button>
          <button class="soft-action" :disabled="runningAction === 'clear-crawler'" @click="runClear('CRAWLER')">清空爬虫数据</button>
          <button class="danger-action" :disabled="runningAction === 'clear-all'" @click="runClear('ALL')">清空全部业务数据</button>
        </div>
        <p class="hint">当前示例数据用于早期联调；后续你把爬虫入库时，只要商品 dataSource 标记为 CRAWLER，这里就能单独清空。</p>
      </section>

      <section class="panel table-panel" v-if="overview">
        <div class="panel-head">
          <h2>最近入库商品</h2>
          <span>{{ overview.recentProducts.length }} 条</span>
        </div>
        <div v-if="!overview.recentProducts.length" class="empty-table">当前数据库里还没有商品。</div>
        <div v-else class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>商品</th>
                <th>价格</th>
                <th>来源</th>
                <th>入库时间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="product in overview.recentProducts" :key="product.id">
                <td>{{ product.name }}</td>
                <td>{{ Number(product.price).toFixed(2) }} 元</td>
                <td><span class="source-pill" :class="product.dataSource.toLowerCase()">{{ product.dataSource }}</span></td>
                <td>{{ formatTime(product.createdAt) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </template>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'

import { useAuth } from '../composables/useAuth'
import { api, type DataAdminOverviewDto } from '../services/api'

const { currentUser } = useAuth()
const overview = ref<DataAdminOverviewDto | null>(null)
const loading = ref(false)
const runningAction = ref('')
const notice = ref('')
const noticeType = ref<'success' | 'error'>('success')

const formatTime = (value: string) => {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString('zh-CN')
}

const loadOverview = async () => {
  if (!currentUser.value) return
  loading.value = true
  try {
    overview.value = await api.getDataAdminOverview()
    notice.value = ''
  } catch {
    noticeType.value = 'error'
    notice.value = '数据概览加载失败，请确认后端服务和数据库仍在运行。'
  } finally {
    loading.value = false
  }
}

const runClear = async (scope: 'SAMPLE' | 'CRAWLER' | 'ALL') => {
  const label = scope === 'SAMPLE' ? '示例数据' : scope === 'CRAWLER' ? '爬虫数据' : '全部业务数据'
  if (!window.confirm(`确认清空${label}？此操作会直接影响当前数据库内容。`)) return

  runningAction.value = `clear-${scope.toLowerCase()}`
  try {
    const result = await api.clearAdminData(scope)
    overview.value = result.overview
    noticeType.value = 'success'
    notice.value = result.message
  } catch {
    noticeType.value = 'error'
    notice.value = `${label}清空失败，请稍后重试。`
  } finally {
    runningAction.value = ''
  }
}

const runRebuild = async () => {
  if (!window.confirm('确认重建示例数据？当前示例用户、订单、购物车、聊天记录和示例商品会被重建。')) return

  runningAction.value = 'rebuild'
  try {
    const result = await api.rebuildSampleData()
    overview.value = result.overview
    noticeType.value = 'success'
    notice.value = result.message
  } catch {
    noticeType.value = 'error'
    notice.value = '示例数据重建失败，请稍后重试。'
  } finally {
    runningAction.value = ''
  }
}

onMounted(() => {
  void loadOverview()
})
</script>

<style scoped>
.data-admin-page { max-width: 1180px; margin: 0 auto; display: grid; gap: 18px; }
.hero { display: flex; justify-content: space-between; gap: 18px; align-items: center; padding: 26px; border-radius: 26px; background: linear-gradient(135deg, rgba(255,255,255,0.58) 0%, rgba(255,255,255,0.22) 100%), radial-gradient(circle at top right, rgba(255,77,77,0.12), transparent 28%), radial-gradient(circle at bottom left, rgba(0,196,180,0.12), transparent 24%); color: var(--text-main); border: 1px solid rgba(255,255,255,.76); box-shadow: var(--shadow-lg); backdrop-filter: blur(14px); }
.eyebrow { margin: 0 0 8px; font-size: 12px; letter-spacing: .18em; text-transform: uppercase; color: var(--brand-deep); }
.hero h1 { margin: 0 0 8px; }
.copy { margin: 0; color: var(--text-soft); max-width: 720px; }
.hero-actions { display: flex; gap: 10px; align-items: center; }
.primary-link, .ghost-btn { padding: 10px 16px; border-radius: 999px; text-decoration: none; cursor: pointer; }
.primary-link { background: linear-gradient(135deg, rgba(255,77,77,0.96) 0%, rgba(255,111,105,0.88) 100%); color: #fff; }
.ghost-btn { border: 1px solid rgba(255,255,255,.72); background: rgba(255,255,255,.74); color: var(--text-main); }
.panel { padding: 22px; border-radius: 22px; background: rgba(255,255,255,.88); border: 1px solid rgba(255,255,255,.78); box-shadow: var(--shadow-lg); backdrop-filter: blur(14px); }
.empty-state { text-align: center; }
.notice { padding: 14px 16px; border-radius: 18px; }
.notice.success { background: #e5f6ef; color: #106b53; }
.notice.error { background: #fff1ec; color: #a53f2d; }
.stats-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.stat-card { display: grid; gap: 8px; padding: 18px; border-radius: 20px; background: rgba(255,255,255,.88); border: 1px solid rgba(255,255,255,.78); box-shadow: var(--shadow-soft); }
.stat-card.accent { background: linear-gradient(135deg, rgba(255,77,77,.1) 0%, rgba(255,255,255,.94) 100%); }
.stat-card span { color: #7a6a58; font-size: 13px; }
.stat-card strong { font-size: 28px; }
.action-panel { display: grid; gap: 14px; }
.action-panel h2, .table-panel h2 { margin: 0; }
.action-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; }
.primary-action, .soft-action, .danger-action { border: none; border-radius: 18px; padding: 14px 16px; cursor: pointer; font-weight: 600; }
.primary-action { background: linear-gradient(135deg, rgba(255,77,77,0.96) 0%, rgba(255,111,105,0.88) 100%); color: #fff; }
.soft-action { background: #f3ede5; color: #5e5348; }
.danger-action { background: #a53f2d; color: #fff; }
.primary-action:disabled, .soft-action:disabled, .danger-action:disabled { opacity: .7; cursor: not-allowed; }
.hint { margin: 0; color: #74695d; font-size: 13px; }
.panel-head { display: flex; justify-content: space-between; gap: 12px; align-items: center; margin-bottom: 12px; }
.table-wrap { overflow: auto; }
table { width: 100%; border-collapse: collapse; }
th, td { text-align: left; padding: 12px 10px; border-bottom: 1px solid #eee3d5; }
th { color: #7a6a58; font-size: 12px; text-transform: uppercase; letter-spacing: .08em; }
.source-pill { display: inline-block; padding: 4px 10px; border-radius: 999px; background: #f3ede5; color: #5e5348; font-size: 12px; }
.source-pill.sample { background: #fff4df; color: #8a5a24; }
.source-pill.crawler { background: #e9f5ff; color: #1c5d8b; }
.source-pill.manual { background: #edf7f0; color: #1d6d4f; }
.empty-table { color: #76695f; }
@media (max-width: 980px) {
  .hero { flex-direction: column; align-items: flex-start; }
  .stats-grid, .action-grid { grid-template-columns: 1fr 1fr; }
}
@media (max-width: 720px) {
  .stats-grid, .action-grid { grid-template-columns: 1fr; }
}
</style>