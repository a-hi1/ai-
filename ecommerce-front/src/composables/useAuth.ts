import { computed, ref } from 'vue'

import { api } from '../services/api'

export interface AuthUser {
  id: string
  email: string
  role: string
  displayName?: string
  phone?: string
  city?: string
  bio?: string
}

const STORAGE_KEY = 'auth_user'

const loadUser = (): AuthUser | null => {
  const raw = localStorage.getItem(STORAGE_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as AuthUser
  } catch {
    return null
  }
}

const currentUser = ref<AuthUser | null>(loadUser())

const isMissingUserError = (error: unknown) => {
  return error instanceof Error && /^Request failed:\s*404\b/.test(error.message)
}

export const useAuth = () => {
  const isLoggedIn = computed(() => Boolean(currentUser.value))

  const persistUser = (user: AuthUser) => {
    currentUser.value = user
    localStorage.setItem(STORAGE_KEY, JSON.stringify(user))
  }

  const login = async (email: string, password: string) => {
    const user = await api.loginUser(email, password)
    persistUser(user)
  }

  const register = async (email: string, password: string) => {
    const user = await api.registerUser(email, password)
    persistUser(user)
  }

  const refreshProfile = async () => {
    if (!currentUser.value) return null
    try {
      const user = await api.getUserProfile(currentUser.value.id)
      persistUser(user)
      return user
    } catch (error) {
      if (isMissingUserError(error)) {
        logout()
        throw new Error('当前登录信息已失效，请重新登录。')
      }
      throw error
    }
  }

  const updateProfile = async (payload: { displayName: string; phone: string; city: string; bio: string }) => {
    if (!currentUser.value) {
      throw new Error('Not logged in')
    }
    try {
      const user = await api.updateUserProfile(currentUser.value.id, payload)
      persistUser(user)
      return user
    } catch (error) {
      if (isMissingUserError(error)) {
        logout()
        throw new Error('当前登录信息已失效，请重新登录。')
      }
      throw error
    }
  }

  const logout = () => {
    currentUser.value = null
    localStorage.removeItem(STORAGE_KEY)
  }

  return {
    currentUser,
    isLoggedIn,
    login,
    register,
    refreshProfile,
    updateProfile,
    logout
  }
}
