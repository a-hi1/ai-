export interface ProductDto {
  id: string
  name: string
  description?: string
  price: number
  imageUrl?: string
  tags?: string
  category?: string
  specs?: string
  sellingPoints?: string
  policy?: string
  topReviewsJson?: string
}

export interface ChatRecommendationDto {
  productId: string
  name: string
  description?: string
  imageUrl?: string
  price: number
  reason: string
  salesCount: number
  withinBudget: boolean
  tags: string[]
}

export interface ChatInsightDto {
  label: string
  value: string
}

export interface ChatResponse {
  reply: string
  timestamp: string
  recommendations: ChatRecommendationDto[]
  relatedRecommendations: ChatRecommendationDto[]
  insights: ChatInsightDto[]
  detectedIntent: string
  budgetSummary: string
  fallback: boolean
}

export interface ChatStreamProgress {
  index: number
  total: number
  step: string
}

export type ChatStreamHandlers = {
  onStart?: () => void
  onDelta?: (chunk: string) => void
  onProgress?: (progress: ChatStreamProgress) => void
  onFinal?: (response: ChatResponse) => void
  onDone?: () => void
  onError?: (message: string) => void
}

export interface AuthUserResponse {
  id: string
  email: string
  role: string
  displayName?: string
  phone?: string
  city?: string
  bio?: string
}

export interface UpdateProfilePayload {
  displayName: string
  phone: string
  city: string
  bio: string
}

export interface OrderDto {
  id: string
  userId: string
  status: string
  totalAmount: number
  paymentMethod?: string
  gatewayTradeNo?: string
  paidAt?: string
  createdAt: string
  itemCount: number
  previewItems?: OrderPreviewItemDto[]
}

export interface OrderPreviewItemDto {
  productId: string
  productName: string
  imageUrl?: string
  quantity: number
}

export interface ChatHistoryItem {
  id: string
  userId: string
  role: string
  content: string
  createdAt: string
}

export interface CartItemDto {
  cartItemId: string
  productId: string
  productName: string
  productDescription?: string
  imageUrl?: string
  unitPrice: number
  quantity: number
  lineTotal: number
}

export interface OrderItemDto {
  id: string
  productId: string
  productName: string
  productDescription?: string
  imageUrl?: string
  unitPrice: number
  quantity: number
  lineTotal: number
}

export interface OrderDetailDto {
  id: string
  userId: string
  status: string
  totalAmount: number
  paymentMethod?: string
  gatewayTradeNo?: string
  paidAt?: string
  createdAt: string
  items: OrderItemDto[]
}

export interface PaymentSessionDto {
  orderId: string
  paymentUrl: string
  provider: string
  mode: string
  gatewayTradeNo: string
}

export interface ProductInsightDto {
  productId: string
  name: string
  description?: string
  imageUrl?: string
  price: number
  reason?: string
  source: string
  viewedAt: string
}

export interface AccountOverviewDto {
  profile: AuthUserResponse
  recentViews: ProductInsightDto[]
  recommendations: ProductInsightDto[]
}

export interface ManagedProductDto {
  id: string
  name: string
  price: number
  dataSource: string
  createdAt: string
}

export interface DataAdminStatsDto {
  totalProducts: number
  sampleProducts: number
  crawlerProducts: number
  manualProducts: number
  userCount: number
  orderCount: number
  cartItemCount: number
  chatMessageCount: number
  productViewCount: number
}

export interface DataAdminOverviewDto {
  stats: DataAdminStatsDto
  recentProducts: ManagedProductDto[]
}

export interface DataAdminActionDto {
  message: string
  overview: DataAdminOverviewDto
}
export interface AiProviderEntryDto {
  hasApiKey: boolean
  maskedApiKey: string
  baseUrl: string
  modelName: string
}
export interface AiProviderOverviewDto {
  provider: string
  activeModelName: string
  fallback: boolean
  runtimeReason: string
  consecutiveFallbacks: number
  persisted: boolean
  deepseek: AiProviderEntryDto
  siliconflow: AiProviderEntryDto
}
export interface AiProviderUpdateDto {
  apiKey?: string
  baseUrl?: string
  modelName?: string
}
export interface UpdateAiProviderConfigPayload {
  provider: string
  deepseek?: AiProviderUpdateDto
  siliconflow?: AiProviderUpdateDto
}
export interface AiProviderConfigActionDto {
  message: string
  config: AiProviderOverviewDto
}

const API_BASE_OVERRIDE_KEY = 'tab_api_base_override'
const API_BASE = resolveApiBase()
const MONITOR_API_BASE = import.meta.env.VITE_MONITOR_API_BASE || 'http://127.0.0.1:9091'
const MONITOR_CONTROL_TOKEN = import.meta.env.VITE_MONITOR_CONTROL_TOKEN || 'monitor-dev-token'

function normalizeBaseUrl(value: string) {
  return value.replace(/\/+$/, '')
}

function resolveApiBase() {
  const fallbackBase = normalizeBaseUrl(import.meta.env.VITE_API_BASE || 'http://localhost:8080')

  try {
    const params = new URLSearchParams(window.location.search)
    const backendBase = params.get('backendBase')?.trim()
    const backendHost = params.get('backendHost')?.trim() || window.location.hostname || '127.0.0.1'
    const backendPort = Number(params.get('backendPort') || 0)

    if (backendBase) {
      const normalized = normalizeBaseUrl(backendBase)
      window.sessionStorage.setItem(API_BASE_OVERRIDE_KEY, normalized)
      return normalized
    }

    if (Number.isInteger(backendPort) && backendPort > 0 && backendPort <= 65535) {
      const normalized = `${window.location.protocol}//${backendHost}:${backendPort}`
      window.sessionStorage.setItem(API_BASE_OVERRIDE_KEY, normalized)
      return normalized
    }

    const stored = window.sessionStorage.getItem(API_BASE_OVERRIDE_KEY)?.trim()
    if (stored) {
      return normalizeBaseUrl(stored)
    }
  } catch {
    return fallbackBase
  }

  return fallbackBase
}

type RequestOptions = RequestInit & {
  timeoutMs?: number
  retry?: number
}

const request = async <T>(path: string, init: RequestOptions = {}): Promise<T> => {
  const { timeoutMs = 12000, retry, headers, method, ...rest } = init
  const upperMethod = (method || 'GET').toUpperCase()
  const maxRetry = retry ?? (upperMethod === 'GET' ? 1 : 0)

  for (let attempt = 0; attempt <= maxRetry; attempt += 1) {
    const controller = new AbortController()
    const timeoutHandle = window.setTimeout(() => controller.abort(), timeoutMs)

    try {
      const response = await fetch(`${API_BASE}${path}`, {
        ...rest,
        method: upperMethod,
        signal: controller.signal,
        headers: {
          'Content-Type': 'application/json',
          ...(headers || {})
        }
      })

      if (!response.ok) {
        const errorText = await response.text()
        throw new Error(`Request failed: ${response.status}${errorText ? ` ${errorText}` : ''}`)
      }

      if (response.status === 204) {
        return undefined as T
      }

      const contentType = response.headers.get('content-type') || ''
      if (contentType.includes('application/json')) {
        return response.json() as Promise<T>
      }

      const text = await response.text()
      return text as T
    } catch (error) {
      const isAbort = error instanceof DOMException && error.name === 'AbortError'
      const normalizedError = isAbort ? new Error('Request timeout') : error
      if (attempt === maxRetry) {
        throw normalizedError
      }
    } finally {
      window.clearTimeout(timeoutHandle)
    }
  }

  throw new Error('Request failed')
}

export const api = {
  async registerUser(email: string, password: string): Promise<AuthUserResponse> {
    return request<AuthUserResponse>('/api/users/register', {
      method: 'POST',
      body: JSON.stringify({ email, password, role: 'USER' })
    })
  },

  async loginUser(email: string, password: string): Promise<AuthUserResponse> {
    return request<AuthUserResponse>('/api/users/login', {
      method: 'POST',
      body: JSON.stringify({ email, password })
    })
  },

  async getUserProfile(userId: string): Promise<AuthUserResponse> {
    return request<AuthUserResponse>(`/api/users/${encodeURIComponent(userId)}`)
  },

  async updateUserProfile(userId: string, payload: UpdateProfilePayload): Promise<AuthUserResponse> {
    return request<AuthUserResponse>(`/api/users/${encodeURIComponent(userId)}/profile`, {
      method: 'PUT',
      body: JSON.stringify(payload)
    })
  },

  async searchProducts(keyword: string): Promise<ProductDto[]> {
    const q = encodeURIComponent(keyword)
    return request<ProductDto[]>(`/api/products?q=${q}`)
  },

  async listProducts(): Promise<ProductDto[]> {
    return request<ProductDto[]>('/api/products')
  },

  async getProduct(productId: string): Promise<ProductDto> {
    return request<ProductDto>(`/api/products/${encodeURIComponent(productId)}`)
  },

  async getRelatedProducts(productId: string, limit = 4): Promise<ChatRecommendationDto[]> {
    return request<ChatRecommendationDto[]>(
      `/api/products/${encodeURIComponent(productId)}/related?limit=${encodeURIComponent(String(limit))}`
    )
  },

  async sendChat(userId: string, message: string): Promise<ChatResponse> {
    return request<ChatResponse>('/api/chat/send', {
      method: 'POST',
      body: JSON.stringify({ userId, message }),
      timeoutMs: 130000,
      retry: 1
    })
  },

  async sendChatStream(userId: string, message: string, handlersOrSessionId?: ChatStreamHandlers | string, sessionIdOrHandlers?: string | ChatStreamHandlers): Promise<ChatResponse | null> {
    let handlers: ChatStreamHandlers = {}
    let sessionId = ''

    // Handle overloaded parameters
    if (typeof handlersOrSessionId === 'string') {
      sessionId = handlersOrSessionId
      handlers = (typeof sessionIdOrHandlers === 'object' ? sessionIdOrHandlers : {}) as ChatStreamHandlers
    } else if (typeof handlersOrSessionId === 'object') {
      handlers = handlersOrSessionId
      sessionId = typeof sessionIdOrHandlers === 'string' ? sessionIdOrHandlers : ''
    }

    const controller = new AbortController()
    const timeoutHandle = window.setTimeout(() => controller.abort(), 130000)

    try {
      const response = await fetch(`${API_BASE}/api/chat/stream`, {
        method: 'POST',
        signal: controller.signal,
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({ userId, message, sessionId })
      })

      if (!response.ok) {
        const errorText = await response.text()
        throw new Error(`Request failed: ${response.status}${errorText ? ` ${errorText}` : ''}`)
      }

      if (!response.body) {
        throw new Error('Stream body is unavailable')
      }

      const decoder = new TextDecoder('utf-8')
      const reader = response.body.getReader()
      let pending = ''
      let finalPayload: ChatResponse | null = null

      while (true) {
        const { done, value } = await reader.read()
        if (done) {
          break
        }

        pending += decoder.decode(value, { stream: true })
        let boundaryIndex = pending.indexOf('\n\n')
        while (boundaryIndex >= 0) {
          const rawEvent = pending.slice(0, boundaryIndex)
          pending = pending.slice(boundaryIndex + 2)
          const parsed = parseSseEvent(rawEvent)

          if (parsed.event === 'start') {
            handlers.onStart?.()
          } else if (parsed.event === 'progress') {
            try {
              const progress = JSON.parse(parsed.data) as ChatStreamProgress
              handlers.onProgress?.(progress)
            } catch {
              handlers.onProgress?.({ index: 0, total: 0, step: parsed.data || '分析中' })
            }
          } else if (parsed.event === 'delta') {
            handlers.onDelta?.(parsed.data)
          } else if (parsed.event === 'final') {
            try {
              finalPayload = JSON.parse(parsed.data) as ChatResponse
              handlers.onFinal?.(finalPayload)
            } catch {
              throw new Error('Invalid final stream payload')
            }
          } else if (parsed.event === 'error') {
            handlers.onError?.(parsed.data)
            throw new Error(parsed.data || 'stream_error')
          } else if (parsed.event === 'done') {
            handlers.onDone?.()
          }

          boundaryIndex = pending.indexOf('\n\n')
        }
      }

      return finalPayload
    } catch (error) {
      const isAbort = error instanceof DOMException && error.name === 'AbortError'
      const normalizedError = isAbort ? new Error('Request timeout') : error
      handlers.onError?.(normalizedError instanceof Error ? normalizedError.message : 'stream_error')
      throw normalizedError
    } finally {
      window.clearTimeout(timeoutHandle)
    }
  },

  async sendChatQuick(userId: string, message: string, sessionId = ''): Promise<ChatResponse> {
    return request<ChatResponse>('/api/chat/quick', {
      method: 'POST',
      body: JSON.stringify({ userId, message, sessionId })
    })
  },

  async getChatHistory(userId: string): Promise<ChatHistoryItem[]> {
    return request<ChatHistoryItem[]>(`/api/chat/history/${encodeURIComponent(userId)}`, {
      retry: 1,
      timeoutMs: 12000
    })
  },

  async getCart(userId: string): Promise<CartItemDto[]> {
    return request<CartItemDto[]>(`/api/cart/${encodeURIComponent(userId)}`)
  },

  async addCartItem(userId: string, productId: string, quantity = 1): Promise<CartItemDto> {
    return request<CartItemDto>('/api/cart/items', {
      method: 'POST',
      body: JSON.stringify({ userId, productId, quantity })
    })
  },

  async updateCartItem(cartItemId: string, quantity: number): Promise<CartItemDto> {
    return request<CartItemDto>(`/api/cart/items/${encodeURIComponent(cartItemId)}`, {
      method: 'PUT',
      body: JSON.stringify({ quantity })
    })
  },

  async removeCartItem(cartItemId: string): Promise<void> {
    return request<void>(`/api/cart/items/${encodeURIComponent(cartItemId)}`, {
      method: 'DELETE'
    })
  },

  async clearCart(userId: string): Promise<void> {
    return request<void>(`/api/cart/user/${encodeURIComponent(userId)}`, {
      method: 'DELETE'
    })
  },

  async createOrder(userId: string, totalAmount: string): Promise<OrderDetailDto> {
    return request<OrderDetailDto>('/api/orders', {
      method: 'POST',
      body: JSON.stringify({ userId, totalAmount: Number(totalAmount) })
    })
  },

  async getOrders(userId: string): Promise<OrderDto[]> {
    return request<OrderDto[]>(`/api/orders/user/${encodeURIComponent(userId)}`)
  },

  async getOrderDetail(orderId: string): Promise<OrderDetailDto> {
    return request<OrderDetailDto>(`/api/orders/${encodeURIComponent(orderId)}`)
  },

  async createPayment(orderId: string, preferredMode?: 'demo' | 'alipay'): Promise<PaymentSessionDto> {
    return request<PaymentSessionDto>('/api/payments/alipay/create', {
      method: 'POST',
      body: JSON.stringify({ orderId, preferredMode })
    })
  },

  async notifyPayment(orderId: string, status = 'PAID', gatewayTradeNo?: string): Promise<string> {
    return request('/api/payments/alipay/notify', {
      method: 'POST',
      body: JSON.stringify({ orderId, status, gatewayTradeNo: gatewayTradeNo || `SANDBOX-${Date.now()}` })
    })
  },

  async verifyAlipayReturn(payload: Record<string, string>): Promise<string> {
    return request('/api/payments/alipay/return/verify', {
      method: 'POST',
      body: JSON.stringify(payload)
    })
  },

  async queryPayment(orderId: string): Promise<string> {
    return request(`/api/payments/alipay/query/${encodeURIComponent(orderId)}`)
  },

  async getAccountOverview(userId: string): Promise<AccountOverviewDto> {
    return request<AccountOverviewDto>(`/api/account/${encodeURIComponent(userId)}/overview`)
  },

  async recordProductView(userId: string, productId: string, source: string, reason = ''): Promise<void> {
    return request<void>('/api/account/views', {
      method: 'POST',
      body: JSON.stringify({ userId, productId, source, reason })
    })
  },

  async getDataAdminOverview(): Promise<DataAdminOverviewDto> {
    return request<DataAdminOverviewDto>('/api/admin/data/overview')
  },

  async clearAdminData(scope: 'SAMPLE' | 'CRAWLER' | 'ALL'): Promise<DataAdminActionDto> {
    return request<DataAdminActionDto>('/api/admin/data/clear', {
      method: 'POST',
      body: JSON.stringify({ scope })
    })
  },

  async rebuildSampleData(): Promise<DataAdminActionDto> {
    return request<DataAdminActionDto>('/api/admin/data/rebuild-sample', {
      method: 'POST'
    })
  },
  async getAiProviderOverview(): Promise<AiProviderOverviewDto> {
    return request<AiProviderOverviewDto>('/api/admin/ai-config/overview')
  },
  async updateAiProviderConfig(payload: UpdateAiProviderConfigPayload): Promise<AiProviderConfigActionDto> {
    return request<AiProviderConfigActionDto>('/api/admin/ai-config', {
      method: 'PUT',
      body: JSON.stringify(payload),
      timeoutMs: 15000
    })
  },

  async syncMonitorAccountPresence(accountKey: string, online: boolean, port: number): Promise<void> {
    const response = await fetch(`${MONITOR_API_BASE}/api/monitor/account/presence`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Monitor-Token': MONITOR_CONTROL_TOKEN
      },
      body: JSON.stringify({
        accountKey,
        online,
        port,
        host: '127.0.0.1',
        token: MONITOR_CONTROL_TOKEN
      })
    })

    if (!response.ok) {
      const errorText = await response.text()
      throw new Error(`Monitor presence sync failed: ${response.status}${errorText ? ` ${errorText}` : ''}`)
    }
  }
}

type ParsedSseEvent = {
  event: string
  data: string
}

const parseSseEvent = (rawEvent: string): ParsedSseEvent => {
  const lines = rawEvent
    .split(/\r?\n/)
    .filter(line => line.trim().length > 0)

  let event = 'message'
  const data: string[] = []

  for (const line of lines) {
    if (line.startsWith('event:')) {
      event = line.slice('event:'.length).trim() || 'message'
      continue
    }
    if (line.startsWith('data:')) {
      const payload = line.slice('data:'.length)
      data.push(payload.startsWith(' ') ? payload.slice(1) : payload)
    }
  }

  return {
    event,
    data: data.join('\n')
  }
}
