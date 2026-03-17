import { computed, ref, watch } from 'vue'

import { api, type CartItemDto } from '../services/api'
import { useAuth } from './useAuth'

export interface CartItem {
  cartItemId: string
  id: string
  name: string
  price: number
  desc: string
  image: string
  quantity: number
  lineTotal: number
}

const { currentUser } = useAuth()
const cartItems = ref<CartItem[]>([])
const syncing = ref(false)
const errorMessage = ref('')

const sanitizeCartDescription = (value?: string) => {
  const text = (value || '').trim()
  if (!text) {
    return ''
  }

  if (text.startsWith('[') && text.includes('"content"')) {
    return '评价内容已收纳，点击商品详情可逐条查看。'
  }

  if (/"content"\s*:\s*"/.test(text)) {
    return '评价内容已收纳，点击商品详情可逐条查看。'
  }

  return text.length > 88 ? `${text.slice(0, 88)}...` : text
}

const mapCartItems = (items: CartItemDto[]): CartItem[] => {
  return items.map(item => ({
    cartItemId: item.cartItemId,
    id: item.productId,
    name: item.productName,
    price: item.unitPrice,
    desc: sanitizeCartDescription(item.productDescription),
    image: item.imageUrl ?? `https://picsum.photos/80/80?random=${item.productId}`,
    quantity: item.quantity,
    lineTotal: item.lineTotal
  }))
}

const mapCartItem = (item: CartItemDto): CartItem => ({
  cartItemId: item.cartItemId,
  id: item.productId,
  name: item.productName,
  price: item.unitPrice,
  desc: sanitizeCartDescription(item.productDescription),
  image: item.imageUrl ?? `https://picsum.photos/80/80?random=${item.productId}`,
  quantity: item.quantity,
  lineTotal: item.lineTotal
})

const upsertCartItem = (nextItem: CartItem) => {
  const index = cartItems.value.findIndex(item => item.cartItemId === nextItem.cartItemId || item.id === nextItem.id)
  if (index === -1) {
    cartItems.value = [...cartItems.value, nextItem]
    return
  }

  const nextCart = [...cartItems.value]
  nextCart[index] = nextItem
  cartItems.value = nextCart
}

const refreshCart = async () => {
  if (!currentUser.value) {
    cartItems.value = []
    return
  }

  syncing.value = true
  try {
    cartItems.value = mapCartItems(await api.getCart(currentUser.value.id))
    errorMessage.value = ''
  } catch {
    errorMessage.value = '购物车同步失败，请确认后端服务仍在运行。'
  } finally {
    syncing.value = false
  }
}

watch(
  currentUser,
  () => {
    void refreshCart()
  },
  { immediate: true }
)

export const useCart = () => {
  const ensureUser = () => {
    if (!currentUser.value) {
      throw new Error('AUTH_REQUIRED')
    }
    return currentUser.value
  }

  const addItem = async (item: Omit<CartItem, 'cartItemId' | 'quantity' | 'lineTotal'>, quantity = 1) => {
    const user = ensureUser()
    const nextItem = mapCartItem(await api.addCartItem(user.id, item.id, quantity))
    upsertCartItem(nextItem)
    errorMessage.value = ''
  }

  const updateQuantity = async (productId: string, quantity: number) => {
    const current = cartItems.value.find(item => item.id === productId)
    if (!current) return
    if (quantity <= 0) {
      await api.removeCartItem(current.cartItemId)
      cartItems.value = cartItems.value.filter(item => item.id !== productId)
    } else {
      const nextItem = mapCartItem(await api.updateCartItem(current.cartItemId, quantity))
      upsertCartItem(nextItem)
    }
    errorMessage.value = ''
  }

  const removeItem = async (productId: string) => {
    const current = cartItems.value.find(item => item.id === productId)
    if (!current) return
    await api.removeCartItem(current.cartItemId)
    cartItems.value = cartItems.value.filter(item => item.id !== productId)
    errorMessage.value = ''
  }

  const clearCart = async () => {
    const user = ensureUser()
    await api.clearCart(user.id)
    cartItems.value = []
    errorMessage.value = ''
  }

  const totalPrice = computed(() => {
    return cartItems.value
      .reduce((sum, item) => sum + item.price * item.quantity, 0)
      .toFixed(2)
  })

  const itemCount = computed(() => {
    return cartItems.value.reduce((sum, item) => sum + item.quantity, 0)
  })

  return {
    cartItems,
    syncing,
    errorMessage,
    addItem,
    updateQuantity,
    removeItem,
    clearCart,
    refreshCart,
    totalPrice,
    itemCount
  }
}
