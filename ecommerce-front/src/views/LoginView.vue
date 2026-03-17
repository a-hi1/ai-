<template>
  <div class="auth-page">
    <section class="auth-shell">
      <div class="auth-hero">
        <p class="eyebrow">电子产品会员入口</p>
        <h1>登录后继续你的智能购物流程</h1>
        <p class="hint">保存 AI 导购记录、购物车、订单和浏览轨迹，形成更完整的电商体验链路。</p>
        <div class="feature-list">
          <article>
            <strong>AI 导购连续会话</strong>
            <span>保留你的预算、场景和偏好，继续追问即可缩小范围。</span>
          </article>
          <article>
            <strong>电子产品多品类联动</strong>
            <span>覆盖耳机、办公本、键盘、手表手环与娱乐设备。</span>
          </article>
          <article>
            <strong>沙箱支付与订单回查</strong>
            <span>从加购到下单支付形成完整演示闭环。</span>
          </article>
        </div>
      </div>

      <div class="panel">
        <h2>登录</h2>
        <p class="hint">使用演示账号即可体验中文聊天导购、搜索、下单与支付流程。</p>
        <label>
          邮箱
          <input v-model="email" type="email" placeholder="请输入邮箱" />
        </label>
        <label>
          密码
          <input v-model="password" type="password" placeholder="请输入密码" />
        </label>
        <p v-if="errorMessage" class="error">{{ errorMessage }}</p>
        <button class="primary" :disabled="submitting" @click="submit">{{ submitting ? '登录中...' : '登录' }}</button>
        <button class="ghost" @click="goRegister">去注册账号</button>
        <p class="hint demo">演示账号：demo@aishop.local / 123456</p>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuth } from '../composables/useAuth'

const router = useRouter()
const { login } = useAuth()

const email = ref('')
const password = ref('')
const submitting = ref(false)
const errorMessage = ref('')

const submit = async () => {
  if (!email.value || !password.value || submitting.value) return
  submitting.value = true
  errorMessage.value = ''
  try {
    await login(email.value, password.value)
    router.push('/chat')
  } catch {
    errorMessage.value = '登录失败，请检查邮箱或密码。默认演示账号：demo@aishop.local / 123456'
  } finally {
    submitting.value = false
  }
}

const goRegister = () => {
  router.push('/register')
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

.eyebrow {
  margin: 0;
  color: #ffd59e;
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
  color: rgba(255, 248, 239, 0.82);
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

.hint.demo {
  margin-top: 0;
  color: #8a6a3b;
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
