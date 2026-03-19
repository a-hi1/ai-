<template>
  <div class="auth-page">
    <section class="auth-shell">
      <div class="auth-hero warm">
        <p class="eyebrow">新用户注册</p>
        <h1>创建你的电子产品购物身份</h1>
        <p class="hint">注册后即可保留 AI 导购偏好、购物车状态、订单记录和浏览轨迹，形成更完整的电商体验。</p>
        <div class="feature-list">
          <article>
            <strong>保存偏好</strong>
            <span>记录你的预算、场景和常购品类，导购会更连贯。</span>
          </article>
          <article>
            <strong>保留购物进度</strong>
            <span>不同设备登录后仍可同步购物车与订单信息。</span>
          </article>
          <article>
            <strong>继续联调支付链路</strong>
            <span>注册后可直接体验下单、支付回跳和订单回查。</span>
          </article>
        </div>
      </div>

      <div class="panel">
        <h2>注册账号</h2>
        <p class="hint">注册后可以保存购物车、聊天记录和订单历史。</p>
        <label>
          邮箱
          <input v-model="email" type="email" placeholder="请输入邮箱" />
        </label>
        <label>
          密码
          <input v-model="password" type="password" placeholder="请输入密码" />
        </label>
        <label>
          监控端口
          <input v-model.number="monitorPort" type="number" min="1" max="65535" placeholder="例如 8081" />
        </label>
        <p v-if="errorMessage" class="error">{{ errorMessage }}</p>
        <button class="primary" :disabled="submitting" @click="submit">{{ submitting ? '注册中...' : '注册' }}</button>
        <button class="ghost" @click="goLogin">返回登录</button>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuth } from '../composables/useAuth'

const router = useRouter()
const route = useRoute()
const { register } = useAuth()

const email = ref(String(route.query.account || ''))
const password = ref('')
const requestedPort = Number(route.query.monitorPort || route.query.backendPort || 8081)
const monitorPort = ref(Number.isInteger(requestedPort) && requestedPort > 0 && requestedPort <= 65535 ? requestedPort : 8081)
const submitting = ref(false)
const errorMessage = ref('')

const submit = async () => {
  if (!email.value || !password.value || submitting.value) return
  submitting.value = true
  errorMessage.value = ''
  try {
    await register(email.value, password.value, monitorPort.value)
    router.push('/chat')
  } catch {
    errorMessage.value = '注册失败，该邮箱可能已存在。'
  } finally {
    submitting.value = false
  }
}

const goLogin = () => {
  router.push('/login')
}
</script>

<style scoped>
.auth-page {
  min-height: 72vh;
  display: grid;
  align-items: center;
}

.auth-shell {
  width: min(1120px, 100%);
  margin: 0 auto;
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(360px, 0.85fr);
  gap: 20px;
}

.auth-hero {
  display: grid;
  gap: 14px;
  padding: 32px;
  border-radius: 28px;
  background:
    linear-gradient(135deg, rgba(255,255,255,0.18) 0%, rgba(255,255,255,0.06) 100%),
    linear-gradient(135deg, rgba(255,77,77,0.92) 0%, rgba(255,111,105,0.82) 48%, rgba(0,196,180,0.68) 100%);
  color: #fff8ef;
  box-shadow: var(--shadow-lg);
}

.auth-hero.warm {
  background:
    linear-gradient(135deg, rgba(255,255,255,0.18) 0%, rgba(255,255,255,0.06) 100%),
    linear-gradient(135deg, rgba(255,77,77,0.92) 0%, rgba(255,111,105,0.82) 42%, rgba(0,196,180,0.66) 100%);
}

.eyebrow {
  margin: 0;
  color: #fff0d0;
  font-size: 12px;
  text-transform: uppercase;
  letter-spacing: 0.16em;
}

.auth-hero h1 {
  margin: 0;
  font-size: 40px;
  line-height: 1.08;
}

.feature-list {
  display: grid;
  gap: 12px;
}

.feature-list article {
  display: grid;
  gap: 6px;
  padding: 16px;
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.12);
  border: 1px solid rgba(255, 255, 255, 0.16);
}

.feature-list span {
  color: rgba(255, 248, 239, 0.84);
  font-size: 13px;
}

.panel {
  width: min(100%, 440px);
  justify-self: end;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(255,255,255,0.78);
  border-radius: 24px;
  padding: 32px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  box-shadow: var(--shadow-lg);
  backdrop-filter: blur(14px);
}

h2 {
  margin: 0;
  font-size: 26px;
  color: #2c2a28;
}

.hint {
  color: #6f655a;
  font-size: 13px;
  margin: 0 0 8px;
}

.error {
  margin: 0;
  color: #b42318;
  font-size: 13px;
}

label {
  display: grid;
  gap: 6px;
  font-size: 13px;
  color: #544c45;
}

input {
  border-radius: 12px;
  border: 1px solid #d8cbb7;
  padding: 10px 12px;
  background: #fff;
}

.primary {
  margin-top: 8px;
  background: linear-gradient(135deg, rgba(255,77,77,0.96) 0%, rgba(255,111,105,0.88) 100%);
  color: #fff;
  border: none;
  padding: 10px;
  border-radius: 20px;
  cursor: pointer;
}

.primary:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.ghost {
  background: transparent;
  border: none;
  color: #9a4c1a;
  cursor: pointer;
}

@media (max-width: 960px) {
  .auth-shell {
    grid-template-columns: 1fr;
  }

  .panel {
    justify-self: stretch;
    width: 100%;
  }
}
</style>
