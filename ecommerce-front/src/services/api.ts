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

const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8080'

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
  }
}
