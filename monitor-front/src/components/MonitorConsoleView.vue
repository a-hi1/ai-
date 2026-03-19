<template>
  <section :class="['console-shell', `theme-${themeMode}`]">
    <header class="console-header glass-panel">
      <div class="header-main">
        <p class="eyebrow">智选监控云台</p>
        <h1>智选监控云台</h1>
        <p class="header-copy">
          聚焦节点本身的 CPU、内存、磁盘、网络与心跳状态，保留控制中心，减少非必要干扰信息。
        </p>
        <div class="header-badges">
          <span :class="['signal-pill', apiReachable ? 'ok' : 'danger']">{{ apiReachable ? '监控 API 在线' : '监控 API 不可用' }}</span>
          <span :class="['signal-pill', socketConnected ? 'ok' : 'muted']">{{ socketConnected ? '实时推送已连接' : '轮询回退中' }}</span>
          <span class="signal-pill ok">最近同步 {{ lastUpdatedAt ? formatTime(lastUpdatedAt) : '--' }}</span>
        </div>
        <div class="page-nav">
          <button :class="['page-nav-button', { active: mode === 'dashboard' }]" @click="goDashboard">总览页</button>
          <button :class="['page-nav-button', { active: mode === 'detail' }]" :disabled="!selectedServiceId" @click="goSelectedDetail">节点详情页</button>
        </div>
      </div>

      <div class="header-tools glass-subpanel">
        <div class="toolbar-input search-box">
          <span>⌕</span>
          <input v-model.trim="searchText" type="text" placeholder="搜索服务名、ID、IP、状态、类型" />
        </div>
        <div class="toolbar-grid compact">
          <label class="toolbar-field">
            <span>服务类型</span>
            <select v-model="typeFilter">
              <option value="ALL">全部类型</option>
              <option v-for="type in availableTypes" :key="type" :value="type">{{ formatServerType(type) }}</option>
            </select>
          </label>
          <label class="toolbar-field">
            <span>节点状态</span>
            <select v-model="statusFilter">
              <option value="ALL">全部状态</option>
              <option value="ONLINE">在线</option>
              <option value="STALE">超时</option>
              <option value="OFFLINE">离线</option>
            </select>
          </label>
          <label class="toolbar-field">
            <span>排序方式</span>
            <select v-model="sortMode">
              <option value="freshness">心跳新鲜度</option>
              <option value="serviceName">服务名</option>
              <option value="serverType">服务类型</option>
              <option value="status">状态</option>
            </select>
          </label>
          <label class="toolbar-field">
            <span>轮询间隔</span>
            <select v-model.number="refreshIntervalMs">
              <option :value="0">关闭</option>
              <option :value="5000">5 秒</option>
              <option :value="10000">10 秒</option>
              <option :value="30000">30 秒</option>
            </select>
          </label>
        </div>
        <div class="port-quick-add">
          <label class="toolbar-field">
            <span>账号标识</span>
            <input v-model.trim="addPortAccountKey" type="text" placeholder="例如 user@example.com" />
          </label>
          <label class="toolbar-field">
            <span>新增监控端口</span>
            <input v-model.number="addPortNumber" type="number" min="1" max="65535" placeholder="例如 8082" />
          </label>
          <button class="primary-button" :disabled="addPortSubmitting" @click="addMonitorPort">
            {{ addPortSubmitting ? '新增中...' : '实时新增端口' }}
          </button>
        </div>
      </div>

      <div class="header-side">
        <div class="clock-card glass-subpanel">
          <span>本地时间</span>
          <strong>{{ liveClockText }}</strong>
          <small>{{ syncHint }}</small>
        </div>
        <div class="header-actions">
          <button class="ghost-button theme-toggle-button" @click="toggleTheme">{{ themeMode === 'dark' ? '切换浅白主题' : '切换深色主题' }}</button>
          <button class="primary-button" :disabled="loading" @click="loadEverything">{{ loading ? '同步中...' : '立即刷新' }}</button>
          <button class="ghost-button" @click="exportCsv">导出 CSV</button>
        </div>
      </div>
    </header>

    <section class="stats-grid">
      <article class="stat-card emphasis glass-panel">
        <span class="stat-label">节点总数</span>
        <strong class="stat-value">{{ services.length }}</strong>
        <small>所有已接入服务实例</small>
      </article>
      <article class="stat-card success glass-panel">
        <span class="stat-label">在线节点</span>
        <strong class="stat-value">{{ onlineCount }}</strong>
        <small>心跳与指标都正常</small>
      </article>
      <article class="stat-card warning glass-panel">
        <span class="stat-label">超时节点</span>
        <strong class="stat-value">{{ staleCount }}</strong>
        <small>超过 {{ staleThresholdSeconds }} 秒无心跳</small>
      </article>
      <article class="stat-card emphasis glass-panel">
        <span class="stat-label">平均 RTT</span>
        <strong class="stat-value">{{ formatLatency(averageNetworkLatency) }}</strong>
        <small>节点网络往返延迟均值</small>
      </article>
    </section>

    <MonitorDashboardSection
      v-if="mode === 'dashboard'"
      :view="dashboardView"
      :formats="formatters"
      :actions="dashboardActions"
      @update:dashboardWindow="dashboardWindow = $event"
    />

    <MonitorDetailSection
      v-else
      :view="detailView"
      :formats="formatters"
      :actions="detailActions"
      @update:nodeWindow="nodeWindow = $event"
      @update:compareWindow="compareWindow = $event"
      @update:controlToken="controlToken = $event"
      @update:artifactUrl="artifactUrl = $event"
    />

    <transition name="fade-slide">
      <div v-if="confirmAction" class="modal-backdrop" @click.self="cancelConfirm()">
        <div class="confirm-dialog glass-panel">
          <p class="panel-kicker">运维确认</p>
          <h3>确认执行 {{ formatAction(confirmAction) }}</h3>
          <p>目标节点 {{ pendingCommandSummary?.serviceName || pendingCommandServiceId || selectedServiceId }}。该操作会立即发送到监控 agent。</p>
          <div class="detail-footer-actions">
            <button class="ghost-button" @click="cancelConfirm()">取消</button>
            <button class="primary-button" @click="sendCommand(confirmAction)">确认执行</button>
          </div>
        </div>
      </div>
    </transition>

    <transition name="toast-in">
      <div v-if="toastMessage" class="toast" :class="toastType">{{ toastMessage }}</div>
    </transition>
  </section>
</template>

<script setup lang="ts">
import MonitorDashboardSection from './MonitorDashboardSection.vue'
import MonitorDetailSection from './MonitorDetailSection.vue'
import {
  CategoryScale,
  Chart,
  Filler,
  Legend,
  LineController,
  LineElement,
  LinearScale,
  PointElement,
  Tooltip
} from 'chart.js'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

Chart.register(LineController, LineElement, PointElement, LinearScale, CategoryScale, Tooltip, Legend, Filler)

type WindowMode = '24h' | '7d'
type LiveWindow = '1m' | '5m' | '15m' | '1h'
type StatusFilter = 'ALL' | 'ONLINE' | 'STALE' | 'OFFLINE'

type ServiceSummary = {
  serverId?: string
  id?: string
  serverType?: string
  serviceName?: string
  host?: string
  port?: number
  status?: string
  cpuUsage?: number
  memoryUsage?: number
  jvmHeapUsage?: number
  diskUsage?: number
  networkLatency?: number
  networkThroughputMbps?: number
  threadCount?: number
  gcCount?: number
  gcPauseMs?: number
  lastHeartbeat?: string
  connectionClosedCount?: number
  registerTime?: string
  startupTime?: string
  uptimeSeconds?: number
  log?: string
}

type MetricSample = {
  timestamp: string
  cpuUsage?: number
  memoryUsage?: number
  jvmHeapUsage?: number
  diskUsage?: number
  networkLatency?: number
  networkThroughputMbps?: number
  threadCount?: number
  gcCount?: number
  gcPauseMs?: number
  status?: string
}

type AlertRecord = {
  id: string
  timestamp: string
  serverId: string
  serviceName: string
  severity: string
  source: string
  message: string
  policyKey?: string
  metric?: string
  currentValue?: number
  thresholdValue?: number
  suppressedCount?: number
  acknowledged: boolean
}

type TrendBundle = {
  label: string
  points: MetricSample[]
}

type JvmSnapshot = {
  heapUsedMb: number
  heapMaxMb: number
  threadCount: number
  daemonThreadCount: number
  gcCount: number
  gcPauseMs: number
  lastGcAt: string
  systemLoad: number
  stackSummary: string
}

type ServiceDetail = {
  summary: ServiceSummary
  metricsHistory: MetricSample[]
  trend24h?: TrendBundle
  trend7d?: TrendBundle
  logs: LogEntry[]
  alerts?: AlertRecord[]
  jvm?: JvmSnapshot
}

type LogEntry = {
  timestamp: string
  category: string
  message: string
}

type RestartWatchStatus = 'idle' | 'pending' | 'confirmed' | 'timeout' | 'failed'

type RestartWatchState = {
  status: RestartWatchStatus
  serviceId: string
  serviceName: string
  commandId: string
  requestedAt: string
  baselineStartupTime: string
  latestStartupTime: string
  baselineUptimeSeconds: number
  baselineHeartbeat: string
  confirmedAt: string
  note: string
}

type RestartEvidence = {
  commandAck: boolean
  disconnectSeen: boolean
  registerSeen: boolean
  startupChanged: boolean
  uptimeReset: boolean
}

type OverviewResponse = {
  timestamp: string
  services: ServiceSummary[]
  alerts: AlertRecord[]
}

const props = withDefaults(defineProps<{ mode?: 'dashboard' | 'detail'; routeServiceId?: string }>(), {
  mode: 'dashboard',
  routeServiceId: ''
})

const apiBase = resolveMonitorApiBase()
const wsBase = resolveMonitorWsBase(apiBase)
const shopFrontBase = resolveShopFrontBase()
const staleThresholdSeconds = 90
const restartWatchMaxWaitMs = 90000

const router = useRouter()
const route = useRoute()
const cpuLiveCanvas = ref<HTMLCanvasElement | null>(null)
const memoryLiveCanvas = ref<HTMLCanvasElement | null>(null)
const networkLiveCanvas = ref<HTMLCanvasElement | null>(null)
const diskLiveCanvas = ref<HTMLCanvasElement | null>(null)
const comparisonCpuCanvas = ref<HTMLCanvasElement | null>(null)
const comparisonMemoryCanvas = ref<HTMLCanvasElement | null>(null)
const fleetCpuCanvas = ref<HTMLCanvasElement | null>(null)
const fleetMemoryCanvas = ref<HTMLCanvasElement | null>(null)
const fleetThroughputCanvas = ref<HTMLCanvasElement | null>(null)
const fleetAlertCanvas = ref<HTMLCanvasElement | null>(null)
const themeMode = ref<'dark' | 'light'>(loadThemeMode())

const services = ref<ServiceSummary[]>([])
const selectedDetail = ref<ServiceDetail | null>(null)
const alerts = ref<AlertRecord[]>([])
const loading = ref(false)
const detailLoading = ref(false)
const errorMessage = ref('')
const apiReachable = ref(true)
const socketConnected = ref(false)
const lastUpdatedAt = ref<string>('')
const liveNow = ref(new Date())
const searchText = ref('')
const typeFilter = ref('ALL')
const statusFilter = ref<StatusFilter>('ALL')
const sortMode = ref('freshness')
const refreshIntervalMs = ref(10000)
const nextRetryDelayMs = ref(5000)
const selectedServiceId = ref(props.routeServiceId || '')
const compareWindow = ref<WindowMode>('24h')
const dashboardWindow = ref<LiveWindow>('15m')
const nodeWindow = ref<LiveWindow>('15m')
const controlToken = ref('monitor-dev-token')
const artifactUrl = ref('')
const addPortAccountKey = ref('')
const addPortNumber = ref(8082)
const addPortSubmitting = ref(false)
const commandBusy = ref(false)
const commandAccepted = ref(false)
const commandMessage = ref('')
const commandPhase = ref<'idle' | 'queued' | 'success' | 'failed'>('idle')
const confirmAction = ref('')
const pendingCommandServiceId = ref('')
const toastMessage = ref('')
const toastType = ref('success')
const lastCommandPayload = ref('')
const lastCommandResult = ref('')
const lastCommandAt = ref('')
const restartWatch = reactive<RestartWatchState>(createRestartWatchState())
const liveSeries = reactive<Record<string, MetricSample[]>>({})
const fleetSeries = ref<MetricSample[]>([])

let pollingTimer: number | null = null
let reconnectTimer: number | null = null
let clockTimer: number | null = null
let socket: WebSocket | null = null
let toastTimer: number | null = null

const chartMap = new Map<string, Chart>()

const setCpuLiveCanvas = (element: HTMLCanvasElement | null) => {
  cpuLiveCanvas.value = element
}
const setMemoryLiveCanvas = (element: HTMLCanvasElement | null) => {
  memoryLiveCanvas.value = element
}
const setNetworkLiveCanvas = (element: HTMLCanvasElement | null) => {
  networkLiveCanvas.value = element
}
const setDiskLiveCanvas = (element: HTMLCanvasElement | null) => {
  diskLiveCanvas.value = element
}
const setComparisonCpuCanvas = (element: HTMLCanvasElement | null) => {
  comparisonCpuCanvas.value = element
}
const setComparisonMemoryCanvas = (element: HTMLCanvasElement | null) => {
  comparisonMemoryCanvas.value = element
}

const normalizedServices = computed(() => services.value.map(normalizeSummary))
const availableTypes = computed(() => Array.from(new Set(normalizedServices.value.map(service => service.serverType).filter(Boolean))))
const onlineCount = computed(() => normalizedServices.value.filter(item => item.status === 'ONLINE' && !item.isStale).length)
const staleCount = computed(() => normalizedServices.value.filter(item => item.isStale && item.status !== 'OFFLINE').length)
const offlineCount = computed(() => normalizedServices.value.filter(item => item.status === 'OFFLINE').length)
const averageCpuUsage = computed(() => averageMetric(normalizedServices.value, service => service.cpuUsage))
const averageMemoryUsage = computed(() => averageMetric(normalizedServices.value, service => service.memoryUsage))
const averageDiskUsage = computed(() => averageMetric(normalizedServices.value, service => service.diskUsage))
const averageNetworkLatency = computed(() => averageMetric(normalizedServices.value, service => service.networkLatency))
const cpuRiskCount = computed(() => normalizedServices.value.filter(service => service.cpuUsage >= 90).length)
const memoryRiskCount = computed(() => normalizedServices.value.filter(service => service.memoryUsage >= 85).length)
const diskRiskCount = computed(() => normalizedServices.value.filter(service => service.diskUsage >= 90).length)
const latencyRiskCount = computed(() => normalizedServices.value.filter(service => service.networkLatency > 200).length)
const totalDisconnectCount = computed(() => normalizedServices.value.reduce((sum, service) => sum + Number(service.connectionClosedCount ?? 0), 0))
const monitorRules = computed(() => ([
  { key: 'heartbeat', title: '服务存活状态', threshold: '90 秒无心跳', description: '直接标记离线，并记录超时日志/告警。' },
  { key: 'cpu', title: 'CPU 使用率', threshold: '>= 90% 持续 5 分钟', description: '高负载会导致响应变慢与命令阻塞。' },
  { key: 'memory', title: '内存使用率', threshold: '>= 85%', description: '持续高内存意味着 OOM 风险升高。' },
  { key: 'disk', title: '磁盘使用率', threshold: '>= 90%', description: '常见于日志、图片或缓存膨胀。' },
  { key: 'latency', title: '网络延迟', threshold: '> 200 ms', description: '链路抖动会直接影响聊天和下单调用。' }
]))

const filteredServices = computed(() => {
  const keyword = searchText.value.trim().toLowerCase()
  return normalizedServices.value
    .filter((service) => {
      const matchesKeyword = !keyword || [
        service.id,
        service.serviceName,
        service.serverType,
        service.host,
        service.status,
        service.healthLabel
      ].some(value => String(value || '').toLowerCase().includes(keyword))

      if (!matchesKeyword) {
        return false
      }
      if (typeFilter.value !== 'ALL' && service.serverType !== typeFilter.value) {
        return false
      }
      if (statusFilter.value === 'ONLINE') {
        return service.status === 'ONLINE' && !service.isStale
      }
      if (statusFilter.value === 'STALE') {
        return service.isStale && service.status !== 'OFFLINE'
      }
      if (statusFilter.value === 'OFFLINE') {
        return service.status === 'OFFLINE'
      }
      return true
    })
    .sort((left, right) => {
      if (sortMode.value === 'serviceName') {
        return left.serviceName.localeCompare(right.serviceName)
      }
      if (sortMode.value === 'serverType') {
        return left.serverType.localeCompare(right.serverType)
      }
      if (sortMode.value === 'status') {
        return left.healthLabel.localeCompare(right.healthLabel)
      }
      return (left.ageSeconds ?? Number.MAX_SAFE_INTEGER) - (right.ageSeconds ?? Number.MAX_SAFE_INTEGER)
    })
})

const groupedServices = computed(() => {
  const groups = new Map<string, ReturnType<typeof buildGroup>>()
  filteredServices.value.forEach((service) => {
    const key = service.serverType || 'unknown'
    if (!groups.has(key)) {
      groups.set(key, buildGroup(key))
    }
    const group = groups.get(key)
    if (!group) {
      return
    }
    group.services.push(service)
    if (service.status === 'ONLINE' && !service.isStale) {
      group.online += 1
    }
    if (service.alertCount > 0) {
      group.alerting += 1
    }
  })
  return Array.from(groups.values())
})

const selectedSummary = computed(() => filteredServices.value.find(service => service.id === selectedServiceId.value) ?? normalizedServices.value.find(service => service.id === selectedServiceId.value) ?? null)
const pendingCommandSummary = computed(() => {
  if (!pendingCommandServiceId.value) {
    return selectedSummary.value
  }
  return normalizedServices.value.find(service => service.id === pendingCommandServiceId.value) ?? null
})
const logs = computed(() => Array.isArray(selectedDetail.value?.logs) ? selectedDetail.value!.logs.slice().reverse().slice(0, 24) : [])
const selectedMetricsHistory = computed(() => selectedDetail.value?.metricsHistory ?? [])
const selectedNodeAlerts = computed(() => alerts.value
  .filter(alert => alert.serverId === selectedServiceId.value)
  .slice()
  .sort((left, right) => (parseDateValue(right.timestamp)?.getTime() ?? 0) - (parseDateValue(left.timestamp)?.getTime() ?? 0))
  .slice(0, 4))
const jvmSummary = computed<JvmSnapshot>(() => selectedDetail.value?.jvm ?? {
  heapUsedMb: 0,
  heapMaxMb: 0,
  threadCount: 0,
  daemonThreadCount: 0,
  gcCount: 0,
  gcPauseMs: 0,
  lastGcAt: '',
  systemLoad: 0,
  stackSummary: ''
})
const jvmLastGcText = computed(() => jvmSummary.value.lastGcAt ? `最近 GC ${formatTime(jvmSummary.value.lastGcAt)}` : '尚无 GC 时间')
const heapUsagePercent = computed(() => {
  if (!jvmSummary.value.heapMaxMb) {
    return 0
  }
  return Math.min(100, (Number(jvmSummary.value.heapUsedMb || 0) / Number(jvmSummary.value.heapMaxMb || 1)) * 100)
})
const formatHeapPercent = computed(() => `${heapUsagePercent.value.toFixed(1)}%`)
const liveClockText = computed(() => formatTime(liveNow.value.toISOString()))
const syncHint = computed(() => {
  if (loading.value || detailLoading.value) {
    return '正在同步最新监控状态'
  }
  if (!apiReachable.value) {
    return `监控后端不可达，${Math.floor(nextRetryDelayMs.value / 1000)} 秒后重试`
  }
  if (socketConnected.value) {
    return `实时推送已连接，最近同步 ${lastUpdatedAt.value ? formatTime(lastUpdatedAt.value) : '--'}`
  }
  return `轮询回退中，最近同步 ${lastUpdatedAt.value ? formatTime(lastUpdatedAt.value) : '--'}`
})
const comparisonSeries = computed(() => compareWindow.value === '24h' ? selectedDetail.value?.trend24h?.points ?? [] : selectedDetail.value?.trend7d?.points ?? [])
const liveSeriesForSelected = computed(() => liveSeries[selectedServiceId.value] ?? selectedMetricsHistory.value.slice(-20))
const windowedFleetSeries = computed(() => sliceSeriesByWindow(fleetSeries.value, dashboardWindow.value))
const windowedLiveSeries = computed(() => sliceSeriesByWindow(liveSeriesForSelected.value, nodeWindow.value))
const restartWatchMatchesSelection = computed(() => Boolean(selectedSummary.value?.id) && selectedSummary.value?.id === restartWatch.serviceId)
const currentRestartEvidence = computed(() => {
  if (!restartWatchMatchesSelection.value || restartWatch.status === 'idle') {
    return emptyRestartEvidence()
  }
  return collectRestartEvidence(selectedSummary.value, selectedDetail.value?.logs ?? [], restartWatch)
})
const restartWatchStatusText = computed(() => {
  if (!restartWatchMatchesSelection.value || restartWatch.status === 'idle') {
    return '待观察'
  }
  if (restartWatch.status === 'pending') {
    return '验证中'
  }
  if (restartWatch.status === 'confirmed') {
    return '已确认'
  }
  if (restartWatch.status === 'timeout') {
    return '已超时'
  }
  return '失败'
})
const restartWatchBadgeClass = computed(() => {
  if (!restartWatchMatchesSelection.value || restartWatch.status === 'idle') {
    return 'neutral'
  }
  if (restartWatch.status === 'pending') {
    return 'stale'
  }
  if (restartWatch.status === 'confirmed') {
    return 'online'
  }
  return 'offline'
})
const restartWatchNoticeClass = computed(() => {
  if (!restartWatchMatchesSelection.value || restartWatch.status === 'idle') {
    return 'restart-idle-notice'
  }
  if (restartWatch.status === 'pending') {
    return 'restart-pending-notice'
  }
  if (restartWatch.status === 'confirmed') {
    return 'success-notice'
  }
  return 'danger-notice'
})
const restartWatchHeadline = computed(() => {
  if (!restartWatchMatchesSelection.value || restartWatch.status === 'idle') {
    return '尚未发起重启验证'
  }
  if (restartWatch.status === 'pending') {
    return '正在等待节点完成重启'
  }
  if (restartWatch.status === 'confirmed') {
    return '已确认后端完成重启'
  }
  if (restartWatch.status === 'timeout') {
    return '重启验证超时'
  }
  return '重启指令发送失败'
})
const restartWatchDescription = computed(() => {
  if (!restartWatchMatchesSelection.value || restartWatch.status === 'idle') {
    return '点击下方重启后，这里会记录基线 startupTime、uptime 和命令号，并自动比对新的启动时间与回连事件。'
  }
  return restartWatch.note || '等待更多重启证据。'
})
const restartWatchCommandText = computed(() => {
  if (!restartWatchMatchesSelection.value || !restartWatch.commandId) {
    return '未发起'
  }
  return restartWatch.commandId
})
const restartWatchRequestedAtText = computed(() => {
  if (!restartWatchMatchesSelection.value || !restartWatch.requestedAt) {
    return '发送重启后自动开始比对'
  }
  return `请求于 ${formatTime(restartWatch.requestedAt)}`
})
const restartBaselineStartupText = computed(() => formatTime(
  restartWatchMatchesSelection.value && restartWatch.baselineStartupTime
    ? restartWatch.baselineStartupTime
    : selectedSummary.value?.startupTime
))
const restartBaselineUptimeText = computed(() => {
  if (!restartWatchMatchesSelection.value || restartWatch.status === 'idle') {
    return selectedSummary.value ? `当前运行时长 ${formatDuration(selectedSummary.value.uptimeSeconds)}` : '等待节点上线'
  }
  return `基线运行时长 ${formatDuration(restartWatch.baselineUptimeSeconds)}`
})
const restartCurrentStartupText = computed(() => formatTime(
  selectedSummary.value?.startupTime || (restartWatchMatchesSelection.value ? restartWatch.latestStartupTime : '')
))
const restartCurrentUptimeText = computed(() => selectedSummary.value
  ? `当前运行时长 ${formatDuration(selectedSummary.value.uptimeSeconds)}`
  : '等待节点重新上报')
const restartHeartbeatText = computed(() => formatTime(selectedSummary.value?.lastHeartbeat))
const restartElapsedText = computed(() => {
  if (!restartWatchMatchesSelection.value || !restartWatch.requestedAt) {
    return '尚未开始重启验证'
  }
  const requestedAtMs = parseDateValue(restartWatch.requestedAt)?.getTime()
  if (!requestedAtMs) {
    return '尚未开始重启验证'
  }
  return `已观察 ${formatDuration(Math.max(0, Math.floor((liveNow.value.getTime() - requestedAtMs) / 1000)))}`
})
const commandPhaseText = computed(() => {
  if (commandPhase.value === 'queued') {
    return '已发送等待回执'
  }
  if (commandPhase.value === 'success') {
    return '执行回执成功'
  }
  if (commandPhase.value === 'failed') {
    return '执行失败'
  }
  return '待发送'
})
const commandPhaseClass = computed(() => {
  if (commandPhase.value === 'queued') {
    return 'queued'
  }
  if (commandPhase.value === 'success') {
    return 'success'
  }
  if (commandPhase.value === 'failed') {
    return 'danger'
  }
  return 'idle'
})
const tcpStatus = computed(() => {
  const summary = selectedSummary.value
  if (!summary) {
    return { label: '无选中节点', detail: '选择节点后展示 TCP 链路画像', badge: 'neutral', badgeText: '待选择' }
  }
  if (summary.status === 'OFFLINE') {
    return { label: '连接已断开', detail: '节点当前离线，未检测到有效的 TCP 心跳。', badge: 'offline', badgeText: 'offline' }
  }
  if (summary.isStale) {
    return { label: '连接不稳定', detail: `最后心跳距今 ${summary.ageText}，可能存在阻塞或网络抖动。`, badge: 'stale', badgeText: 'stale' }
  }
  if (summary.networkLatency > 200) {
    return { label: '高延迟链路', detail: `当前 RTT ${formatLatency(summary.networkLatency)}，建议排查链路拥塞与重传。`, badge: 'stale', badgeText: 'degraded' }
  }
  return { label: '连接稳定', detail: `当前 RTT ${formatLatency(summary.networkLatency)}，心跳和吞吐均在可接受范围。`, badge: 'online', badgeText: 'healthy' }
})
const selectedRiskItems = computed(() => {
  const summary = selectedSummary.value
  if (!summary) {
    return [] as string[]
  }
  const items: string[] = []
  if (summary.status === 'OFFLINE') {
    items.push('服务已离线，业务请求不可达。')
  } else if (summary.isStale) {
    items.push('心跳已接近超时窗口，需要立即排查。')
  }
  if (summary.cpuUsage >= 90) {
    items.push(`CPU ${formatPercent(summary.cpuUsage)}，已超过 90% 告警线。`)
  }
  if (summary.memoryUsage >= 85) {
    items.push(`内存 ${formatPercent(summary.memoryUsage)}，存在 OOM 风险。`)
  }
  if (summary.diskUsage >= 90) {
    items.push(`磁盘 ${formatPercent(summary.diskUsage)}，需要清理日志或文件。`)
  }
  if (summary.networkLatency > 200) {
    items.push(`网络延迟 ${formatLatency(summary.networkLatency)}，已超过 200 ms。`)
  }
  if (items.length === 0) {
    items.push('当前节点未命中高优先级风险阈值。')
  }
  return items
})
const threadTopologySegments = computed(() => {
  const total = Math.max(1, Number(selectedSummary.value?.threadCount ?? jvmSummary.value.threadCount ?? 0))
  const daemon = Math.min(total, Math.max(0, Number(jvmSummary.value.daemonThreadCount ?? 0)))
  const gc = Math.min(Math.max(total - daemon, 0), Math.max(1, Math.round(total * 0.08)))
  const io = Math.min(Math.max(total - daemon - gc, 0), Math.max(1, Math.round(total * 0.22)))
  const worker = Math.max(total - daemon - gc - io, 0)

  return [
    { label: '业务线程', value: worker, percent: ratioPercent(worker, total), color: '#00c4b4' },
    { label: 'I/O 线程', value: io, percent: ratioPercent(io, total), color: '#38bdf8' },
    { label: '守护线程', value: daemon, percent: ratioPercent(daemon, total), color: '#f59e0b' },
    { label: 'GC/系统', value: gc, percent: ratioPercent(gc, total), color: '#f43f5e' }
  ]
})
const threadTopologyTotal = computed(() => `总计 ${threadTopologySegments.value.reduce((sum, segment) => sum + segment.value, 0)} 线程`)

const formatters = {
  formatPercent,
  formatLatency,
  formatThroughput,
  formatGcPause,
  formatDuration,
  formatHeap,
  meterWidth,
  formatServerType,
  formatTime,
  formatSeverity,
  severityBadgeClass,
  formatLogCategory,
  highlightLog
}

const dashboardServices = computed(() => normalizedServices.value
  .filter((service) => typeFilter.value === 'ALL' || service.serverType === typeFilter.value)
  .sort((left, right) => (left.ageSeconds ?? Number.MAX_SAFE_INTEGER) - (right.ageSeconds ?? Number.MAX_SAFE_INTEGER)))

const dashboardActions = {
  openService,
  setTypeFilter,
  requestCommandForService,
  purgeOfflineService,
  openAccountWorkspace
}

const detailActions = {
  openService,
  goDashboard,
  requestCommand,
  requestCommandForService,
  purgeOfflineService,
  openAccountWorkspace,
  copyJson,
  copyText
}

const dashboardView = computed(() => ({
  socketConnected: socketConnected.value,
  allServices: normalizedServices.value,
  allServicesLength: normalizedServices.value.length,
  dashboardServices: dashboardServices.value,
  categoryOptions: [
    { value: 'ALL', label: '当前监控节点', count: normalizedServices.value.length },
    { value: 'backend', label: '电商后端', count: normalizedServices.value.filter(service => service.serverType === 'backend').length }
  ],
  typeFilter: typeFilter.value,
  averageCpuUsage: averageCpuUsage.value,
  averageMemoryUsage: averageMemoryUsage.value,
  averageDiskUsage: averageDiskUsage.value,
  averageNetworkLatency: averageNetworkLatency.value,
  onlineCount: onlineCount.value,
  staleCount: staleCount.value,
  offlineCount: offlineCount.value,
  filteredServices: filteredServices.value,
  cpuRiskCount: cpuRiskCount.value,
  memoryRiskCount: memoryRiskCount.value,
  diskRiskCount: diskRiskCount.value,
  latencyRiskCount: latencyRiskCount.value,
  totalDisconnectCount: totalDisconnectCount.value,
  lastUpdatedAt: lastUpdatedAt.value,
  dashboardServicesLength: dashboardServices.value.length,
  loading: loading.value,
  errorMessage: errorMessage.value,
  selectedServiceId: selectedServiceId.value,
  ringStyle
}))

const detailView = computed(() => ({
  selectedSummary: selectedSummary.value,
  selectedDetail: selectedDetail.value,
  groupedServices: groupedServices.value,
  filteredServicesLength: filteredServices.value.length,
  loading: loading.value,
  errorMessage: errorMessage.value,
  selectedServiceId: selectedServiceId.value,
  nodeWindow: nodeWindow.value,
  compareWindow: compareWindow.value,
  controlToken: controlToken.value,
  artifactUrl: artifactUrl.value,
  selectedNodeAlerts: selectedNodeAlerts.value,
  selectedRiskItems: selectedRiskItems.value,
  jvmLastGcText: jvmLastGcText.value,
  formatHeapPercent: formatHeapPercent.value,
  heapUsagePercent: heapUsagePercent.value,
  monitorRules: monitorRules.value,
  tcpStatus: tcpStatus.value,
  threadTopologySegments: threadTopologySegments.value,
  threadTopologyTotal: threadTopologyTotal.value,
  jvmSummary: jvmSummary.value,
  logs: logs.value,
  restartWatchBadgeClass: restartWatchBadgeClass.value,
  restartWatchStatusText: restartWatchStatusText.value,
  restartWatchCommandText: restartWatchCommandText.value,
  restartWatchRequestedAtText: restartWatchRequestedAtText.value,
  restartBaselineStartupText: restartBaselineStartupText.value,
  restartBaselineUptimeText: restartBaselineUptimeText.value,
  restartCurrentStartupText: restartCurrentStartupText.value,
  restartCurrentUptimeText: restartCurrentUptimeText.value,
  restartHeartbeatText: restartHeartbeatText.value,
  restartElapsedText: restartElapsedText.value,
  restartWatchNoticeClass: restartWatchNoticeClass.value,
  restartWatchHeadline: restartWatchHeadline.value,
  restartWatchDescription: restartWatchDescription.value,
  currentRestartEvidence: currentRestartEvidence.value,
  commandBusy: commandBusy.value,
  commandPhaseClass: commandPhaseClass.value,
  commandPhaseText: commandPhaseText.value,
  commandAccepted: commandAccepted.value,
  commandMessage: commandMessage.value,
  lastCommandPayload: lastCommandPayload.value,
  lastCommandResult: lastCommandResult.value,
  lastCommandAt: lastCommandAt.value,
  ringStyle,
  latencyToPercent,
  threadSegmentStyle,
  setCpuLiveCanvas,
  setMemoryLiveCanvas,
  setNetworkLiveCanvas,
  setDiskLiveCanvas,
  setComparisonCpuCanvas,
  setComparisonMemoryCanvas
}))

function createRestartWatchState(): RestartWatchState {
  return {
    status: 'idle',
    serviceId: '',
    serviceName: '',
    commandId: '',
    requestedAt: '',
    baselineStartupTime: '',
    latestStartupTime: '',
    baselineUptimeSeconds: 0,
    baselineHeartbeat: '',
    confirmedAt: '',
    note: ''
  }
}

function emptyRestartEvidence(): RestartEvidence {
  return {
    commandAck: false,
    disconnectSeen: false,
    registerSeen: false,
    startupChanged: false,
    uptimeReset: false
  }
}

function buildGroup(type: string) {
  return {
    type,
    services: [] as ReturnType<typeof normalizeSummary>[],
    online: 0,
    alerting: 0
  }
}

function ringStyle(value: number, color: string) {
  const safeValue = Math.max(0, Math.min(100, Number(value ?? 0)))
  return {
    '--progress': `${safeValue}%`,
    '--orbit-color': color
  }
}

function threadSegmentStyle(segment: { percent: number; color: string }) {
  return {
    width: `${Math.max(segment.percent, 6)}%`,
    background: segment.color
  }
}

function latencyToPercent(value?: number) {
  const latency = Math.max(0, Number(value ?? 0))
  if (!latency) {
    return 0
  }
  return Math.min(100, latency / 4)
}

function sliceSeriesByWindow<T extends { timestamp?: string }>(samples: T[], window: LiveWindow) {
  const limit = window === '1m'
    ? 6
    : window === '5m'
      ? 10
      : window === '15m'
        ? 20
        : 40
  return samples.slice(-limit)
}

function averageMetric<T>(items: T[], selector: (item: T) => number) {
  if (!items.length) {
    return 0
  }
  const total = items.reduce((sum, item) => sum + Number(selector(item) || 0), 0)
  return total / items.length
}

function sanitizeServices(items: ServiceSummary[]) {
  const uniqueServices = new Map<string, ServiceSummary>()
  items.forEach((service) => {
    const key = String(service.serverId || service.id || `${service.serviceName || 'unknown'}@${service.host || '127.0.0.1'}:${service.port || 0}`)
    uniqueServices.set(key, service)
  })
  return Array.from(uniqueServices.values())
}

function inferServerType(service: ServiceSummary) {
  const rawType = String(service.serverType || '').trim().toLowerCase()
  const signature = `${service.serviceName || ''} ${service.serverId || ''}`.toLowerCase()
  if (signature.includes('ecommerce-backend') || signature.includes('ecommerce') || signature.includes('backend')) return 'backend'
  const normalizedSource = rawType || signature
  if (normalizedSource.includes('product')) return 'product'
  if (normalizedSource.includes('order')) return 'order'
  if (normalizedSource.includes('cart')) return 'cart'
  if (normalizedSource.includes('user')) return 'user'
  if (normalizedSource.includes('ecommerce') || normalizedSource.includes('backend') || normalizedSource.includes('monolith')) return 'backend'
  if (signature.includes('product')) return 'product'
  if (signature.includes('order')) return 'order'
  if (signature.includes('cart')) return 'cart'
  if (signature.includes('user')) return 'user'
  if (signature.includes('ecommerce') || signature.includes('backend')) return 'backend'
  return 'unknown'
}

function normalizeSummary(service: ServiceSummary) {
  const id = service.serverId ?? service.id ?? ''
  const lastHeartbeat = service.lastHeartbeat ?? ''
  const heartbeatAt = parseDateValue(lastHeartbeat)?.getTime() ?? 0
  const ageSeconds = heartbeatAt ? Math.max(0, Math.floor((liveNow.value.getTime() - heartbeatAt) / 1000)) : null
  const status = service.status ?? 'OFFLINE'
  const isStale = ageSeconds !== null && ageSeconds > staleThresholdSeconds && status !== 'OFFLINE'
  const alertCount = alerts.value.filter(alert => alert.serverId === id && !alert.acknowledged).length

  return {
    ...service,
    id,
    serviceName: service.serviceName || id || '未知服务',
    serverType: inferServerType(service),
    host: service.host || '127.0.0.1',
    port: service.port || 0,
    status,
    cpuUsage: Number(service.cpuUsage ?? 0),
    memoryUsage: Number(service.memoryUsage ?? 0),
    jvmHeapUsage: Number(service.jvmHeapUsage ?? 0),
    diskUsage: Number(service.diskUsage ?? 0),
    networkLatency: Number(service.networkLatency ?? 0),
    networkThroughputMbps: Number(service.networkThroughputMbps ?? 0),
    threadCount: Number(service.threadCount ?? 0),
    gcCount: Number(service.gcCount ?? 0),
    gcPauseMs: Number(service.gcPauseMs ?? 0),
    connectionClosedCount: Number(service.connectionClosedCount ?? 0),
    uptimeSeconds: Number(service.uptimeSeconds ?? 0),
    ageSeconds,
    isStale,
    alertCount,
    healthLabel: status === 'OFFLINE' ? '离线' : isStale ? '超时' : '在线',
    ageText: formatAge(ageSeconds)
  }
}

function extractAccountKey(service: ReturnType<typeof normalizeSummary> | ServiceSummary | null) {
  if (!service) {
    return ''
  }
  const serviceName = String(service.serviceName || '').trim()
  const directMatch = serviceName.match(/^账号节点\s+(.+)$/)
  if (directMatch?.[1]) {
    return directMatch[1].trim()
  }
  const logText = String(service.log || '')
  const logMatch = logText.match(/Account\s+(.+?)\s+(?:online|offline|mapped)/i)
  if (logMatch?.[1]) {
    return logMatch[1].trim()
  }
  return ''
}

function buildAccountWorkspaceUrl(service: ReturnType<typeof normalizeSummary> | ServiceSummary | null) {
  if (!service) {
    return ''
  }
  const port = Number(service.port || 0)
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    return ''
  }
  const url = new URL(shopFrontBase)
  url.pathname = '/login'
  url.searchParams.set('backendPort', String(port))
  url.searchParams.set('monitorPort', String(port))
  url.searchParams.set('backendHost', String(service.host || '127.0.0.1'))
  const accountKey = extractAccountKey(service)
  if (accountKey) {
    url.searchParams.set('account', accountKey)
  }
  return url.toString()
}

function openAccountWorkspace(serviceId: string) {
  const targetSummary = normalizedServices.value.find(service => service.id === serviceId) ?? selectedSummary.value
  if (!targetSummary) {
    pushToast('未找到目标账号节点', 'danger')
    return
  }
  const targetUrl = buildAccountWorkspaceUrl(targetSummary)
  if (!targetUrl) {
    pushToast('该节点未绑定有效端口，无法打开独立页面', 'danger')
    return
  }
  window.open(targetUrl, '_blank')
}

function ensureSelection() {
  if (props.mode === 'detail' && props.routeServiceId) {
    selectedServiceId.value = props.routeServiceId
    return
  }
  if (!selectedServiceId.value && filteredServices.value.length) {
    const firstService = filteredServices.value[0]
    if (firstService) {
      selectedServiceId.value = firstService.id
    }
  }
  if (selectedServiceId.value && !normalizedServices.value.some(service => service.id === selectedServiceId.value) && filteredServices.value.length) {
    const firstService = filteredServices.value[0]
    if (firstService) {
      selectedServiceId.value = firstService.id
    }
  }
}

function beginRestartWatch(service: ReturnType<typeof normalizeSummary>, commandId?: string) {
  Object.assign(restartWatch, createRestartWatchState(), {
    status: 'pending',
    serviceId: service.id,
    serviceName: service.serviceName,
    commandId: commandId || '',
    requestedAt: new Date().toISOString(),
    baselineStartupTime: service.startupTime || '',
    latestStartupTime: service.startupTime || '',
    baselineUptimeSeconds: Number(service.uptimeSeconds ?? 0),
    baselineHeartbeat: service.lastHeartbeat || '',
    note: '重启命令已提交，等待 monitor agent 执行本地重启脚本并重新上线。'
  })
}

function markRestartWatchFailed(message: string) {
  Object.assign(restartWatch, createRestartWatchState(), {
    status: 'failed',
    serviceId: selectedSummary.value?.id || '',
    serviceName: selectedSummary.value?.serviceName || '',
    requestedAt: new Date().toISOString(),
    baselineStartupTime: selectedSummary.value?.startupTime || '',
    latestStartupTime: selectedSummary.value?.startupTime || '',
    baselineUptimeSeconds: Number(selectedSummary.value?.uptimeSeconds ?? 0),
    baselineHeartbeat: selectedSummary.value?.lastHeartbeat || '',
    note: message
  })
}

function collectRestartEvidence(
  summary: ReturnType<typeof normalizeSummary> | null,
  logItems: LogEntry[],
  watchState: RestartWatchState
): RestartEvidence {
  if (!summary || !watchState.requestedAt) {
    return emptyRestartEvidence()
  }
  const requestedAtMs = parseDateValue(watchState.requestedAt)?.getTime() ?? 0
  const relatedLogs = logItems.filter(log => (parseDateValue(log.timestamp)?.getTime() ?? 0) >= requestedAtMs - 2000)
  const currentUptime = Number(summary.uptimeSeconds ?? 0)
  const baselineUptime = Number(watchState.baselineUptimeSeconds ?? 0)
  return {
    commandAck: relatedLogs.some(log => log.category === 'COMMAND_ACK' && /restart|重启/i.test(log.message)),
    disconnectSeen: relatedLogs.some(log => ['DISCONNECT', 'OFFLINE', 'TIMEOUT'].includes(log.category)),
    registerSeen: relatedLogs.some(log => log.category === 'REGISTER'),
    startupChanged: hasStartupChanged(watchState.baselineStartupTime, summary.startupTime || watchState.latestStartupTime, watchState.requestedAt),
    uptimeReset: baselineUptime > 0 ? currentUptime + 15 < baselineUptime : currentUptime >= 0 && currentUptime < 30 && Date.now() - requestedAtMs > 3000
  }
}

function hasStartupChanged(baselineStartupTime?: string, currentStartupTime?: string, requestedAt?: string) {
  const currentMs = parseDateValue(currentStartupTime)?.getTime() ?? 0
  if (!currentMs) {
    return false
  }
  const baselineMs = parseDateValue(baselineStartupTime)?.getTime() ?? 0
  if (baselineMs) {
    return currentMs > baselineMs + 1000
  }
  const requestedMs = parseDateValue(requestedAt)?.getTime() ?? 0
  return requestedMs ? currentMs >= requestedMs - 5000 : false
}

function buildRestartConfirmedNote(summary: ReturnType<typeof normalizeSummary> | null, evidence: RestartEvidence) {
  const startupText = formatTime(summary?.startupTime || restartWatch.latestStartupTime)
  if (evidence.startupChanged) {
    return `检测到新的 startupTime：${startupText}，可以确认当前节点已经完成重启。`
  }
  if (evidence.uptimeReset) {
    return `当前 uptime 已明显回落到 ${formatDuration(summary?.uptimeSeconds)}，说明后端进程已经重拉。`
  }
  return `已看到断连与重新注册事件，节点当前最新心跳为 ${formatTime(summary?.lastHeartbeat)}。`
}

function evaluateRestartWatch() {
  if (restartWatch.status === 'idle' || !restartWatch.serviceId) {
    return
  }
  const currentSummary = normalizedServices.value.find(service => service.id === restartWatch.serviceId) ?? null
  if (currentSummary?.startupTime) {
    restartWatch.latestStartupTime = currentSummary.startupTime
  }
  if (restartWatch.status === 'failed') {
    return
  }

  const detailLogs = restartWatch.serviceId === selectedServiceId.value ? (selectedDetail.value?.logs ?? []) : []
  const evidence = collectRestartEvidence(currentSummary, detailLogs, restartWatch)
  const requestedAtMs = parseDateValue(restartWatch.requestedAt)?.getTime() ?? 0

  if (currentSummary && (evidence.startupChanged || evidence.uptimeReset || (evidence.disconnectSeen && evidence.registerSeen))) {
    restartWatch.status = 'confirmed'
    restartWatch.confirmedAt = restartWatch.confirmedAt || new Date().toISOString()
    restartWatch.note = buildRestartConfirmedNote(currentSummary, evidence)
    return
  }

  if (restartWatch.status === 'pending' && requestedAtMs && Date.now() - requestedAtMs > restartWatchMaxWaitMs) {
    restartWatch.status = 'timeout'
    restartWatch.note = '90 秒内未检测到新的 startupTime 或 uptime 重置，请检查后端启动日志、端口恢复和 monitor agent 回连。'
    return
  }

  if (restartWatch.status === 'pending') {
    if (currentSummary?.status === 'OFFLINE' || evidence.disconnectSeen) {
      restartWatch.note = '节点已断连，正在等待新进程回连并重新注册。'
      return
    }
    if (evidence.commandAck) {
      restartWatch.note = '已收到重启回执，等待 monitor agent 上报新的 startupTime。'
      return
    }
    restartWatch.note = '重启命令已发送，等待 monitor-server 收到节点新的心跳与启动时间。'
  }
}

async function fetchJson<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${apiBase}${path}`, init)
  if (!response.ok) {
    const rawBody = await response.text()
    let reason = ''
    if (rawBody) {
      try {
        const parsed = JSON.parse(rawBody) as Record<string, unknown>
        reason = String(parsed.status || parsed.message || '').trim()
      } catch {
        reason = rawBody.trim()
      }
    }
    const suffix = reason ? `: ${reason}` : ''
    const error = new Error(`HTTP ${response.status}${suffix}`) as Error & { status?: number; detail?: string }
    error.status = response.status
    error.detail = reason
    throw error
  }
  return response.json() as Promise<T>
}

async function loadOverview() {
  try {
    const overview = await fetchJson<OverviewResponse>('/api/monitor/overview')
    services.value = sanitizeServices(Array.isArray(overview.services) ? overview.services : [])
    alerts.value = Array.isArray(overview.alerts) ? overview.alerts : []
    lastUpdatedAt.value = overview.timestamp || new Date().toISOString()
    pushFleetPoint(services.value, lastUpdatedAt.value)
  } catch (error) {
    const status = error instanceof Error && 'status' in error ? Number((error as Error & { status?: number }).status) : 0
    if (status !== 404) {
      throw error
    }

    const legacyServices = await fetchJson<ServiceSummary[]>('/api/monitor/list')
  services.value = sanitizeServices(Array.isArray(legacyServices) ? legacyServices : [])
    alerts.value = []
    lastUpdatedAt.value = new Date().toISOString()
    pushFleetPoint(services.value, lastUpdatedAt.value)
  }

  apiReachable.value = true
  errorMessage.value = ''
  seedLiveSeries(services.value)
  ensureSelection()
}

async function loadDetail(serviceId = selectedServiceId.value) {
  if (!serviceId) {
    selectedDetail.value = null
    return
  }
  detailLoading.value = true
  try {
    selectedDetail.value = await fetchJson<ServiceDetail>(`/api/monitor/detail/${encodeURIComponent(serviceId)}`)
    seedLiveSeriesFromDetail(serviceId, selectedDetail.value?.metricsHistory || [])
    errorMessage.value = ''
    apiReachable.value = true
  } catch (error) {
    selectedDetail.value = null
    errorMessage.value = error instanceof Error ? `节点详情加载失败。${error.message}` : '节点详情加载失败。'
  } finally {
    detailLoading.value = false
  }
}

async function loadEverything() {
  loading.value = true
  try {
    await loadOverview()
    if (selectedServiceId.value) {
      await loadDetail(selectedServiceId.value)
    }
    nextRetryDelayMs.value = refreshIntervalMs.value || 5000
  } catch (error) {
    services.value = []
    selectedDetail.value = null
    apiReachable.value = false
    nextRetryDelayMs.value = Math.min(nextRetryDelayMs.value * 2, 30000)
    errorMessage.value = error instanceof Error ? `无法连接 monitor-server。${error.message}` : '无法连接 monitor-server。'
  } finally {
    loading.value = false
    restartPolling()
  }
}

function seedLiveSeries(items: ServiceSummary[]) {
  items.forEach((service) => {
    const id = service.serverId || service.id || ''
    if (!id) {
      return
    }
    pushLivePoint(id, {
      timestamp: new Date().toISOString(),
      cpuUsage: Number(service.cpuUsage ?? 0),
      memoryUsage: Number(service.memoryUsage ?? 0),
      jvmHeapUsage: Number(service.jvmHeapUsage ?? 0),
      diskUsage: Number(service.diskUsage ?? 0),
      networkLatency: Number(service.networkLatency ?? 0),
      networkThroughputMbps: Number(service.networkThroughputMbps ?? 0),
      threadCount: Number(service.threadCount ?? 0),
      gcCount: Number(service.gcCount ?? 0),
      gcPauseMs: Number(service.gcPauseMs ?? 0),
      status: String(service.status ?? 'OFFLINE')
    })
  })
}

function seedLiveSeriesFromDetail(serviceId: string, samples: MetricSample[]) {
  if (!serviceId) {
    return
  }
  const history = samples.slice(-20)
  const existing = Array.isArray(liveSeries[serviceId]) ? liveSeries[serviceId] : []
  const merged = [...history]
  existing.forEach((sample) => {
    if (!merged.some(item => item.timestamp === sample.timestamp)) {
      merged.push(sample)
    }
  })
  merged.sort((left, right) => (parseDateValue(left.timestamp)?.getTime() ?? 0) - (parseDateValue(right.timestamp)?.getTime() ?? 0))
  liveSeries[serviceId] = merged.slice(-40)
}

function pushLivePoint(serviceId: string, sample: MetricSample) {
  if (!serviceId) {
    return
  }
  if (!Array.isArray(liveSeries[serviceId])) {
    liveSeries[serviceId] = []
  }
  liveSeries[serviceId].push(sample)
  if (liveSeries[serviceId].length > 40) {
    liveSeries[serviceId].shift()
  }
}

function handleSnapshot(payload: { services?: ServiceSummary[]; alerts?: AlertRecord[] }) {
  if (!Array.isArray(payload.services)) {
    return
  }
  services.value = sanitizeServices(payload.services)
  if (Array.isArray(payload.alerts)) {
    alerts.value = payload.alerts
  }
  lastUpdatedAt.value = new Date().toISOString()
  apiReachable.value = true
  seedLiveSeries(services.value)
  pushFleetPoint(services.value, lastUpdatedAt.value)
  ensureSelection()
  if (selectedServiceId.value) {
    loadDetail(selectedServiceId.value).catch(() => undefined)
  }
}

function pushFleetPoint(snapshotServices: ServiceSummary[], timestamp = new Date().toISOString()) {
  fleetSeries.value.push({
    timestamp,
    cpuUsage: averageMetric(snapshotServices, service => Number(service.cpuUsage ?? 0)),
    memoryUsage: averageMetric(snapshotServices, service => Number(service.memoryUsage ?? 0)),
    networkLatency: averageMetric(snapshotServices, service => Number(service.networkLatency ?? 0)),
    diskUsage: averageMetric(snapshotServices, service => Number(service.diskUsage ?? 0))
  })
  if (fleetSeries.value.length > 40) {
    fleetSeries.value.shift()
  }
}

function connectSocket() {
  closeSocket()
  try {
    socket = new WebSocket(wsBase)
    socket.onopen = () => {
      socketConnected.value = true
      socket?.send('SNAPSHOT')
    }
    socket.onmessage = (event) => {
      if (typeof event.data !== 'string' || event.data === 'PONG') {
        return
      }
      try {
        const payload = JSON.parse(event.data)
        if (payload.type === 'snapshot') {
          handleSnapshot(payload)
        }
      } catch {
        errorMessage.value = '监控推送消息解析失败'
      }
    }
    socket.onclose = () => {
      socketConnected.value = false
      scheduleReconnect()
    }
    socket.onerror = () => {
      socketConnected.value = false
    }
  } catch {
    socketConnected.value = false
    scheduleReconnect()
  }
}

function closeSocket() {
  if (reconnectTimer) {
    window.clearTimeout(reconnectTimer)
    reconnectTimer = null
  }
  if (socket) {
    socket.close()
    socket = null
  }
}

function scheduleReconnect() {
  if (reconnectTimer) {
    window.clearTimeout(reconnectTimer)
  }
  reconnectTimer = window.setTimeout(connectSocket, 4000)
}

function restartPolling() {
  if (pollingTimer) {
    window.clearInterval(pollingTimer)
    pollingTimer = null
  }
  const delay = apiReachable.value ? refreshIntervalMs.value : nextRetryDelayMs.value
  if (delay > 0) {
    pollingTimer = window.setInterval(() => {
      if (!socketConnected.value) {
        loadEverything().catch(() => undefined)
      }
    }, delay)
  }
}

function openService(serviceId: string) {
  selectedServiceId.value = serviceId
  selectedDetail.value = null
  router.push({ path: `/node/${encodeURIComponent(serviceId)}` })
}

function goDashboard() {
  router.push({ name: 'dashboard' })
}

function goSelectedDetail() {
  if (!selectedServiceId.value) {
    return
  }
  router.push({ path: `/node/${encodeURIComponent(selectedServiceId.value)}` })
}

function requestCommand(action: string) {
  pendingCommandServiceId.value = selectedServiceId.value || ''
  confirmAction.value = action
}

function requestCommandForService(serviceId: string, action: string) {
  if (!serviceId) {
    return
  }
  pendingCommandServiceId.value = serviceId
  selectedServiceId.value = serviceId
  confirmAction.value = action
}

function cancelConfirm() {
  confirmAction.value = ''
  pendingCommandServiceId.value = ''
}

async function sendCommand(action: string) {
  const targetServiceId = pendingCommandServiceId.value || selectedServiceId.value
  const targetSummary = normalizedServices.value.find(service => service.id === targetServiceId) ?? selectedSummary.value
  if (!targetSummary) {
    return
  }
  confirmAction.value = ''
  commandBusy.value = true
  commandAccepted.value = false
  commandMessage.value = ''
  commandPhase.value = 'queued'
  const payload = {
    serverId: targetSummary.id,
    action,
    token: controlToken.value,
    artifactUrl: action === 'UPGRADE' ? artifactUrl.value : '',
    args: {
      host: String(targetSummary.host || '127.0.0.1'),
      port: String(Number(targetSummary.port || 0)),
      serviceName: String(targetSummary.serviceName || ''),
      serverType: String(targetSummary.serverType || 'backend'),
      serviceId: String(targetSummary.id || ''),
      accountKey: extractAccountKey(targetSummary)
    }
  }
  lastCommandPayload.value = JSON.stringify(payload, null, 2)
  lastCommandResult.value = ''
  lastCommandAt.value = new Date().toISOString()
  try {
    const result = await fetchJson<{ accepted: boolean; status: string; commandId?: string }>(
      '/api/monitor/command',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      }
    )
    if (action === 'RESTART' && result.accepted) {
      beginRestartWatch(targetSummary, result.commandId)
    }
    commandAccepted.value = !!result.accepted
    commandPhase.value = result.accepted ? 'success' : 'failed'
    commandMessage.value = result.accepted
      ? formatCommandStatusMessage(action, result.status, result.commandId)
      : `${formatAction(action)}失败，状态 ${result.status || 'UNKNOWN'}`
    lastCommandResult.value = JSON.stringify(result, null, 2)
    pushToast(commandAccepted.value ? '控制指令已发送' : '控制指令失败', commandAccepted.value ? 'success' : 'danger')
    await loadEverything()
  } catch (error) {
    commandAccepted.value = false
    commandPhase.value = 'failed'
    commandMessage.value = error instanceof Error ? error.message : '指令发送失败'
    lastCommandResult.value = JSON.stringify({ error: commandMessage.value, action }, null, 2)
    if (action === 'RESTART') {
      markRestartWatchFailed(commandMessage.value)
    }
    pushToast('控制指令失败', 'danger')
  } finally {
    pendingCommandServiceId.value = ''
    commandBusy.value = false
  }
}

async function purgeOfflineService(serviceId: string) {
  if (!serviceId) {
    return
  }
  const targetSummary = normalizedServices.value.find(service => service.id === serviceId) ?? null
  if (!targetSummary) {
    pushToast('未找到目标节点', 'danger')
    return
  }
  if (targetSummary.status !== 'OFFLINE') {
    pushToast('仅允许清理离线节点的历史记录', 'danger')
    return
  }

  const confirmed = window.confirm(`确认清理 ${targetSummary.serviceName} 的历史记录吗？该操作会移除该节点在监控列表中的离线残留。`)
  if (!confirmed) {
    return
  }

  commandBusy.value = true
  try {
    const result = await fetchJson<{ accepted: boolean; status: string; serverId?: string }>(
      '/api/monitor/service/purge',
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Monitor-Token': controlToken.value
        },
        body: JSON.stringify({
          serverId: targetSummary.id,
          token: controlToken.value
        })
      }
    )
    if (!result.accepted) {
      throw new Error(`清理失败，状态 ${result.status || 'UNKNOWN'}`)
    }
    if (selectedServiceId.value === targetSummary.id) {
      selectedServiceId.value = ''
      selectedDetail.value = null
      if (route.name === 'detail') {
        router.push({ name: 'dashboard' })
      }
    }
    pushToast(`已清理 ${targetSummary.serviceName} 的历史记录`, 'success')
    await loadEverything()
  } catch (error) {
    const message = error instanceof Error ? error.message : '清理失败'
    pushToast(message, 'danger')
  } finally {
    commandBusy.value = false
  }
}

async function addMonitorPort() {
  const accountKey = addPortAccountKey.value.trim()
  if (!accountKey) {
    pushToast('请先输入账号标识（一个账号对应一个节点）', 'danger')
    return
  }

  const port = Number(addPortNumber.value || 0)
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    pushToast('请输入 1 到 65535 的端口号', 'danger')
    return
  }

  addPortSubmitting.value = true
  try {
    const result = await fetchJson<{ accepted: boolean; status: string; serverId?: string; port?: number }>(
      '/api/monitor/service/add-port',
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Monitor-Token': controlToken.value
        },
        body: JSON.stringify({
          accountKey,
          port,
          host: '127.0.0.1',
          serviceName: `账号节点 ${accountKey}`,
          serverType: 'backend',
          token: controlToken.value
        })
      }
    )

    if (!result.accepted) {
      throw new Error(`新增端口失败，状态 ${result.status || 'UNKNOWN'}`)
    }

    await loadEverything()
    if (result.serverId) {
      selectedServiceId.value = result.serverId
    }
    pushToast(`端口 ${result.port || port} 已加入监控列表`, 'success')
  } catch (error) {
    pushToast(error instanceof Error ? error.message : '新增端口失败', 'danger')
  } finally {
    addPortSubmitting.value = false
  }
}

function exportCsv() {
  const headers = ['serverId', 'serviceName', 'serverType', 'status', 'host', 'port', 'cpuUsage', 'memoryUsage', 'diskUsage', 'networkLatency', 'networkThroughputMbps', 'threadCount', 'gcCount', 'lastHeartbeat']
  const rows = filteredServices.value.map(service => headers.map(header => csvEscape(String((service as Record<string, unknown>)[header] ?? ''))).join(','))
  const payload = [headers.join(','), ...rows].join('\n')
  const blob = new Blob(['\ufeff' + payload], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `monitor-report-${Date.now()}.csv`
  link.click()
  URL.revokeObjectURL(url)
}

function resolveMonitorApiBase() {
  const fromEnv = import.meta.env.VITE_MONITOR_API_BASE?.trim()
  if (fromEnv) {
    return fromEnv.replace(/\/+$/, '')
  }
  const protocol = window.location.protocol === 'https:' ? 'https:' : 'http:'
  return `${protocol}//${window.location.hostname}:9091`
}

function resolveMonitorWsBase(currentApiBase: string) {
  const fromEnv = import.meta.env.VITE_MONITOR_WS_BASE?.trim()
  if (fromEnv) {
    return fromEnv.replace(/\/+$/, '')
  }
  const normalizedApiBase = currentApiBase.replace(/\/+$/, '')
  const wsProtocol = normalizedApiBase.startsWith('https://') ? 'wss://' : 'ws://'
  const hostPart = normalizedApiBase.replace(/^https?:\/\//, '')
  return `${wsProtocol}${hostPart}/ws/monitor`
}

function resolveShopFrontBase() {
  const fromEnv = import.meta.env.VITE_SHOP_FRONT_BASE?.trim()
  if (fromEnv) {
    return fromEnv.replace(/\/+$/, '')
  }
  return 'http://127.0.0.1:5173'
}

function loadThemeMode(): 'dark' | 'light' {
  try {
    const saved = window.localStorage.getItem('monitor-console-theme')
    return saved === 'light' ? 'light' : 'dark'
  } catch {
    return 'dark'
  }
}

function toggleTheme() {
  themeMode.value = themeMode.value === 'dark' ? 'light' : 'dark'
}

function pushToast(message: string, type: 'success' | 'danger') {
  toastMessage.value = message
  toastType.value = type
  if (toastTimer) {
    window.clearTimeout(toastTimer)
  }
  toastTimer = window.setTimeout(() => {
    toastMessage.value = ''
  }, 2600)
}

function renderLineChart(key: string, canvas: HTMLCanvasElement | null, samples: MetricSample[], field: keyof MetricSample, color: string, maxOverride = 100, suffix = '%') {
  if (!canvas) {
    return
  }
  const labels = samples.map(item => formatMiniTime(item.timestamp))
  const values = samples.map(item => Number(item[field] ?? 0))
  const maxValue = Math.max(maxOverride, ...values, 1)
  const existing = chartMap.get(key)

  if (existing) {
    existing.data.labels = labels
    if (existing.data.datasets[0]) {
      existing.data.datasets[0].data = values
    }
    existing.options.scales = {
      x: { grid: { color: 'rgba(148,163,184,0.08)' }, ticks: { color: '#94A3B8', maxTicksLimit: 6 } },
      y: { min: 0, max: maxValue, grid: { color: 'rgba(148,163,184,0.08)' }, ticks: { color: '#94A3B8' } }
    }
    existing.update('none')
    return
  }

  const context = canvas.getContext('2d')
  if (!context) {
    return
  }
  const gradient = context.createLinearGradient(0, 0, 0, 240)
  gradient.addColorStop(0, `${color}88`)
  gradient.addColorStop(1, `${color}00`)

  const chart = new Chart(canvas, {
    type: 'line',
    data: {
      labels,
      datasets: [{
        data: values,
        borderColor: color,
        backgroundColor: gradient,
        fill: true,
        borderWidth: 2.4,
        pointRadius: 2,
        pointHoverRadius: 3,
        tension: 0.35
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      animation: false,
      plugins: {
        legend: { display: false },
        tooltip: {
          callbacks: {
            label: context => `${Number(context.parsed.y).toFixed(suffix === 'ms' ? 0 : 1)} ${suffix}`
          },
          backgroundColor: 'rgba(15, 23, 42, 0.94)',
          borderColor: 'rgba(148, 163, 184, 0.18)',
          borderWidth: 1
        }
      },
      scales: {
        x: { grid: { color: 'rgba(148,163,184,0.08)' }, ticks: { color: '#94A3B8', maxTicksLimit: 6 } },
        y: { min: 0, max: maxValue, grid: { color: 'rgba(148,163,184,0.08)' }, ticks: { color: '#94A3B8' } }
      }
    }
  })
  chartMap.set(key, chart)
}

function destroyCharts() {
  chartMap.forEach(chart => chart.destroy())
  chartMap.clear()
}

function renderCharts() {
  renderLineChart('cpu-live', cpuLiveCanvas.value, windowedLiveSeries.value, 'cpuUsage', '#00C4B4', 100, '%')
  renderLineChart('memory-live', memoryLiveCanvas.value, windowedLiveSeries.value, 'memoryUsage', '#5EEAD4', 100, '%')
  renderLineChart('network-live', networkLiveCanvas.value, windowedLiveSeries.value, 'networkLatency', '#7DD3FC', 500, 'ms')
  renderLineChart('disk-live', diskLiveCanvas.value, windowedLiveSeries.value, 'diskUsage', '#FF4D4D', 100, '%')
  renderLineChart('cpu-compare', comparisonCpuCanvas.value, comparisonSeries.value, 'cpuUsage', '#F97316', 100, '%')
  renderLineChart('memory-compare', comparisonMemoryCanvas.value, comparisonSeries.value, 'memoryUsage', '#38BDF8', 100, '%')
  renderLineChart('fleet-cpu', fleetCpuCanvas.value, windowedFleetSeries.value, 'cpuUsage', '#2DD4BF', 100, '%')
  renderLineChart('fleet-memory', fleetMemoryCanvas.value, windowedFleetSeries.value, 'memoryUsage', '#34D399', 100, '%')
  renderLineChart('fleet-throughput', fleetThroughputCanvas.value, windowedFleetSeries.value, 'networkLatency', '#38BDF8', 500, 'ms')
  renderLineChart('fleet-alert', fleetAlertCanvas.value, windowedFleetSeries.value, 'diskUsage', '#F97316', 100, '%')
}

function copyText(value: string, successMessage = '内容已复制') {
  if (!value) {
    return
  }
  navigator.clipboard.writeText(value).then(() => {
    pushToast(successMessage, 'success')
  }).catch(() => {
    pushToast('复制失败，请检查剪贴板权限', 'danger')
  })
}

function copyJson(value: unknown) {
  copyText(JSON.stringify(value, null, 2), 'JSON 已复制')
}

function csvEscape(value: string) {
  return `"${value.replace(/"/g, '""')}"`
}

function parseDateValue(value?: unknown) {
  if (value === undefined || value === null || value === '') {
    return null
  }

  if (value instanceof Date) {
    return Number.isNaN(value.getTime()) ? null : value
  }

  if (typeof value === 'number') {
    const timestamp = value < 1_000_000_000_000 ? value * 1000 : value
    const date = new Date(timestamp)
    return Number.isNaN(date.getTime()) ? null : date
  }

  const trimmed = String(value).trim()
  if (!trimmed) {
    return null
  }

  if (/^\d+$/.test(trimmed)) {
    const numeric = Number(trimmed)
    const timestamp = numeric < 1_000_000_000_000 ? numeric * 1000 : numeric
    const date = new Date(timestamp)
    return Number.isNaN(date.getTime()) ? null : date
  }

  const date = new Date(trimmed)
  return Number.isNaN(date.getTime()) ? null : date
}

function formatTime(value?: string | number | null) {
  const date = parseDateValue(value)
  if (!date) {
    return '-'
  }
  const year = date.getFullYear()
  const month = `${date.getMonth() + 1}`.padStart(2, '0')
  const day = `${date.getDate()}`.padStart(2, '0')
  const hour = `${date.getHours()}`.padStart(2, '0')
  const minute = `${date.getMinutes()}`.padStart(2, '0')
  const second = `${date.getSeconds()}`.padStart(2, '0')
  return `${year}-${month}-${day} ${hour}:${minute}:${second}`
}

function ratioPercent(count: number, total: number) {
  if (!total) {
    return 0
  }
  return Math.max(0, Math.min(100, (count / total) * 100))
}

function formatMiniTime(value?: string | number | null) {
  const date = parseDateValue(value)
  if (!date) {
    return '--:--'
  }
  return `${`${date.getHours()}`.padStart(2, '0')}:${`${date.getMinutes()}`.padStart(2, '0')}`
}

function formatAge(seconds: number | null) {
  if (seconds === null || seconds === undefined) {
    return '无心跳'
  }
  if (seconds < 60) {
    return `${seconds}s 前`
  }
  const minutes = Math.floor(seconds / 60)
  const remain = seconds % 60
  return `${minutes}m ${remain}s 前`
}

function formatPercent(value?: number) {
  return `${Number(value ?? 0).toFixed(1)}%`
}

function formatLatency(value?: number) {
  return value && value > 0 ? `${Math.round(value)} ms` : 'N/A'
}

function formatThroughput(value?: number) {
  return `${Number(value ?? 0).toFixed(1)} Mbps`
}

function formatGcPause(value?: number) {
  return `${Number(value ?? 0).toFixed(1)} ms`
}

function formatDuration(seconds?: number) {
  const total = Math.max(0, Math.floor(Number(seconds ?? 0)))
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const remain = total % 60
  return `${hours}h ${minutes}m ${remain}s`
}

function formatHeap(used?: number, max?: number) {
  return `${Number(used ?? 0).toFixed(0)} / ${Number(max ?? 0).toFixed(0)} MB`
}

function meterWidth(value?: number) {
  const safeValue = Math.max(0, Math.min(100, Number(value ?? 0)))
  return `${safeValue}%`
}

function formatServerType(type?: string) {
  if (type === 'ecommerce' || type === 'backend' || type === 'monolith') return '电商后端'
  if (type === 'product') return '产品服务'
  if (type === 'order') return '订单服务'
  if (type === 'user') return '用户服务'
  if (type === 'cart') return '购物车服务'
  if (type === 'monitor') return '监控服务'
  return type || '未知类型'
}

function setTypeFilter(value: string) {
  typeFilter.value = value
}

function formatLogCategory(value?: string) {
  if (value === 'REGISTER') return '注册'
  if (value === 'ALERT') return '告警'
  if (value === 'OFFLINE') return '下线'
  if (value === 'TIMEOUT') return '超时'
  if (value === 'DISCONNECT') return '断开连接'
  if (value === 'COMMAND') return '命令下发'
  if (value === 'COMMAND_ACK') return '命令回执'
  if (value === 'FORCE_OFFLINE') return '强制下线'
  return value || '日志'
}

function formatSeverity(value?: string) {
  const normalized = String(value || '').toUpperCase()
  if (normalized === 'CRITICAL') return '严重'
  if (normalized === 'WARNING') return '警告'
  return normalized || '提示'
}

function severityBadgeClass(value?: string) {
  const normalized = String(value || '').toLowerCase()
  if (normalized === 'critical') {
    return 'offline'
  }
  if (normalized === 'warning') {
    return 'stale'
  }
  return 'online'
}

function formatAction(action: string) {
  if (action === 'RESTART') return '重启后端'
  if (action === 'CLOSE') return '关闭'
  if (action === 'UPGRADE') return '在线升级'
  if (action === 'FORCE_OFFLINE') return '强制下线'
  return action
}

function formatCommandStatusMessage(action: string, status?: string, commandId?: string) {
  if (action === 'RESTART' && status === 'LOCAL_LAUNCHED') {
    return `已触发本地重启脚本，命令号 ${commandId || 'N/A'}，等待后端重新上线。`
  }
  if (action === 'RESTART' && status === 'SENT') {
    return `已向在线节点发送重启命令，命令号 ${commandId || 'N/A'}。`
  }
  if (action === 'RESTART' && status === 'LOCAL_RESTART_SCRIPT_NOT_FOUND') {
    return '重启失败：monitor-server 未找到本地重启脚本，请检查 MONITOR_LOCAL_RESTART_SCRIPT 或工作目录。'
  }
  if (action === 'RESTART' && String(status || '').startsWith('LOCAL_LAUNCH_FAILED')) {
    return `重启失败：${status}`
  }
  if (action === 'RESTART' && status === 'INVALID_TARGET_PORT') {
    return '重启失败：该节点没有有效端口，请先在登录页填写后端端口并完成在线同步。'
  }
  return `${formatAction(action)}已发送，命令号 ${commandId || 'N/A'}`
}

function highlightLog(message?: string) {
  const text = String(message || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  return text.replace(/(error|warn|alert|timeout|disconnect|close|upgrade|restart|offline|failed|exception|critical)/gi, '<mark>$1</mark>')
}

watch(() => props.routeServiceId, (value) => {
  if (props.mode === 'detail') {
    selectedServiceId.value = value || ''
    selectedDetail.value = null
    if (value) {
      loadDetail(value).catch(() => undefined)
    }
  }
}, { immediate: true })
watch(themeMode, (value) => {
  try {
    window.localStorage.setItem('monitor-console-theme', value)
  } catch {
    // ignore theme persistence failures
  }
})
watch(refreshIntervalMs, restartPolling)
watch([selectedServiceId, compareWindow], async () => {
  if (selectedServiceId.value) {
    await loadDetail(selectedServiceId.value)
  }
  await nextTick()
  renderCharts()
})
watch([dashboardWindow, nodeWindow], async () => {
  await nextTick()
  renderCharts()
})
watch([filteredServices, alerts], async () => {
  ensureSelection()
  evaluateRestartWatch()
  await nextTick()
  renderCharts()
}, { deep: true })
watch([selectedMetricsHistory, comparisonSeries, liveSeriesForSelected], async () => {
  evaluateRestartWatch()
  await nextTick()
  renderCharts()
}, { deep: true })
watch(() => route.fullPath, async () => {
  if (props.mode === 'detail' && props.routeServiceId) {
    selectedServiceId.value = props.routeServiceId
    selectedDetail.value = null
    await loadDetail(props.routeServiceId)
  }
})
watch(liveNow, evaluateRestartWatch)

onMounted(async () => {
  clockTimer = window.setInterval(() => {
    liveNow.value = new Date()
  }, 1000)
  await loadEverything()
  connectSocket()
  restartPolling()
  await nextTick()
  renderCharts()
})

onBeforeUnmount(() => {
  if (pollingTimer) {
    window.clearInterval(pollingTimer)
  }
  if (clockTimer) {
    window.clearInterval(clockTimer)
  }
  if (toastTimer) {
    window.clearTimeout(toastTimer)
  }
  closeSocket()
  destroyCharts()
})
</script>

<style>
.console-shell {
  display: grid;
  gap: 22px;
  padding: 8px 0 20px;
  color: #e2e8f0;
  --panel-base: rgba(15, 23, 42, 0.88);
  --panel-base-soft: rgba(15, 23, 42, 0.72);
  --panel-border: rgba(148, 163, 184, 0.14);
  --page-glow-a: rgba(0, 196, 180, 0.14);
  --page-glow-b: rgba(255, 77, 77, 0.14);
  --shell-bg: transparent;
  --text-strong: #f8fafc;
  --text-muted: #94a3b8;
  background: var(--shell-bg);
}

.console-shell.theme-light {
  color: #1f2937;
  --panel-base: rgba(255, 255, 255, 0.94);
  --panel-base-soft: rgba(251, 252, 253, 0.92);
  --panel-border: rgba(148, 163, 184, 0.2);
  --page-glow-a: rgba(99, 102, 241, 0.09);
  --page-glow-b: rgba(16, 185, 129, 0.08);
  --shell-bg: linear-gradient(180deg, #f8fafc 0%, #eef2f7 56%, #e8eef5 100%);
  --text-strong: #111827;
  --text-muted: #64748b;
}

.dashboard-page,
.detail-page,
.dashboard-services {
  display: grid;
  gap: 22px;
}

.fleet-trend-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) minmax(320px, 0.9fr);
  gap: 22px;
}

.glass-panel,
.glass-subpanel {
  border: 1px solid var(--panel-border);
  background: linear-gradient(180deg, var(--panel-base) 0%, var(--panel-base-soft) 100%);
  box-shadow: 0 24px 60px rgba(2, 6, 23, 0.36);
  backdrop-filter: blur(18px);
}

.console-header {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr) 320px;
  gap: 18px;
  padding: 24px;
  border-radius: 28px;
  background:
    radial-gradient(circle at left top, var(--page-glow-a), transparent 32%),
    radial-gradient(circle at right top, var(--page-glow-b), transparent 28%),
    linear-gradient(135deg, rgba(15, 23, 42, 0.92) 0%, rgba(17, 24, 39, 0.84) 55%, rgba(15, 118, 110, 0.22) 100%);
}

.theme-light .console-header {
  background:
    radial-gradient(circle at left top, var(--page-glow-a), transparent 32%),
    radial-gradient(circle at right top, var(--page-glow-b), transparent 28%),
    linear-gradient(135deg, rgba(255, 255, 255, 0.97) 0%, rgba(249, 251, 253, 0.95) 52%, rgba(240, 245, 251, 0.93) 100%);
  box-shadow: 0 18px 42px rgba(148, 163, 184, 0.18);
}

.header-main h1,
.panel-topbar h2,
.group-head h3,
.detail-sidebar-head h2,
.confirm-dialog h3 {
  margin: 0;
}

.eyebrow,
.panel-kicker {
  margin: 0 0 8px;
  font-size: 12px;
  letter-spacing: 0.2em;
  text-transform: uppercase;
  color: #5eead4;
}

.theme-light .eyebrow,
.theme-light .panel-kicker {
  color: #475569;
}

.header-copy,
.group-head p,
.detail-sidebar-head p,
.clock-card small,
.stat-card small,
.notice span,
.alert-card p,
.alert-card-head p,
.timeline-meta,
.service-card p,
.service-footnote,
.toolbar-field span,
.section-head-inline span {
  color: var(--text-muted);
}

.incident-list {
  display: grid;
  gap: 12px;
}

.incident-card {
  display: grid;
  gap: 10px;
  padding: 16px 18px;
  border-radius: 18px;
  border: 1px solid rgba(148, 163, 184, 0.14);
  background: rgba(15, 23, 42, 0.44);
  cursor: pointer;
  transition: transform 0.2s ease, border-color 0.2s ease, background 0.2s ease;
}

.incident-card:hover {
  transform: translateY(-2px);
  border-color: rgba(94, 234, 212, 0.28);
  background: rgba(15, 23, 42, 0.62);
}

.header-badges,
.page-nav,
.header-actions,
.panel-topbar-actions,
.detail-head-actions,
.alert-actions,
.detail-footer-actions,
.command-grid {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  align-items: center;
}

.signal-pill,
.panel-pill,
.status-badge,
.timeline-category,
.segment-button,
.page-nav-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 34px;
  padding: 0 14px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
  border: 1px solid rgba(148, 163, 184, 0.16);
  background: rgba(30, 41, 59, 0.74);
}

.page-nav-button {
  cursor: pointer;
  color: #cbd5e1;
}

.theme-light .page-nav-button {
  color: #475569;
  background: rgba(255, 255, 255, 0.9);
  border-color: rgba(148, 163, 184, 0.24);
}

.page-nav-button.active {
  color: #04111f;
  border-color: rgba(0, 196, 180, 0.34);
  background: linear-gradient(135deg, #00c4b4 0%, #67e8f9 100%);
}

.page-nav-button:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.signal-pill.ok {
  color: #ccfbf1;
  border-color: rgba(0, 196, 180, 0.34);
}

.signal-pill.danger,
.panel-pill.red {
  color: #fecaca;
  border-color: rgba(255, 77, 77, 0.34);
}

.signal-pill.muted {
  color: #cbd5e1;
}

.header-tools,
.clock-card,
.toolbar-field,
.chart-card,
.command-panel,
.mini-detail-card,
.alert-card,
.timeline-card.inner,
.jvm-card {
  border-radius: 22px;
  border: 1px solid rgba(148, 163, 184, 0.12);
  background: rgba(15, 23, 42, 0.52);
}

.theme-light .header-tools,
.theme-light .clock-card,
.theme-light .toolbar-field,
.theme-light .chart-card,
.theme-light .command-panel,
.theme-light .mini-detail-card,
.theme-light .alert-card,
.theme-light .timeline-card.inner,
.theme-light .jvm-card,
.theme-light .service-card,
.theme-light .services-panel,
.theme-light .detail-sidebar,
.theme-light .glass-subpanel,
.theme-light .ops-kpi-card,
.theme-light .ops-status-card,
.theme-light .type-stat-card,
.theme-light .health-meter-card,
.theme-light .node-alert-card,
.theme-light .hotspot-card,
.theme-light .alert-side-rail,
.theme-light .policy-card,
.theme-light .tcp-chip,
.theme-light .thread-chip,
.theme-light .mini-stat-item,
.theme-light .alert-brief-card,
.theme-light .command-bubble,
.theme-light .service-skeleton-card {
  background: rgba(255, 255, 255, 0.86);
  border-color: rgba(148, 163, 184, 0.2);
  box-shadow: 0 10px 24px rgba(148, 163, 184, 0.1);
}

.header-tools {
  display: grid;
  gap: 14px;
  padding: 16px;
}

.port-quick-add {
  display: flex;
  gap: 10px;
  align-items: end;
  flex-wrap: wrap;
}

.port-quick-add .toolbar-field {
  min-width: 180px;
}

.nav-alert-dot {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  border-radius: 999px;
  background: #f43f5e;
  color: #fff;
  font-size: 11px;
  line-height: 1;
  box-shadow: 0 0 0 4px rgba(244, 63, 94, 0.16);
}

.page-nav-button.alerted {
  position: relative;
  gap: 8px;
}

.stat-meta-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 10px;
}

.stat-meta-row p {
  margin: 0;
  color: #94a3b8;
  font-size: 12px;
  line-height: 1.6;
}

.stat-orbit {
  --progress: 0%;
  --orbit-color: #00c4b4;
  position: relative;
  width: 58px;
  height: 58px;
  border-radius: 50%;
  background: conic-gradient(var(--orbit-color) 0 var(--progress), rgba(148, 163, 184, 0.14) var(--progress) 100%);
  display: grid;
  place-items: center;
  flex: 0 0 auto;
}

.stat-orbit::before,
.metric-ring::before {
  content: '';
  position: absolute;
  inset: 7px;
  border-radius: 50%;
  background: rgba(8, 15, 28, 0.95);
}

.stat-orbit span,
.metric-ring strong,
.metric-ring span {
  position: relative;
  z-index: 1;
}

.stat-orbit span {
  font-size: 11px;
  font-weight: 700;
}

.section-head-actions {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}

.segment-control.compact {
  gap: 8px;
}

.service-skeleton-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
}

.service-skeleton-card {
  padding: 18px;
  border-radius: 22px;
  background: rgba(15, 23, 42, 0.58);
  border: 1px solid rgba(148, 163, 184, 0.08);
}

.skeleton-line,
.skeleton-ring {
  display: block;
  background: linear-gradient(90deg, rgba(148, 163, 184, 0.08), rgba(148, 163, 184, 0.22), rgba(148, 163, 184, 0.08));
  background-size: 220% 100%;
  animation: shimmer 1.6s linear infinite;
}

.skeleton-line {
  height: 14px;
  border-radius: 999px;
  margin-bottom: 10px;
}

.skeleton-line.wide {
  width: 72%;
}

.skeleton-line.medium {
  width: 48%;
}

.skeleton-ring-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-top: 16px;
}

.skeleton-ring {
  width: 100%;
  aspect-ratio: 1;
  border-radius: 50%;
}

.metric-ring-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.metric-ring {
  --progress: 0%;
  --orbit-color: #00c4b4;
  position: relative;
  min-height: 92px;
  border-radius: 20px;
  padding: 14px 12px;
  background:
    radial-gradient(circle at center, transparent 0 26px, rgba(8, 15, 28, 0.92) 26px 100%),
    conic-gradient(var(--orbit-color) 0 var(--progress), rgba(148, 163, 184, 0.14) var(--progress) 100%);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.metric-ring span {
  font-size: 12px;
  color: #94a3b8;
}

.metric-ring strong {
  font-size: 14px;
}

.latency-ring strong {
  font-size: 13px;
}

.topology-grid,
.alerts-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.35fr) minmax(280px, 0.85fr);
  gap: 18px;
}

.tcp-card,
.topology-card,
.alert-side-rail,
.command-bubble,
.service-skeleton-card {
  border-radius: 24px;
}

.tcp-head,
.command-bubble-head,
.alert-brief-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.tcp-card p,
.alert-brief-card p {
  margin: 10px 0 0;
  color: #94a3b8;
  line-height: 1.7;
}

.tcp-chip-grid,
.thread-chip-list,
.mini-stat-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-top: 16px;
}

.tcp-chip,
.thread-chip,
.mini-stat-item {
  padding: 14px;
  border-radius: 18px;
  background: rgba(15, 23, 42, 0.58);
  border: 1px solid rgba(148, 163, 184, 0.08);
}

.tcp-chip span,
.thread-chip span,
.mini-stat-item span {
  display: block;
  color: #94a3b8;
  font-size: 12px;
}

.tcp-chip strong,
.thread-chip strong,
.mini-stat-item strong {
  display: block;
  margin-top: 6px;
}

.thread-chip small,
.mini-stat-item small {
  color: #64748b;
}

.thread-topology-bar {
  display: flex;
  gap: 8px;
  margin-top: 16px;
  min-height: 18px;
}

.thread-segment {
  display: block;
  min-width: 8%;
  border-radius: 999px;
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.08);
}

.command-status-strip {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}

.command-state-pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 32px;
  padding: 0 12px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
  background: rgba(148, 163, 184, 0.14);
  color: #cbd5e1;
}

.command-state-pill.queued {
  background: rgba(245, 158, 11, 0.18);
  color: #fde68a;
}

.command-state-pill.success {
  background: rgba(16, 185, 129, 0.18);
  color: #a7f3d0;
}

.command-state-pill.danger {
  background: rgba(244, 63, 94, 0.18);
  color: #fecdd3;
}

.command-state-pill.idle {
  background: rgba(71, 85, 105, 0.22);
}

.command-bubble-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
  margin-top: 14px;
}

.command-bubble {
  padding: 16px;
  background: rgba(8, 15, 28, 0.72);
  border: 1px solid rgba(148, 163, 184, 0.1);
}

.command-bubble pre {
  margin: 12px 0 0;
  max-height: 200px;
  overflow: auto;
  padding: 14px;
  border-radius: 16px;
  background: rgba(15, 23, 42, 0.9);
  color: #dbeafe;
  font-size: 12px;
  line-height: 1.6;
}

.alert-side-rail {
  padding: 18px;
  align-self: start;
  position: sticky;
  top: 12px;
}

.alert-brief-list {
  display: grid;
  gap: 10px;
}

.alert-brief-card {
  padding: 14px;
  border-radius: 18px;
  background: rgba(15, 23, 42, 0.58);
  border: 1px solid rgba(148, 163, 184, 0.08);
  cursor: pointer;
}

.alert-brief-card small {
  display: block;
  margin-top: 8px;
  color: #64748b;
}

.top-gap {
  margin-top: 18px;
}

@keyframes shimmer {
  0% { background-position: 100% 0; }
  100% { background-position: -100% 0; }
}

.toolbar-input {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 16px;
  min-height: 56px;
  border-radius: 18px;
  border: 1px solid rgba(148, 163, 184, 0.12);
  background: rgba(15, 23, 42, 0.46);
}

.toolbar-grid,
.notification-grid,
.detail-grid,
.metrics-grid,
.jvm-grid,
.command-fields,
.stats-grid,
.workspace-grid,
.mini-kpi-grid {
  display: grid;
  gap: 12px;
}

.toolbar-grid.compact,
.notification-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.toolbar-field {
  display: grid;
  gap: 8px;
  padding: 14px 16px;
  transition: border-color 220ms ease, box-shadow 220ms ease, background 220ms ease;
}

.toolbar-field:hover {
  border-color: rgba(94, 234, 212, 0.24);
  background: rgba(15, 23, 42, 0.62);
}

.toolbar-field:focus-within {
  border-color: rgba(45, 212, 191, 0.45);
  box-shadow: 0 0 0 3px rgba(45, 212, 191, 0.14);
}

.toolbar-field input,
.toolbar-field select,
.search-box input {
  width: 100%;
  border: 1px solid rgba(148, 163, 184, 0.18);
  border-radius: 12px;
  min-height: 42px;
  padding: 0 12px;
  outline: none;
  background: rgba(8, 15, 28, 0.54);
  color: #f8fafc;
  transition: border-color 180ms ease, box-shadow 180ms ease, background 180ms ease;
}

.search-box input {
  border-color: transparent;
  background: transparent;
  min-height: 52px;
  padding: 0;
}

.toolbar-field input::placeholder,
.search-box input::placeholder {
  color: rgba(148, 163, 184, 0.9);
}

.toolbar-field input:focus,
.toolbar-field select:focus,
.search-box input:focus {
  border-color: rgba(45, 212, 191, 0.52);
  box-shadow: 0 0 0 3px rgba(45, 212, 191, 0.12);
}

.toolbar-field select {
  appearance: none;
  -webkit-appearance: none;
  -moz-appearance: none;
  padding-right: 36px;
  cursor: pointer;
  background-image:
    linear-gradient(45deg, transparent 50%, #7dd3fc 50%),
    linear-gradient(135deg, #7dd3fc 50%, transparent 50%);
  background-position:
    calc(100% - 18px) calc(50% - 3px),
    calc(100% - 12px) calc(50% - 3px);
  background-size: 6px 6px, 6px 6px;
  background-repeat: no-repeat;
}

.toolbar-field select option {
  background: #101a2f;
  color: #e2e8f0;
}

.toolbar-field select option:checked {
  background: #0f4c81;
  color: #f8fafc;
}

.theme-light .toolbar-field input,
.theme-light .toolbar-field select,
.theme-light .search-box input,
.theme-light .command-bubble pre,
.theme-light .stack-preview {
  color: #0f172a;
}

.theme-light .toolbar-field:hover {
  background: rgba(255, 255, 255, 0.98);
  border-color: rgba(99, 102, 241, 0.24);
}

.theme-light .toolbar-field:focus-within {
  border-color: rgba(99, 102, 241, 0.34);
  box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.12);
}

.theme-light .toolbar-field input,
.theme-light .toolbar-field select {
  background: rgba(250, 251, 253, 0.98);
  border-color: rgba(148, 163, 184, 0.3);
}

.theme-light .toolbar-field input::placeholder,
.theme-light .search-box input::placeholder {
  color: rgba(71, 85, 105, 0.88);
}

.theme-light .toolbar-field input:focus,
.theme-light .toolbar-field select:focus,
.theme-light .search-box input:focus {
  border-color: rgba(99, 102, 241, 0.4);
  box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.13);
}

.theme-light .toolbar-field select {
  background-image:
    linear-gradient(45deg, transparent 50%, #64748b 50%),
    linear-gradient(135deg, #64748b 50%, transparent 50%);
}

.theme-light .toolbar-field select option {
  background: #ffffff;
  color: #0f172a;
}

.theme-light .toolbar-field select option:checked {
  background: #e8eef9;
  color: #0f172a;
}

.header-side {
  display: grid;
  gap: 12px;
}

.clock-card {
  display: grid;
  gap: 6px;
  padding: 16px 18px;
}

.clock-card strong,
.stat-value,
.mini-detail-card strong,
.mini-kpi-grid strong {
  color: var(--text-strong);
}

.primary-button,
.ghost-button,
.command-fab {
  border: none;
  cursor: pointer;
  transition: transform 220ms ease, box-shadow 220ms ease, background 220ms ease;
}

.primary-button,
.ghost-button {
  min-height: 44px;
  padding: 0 16px;
  border-radius: 16px;
}

.primary-button {
  background: linear-gradient(135deg, #00c4b4 0%, #0891b2 100%);
  color: #04111f;
  font-weight: 800;
}

.ghost-button {
  background: rgba(15, 23, 42, 0.68);
  color: #cbd5e1;
  border: 1px solid rgba(148, 163, 184, 0.16);
}

.theme-toggle-button {
  min-width: 128px;
}

.theme-light .ghost-button {
  background: rgba(255, 255, 255, 0.9);
  color: #475569;
  border-color: rgba(148, 163, 184, 0.26);
}

.theme-light .primary-button {
  color: #ffffff;
  background: linear-gradient(135deg, #4f46e5 0%, #0ea5a3 100%);
  box-shadow: 0 10px 24px rgba(79, 70, 229, 0.2);
}

.theme-light .stat-label,
.theme-light .ops-kpi-card span,
.theme-light .ops-status-card span,
.theme-light .summary-tile span,
.theme-light .health-meter-head span,
.theme-light .type-stat-card p,
.theme-light .hotspot-card p,
.theme-light .tcp-chip span,
.theme-light .thread-chip span,
.theme-light .mini-stat-item span,
.theme-light .thread-chip small,
.theme-light .mini-stat-item small,
.theme-light .alert-brief-card small,
.theme-light .tcp-card p,
.theme-light .alert-brief-card p,
.theme-light .metric-ring span,
.theme-light .command-state-pill {
  color: #64748b;
}

.theme-light .ops-kpi-card strong,
.theme-light .ops-status-card strong,
.theme-light .summary-tile strong,
.theme-light .health-meter-head strong,
.theme-light .type-stat-card strong,
.theme-light .hotspot-card strong,
.theme-light .restart-evidence-item strong,
.theme-light .clock-card strong,
.theme-light .stat-value,
.theme-light .mini-detail-card strong,
.theme-light .mini-kpi-grid strong {
  color: #111827;
}

.theme-light .service-card {
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.96) 0%, rgba(248, 250, 252, 0.92) 100%);
  border-color: rgba(148, 163, 184, 0.24);
}

.theme-light .service-card.active {
  border-color: rgba(79, 70, 229, 0.34);
  box-shadow: 0 12px 24px rgba(79, 70, 229, 0.12);
}

.theme-light .service-card.stale {
  border-color: rgba(245, 158, 11, 0.34);
}

.theme-light .meter-track {
  background: rgba(203, 213, 225, 0.58);
}

.theme-light .command-fab {
  color: #ffffff;
}

.theme-light .command-fab.primary {
  color: #ffffff;
  background: linear-gradient(135deg, #4f46e5 0%, #0ea5a3 100%);
}

.theme-light .command-fab.ghost {
  color: #334155;
  background: rgba(255, 255, 255, 0.92);
  border-color: rgba(148, 163, 184, 0.3);
}

.theme-light .command-fab.warning {
  color: #b91c1c;
  background: rgba(254, 226, 226, 0.86);
  border: 1px solid rgba(239, 68, 68, 0.24);
}

.theme-light .command-bubble {
  background: rgba(248, 250, 252, 0.95);
  border-color: rgba(148, 163, 184, 0.2);
}

.theme-light .command-bubble pre,
.theme-light .stack-preview {
  background: rgba(241, 245, 249, 0.9);
  color: #1e293b;
}

.theme-light .restart-evidence-item {
  background: rgba(248, 250, 252, 0.95);
  border-color: rgba(148, 163, 184, 0.2);
}

.theme-light .restart-evidence-item.done {
  border-color: rgba(14, 116, 144, 0.28);
  background: rgba(236, 253, 245, 0.86);
}

.theme-light .empty-notice,
.theme-light .empty-state,
.theme-light .sync-hint,
.theme-light .stat-meta-row p {
  color: #64748b;
}

.primary-button:hover,
.ghost-button:hover,
.command-fab:hover,
.stat-card.clickable:hover,
.service-card:hover {
  transform: translateY(-2px);
}

.stats-grid {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.ops-overview-grid,
.alert-summary-grid,
.ops-kpi-grid,
.ops-status-grid,
.type-stat-list,
.node-health-grid {
  display: grid;
  gap: 12px;
}

.ops-overview-grid {
  grid-template-columns: minmax(0, 1.15fr) minmax(360px, 0.85fr);
}

.stat-card {
  display: grid;
  gap: 10px;
  padding: 22px;
  border-radius: 24px;
}

.stat-card.clickable {
  cursor: pointer;
}

.stat-label {
  color: #94a3b8;
  font-size: 13px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
}

.stat-value {
  font-size: clamp(34px, 5vw, 46px);
}

.trend-strip,
.dashboard-trend-section,
.group-section {
  display: grid;
  gap: 12px;
}

.workspace-grid {
  grid-template-columns: minmax(0, 1.3fr) minmax(380px, 0.95fr);
}

.services-panel,
.detail-sidebar,
.alert-drawer {
  padding: 22px;
  border-radius: 28px;
}

.panel-topbar,
.section-head-inline,
.detail-sidebar-head,
.group-head,
.service-card-head,
.service-footnote,
.alert-card-head,
.timeline-meta {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
}

.ops-panel {
  display: grid;
  gap: 16px;
  padding: 22px;
  border-radius: 28px;
}

.ops-kpi-grid,
.ops-status-grid,
.alert-summary-grid,
.node-health-grid {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.ops-kpi-card,
.ops-status-card,
.summary-tile,
.type-stat-card,
.health-meter-card,
.node-alert-card,
.hotspot-card {
  border-radius: 20px;
  border: 1px solid rgba(148, 163, 184, 0.12);
  background: rgba(15, 23, 42, 0.5);
}

.ops-kpi-card,
.ops-status-card,
.summary-tile,
.health-meter-card {
  display: grid;
  gap: 6px;
  padding: 16px;
}

.ops-kpi-card span,
.ops-status-card span,
.summary-tile span,
.health-meter-head span,
.type-stat-card p,
.hotspot-card p {
  color: #94a3b8;
}

.ops-kpi-card strong,
.ops-status-card strong,
.summary-tile strong,
.health-meter-head strong,
.type-stat-card strong,
.hotspot-card strong {
  color: #f8fafc;
}

.ops-status-card.critical,
.summary-tile.critical {
  box-shadow: inset 0 0 0 1px rgba(255, 77, 77, 0.22);
}

.ops-status-card.warning,
.summary-tile.warning {
  box-shadow: inset 0 0 0 1px rgba(250, 204, 21, 0.18);
}

.type-stat-card,
.hotspot-card,
.node-alert-card {
  display: flex;
  justify-content: space-between;
  gap: 14px;
  align-items: center;
  padding: 16px;
}

.type-stat-meta,
.hotspot-meta {
  display: grid;
  gap: 6px;
  justify-items: end;
}

.hotspot-list,
.node-alert-list {
  display: grid;
  gap: 10px;
}

.hotspot-card {
  cursor: pointer;
}

.alert-toolbar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  gap: 12px;
  padding: 14px;
  border-radius: 22px;
}

.alert-toolbar-meta {
  display: flex;
  align-items: center;
  justify-content: flex-end;
}

.health-meter-head {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  align-items: center;
}

.meter-track {
  overflow: hidden;
  width: 100%;
  height: 10px;
  border-radius: 999px;
  background: rgba(30, 41, 59, 0.9);
}

.meter-fill {
  display: block;
  height: 100%;
  border-radius: inherit;
}

.meter-fill.cpu {
  background: linear-gradient(90deg, #22c55e 0%, #00c4b4 100%);
}

.meter-fill.memory {
  background: linear-gradient(90deg, #38bdf8 0%, #5eead4 100%);
}

.meter-fill.disk {
  background: linear-gradient(90deg, #fb7185 0%, #ff4d4d 100%);
}

.meter-fill.heap {
  background: linear-gradient(90deg, #f59e0b 0%, #f97316 100%);
}

.services-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.service-card {
  display: grid;
  gap: 14px;
  padding: 18px;
  border-radius: 22px;
  cursor: pointer;
  background: linear-gradient(180deg, rgba(15, 23, 42, 0.78) 0%, rgba(15, 23, 42, 0.62) 100%);
  border: 1px solid rgba(148, 163, 184, 0.14);
}

.service-card.active {
  border-color: rgba(0, 196, 180, 0.34);
  box-shadow: 0 20px 40px rgba(0, 196, 180, 0.1);
}

.service-card.stale {
  border-color: rgba(250, 204, 21, 0.3);
}

.service-card.alerting {
  box-shadow: inset 0 0 0 1px rgba(255, 77, 77, 0.28);
}

.status-badge.online {
  color: #ccfbf1;
  border-color: rgba(0, 196, 180, 0.26);
}

.status-badge.offline {
  color: #fecaca;
  border-color: rgba(255, 77, 77, 0.26);
}

.status-badge.stale {
  color: #fde68a;
  border-color: rgba(250, 204, 21, 0.26);
}

.status-badge.neutral {
  color: #cbd5e1;
  border-color: rgba(148, 163, 184, 0.18);
}

.status-dot {
  width: 8px;
  height: 8px;
  margin-right: 8px;
  border-radius: 50%;
  background: currentColor;
}

.mini-kpi-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.mini-kpi-grid div,
.jvm-metrics div {
  display: grid;
  gap: 4px;
}

.detail-sidebar-body,
.alert-list {
  display: grid;
  gap: 14px;
}

.alerts-page {
  display: grid;
  gap: 16px;
}

.alert-config-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(320px, 0.8fr);
  gap: 14px;
}

.config-panel {
  display: grid;
  gap: 14px;
  padding: 18px;
  border-radius: 24px;
}

.compact-grid {
  margin: 0;
}

.config-actions {
  justify-content: flex-end;
}

.policy-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.policy-card {
  display: grid;
  gap: 8px;
  padding: 16px;
  border-radius: 20px;
  border: 1px solid rgba(148, 163, 184, 0.12);
  background: rgba(15, 23, 42, 0.52);
}

.policy-card p,
.policy-card small {
  margin: 0;
  color: #94a3b8;
}

.compact-head {
  margin-bottom: 0;
}

.detail-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.metrics-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.metrics-grid.wide {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.chart-card {
  padding: 16px;
}

.chart-card canvas {
  width: 100% !important;
  height: 220px !important;
}

.jvm-grid {
  grid-template-columns: 1fr 1.1fr;
}

.jvm-card {
  padding: 16px;
}

.jvm-metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}

.stack-preview {
  margin: 0;
  padding: 14px;
  border-radius: 16px;
  background: rgba(2, 6, 23, 0.5);
  color: #cbd5e1;
  white-space: pre-wrap;
  min-height: 180px;
}

.timeline-list {
  display: grid;
  gap: 10px;
  max-height: 280px;
  overflow: auto;
}

.timeline-list.tall {
  max-height: 420px;
}

.timeline-card.inner {
  padding: 14px 16px;
}

.timeline-card :deep(mark) {
  padding: 2px 6px;
  border-radius: 999px;
  background: rgba(255, 77, 77, 0.18);
  color: #fecaca;
}

.restart-watch-card {
  display: grid;
  gap: 14px;
}

.restart-watch-grid,
.restart-evidence-list {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.restart-evidence-list {
  display: grid;
  gap: 10px;
}

.restart-evidence-item {
  display: grid;
  gap: 6px;
  padding: 14px 16px;
  border-radius: 18px;
  border: 1px solid rgba(148, 163, 184, 0.12);
  background: rgba(15, 23, 42, 0.4);
}

.restart-evidence-item span {
  color: #94a3b8;
}

.restart-evidence-item strong {
  color: #e2e8f0;
}

.restart-evidence-item.done {
  border-color: rgba(0, 196, 180, 0.24);
  background: rgba(6, 95, 70, 0.12);
}

.restart-pending-notice {
  background: rgba(120, 53, 15, 0.18);
  color: #fde68a;
}

.restart-idle-notice {
  background: rgba(30, 41, 59, 0.46);
  color: #cbd5e1;
}

.command-panel {
  padding: 16px;
}

.command-fields.grid-two {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.command-grid {
  margin-top: 14px;
}

.command-fab {
  min-height: 42px;
  padding: 0 16px;
  border-radius: 999px;
  font-weight: 800;
  color: #f8fafc;
}

.command-fab.primary {
  background: linear-gradient(135deg, #00c4b4 0%, #0891b2 100%);
  color: #04111f;
}

.command-fab.warning {
  background: rgba(255, 77, 77, 0.16);
  color: #fecaca;
}

.command-fab.mint {
  background: rgba(0, 196, 180, 0.14);
  color: #ccfbf1;
}

.command-fab.ghost {
  background: rgba(30, 41, 59, 0.84);
  color: #e2e8f0;
  border: 1px solid rgba(148, 163, 184, 0.16);
}

.notice {
  display: grid;
  gap: 6px;
  padding: 16px 18px;
  border-radius: 20px;
}

.danger-notice {
  background: rgba(127, 29, 29, 0.18);
  color: #fecaca;
}

.success-notice {
  background: rgba(6, 95, 70, 0.18);
  color: #ccfbf1;
}

.empty-notice,
.empty-state {
  color: #94a3b8;
  text-align: center;
}

.alert-drawer {
  position: relative;
}

.notification-grid {
  margin: 16px 0;
}

.switch-field {
  grid-template-columns: 1fr auto;
  align-items: center;
}

.alert-list {
  max-height: 460px;
  overflow: auto;
}

.alert-card {
  padding: 16px;
}

.alert-card.critical {
  border-color: rgba(255, 77, 77, 0.28);
}

.alert-card.warning {
  border-color: rgba(250, 204, 21, 0.24);
}

.alert-card.acknowledged {
  opacity: 0.65;
}

.alert-message {
  margin: 10px 0 14px;
}

.alert-tags {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.modal-backdrop {
  position: fixed;
  inset: 0;
  display: grid;
  place-items: center;
  background: rgba(2, 6, 23, 0.52);
  z-index: 20;
}

.confirm-dialog {
  width: min(460px, calc(100vw - 32px));
  padding: 22px;
  border-radius: 24px;
}

.dashboard-panel {
  display: grid;
  gap: 18px;
}

.compact-summary {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.node-card-grid {
  display: grid;
  gap: 14px;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
}

.metrics-row {
  justify-content: space-between;
}

.dashboard-table-panel {
  padding: 18px;
  border-radius: 24px;
}

.node-table-wrap {
  overflow-x: auto;
}

.node-table {
  width: 100%;
  min-width: 980px;
  border-collapse: collapse;
}

.node-table th,
.node-table td {
  padding: 14px 12px;
  text-align: left;
  border-bottom: 1px solid rgba(148, 163, 184, 0.12);
  white-space: nowrap;
}

.node-table th {
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 600;
}

.node-table tbody tr {
  transition: background 0.2s ease;
}

.node-table tbody tr:hover {
  background: rgba(15, 23, 42, 0.18);
}

.ghost-button.small {
  padding: 8px 12px;
  min-height: auto;
}

.command-grid.single-action {
  grid-template-columns: minmax(220px, 320px);
}

.heartbeat-list,
.dashboard-main {
  display: grid;
  gap: 12px;
}

.workspace-grid.single-column {
  grid-template-columns: 1fr;
}

.toast {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 24;
  min-width: 220px;
  padding: 14px 18px;
  border-radius: 16px;
  color: #f8fafc;
  box-shadow: 0 18px 40px rgba(2, 6, 23, 0.34);
}

.toast.success {
  background: rgba(6, 95, 70, 0.92);
}

.toast.danger {
  background: rgba(127, 29, 29, 0.92);
}

.fade-slide-enter-active,
.fade-slide-leave-active,
.toast-in-enter-active,
.toast-in-leave-active {
  transition: all 220ms ease;
}

.fade-slide-enter-from,
.fade-slide-leave-to,
.toast-in-enter-from,
.toast-in-leave-to {
  opacity: 0;
  transform: translateY(8px);
}

@media (max-width: 1440px) {
  .console-header,
  .ops-overview-grid,
  .alert-config-grid,
  .workspace-grid,
  .jvm-grid,
  .metrics-grid.wide {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 980px) {
  .stats-grid,
  .ops-kpi-grid,
  .ops-status-grid,
  .alert-summary-grid,
  .node-health-grid,
  .node-card-grid,
  .toolbar-grid.compact,
  .notification-grid,
  .policy-grid,
  .detail-grid,
  .metrics-grid,
  .services-grid,
  .command-fields.grid-two,
  .jvm-metrics {
    grid-template-columns: 1fr;
  }

  .alert-toolbar {
    grid-template-columns: 1fr;
  }

  .type-stat-card,
  .hotspot-card,
  .node-alert-card {
    display: grid;
  }

  .type-stat-meta,
  .hotspot-meta,
  .alert-toolbar-meta {
    justify-items: start;
    justify-content: flex-start;
  }
}
</style>
