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

const STORAGE_KEY = 'auth_user_session'
const LEGACY_STORAGE_KEY = 'auth_user'
const ACCOUNT_PORT_MAP_KEY = 'monitor_account_port_map'

const hasLaunchContextQuery = () => {
  const params = new URLSearchParams(window.location.search)
  return Boolean(params.get('account') || params.get('backendPort') || params.get('backendBase'))
}

const loadUser = (): AuthUser | null => {
  const raw = window.sessionStorage.getItem(STORAGE_KEY)
  if (raw) {
    try {
      return JSON.parse(raw) as AuthUser
    } catch {
      window.sessionStorage.removeItem(STORAGE_KEY)
    }
  }

  if (hasLaunchContextQuery()) {
    return null
  }

  const legacyRaw = localStorage.getItem(LEGACY_STORAGE_KEY)
  if (!legacyRaw) return null
  try {
    const parsed = JSON.parse(legacyRaw) as AuthUser
    window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(parsed))
    return parsed
  } catch {
    localStorage.removeItem(LEGACY_STORAGE_KEY)
    return null
  }
}

const currentUser = ref<AuthUser | null>(loadUser())

const loadAccountPortMap = (): Record<string, number> => {
  const raw = localStorage.getItem(ACCOUNT_PORT_MAP_KEY)
  if (!raw) {
    return {}
  }
  try {
    const parsed = JSON.parse(raw) as Record<string, number>
    return parsed || {}
  } catch {
    return {}
  }
}

const saveAccountPortMap = (value: Record<string, number>) => {
  localStorage.setItem(ACCOUNT_PORT_MAP_KEY, JSON.stringify(value))
}

const normalizeMonitorPort = (port?: number) => {
  const normalized = Number(port || 0)
  if (!Number.isInteger(normalized) || normalized < 1 || normalized > 65535) {
    return 8081
  }
  return normalized
}

const persistAccountPort = (accountKey: string, port: number) => {
  const map = loadAccountPortMap()
  map[accountKey] = normalizeMonitorPort(port)
  saveAccountPortMap(map)
}

const resolveAccountPort = (accountKey: string) => {
  const map = loadAccountPortMap()
  return normalizeMonitorPort(map[accountKey])
}

const wait = (ms: number) => new Promise(resolve => window.setTimeout(resolve, ms))

const syncPresence = async (accountKey: string, online: boolean, port: number, attempts = 3) => {
  const resolvedPort = normalizeMonitorPort(port)
  let lastError: unknown = null

  for (let attempt = 0; attempt < attempts; attempt += 1) {
    try {
      await api.syncMonitorAccountPresence(accountKey, online, resolvedPort)
      return true
    } catch (error) {
      lastError = error
      if (attempt < attempts - 1) {
        await wait(600 * (attempt + 1))
      }
    }
  }

  throw lastError instanceof Error ? lastError : new Error('Monitor presence sync failed')
}

const ensurePresenceEventually = (accountKey: string, online: boolean, port: number) => {
  const resolvedPort = normalizeMonitorPort(port)
  void (async () => {
    for (let attempt = 0; attempt < 10; attempt += 1) {
      try {
        await api.syncMonitorAccountPresence(accountKey, online, resolvedPort)
        return
      } catch {
        await wait(1200 + attempt * 300)
      }
    }
  })()
}

const isMissingUserError = (error: unknown) => {
  return error instanceof Error && /^Request failed:\s*404\b/.test(error.message)
}

export const useAuth = () => {
  const isLoggedIn = computed(() => Boolean(currentUser.value))

  const persistUser = (user: AuthUser) => {
    currentUser.value = user
    window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(user))
    localStorage.removeItem(LEGACY_STORAGE_KEY)
  }

  const login = async (email: string, password: string, monitorPort?: number) => {
    const user = await api.loginUser(email, password)
    persistUser(user)
    const port = normalizeMonitorPort(monitorPort)
    persistAccountPort(user.email, port)
    try {
      await syncPresence(user.email, true, port)
    } catch {
      ensurePresenceEventually(user.email, true, port)
    }
  }

  const register = async (email: string, password: string, monitorPort?: number) => {
    const user = await api.registerUser(email, password)
    persistUser(user)
    const port = normalizeMonitorPort(monitorPort)
    persistAccountPort(user.email, port)
    try {
      await syncPresence(user.email, true, port)
    } catch {
      ensurePresenceEventually(user.email, true, port)
    }
  }

  const syncCurrentUserPresence = async (online = true) => {
    const user = currentUser.value
    if (!user?.email) {
      return false
    }

    const port = resolveAccountPort(user.email)
    try {
      await syncPresence(user.email, online, port)
      return true
    } catch {
      return false
    }
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
    const previousUser = currentUser.value
    if (previousUser?.email) {
      const previousPort = resolveAccountPort(previousUser.email)
      void syncPresence(previousUser.email, false, previousPort).catch(() => {
        // keep logout flow independent from monitor availability
      })
    }
    currentUser.value = null
    window.sessionStorage.removeItem(STORAGE_KEY)
    localStorage.removeItem(LEGACY_STORAGE_KEY)
  }

  return {
    currentUser,
    isLoggedIn,
    login,
    register,
    syncCurrentUserPresence,
    refreshProfile,
    updateProfile,
    logout
  }
}
