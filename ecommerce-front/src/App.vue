<template>
  <div class="app">
    <header class="topbar-wrap" :class="{ compact: isCompactHeader }">
      <div class="topbar">
        <RouterLink to="/" class="brand-link">
          <div class="brand">
            <span class="brand-mark">
              <span class="dot"></span>
            </span>
            <div class="brand-copy">
              <strong>智选</strong>
              <small>会聊天的生活商城</small>
            </div>
          </div>
        </RouterLink>

        <nav class="nav">
          <RouterLink v-for="item in topNavItems" :key="item.to" :to="item.to" :class="['nav-link', { 'with-badge': item.showBadge }]">
            {{ item.label }}
            <span v-if="item.showBadge" class="badge">{{ item.badge }}</span>
          </RouterLink>
        </nav>

        <div class="account-panel">
          <div v-if="currentUser" ref="accountMenuRef" class="avatar-menu-wrap">
            <button class="avatar-trigger" type="button" @click="toggleAccountMenu">
              <span class="account-avatar">{{ accountInitial }}</span>
            </button>
            <div v-if="isAccountMenuOpen" class="account-dropdown">
              <RouterLink class="account-menu-link" to="/account" @click="closeAccountMenu">进入个人空间</RouterLink>
              <button class="account-menu-link logout-link" type="button" @click="handleLogout">退出登录</button>
            </div>
          </div>
          <RouterLink v-else class="login-link compact-login" to="/login">登录</RouterLink>
        </div>
      </div>
    </header>

    <main class="content">
      <RouterView />
    </main>

    <aside
      v-if="showFloatingGuide"
      ref="floatingGuideRef"
      class="floating-guide"
      :class="{ open: isFloatingGuideOpen }"
      @mouseenter="handleFloatingGuideMouseEnter"
    >
      <div class="floating-guide-minimized">
        <button class="floating-guide-ticker" type="button" aria-live="polite" @click="toggleFloatingGuidePanel">
          <div class="floating-guide-ticker-copy">
            <strong>AI 导购</strong>
            <span>输入需求，直接发送</span>
          </div>
        </button>

        <button
          class="floating-guide-trigger"
          type="button"
          @click="goToChat()"
          aria-label="进入 AI 导购页面"
        >
          <span class="floating-guide-orbit"></span>
          <span class="floating-guide-dot"></span>
          <span class="floating-guide-ping"></span>
          <span class="floating-guide-badge">AI</span>
        </button>
      </div>

      <div v-if="isFloatingGuideOpen" class="floating-guide-panel">
        <div class="floating-guide-head">
          <div>
            <strong>AI 导购聊天框</strong>
            <small class="floating-guide-subline">输入你的需求并发送，立即进入导购对话。</small>
          </div>
          <button class="floating-guide-close" type="button" @click="closeFloatingGuide" aria-label="关闭悬浮导购">×</button>
        </div>

        <form class="floating-guide-form" @submit.prevent="submitFloatingGuide">
          <input
            ref="floatingGuideInputRef"
            v-model="floatingGuidePrompt"
            class="floating-guide-input"
            type="text"
            placeholder="例如：预算 1500，通勤和视频会议用的降噪耳机"
          />
          <div class="floating-guide-actions">
            <button class="floating-guide-submit" type="submit">发送并进入导购</button>
          </div>
        </form>
      </div>
    </aside>

    <nav class="mobile-bottom-nav" aria-label="移动端导航">
      <RouterLink v-for="item in mobileNavItems" :key="item.to" :to="item.to" :class="['mobile-nav-link', { 'with-badge': item.showBadge }]">
        <span>{{ item.label }}</span>
        <span v-if="item.showBadge" class="mobile-badge">{{ item.badge }}</span>
      </RouterLink>
    </nav>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useCart } from './composables/useCart'
import { useAuth } from './composables/useAuth'

const { itemCount } = useCart()
const { currentUser, logout, syncCurrentUserPresence } = useAuth()
const route = useRoute()
const router = useRouter()
const isCompactHeader = ref(false)
const isAccountMenuOpen = ref(false)
const isHoverCapable = ref(false)
const accountMenuRef = ref<HTMLElement | null>(null)
const floatingGuideRef = ref<HTMLElement | null>(null)
const floatingGuideInputRef = ref<HTMLInputElement | null>(null)
const isFloatingGuideOpen = ref(false)
const floatingGuidePrompt = ref('')
let headerRaf = 0
let presenceHeartbeatTimer: number | null = null

const stopPresenceHeartbeat = () => {
  if (presenceHeartbeatTimer !== null) {
    window.clearInterval(presenceHeartbeatTimer)
    presenceHeartbeatTimer = null
  }
}

const startPresenceHeartbeat = () => {
  stopPresenceHeartbeat()
  presenceHeartbeatTimer = window.setInterval(() => {
    if (!currentUser.value?.email) {
      return
    }
    void syncCurrentUserPresence(true)
  }, 30000)
}

const showFloatingGuide = computed(() => route.path !== '/chat')

const accountInitial = computed(() => {
  const source = currentUser.value?.displayName || currentUser.value?.email || '智'
  return source.slice(0, 1).toUpperCase()
})

const topNavItems = computed(() => {
  const items = [
    { to: '/', label: '首页' },
    { to: '/shop', label: '商城' },
    { to: '/cart', label: '购物车', showBadge: true, badge: itemCount.value }
  ]

  if (currentUser.value) {
    return [
      ...items,
      { to: '/data-admin', label: '数据' },
      { to: '/history', label: '会话' },
      { to: '/orders', label: '订单' }
    ]
  }

  return [
    ...items,
    { to: '/login', label: '登录' },
    { to: '/register', label: '注册' }
  ]
})

const mobileNavItems = computed(() => {
  const items = [
    { to: '/', label: '首页' },
    { to: '/shop', label: '商城' },
    { to: '/cart', label: '购物车', showBadge: true, badge: itemCount.value }
  ]

  return currentUser.value
    ? [...items, { to: '/data-admin', label: '数据' }]
    : [...items, { to: '/login', label: '登录' }]
})

const syncHeaderState = () => {
  const nextCompact = window.scrollY > 64
  if (nextCompact !== isCompactHeader.value) {
    isCompactHeader.value = nextCompact
  }
}

const scheduleHeaderState = () => {
  if (headerRaf) {
    return
  }

  headerRaf = window.requestAnimationFrame(() => {
    headerRaf = 0
    syncHeaderState()
  })
}

const closeAccountMenu = () => {
  isAccountMenuOpen.value = false
}

const closeFloatingGuide = () => {
  isFloatingGuideOpen.value = false
}

const openFloatingGuidePanel = () => {
  if (isFloatingGuideOpen.value) {
    return
  }

  isFloatingGuideOpen.value = true
  void nextTick(() => {
    floatingGuideInputRef.value?.focus()
  })
}

const toggleAccountMenu = () => {
  isAccountMenuOpen.value = !isAccountMenuOpen.value
}

const toggleFloatingGuidePanel = () => {
  if (isFloatingGuideOpen.value) {
    closeFloatingGuide()
    return
  }

  openFloatingGuidePanel()
}

const goToChat = (prompt = '') => {
  const trimmedPrompt = prompt.trim()
  router.push({
    path: '/chat',
    query: trimmedPrompt ? { prompt: trimmedPrompt, autoSend: '1' } : {}
  })
  closeFloatingGuide()
}

const submitFloatingGuide = () => {
  goToChat(floatingGuidePrompt.value)
}

const handleLogout = () => {
  closeAccountMenu()
  logout()
}

const handleFloatingGuideMouseEnter = () => {
  if (!isHoverCapable.value) {
    return
  }

  openFloatingGuidePanel()
}

const handleDocumentClick = (event: MouseEvent) => {
  const target = event.target
  if (accountMenuRef.value && target instanceof Node && !accountMenuRef.value.contains(target)) {
    closeAccountMenu()
  }

  if (floatingGuideRef.value && target instanceof Node && !floatingGuideRef.value.contains(target)) {
    closeFloatingGuide()
  }
}

watch(() => route.fullPath, () => {
  closeFloatingGuide()
  closeAccountMenu()
})

watch(
  () => currentUser.value?.email,
  async (email) => {
    if (!email) {
      stopPresenceHeartbeat()
      return
    }
    await syncCurrentUserPresence(true)
    startPresenceHeartbeat()
  },
  { immediate: true }
)

onMounted(() => {
  isHoverCapable.value = window.matchMedia('(hover: hover) and (pointer: fine)').matches
  syncHeaderState()
  window.addEventListener('scroll', scheduleHeaderState, { passive: true })
  document.addEventListener('click', handleDocumentClick)
  void syncCurrentUserPresence(true)
  if (currentUser.value?.email) {
    startPresenceHeartbeat()
  }
})

onBeforeUnmount(() => {
  stopPresenceHeartbeat()
  window.removeEventListener('scroll', scheduleHeaderState)
  document.removeEventListener('click', handleDocumentClick)
  if (headerRaf) {
    window.cancelAnimationFrame(headerRaf)
  }
})
</script>

<style scoped>
.app {
  min-height: 100vh;
  background:
    radial-gradient(circle at top left, rgba(255, 77, 77, 0.14), transparent 28%),
    radial-gradient(circle at top right, rgba(0, 196, 180, 0.12), transparent 24%),
    linear-gradient(180deg, #fffcf8 0%, #faf7f2 38%, #f4efe8 100%);
  color: var(--text-main);
}

.topbar,
.content {
  width: min(calc(100% - 32px), var(--page-max));
  margin: 0 auto;
}

.topbar-wrap {
  position: sticky;
  top: 0;
  z-index: 20;
  backdrop-filter: blur(14px) saturate(135%);
  transition: transform 220ms ease, opacity 220ms ease;
}

.topbar {
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: 16px;
  align-items: center;
  padding: 16px 0;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.48) 0%, rgba(255, 255, 255, 0.22) 100%);
  border-bottom: 1px solid rgba(47, 38, 38, 0.08);
  box-shadow: 0 8px 24px rgba(120, 105, 101, 0.05);
  transition: padding 220ms ease, gap 220ms ease, box-shadow 220ms ease;
}

.topbar-wrap.compact .topbar {
  gap: 12px;
  padding: 10px 0;
  box-shadow: 0 10px 28px rgba(120, 105, 101, 0.1);
}

.brand-link {
  text-decoration: none;
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  transition: font-size 220ms ease, gap 220ms ease;
}

.brand-mark {
  position: relative;
  width: 42px;
  height: 42px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 16px;
  background: linear-gradient(135deg, rgba(255, 77, 77, 0.96) 0%, rgba(255, 77, 77, 0.78) 52%, rgba(0, 196, 180, 0.74) 100%);
  box-shadow: 0 14px 24px rgba(255, 77, 77, 0.18);
}

.brand-mark::after {
  content: '';
  position: absolute;
  inset: -7px;
  border-radius: 20px;
  border: 1px solid rgba(255, 77, 77, 0.18);
  opacity: 0.52;
}

.dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 0 0 6px rgba(255, 255, 255, 0.18);
}

.brand-copy {
  display: grid;
  gap: 2px;
}

.brand-copy strong {
  font-size: 28px;
  line-height: 1;
  letter-spacing: 0.08em;
}

.brand-copy small {
  color: var(--text-faint);
  font-size: 12px;
  letter-spacing: 0.22em;
  text-transform: uppercase;
}

.topbar-wrap.compact .brand-copy strong {
  font-size: 23px;
}

.nav {
  display: flex;
  justify-content: center;
  flex-wrap: wrap;
  gap: 18px;
  font-size: 14px;
  transition: gap 220ms ease, font-size 220ms ease;
}

.topbar-wrap.compact .nav {
  gap: 10px;
  font-size: 13px;
}

.nav-link {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  position: relative;
  color: #4e4340;
  text-decoration: none;
  padding: 10px 4px;
  border-radius: 0;
  font-weight: 600;
  border-bottom: 1px solid transparent;
  transition: padding 220ms ease, gap 220ms ease, color 220ms ease, border-color 220ms ease;
}

.nav-link::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: 1px;
  height: 1px;
  background: linear-gradient(90deg, transparent 0%, rgba(255, 77, 77, 0.9) 50%, transparent 100%);
  transform: scaleX(0);
  transform-origin: center;
  transition: transform 220ms ease;
}

.nav-link:hover,
.nav-link.router-link-active {
  color: var(--brand-deep);
}

.nav-link:hover::after,
.nav-link.router-link-active::after {
  transform: scaleX(1);
}

.topbar-wrap.compact .nav-link {
  gap: 6px;
  padding: 7px 4px;
}

.badge {
  position: absolute;
  top: 2px;
  right: -10px;
  min-width: 18px;
  height: 18px;
  display: inline-grid;
  place-items: center;
  padding: 0 5px;
  border-radius: 999px;
  background: linear-gradient(135deg, #ff4d4d 0%, #ff7b72 100%);
  color: #fff;
  box-shadow: 0 10px 18px rgba(255, 77, 77, 0.22);
  font-size: 11px;
}

.account-panel {
  display: flex;
  justify-content: flex-end;
  position: relative;
}

.avatar-menu-wrap {
  position: relative;
}

.avatar-trigger {
  border: 1px solid rgba(255, 77, 77, 0.12);
  background: rgba(255, 255, 255, 0.48);
  padding: 4px;
  border-radius: 20px;
  cursor: pointer;
  backdrop-filter: blur(14px);
}

.account-avatar {
  width: 42px;
  height: 42px;
  border-radius: 16px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, rgba(255, 77, 77, 0.95) 0%, rgba(0, 196, 180, 0.9) 100%);
  color: #fff;
  font-weight: 800;
  box-shadow: 0 14px 24px rgba(255, 77, 77, 0.18);
}

.account-dropdown {
  position: absolute;
  top: calc(100% + 10px);
  right: 0;
  min-width: 136px;
  display: grid;
  gap: 6px;
  padding: 8px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(255, 255, 255, 0.6);
  box-shadow: var(--shadow-md);
  backdrop-filter: blur(20px);
}

.login-link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 8px 12px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.58);
  color: var(--brand-deep);
  font-size: 12px;
  text-decoration: none;
  border: 1px solid rgba(255, 77, 77, 0.12);
}

.compact-login {
  min-height: 40px;
}

.account-menu-link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 36px;
  padding: 0 12px;
  border-radius: 14px;
  color: var(--text-main);
  text-decoration: none;
  background: #fffdfb;
  border: 1px solid rgba(255, 77, 77, 0.08);
  cursor: pointer;
}

.logout-link {
  border: none;
  color: #b45309;
}

.content {
  padding: 20px 0 80px;
}

.floating-guide {
  position: fixed;
  right: 18px;
  bottom: 104px;
  z-index: 35;
  display: grid;
  justify-items: end;
  gap: 10px;
}

.floating-guide-minimized {
  display: flex;
  align-items: flex-end;
  gap: 10px;
}

.floating-guide-trigger,
.floating-guide-panel {
  border: 1px solid rgba(255, 255, 255, 0.78);
  background: rgba(255, 255, 255, 0.84);
  box-shadow: var(--shadow-lg);
  backdrop-filter: blur(12px);
}

.floating-guide-trigger {
  position: relative;
  width: 72px;
  height: 72px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  border-radius: 50%;
  cursor: pointer;
}

.floating-guide-orbit {
  position: absolute;
  inset: -7px;
  border-radius: 50%;
  border: 1px solid rgba(255, 77, 77, 0.18);
}

.floating-guide-dot {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: linear-gradient(135deg, rgba(255, 77, 77, 0.98) 0%, rgba(0, 196, 180, 0.86) 100%);
  box-shadow: 0 0 0 14px rgba(255, 77, 77, 0.08);
}

.floating-guide-ping {
  position: absolute;
  inset: 10px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(255, 255, 255, 0.12) 0%, transparent 62%);
}

.floating-guide-badge {
  position: absolute;
  right: -4px;
  top: -4px;
  min-width: 24px;
  height: 24px;
  display: inline-grid;
  place-items: center;
  padding: 0 6px;
  border-radius: 999px;
  background: linear-gradient(135deg, rgba(255, 77, 77, 0.96) 0%, rgba(255, 111, 105, 0.92) 100%);
  color: #fff;
  font-size: 11px;
  font-weight: 800;
  box-shadow: 0 12px 20px rgba(255, 77, 77, 0.24);
}

.floating-guide-ticker {
  max-width: 188px;
  display: grid;
  grid-template-columns: 1fr;
  gap: 0;
  align-items: center;
  padding: 10px 12px;
  border-radius: 18px;
  border: 1px solid rgba(255, 255, 255, 0.78);
  background: rgba(255, 255, 255, 0.88);
  box-shadow: var(--shadow-soft);
  backdrop-filter: blur(10px);
  cursor: pointer;
}

.floating-guide-ticker-copy {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.floating-guide-ticker-copy strong {
  font-size: 12px;
}

.floating-guide-ticker-copy span {
  color: var(--text-faint);
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.floating-guide-panel {
  width: min(328px, calc(100vw - 32px));
  display: grid;
  gap: 12px;
  padding: 14px;
  border-radius: 24px;
  transform-origin: right bottom;
  box-shadow: 0 18px 34px rgba(101, 72, 62, 0.1);
}

.floating-guide-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
}

.floating-guide-head strong {
  display: block;
  font-size: 17px;
}

.floating-guide-subline {
  display: block;
  margin-top: 4px;
  color: var(--text-faint);
  line-height: 1.55;
}

.floating-guide-close {
  width: 34px;
  height: 34px;
  display: inline-grid;
  place-items: center;
  padding: 0;
  border: 1px solid rgba(255, 255, 255, 0.82);
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.78);
  cursor: pointer;
  font-size: 22px;
  line-height: 1;
}

.floating-guide-form {
  display: grid;
  gap: 8px;
}

.floating-guide-input {
  width: 100%;
  min-height: 44px;
  padding: 0 16px;
  border-radius: 14px;
  border: 1px solid rgba(255, 77, 77, 0.12);
  background: rgba(255, 255, 255, 0.9);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.74);
}

.floating-guide-actions {
  width: 100%;
}

.floating-guide-submit {
  min-height: 38px;
  padding: 0 14px;
  border-radius: 12px;
  font-weight: 700;
  cursor: pointer;
  border: none;
  background: linear-gradient(135deg, rgba(255, 77, 77, 0.96) 0%, rgba(255, 111, 105, 0.88) 100%);
  color: #fff;
  box-shadow: 0 16px 28px rgba(255, 77, 77, 0.2);
}

.mobile-bottom-nav {
  display: none;
}

@media (max-width: 1180px) {
  .topbar {
    grid-template-columns: 1fr;
  }

  .nav,
  .account-panel {
    justify-content: flex-start;
  }

  .account-panel {
    justify-content: flex-start;
  }
}

@media (max-width: 720px) {
  .topbar {
    grid-template-columns: auto 1fr;
    gap: 10px;
    padding: 10px 0;
  }

  .brand {
    gap: 8px;
  }

  .brand-mark {
    width: 34px;
    height: 34px;
  }

  .brand-copy strong {
    font-size: 22px;
  }

  .nav {
    display: none;
  }

  .account-panel {
    display: none;
  }

  .brand {
    gap: 8px;
  }

  .brand-mark {
    width: 30px;
    height: 30px;
  }

  .brand-copy strong {
    font-size: 18px;
  }

  .content {
    padding: 16px 0 96px;
  }

  .floating-guide {
    right: 12px;
    bottom: 86px;
  }

  .floating-guide-trigger {
    width: 64px;
    height: 64px;
  }

  .floating-guide-ticker {
    max-width: min(220px, calc(100vw - 96px));
  }

  .mobile-bottom-nav {
    position: fixed;
    left: 0;
    right: 0;
    bottom: 0;
    z-index: 30;
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(0, 1fr));
    gap: 6px;
    padding: 8px 10px calc(8px + env(safe-area-inset-bottom));
    background: rgba(255, 255, 255, 0.72);
    border-top: 1px solid rgba(255, 77, 77, 0.1);
    backdrop-filter: blur(12px) saturate(140%);
    box-shadow: 0 -12px 30px rgba(120, 105, 101, 0.08);
  }

  .mobile-nav-link {
    min-height: 46px;
    display: grid;
    justify-items: center;
    align-content: center;
    gap: 2px;
    padding: 6px 4px;
    position: relative;
    border-radius: 14px;
    color: #54483d;
    text-decoration: none;
    font-size: 11px;
    font-weight: 600;
    background: rgba(255, 255, 255, 0.44);
  }

  .mobile-nav-link.router-link-active {
    background: linear-gradient(135deg, rgba(255, 77, 77, 0.95) 0%, rgba(255, 77, 77, 0.82) 100%);
    color: #fff;
  }

  .mobile-badge {
    position: absolute;
    top: 4px;
    right: 10px;
    min-width: 18px;
    height: 18px;
    display: inline-grid;
    place-items: center;
    padding: 0 5px;
    border-radius: 999px;
    background: linear-gradient(135deg, #ff4d4d 0%, #ff7b72 100%);
    color: #fff;
    box-shadow: 0 10px 18px rgba(255, 77, 77, 0.22);
    font-size: 11px;
  }
}
</style>
