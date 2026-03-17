<template>
  <section class="callback-page">
    <div class="panel" :class="statusClass">
      <p class="eyebrow">支付回调</p>
      <h1>{{ title }}</h1>
      <p>{{ description }}</p>
      <div class="actions">
        <RouterLink class="primary-link" v-if="orderId" :to="`/orders/${orderId}`">查看订单详情</RouterLink>
        <RouterLink class="secondary-link" to="/orders">返回订单列表</RouterLink>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'

import { api } from '../services/api'

const route = useRoute()
const orderId = ref('')
const title = ref('正在处理支付结果...')
const description = ref('请稍候，系统正在同步支付状态。')
let timer: number | undefined
const statusClass = computed(() => {
  if (title.value.includes('成功')) return 'success'
  if (title.value.includes('失败')) return 'error'
  return 'pending'
})

const clearTimer = () => {
  if (timer) {
    window.clearInterval(timer)
    timer = undefined
  }
}

const startPolling = () => {
  clearTimer()
  timer = window.setInterval(async () => {
    if (!orderId.value) return
    try {
      const result = await api.queryPayment(orderId.value)
      if (result === 'PAID') {
        clearTimer()
        title.value = '支付成功'
        description.value = '订单状态已经同步为已支付，可以继续查看订单详情。'
      }
    } catch {
      // ignore transient polling errors
    }
  }, 2500)
}

onMounted(async () => {
  orderId.value = String(route.query.orderId || route.query.out_trade_no || '')
  const status = String(route.query.status || 'PAID')
  const tradeNo = String(route.query.tradeNo || route.query.trade_no || '')
  const sign = String(route.query.sign || '')
  const pending = String(route.query.pending || '')

  if (!orderId.value) {
    title.value = '支付回调失败'
    description.value = '缺少订单编号，无法确认支付结果。'
    return
  }

  try {
    if (sign) {
      const payload = Object.fromEntries(
        Object.entries(route.query)
          .map(([key, value]) => [key, Array.isArray(value) ? String(value[0] || '') : String(value || '')])
      )
      const result = await api.verifyAlipayReturn(payload)
      if (result !== 'OK') {
        throw new Error(result)
      }
    } else if (pending) {
      const result = await api.queryPayment(orderId.value)
      if (result === 'PAID') {
        title.value = '支付成功'
        description.value = '订单状态已经更新为已支付，可以继续查看订单详情。'
        return
      }

      title.value = '等待支付完成'
      description.value = '如果你正在手机上扫码支付，当前页面会自动轮询同步支付结果。'
      startPolling()
      return
    } else {
      await api.notifyPayment(orderId.value, status, tradeNo || undefined)
    }
    title.value = '支付成功'
    description.value = '订单状态已经更新为已支付，可以继续查看订单详情。'
  } catch {
    title.value = '支付结果同步失败'
    description.value = '支付可能已经完成，但系统未能同步状态，请稍后重试。'
  }
})

onBeforeUnmount(() => {
  clearTimer()
})
</script>

<style scoped>
.callback-page { min-height: 60vh; display: grid; place-items: center; }
.panel { max-width: 620px; padding: 30px; border-radius: 28px; border: 1px solid rgba(255,255,255,.78); background: rgba(255,255,255,.92); text-align: center; box-shadow: var(--shadow-lg); backdrop-filter: blur(14px); }
.panel.success { background: linear-gradient(135deg, rgba(223,247,243,.92) 0%, rgba(255,255,255,.96) 100%); }
.panel.error { background: linear-gradient(135deg, rgba(255,240,239,.96) 0%, rgba(255,255,255,.96) 100%); }
.panel.pending { background: linear-gradient(135deg, rgba(255,248,239,.96) 0%, rgba(255,255,255,.96) 100%); }
.eyebrow { margin: 0 0 8px; font-size: 12px; letter-spacing: .16em; text-transform: uppercase; color: var(--brand-deep); }
h1 { margin: 0 0 10px; }
p { color: #5f5a55; }
.actions { display: flex; justify-content: center; gap: 12px; margin-top: 18px; }
.primary-link, .secondary-link { padding: 10px 16px; border-radius: 999px; text-decoration: none; }
.primary-link { background: linear-gradient(135deg, rgba(255,77,77,0.96) 0%, rgba(255,111,105,0.88) 100%); color: #fff; }
.secondary-link { background: #f1ece3; color: #5f5246; }
</style>
