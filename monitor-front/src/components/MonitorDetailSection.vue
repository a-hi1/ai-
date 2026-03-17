<template>
  <section class="detail-page">
    <section class="trend-strip">
      <article class="trend-card glass-panel">
        <div class="section-head-inline">
          <div>
            <p class="panel-kicker">实时线图</p>
            <h3>单节点 CPU / 内存 / 延迟 / 磁盘</h3>
          </div>
          <div class="section-head-actions">
            <div class="segment-control compact">
              <button :class="['segment-button', { active: view.nodeWindow === '1m' }]" @click="emit('update:nodeWindow', '1m')">1 分钟</button>
              <button :class="['segment-button', { active: view.nodeWindow === '5m' }]" @click="emit('update:nodeWindow', '5m')">5 分钟</button>
              <button :class="['segment-button', { active: view.nodeWindow === '15m' }]" @click="emit('update:nodeWindow', '15m')">15 分钟</button>
              <button :class="['segment-button', { active: view.nodeWindow === '1h' }]" @click="emit('update:nodeWindow', '1h')">1 小时</button>
            </div>
            <span>{{ view.selectedSummary ? view.selectedSummary.serviceName : '未选中节点' }}</span>
          </div>
        </div>
        <div class="metrics-grid wide">
          <div class="chart-card"><canvas :ref="view.setCpuLiveCanvas"></canvas></div>
          <div class="chart-card"><canvas :ref="view.setMemoryLiveCanvas"></canvas></div>
          <div class="chart-card"><canvas :ref="view.setNetworkLiveCanvas"></canvas></div>
          <div class="chart-card"><canvas :ref="view.setDiskLiveCanvas"></canvas></div>
        </div>
      </article>
    </section>

    <section :class="['workspace-grid', { 'single-column': view.modalMode }]">
      <div v-if="!view.modalMode" class="services-panel glass-panel">
        <div class="panel-topbar">
          <div>
            <p class="panel-kicker">节点导航</p>
            <h2>服务列表</h2>
          </div>
          <div class="panel-topbar-actions">
            <span class="panel-pill">{{ view.filteredServicesLength }} 个可见节点</span>
            <button class="ghost-button" @click="actions.goDashboard">返回总览</button>
          </div>
        </div>

        <div v-if="view.loading && !view.filteredServicesLength" class="service-skeleton-grid">
          <article v-for="index in 4" :key="index" class="service-skeleton-card">
            <span class="skeleton-line wide"></span>
            <span class="skeleton-line medium"></span>
            <div class="skeleton-ring-row">
              <span class="skeleton-ring"></span>
              <span class="skeleton-ring"></span>
              <span class="skeleton-ring"></span>
              <span class="skeleton-ring"></span>
            </div>
          </article>
        </div>

        <div v-else-if="view.errorMessage" class="notice danger-notice">
          <strong>监控后端连接失败</strong>
          <span>{{ view.errorMessage }}</span>
        </div>

        <div v-else-if="!view.groupedServices.length" class="notice empty-notice">
          当前没有匹配条件的节点。
        </div>

        <section v-for="group in view.groupedServices" :key="group.type" class="group-section">
          <div class="group-head">
            <div>
              <h3>{{ formats.formatServerType(group.type) }}</h3>
              <p>{{ group.services.length }} 个节点</p>
            </div>
            <span class="panel-pill">{{ group.online }} 在线 / {{ group.services.length - group.online }} 非在线</span>
          </div>

          <div class="services-grid">
            <article
              v-for="service in group.services"
              :key="service.id"
              :class="['service-card', { active: view.selectedServiceId === service.id, stale: service.isStale }]"
              @click="actions.openService(service.id)"
            >
              <div class="service-card-head">
                <div>
                  <h3>{{ service.serviceName }}</h3>
                  <p>{{ service.host }}:{{ service.port }}</p>
                </div>
                <span :class="['status-badge', service.status.toLowerCase(), service.isStale ? 'stale' : '']">
                  <i class="status-dot"></i>
                  {{ service.healthLabel }}
                </span>
              </div>

              <div class="service-footnote">
                <span>最后心跳 {{ formats.formatTime(service.lastHeartbeat) }}</span>
                <span>断连 {{ service.connectionClosedCount || 0 }} 次</span>
              </div>

              <div class="detail-footer-actions">
                <button class="ghost-button small" @click.stop="actions.requestCommandForService(service.id, 'RESTART')">重启</button>
                <button class="ghost-button small" @click.stop="actions.requestCommandForService(service.id, 'FORCE_OFFLINE')">下线</button>
                <button
                  v-if="service.status === 'OFFLINE'"
                  class="ghost-button small"
                  @click.stop="actions.purgeOfflineService(service.id)"
                >
                  清理历史
                </button>
                <button class="ghost-button" @click.stop="actions.openService(service.id)">查看详情</button>
              </div>
            </article>
          </div>
        </section>
      </div>

      <aside class="detail-sidebar glass-panel">
        <div v-if="view.selectedSummary" class="detail-sidebar-body">
          <div class="detail-sidebar-head">
            <div>
              <p class="panel-kicker">节点详情</p>
              <h2>{{ view.selectedSummary.serviceName }}</h2>
              <p>{{ view.selectedSummary.host }}:{{ view.selectedSummary.port }} · {{ formats.formatServerType(view.selectedSummary.serverType) }}</p>
            </div>
            <div class="detail-head-actions">
              <span :class="['status-badge', view.selectedSummary.status.toLowerCase(), view.selectedSummary.isStale ? 'stale' : '']">
                <i class="status-dot"></i>
                {{ view.selectedSummary.healthLabel }}
              </span>
            </div>
          </div>

          <div class="detail-grid">
            <article class="mini-detail-card">
              <span>服务状态</span>
              <strong>{{ view.selectedSummary.healthLabel }}</strong>
              <small>{{ view.selectedSummary.status === 'ONLINE' && !view.selectedSummary.isStale ? '节点当前可通信' : '节点当前不可通信' }}</small>
            </article>
            <article class="mini-detail-card">
              <span>最近心跳</span>
              <strong>{{ formats.formatTime(view.selectedSummary.lastHeartbeat) }}</strong>
              <small>{{ view.selectedSummary.ageText }}</small>
            </article>
            <article class="mini-detail-card">
              <span>断连次数</span>
              <strong>{{ view.selectedSummary.connectionClosedCount || 0 }}</strong>
              <small>TCP 通道累计关闭次数</small>
            </article>
            <article class="mini-detail-card">
              <span>超时规则</span>
              <strong>90 秒</strong>
              <small>无心跳即标记离线</small>
            </article>
            <article class="mini-detail-card">
              <span>TCP 状态</span>
              <strong>{{ view.tcpStatus.label }}</strong>
              <small>{{ view.tcpStatus.detail }}</small>
            </article>
          </div>

          <section class="detail-section">
            <div class="section-head-inline">
              <h4>核心指标</h4>
              <span>{{ view.selectedRiskItems.length }} 项命中</span>
            </div>
            <div class="node-health-grid">
              <article class="health-meter-card">
                <div class="health-meter-head">
                  <span>CPU 压力</span>
                  <strong>{{ formats.formatPercent(view.selectedSummary.cpuUsage) }}</strong>
                </div>
                <div class="meter-track"><span class="meter-fill cpu" :style="{ width: formats.meterWidth(view.selectedSummary.cpuUsage) }"></span></div>
                <small>告警线: 90% 持续 5 分钟</small>
              </article>
              <article class="health-meter-card">
                <div class="health-meter-head">
                  <span>内存压力</span>
                  <strong>{{ formats.formatPercent(view.selectedSummary.memoryUsage) }}</strong>
                </div>
                <div class="meter-track"><span class="meter-fill memory" :style="{ width: formats.meterWidth(view.selectedSummary.memoryUsage) }"></span></div>
                <small>告警线: 85%</small>
              </article>
              <article class="health-meter-card">
                <div class="health-meter-head">
                  <span>磁盘占用</span>
                  <strong>{{ formats.formatPercent(view.selectedSummary.diskUsage) }}</strong>
                </div>
                <div class="meter-track"><span class="meter-fill disk" :style="{ width: formats.meterWidth(view.selectedSummary.diskUsage) }"></span></div>
                <small>告警线: 90%</small>
              </article>
              <article class="health-meter-card">
                <div class="health-meter-head">
                  <span>网络延迟</span>
                  <strong>{{ formats.formatLatency(view.selectedSummary.networkLatency) }}</strong>
                </div>
                <div class="meter-track"><span class="meter-fill heap" :style="{ width: formats.meterWidth(view.latencyToPercent(view.selectedSummary.networkLatency)) }"></span></div>
                <small>告警线: 200 ms</small>
              </article>
            </div>
            <div v-if="view.selectedRiskItems.length" class="node-alert-list">
              <article v-for="item in view.selectedRiskItems" :key="item" class="node-alert-card">
                <div class="alert-card-head">
                  <strong>命中规则</strong>
                  <span class="timeline-category">需要处理</span>
                </div>
                <p class="alert-message">{{ item }}</p>
              </article>
            </div>
          </section>

          <section class="detail-section">
            <div class="section-head-inline">
              <h4>运维操作</h4>
              <span :class="['status-badge', view.tcpStatus.badge]">{{ view.tcpStatus.badgeText }}</span>
            </div>

            <div class="command-panel">
              <div class="command-status-strip">
                <span :class="['command-state-pill', view.commandPhaseClass]">{{ view.commandPhaseText }}</span>
                <span>目标节点 {{ view.selectedSummary.serviceName }}</span>
                <button class="ghost-button" :disabled="!view.lastCommandPayload" @click="actions.copyText(view.lastCommandPayload, '请求 JSON 已复制')">复制请求 JSON</button>
                <button class="ghost-button" :disabled="!view.lastCommandResult" @click="actions.copyText(view.lastCommandResult, '结果 JSON 已复制')">复制结果 JSON</button>
              </div>
              <div class="command-fields grid-two">
                <label class="toolbar-field">
                  <span>控制令牌</span>
                  <input :value="view.controlToken" type="password" placeholder="monitor-dev-token" @input="emit('update:controlToken', ($event.target as HTMLInputElement).value)" />
                </label>
                <div class="toolbar-field read-only-field">
                  <span>执行动作</span>
                  <strong>重启后端并支持脚本重跑</strong>
                </div>
              </div>
              <div class="notice empty-notice compact">
                节点离线时，monitor-server 会尝试执行本地重启脚本重新拉起 ecommerce-backend。
              </div>
              <div class="command-grid">
                <button class="command-fab primary" :disabled="view.commandBusy" @click="actions.requestCommand('RESTART')">
                  重启后端
                </button>
                <button class="command-fab ghost" :disabled="view.commandBusy" @click="actions.requestCommand('FORCE_OFFLINE')">
                  强制下线
                </button>
                <button class="command-fab warning" :disabled="view.commandBusy" @click="actions.requestCommand('CLOSE')">
                  关闭服务
                </button>
              </div>
              <div v-if="view.commandMessage" :class="['notice', view.commandAccepted ? 'success-notice' : 'danger-notice']">
                <strong>{{ view.commandAccepted ? '控制指令已提交' : '控制指令失败' }}</strong>
                <span>{{ view.commandMessage }}</span>
              </div>
            </div>
          </section>
        </div>

        <div v-else class="empty-state">选择一个节点后显示状态、心跳、资源指标和控制中心。</div>
      </aside>
    </section>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  view: any
  formats: any
  actions: any
}>()

const emit = defineEmits<{
  (e: 'update:nodeWindow', value: '1m' | '5m' | '15m' | '1h'): void
  (e: 'update:compareWindow', value: '24h' | '7d'): void
  (e: 'update:controlToken', value: string): void
  (e: 'update:artifactUrl', value: string): void
}>()

// 使用 computed 包装 props 属性，避免 Vue 3 解构后丢失响应式
const view = computed(() => props.view)
const formats = computed(() => props.formats)
const actions = computed(() => props.actions)
</script>