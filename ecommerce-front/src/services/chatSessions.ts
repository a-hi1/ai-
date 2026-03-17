export interface ChatSessionGoods {
  id: string
  name: string
  price: number
  desc: string
  image: string
  reason?: string
  salesCount: number
  withinBudget: boolean
  tags: string[]
}

export interface ChatSessionInsight {
  label: string
  value: string
}

export interface ChatSessionMessage {
  role: 'user' | 'assistant'
  content: string
  goodsList?: ChatSessionGoods[]
  relatedGoods?: ChatSessionGoods[]
  insights?: ChatSessionInsight[]
  timestamp: string
  budgetSummary?: string
  detectedIntent?: string
  fallback?: boolean
}

export interface ChatSessionRecord {
  id: string
  userId: string
  title: string
  createdAt: string
  updatedAt: string
  messages: ChatSessionMessage[]
}

interface LegacyHistoryItem {
  id: string
  role: string
  content: string
  createdAt: string
}

type ImportHistoryOptions = {
  replaceExisting?: boolean
}

const STORAGE_PREFIX = 'ecommerce-chat-sessions'

const getStorageKey = (userId: string) => `${STORAGE_PREFIX}:${userId}`

const safeParse = <T>(value: string | null, fallback: T): T => {
  if (!value) {
    return fallback
  }

  try {
    return JSON.parse(value) as T
  } catch {
    return fallback
  }
}

const writeSessions = (userId: string, sessions: ChatSessionRecord[]) => {
  window.localStorage.setItem(getStorageKey(userId), JSON.stringify(sessions))
}

const normalizeSessions = (sessions: ChatSessionRecord[]) => {
  return [...sessions].sort((left, right) => {
    return new Date(right.updatedAt).getTime() - new Date(left.updatedAt).getTime()
  })
}

export const listChatSessions = (userId: string) => {
  const sessions = safeParse<ChatSessionRecord[]>(window.localStorage.getItem(getStorageKey(userId)), [])
  return normalizeSessions(sessions)
}

export const getChatSession = (userId: string, sessionId: string) => {
  return listChatSessions(userId).find(session => session.id === sessionId) ?? null
}

const buildTitle = (messages: ChatSessionMessage[]) => {
  const firstUserMessage = messages.find(message => message.role === 'user')
  if (!firstUserMessage) {
    return '新对话'
  }

  return firstUserMessage.content.replace(/\s+/g, ' ').trim().slice(0, 18) || '新对话'
}

export const createChatSession = (userId: string, initialPrompt = '') => {
  const now = new Date().toISOString()
  const session: ChatSessionRecord = {
    id: `chat-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    userId,
    title: initialPrompt.trim().slice(0, 18) || '新对话',
    createdAt: now,
    updatedAt: now,
    messages: []
  }

  const sessions = listChatSessions(userId)
  writeSessions(userId, [session, ...sessions])
  return session
}

export const saveChatSession = (session: ChatSessionRecord) => {
  const sessions = listChatSessions(session.userId)
  const nextSessions = normalizeSessions([
    session,
    ...sessions.filter(item => item.id !== session.id)
  ])
  writeSessions(session.userId, nextSessions)
  return session
}

export const deleteChatSession = (userId: string, sessionId: string) => {
  const sessions = listChatSessions(userId)
  const nextSessions = sessions.filter(session => session.id !== sessionId)
  writeSessions(userId, nextSessions)
  return nextSessions
}

export const updateChatSessionMessages = (userId: string, sessionId: string, messages: ChatSessionMessage[]) => {
  const current = getChatSession(userId, sessionId)
  if (!current) {
    return null
  }

  const timestamp = messages[messages.length - 1]?.timestamp || current.updatedAt
  return saveChatSession({
    ...current,
    title: buildTitle(messages),
    updatedAt: timestamp,
    messages
  })
}

export const ensureImportedHistorySession = (userId: string, historyItems: LegacyHistoryItem[], options: ImportHistoryOptions = {}) => {
  const existingSessions = listChatSessions(userId)
  if (!historyItems.length) {
    return existingSessions
  }

  if (existingSessions.length && !options.replaceExisting) {
    return existingSessions
  }

  const orderedHistory = [...historyItems].sort((left, right) => {
    return new Date(left.createdAt).getTime() - new Date(right.createdAt).getTime()
  })
  const firstItem = orderedHistory[0]
  const lastItem = orderedHistory[orderedHistory.length - 1]

  if (!firstItem || !lastItem) {
    return existingSessions
  }

  const importedSession: ChatSessionRecord = {
    id: `chat-import-${Date.now()}`,
    userId,
    title: '历史导入会话',
    createdAt: firstItem.createdAt,
    updatedAt: lastItem.createdAt,
    messages: orderedHistory.map(item => ({
      role: item.role === 'USER' ? 'user' : 'assistant',
      content: item.content,
      timestamp: item.createdAt
    }))
  }

  writeSessions(userId, [importedSession])
  return [importedSession]
}