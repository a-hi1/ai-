<template>
  <section class="cart-page">
    <header class="cart-head">
      <div>
        <p class="eyebrow">Checkout</p>
        <h1>购物车</h1>
        <p class="copy">保留必要信息，确认数量后直接结算。</p>
      </div>
      <div class="head-metrics">
        <article class="metric-card">
          <span>件数</span>
          <strong>{{ itemCount }}</strong>
        </article>
        <article class="metric-card accent">
          <span>总额</span>
          <strong>{{ formatCurrency(Number(totalPrice)) }}</strong>
        </article>
      </div>
    </header>

    <div v-if="message" :class="['notice', messageType]">{{ message }}</div>

    <div class="cart-layout">
      <section class="cart-panel">
        <header class="cart-header">
          <div>
            <h2>已选商品</h2>
            <p>{{ cartItems.length ? '支持直接点开商品详情继续查看信息。' : '还没有加入商品。' }}</p>
          </div>
          <button class="clear" :disabled="!cartItems.length" @click="clearCurrentCart">清空</button>
        </header>

        <div v-if="errorMessage" class="error-inline">{{ errorMessage }}</div>

        <div v-if="!cartItems.length" class="empty-state">
          <strong>购物车还是空的</strong>
          <p>可以先去商城直接挑商品，或者去导购页按需求缩小范围。</p>
          <div class="empty-actions">
            <RouterLink class="ghost-link" to="/shop">去商城</RouterLink>
            <RouterLink class="ghost-link" to="/chat">去导购</RouterLink>
          </div>
        </div>

        <div v-else class="cart-list">
          <article v-for="item in cartItems" :key="item.id" class="cart-item">
            <button class="item-preview" @click="openProduct(item)">
              <img :src="item.image" :alt="item.name" loading="lazy" decoding="async" />
              <div class="info">
                <div class="name">{{ item.name }}</div>
                <div class="desc">{{ item.desc || '查看商品详情与购买信息' }}</div>
                <div class="price">{{ formatCurrency(item.price) }}</div>
              </div>
            </button>

            <div class="item-actions">
              <div class="quantity-row">
                <button class="qty-btn" @click="changeQuantity(item.id, item.quantity - 1)">-</button>
                <span>{{ item.quantity }}</span>
                <button class="qty-btn" @click="changeQuantity(item.id, item.quantity + 1)">+</button>
              </div>
              <strong class="line-total">{{ formatCurrency(item.price * item.quantity) }}</strong>
              <button class="remove" @click="removeCurrentItem(item.id)">删除</button>
            </div>
          </article>
        </div>
      </section>

      <aside class="checkout-panel">
        <h3>支付摘要</h3>
        <div class="checkout-row">
          <span>商品数量</span>
          <strong>{{ itemCount }} 件</strong>
        </div>
        <div class="checkout-row">
          <span>支付方式</span>
          <strong>{{ paymentMode === 'alipay' ? '支付宝沙箱' : '站内快速演示' }}</strong>
        </div>
        <div class="payment-mode-group">
          <button class="payment-mode" :class="{ active: paymentMode === 'demo' }" type="button" @click="paymentMode = 'demo'">
            <strong>快速演示</strong>
            <span>本地立即回调，适合联调订单流转。</span>
          </button>
          <button class="payment-mode" :class="{ active: paymentMode === 'alipay' }" type="button" @click="paymentMode = 'alipay'">
            <strong>支付宝沙箱</strong>
            <span>跳转官方沙箱收银台，验证真实支付回跳。</span>
          </button>
        </div>
        <p class="payment-hint">{{ paymentMode === 'alipay' ? '将按本次下单强制请求支付宝沙箱支付。如果沙箱配置未完成，页面会直接提示原因。' : '本地环境默认优先走站内快速支付，避免支付宝沙箱响应慢导致无法完成下单验证。' }}</p>
        <div class="checkout-row total">
          <span>应付金额</span>
          <strong>{{ formatCurrency(Number(totalPrice)) }}</strong>
        </div>
        <button class="pay" :disabled="paying || !cartItems.length" @click="payNow">
          {{ paying ? '正在跳转...' : '立即支付' }}
        </button>
        <RouterLink class="back-link" to="/shop">继续逛商城</RouterLink>
        <RouterLink class="back-link muted" to="/chat">回导购页补充挑选</RouterLink>
      </aside>
    </div>
  </section>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

import { useAuth } from '../composables/useAuth'
import { useCart, type CartItem } from '../composables/useCart'
import { api } from '../services/api'

const { cartItems, clearCart, removeItem, updateQuantity, totalPrice, itemCount, refreshCart, errorMessage } = useCart()
const { currentUser } = useAuth()
const router = useRouter()
const paying = ref(false)
const message = ref('')
const messageType = ref<'success' | 'error'>('success')
const paymentMode = ref<'demo' | 'alipay'>('demo')

const formatCurrency = (price: number) => new Intl.NumberFormat('zh-CN', {
  style: 'currency',
  currency: 'CNY',
  maximumFractionDigits: 0
}).format(price)

const openProduct = (item: CartItem) => {
  try {
    window.sessionStorage.setItem(`product-preview:${item.id}`, JSON.stringify({
      id: item.id,
      name: item.name,
      description: item.desc,
      price: item.price,
      imageUrl: item.image,
      tags: ''
    }))
  } catch {
    // ignore session storage failures
  }

  router.push(`/products/${item.id}`)
}

const changeQuantity = async (productId: string, quantity: number) => {
  try {
    await updateQuantity(productId, quantity)
  } catch {
    messageType.value = 'error'
    message.value = '更新商品数量失败。'
  }
}

const removeCurrentItem = async (productId: string) => {
  try {
    await removeItem(productId)
  } catch {
    messageType.value = 'error'
    message.value = '删除购物车商品失败。'
  }
}

const clearCurrentCart = async () => {
  try {
    await clearCart()
    messageType.value = 'success'
    message.value = '购物车已清空。'
  } catch {
    messageType.value = 'error'
    message.value = '清空购物车失败。'
  }
}

const payNow = async () => {
  if (!currentUser.value) {
    router.push('/login')
    return
  }
  if (!cartItems.value.length || paying.value) return

  paying.value = true
  message.value = ''
  try {
    const order = await api.createOrder(currentUser.value.id, totalPrice.value)
    const paymentSession = await api.createPayment(order.id, paymentMode.value)

    if (paymentSession.mode === 'demo') {
      await refreshCart()
      messageType.value = 'success'
      message.value = '已使用站内快速演示支付，无需等待支付宝沙箱响应。'
      router.push(`/payment/callback?orderId=${encodeURIComponent(order.id)}&status=PAID&tradeNo=${encodeURIComponent(paymentSession.gatewayTradeNo)}`)
      return
    }

    const paymentWindow = window.open(paymentSession.paymentUrl, '_blank')

    if (paymentWindow) {
      paymentWindow.focus()
    } else {
      window.location.href = paymentSession.paymentUrl
      return
    }

    await refreshCart()
    router.push(`/payment/callback?orderId=${encodeURIComponent(order.id)}&pending=1`)
    messageType.value = 'success'
    message.value = `订单 ${order.id} 已创建，支付窗口已打开。扫码支付后，当前页面会自动同步订单状态。`
  } catch (error) {
    messageType.value = 'error'
    const errorText = error instanceof Error ? error.message : ''
    message.value = errorText.includes('支付宝沙箱未配置完成')
      ? '当前环境还没有完成支付宝沙箱配置，请先补全后端 appId、私钥和公钥。'
      : '支付流程暂时不可用，请确认后端服务仍在运行。'
  } finally {
    paying.value = false
  }
}
</script>

<style scoped>
.cart-page {
  max-width: 1160px;
  margin: 0 auto;
  display: grid;
  gap: 18px;
}

.cart-head {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 16px;
  padding: 20px 22px;
  border-radius: 22px;
  background:
    linear-gradient(135deg, rgba(255,255,255,0.62) 0%, rgba(255,255,255,0.28) 100%),
    radial-gradient(circle at top right, rgba(255,77,77,0.12), transparent 32%),
    radial-gradient(circle at bottom left, rgba(0,196,180,0.1), transparent 26%);
  border: 1px solid rgba(255,255,255,0.76);
  box-shadow: var(--shadow-lg);
  backdrop-filter: blur(14px);
}

.eyebrow {
  margin: 0 0 8px;
  font-size: 12px;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: var(--brand-deep);
}

h1 {
  margin: 0 0 8px;
  font-size: 30px;
}

.copy {
  margin: 0;
  color: #6f655a;
}

.head-metrics {
  display: grid;
  grid-template-columns: repeat(2, minmax(120px, 1fr));
  gap: 10px;
}

.metric-card {
  display: grid;
  gap: 6px;
  align-content: center;
  padding: 14px 16px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.76);
  border: 1px solid rgba(255, 255, 255, 0.8);
  box-shadow: var(--shadow-soft);
}

.metric-card span {
  font-size: 12px;
  color: var(--text-faint);
}

.metric-card strong {
  font-size: 22px;
}

.metric-card.accent {
  background: rgba(255, 255, 255, 0.9);
}

.notice {
  padding: 14px 16px;
  border-radius: 18px;
}

.notice.success {
  background: #e5f6ef;
  color: #106b53;
}

.notice.error {
  background: #fff1ec;
  color: #a53f2d;
}

.cart-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) minmax(280px, 0.75fr);
  gap: 18px;
}

.cart-panel,
.checkout-panel {
  background: rgba(255, 255, 255, 0.86);
  border-radius: 20px;
  padding: 20px;
  border: 1px solid rgba(255,255,255,0.78);
  box-shadow: var(--shadow-lg);
  backdrop-filter: blur(14px);
}

.cart-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 16px;
}

.cart-header h2,
.checkout-panel h3 {
  margin: 0;
}

.cart-header p {
  margin: 6px 0 0;
  color: #6f655a;
  font-size: 13px;
}

.clear {
  border: 1px solid #c9b7a1;
  background: transparent;
  padding: 6px 14px;
  border-radius: 20px;
  cursor: pointer;
}

.clear:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.empty-state {
  display: grid;
  gap: 10px;
  justify-items: center;
  padding: 42px 16px;
  text-align: center;
  color: #6f655a;
  border-radius: 16px;
  background: #fffcf8;
  border: 1px dashed #e6dccf;
}

.empty-state p {
  margin: 0;
  max-width: 420px;
}

.empty-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.cart-list {
  display: grid;
  gap: 12px;
}

.cart-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 14px;
  align-items: stretch;
  padding: 14px;
  border-radius: 16px;
  background: rgba(255,255,255,0.94);
  border: 1px solid rgba(255,255,255,0.82);
  box-shadow: var(--shadow-soft);
}

.item-preview {
  display: grid;
  grid-template-columns: 88px minmax(0, 1fr);
  gap: 14px;
  align-items: center;
  padding: 0;
  border: none;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.cart-item img {
  width: 80px;
  height: 80px;
  border-radius: 12px;
  object-fit: cover;
}

.info .name {
  font-weight: 700;
  margin-bottom: 4px;
}

.info .desc {
  font-size: 13px;
  color: #6f655a;
  margin-bottom: 6px;
}

.item-actions {
  display: grid;
  justify-items: end;
  align-content: center;
  gap: 10px;
}

.quantity-row {
  display: inline-flex;
  align-items: center;
  gap: 10px;
}

.qty-btn {
  width: 26px;
  height: 26px;
  border: none;
  border-radius: 50%;
  background: #f3efe8;
  cursor: pointer;
}

.price {
  color: #9a4c1a;
  font-weight: 600;
}

.line-total {
  color: #2c2a28;
}

.error-inline {
  margin-bottom: 12px;
  padding: 10px 12px;
  border-radius: 14px;
  background: #fff1ec;
  color: #a53f2d;
}

.remove {
  background: #fbe7d1;
  border: none;
  padding: 8px 16px;
  border-radius: 18px;
  cursor: pointer;
}

.checkout-panel {
  display: grid;
  gap: 16px;
  align-content: start;
  position: sticky;
  top: 84px;
}

.checkout-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  color: #6f655a;
}

.payment-mode-group {
  display: grid;
  gap: 10px;
}

.payment-mode {
  display: grid;
  gap: 4px;
  padding: 14px 16px;
  border-radius: 16px;
  border: 1px solid #e2d6c7;
  background: #fff9f2;
  text-align: left;
  cursor: pointer;
  color: #4b4239;
}

.payment-mode strong {
  font-size: 15px;
}

.payment-mode span {
  font-size: 12px;
  color: #7b6956;
  line-height: 1.5;
}

.payment-mode.active {
  border-color: #1f6f5c;
  background: #edf7f2;
  box-shadow: inset 0 0 0 1px rgba(31, 111, 92, 0.12);
}

.payment-hint {
  margin: -4px 0 0;
  color: #7b6956;
  font-size: 13px;
  line-height: 1.55;
}

.checkout-row.total {
  padding-top: 12px;
  border-top: 1px solid #eadfce;
  color: #2c2a28;
}

.pay {
  width: 100%;
  background: linear-gradient(135deg, rgba(255,77,77,0.96) 0%, rgba(255,111,105,0.88) 100%);
  color: #fff;
  border: none;
  padding: 10px 22px;
  border-radius: 24px;
  cursor: pointer;
}

.pay:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.back-link {
  text-align: center;
  color: #9a4c1a;
  text-decoration: none;
}

.back-link.muted {
  color: #6f655a;
}

.ghost-link {
  min-height: 40px;
  padding: 0 14px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  border: 1px solid #d7cab9;
  color: #6f5842;
  text-decoration: none;
  background: #fff;
}

@media (max-width: 960px) {
  .cart-head,
  .cart-layout {
    grid-template-columns: 1fr;
  }

  .checkout-panel {
    position: static;
  }
}

@media (max-width: 720px) {
  h1 {
    font-size: 24px;
  }

  .cart-head,
  .cart-panel,
  .checkout-panel {
    padding: 16px;
  }

  .cart-header,
  .cart-item {
    grid-template-columns: 1fr;
  }

  .item-preview {
    grid-template-columns: 72px minmax(0, 1fr);
  }

  .cart-item img {
    width: 72px;
    height: 72px;
  }

  .item-actions {
    justify-items: stretch;
  }

  .quantity-row {
    justify-content: center;
  }
}
</style>
