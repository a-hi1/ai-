<template>
  <section class="dashboard-page">
    <section class="glass-panel dashboard-panel">
      <div class="panel-topbar">
        <div>
          <p class="panel-kicker">总览页面</p>
          <h2>节点排列与数据表格</h2>
        </div>
        <div class="panel-topbar-actions">
          <span class="panel-pill">{{ view.socketConnected ? '实时推送' : '轮询同步' }}</span>
          <span class="panel-pill">最近同步 {{ formats.formatTime(view.lastUpdatedAt) }}</span>
        </div>
      </div>

      <div class="page-nav">
        <button
          v-for="option in view.categoryOptions"
          :key="option.value"
          :class="['page-nav-button', { active: view.typeFilter === option.value }]"
          @click="actions.setTypeFilter(option.value)"
        >
          {{ option.label }} {{ option.count }}
        </button>
      </div>

      <div class="ops-status-grid compact-summary">
        <article class="ops-status-card neutral">
          <span>总节点</span>
          <strong>{{ view.allServicesLength }}</strong>
        </article>
        <article class="ops-status-card neutral">
          <span>当前展示</span>
          <strong>{{ view.dashboardServicesLength }}</strong>
        </article>
        <article class="ops-status-card warning">
          <span>在线节点</span>
          <strong>{{ view.onlineCount }}</strong>
        </article>
        <article class="ops-status-card critical">
          <span>离线节点</span>
          <strong>{{ view.offlineCount }}</strong>
        </article>
      </div>

      <div v-if="view.loading && !view.dashboardServicesLength" class="service-skeleton-grid">
        <article v-for="index in 4" :key="index" class="service-skeleton-card">
          <span class="skeleton-line wide"></span>
          <span class="skeleton-line medium"></span>
        </article>
      </div>

      <div v-else-if="view.errorMessage" class="notice danger-notice">
        <strong>监控后端连接失败</strong>
        <span>{{ view.errorMessage }}</span>
      </div>

      <div v-else-if="!view.dashboardServices.length" class="notice empty-notice">
        当前没有可展示的节点。
      </div>

      <template v-else>
        <section class="node-card-grid">
          <article
            v-for="service in view.dashboardServices"
            :key="service.id"
            :class="['service-card', { active: view.selectedServiceId === service.id, stale: service.isStale }]"
            @click="actions.openService(service.id)"
          >
            <div class="service-card-head">
              <div>
                <h3>{{ service.serviceName }}</h3>
                <p>{{ formats.formatServerType(service.serverType) }}</p>
              </div>
              <span :class="['status-badge', service.status.toLowerCase(), service.isStale ? 'stale' : '']">
                <i class="status-dot"></i>
                {{ service.healthLabel }}
              </span>
            </div>
            <div class="service-footnote">
              <span>{{ service.host }}:{{ service.port }}</span>
              <span>{{ service.ageText }}</span>
            </div>
            <div class="service-footnote metrics-row">
              <span>CPU {{ formats.formatPercent(service.cpuUsage) }}</span>
              <span>内存 {{ formats.formatPercent(service.memoryUsage) }}</span>
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
              <button class="ghost-button" @click.stop="actions.openService(service.id)">进入详情</button>
            </div>
          </article>
        </section>

        <section class="glass-subpanel dashboard-table-panel">
          <div class="section-head-inline">
            <div>
              <p class="panel-kicker">节点表格</p>
              <h3>当前节点明细</h3>
            </div>
            <span>{{ view.dashboardServicesLength }} 行</span>
          </div>

          <div class="node-table-wrap">
            <table class="node-table">
              <thead>
                <tr>
                  <th>节点</th>
                  <th>类型</th>
                  <th>状态</th>
                  <th>地址</th>
                  <th>最近心跳</th>
                  <th>CPU</th>
                  <th>内存</th>
                  <th>磁盘</th>
                  <th>网络延迟</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="service in view.dashboardServices" :key="`${service.id}-row`">
                  <td>
                    <strong>{{ service.serviceName }}</strong>
                  </td>
                  <td>{{ formats.formatServerType(service.serverType) }}</td>
                  <td>
                    <span :class="['status-badge', service.status.toLowerCase(), service.isStale ? 'stale' : '']">
                      <i class="status-dot"></i>
                      {{ service.healthLabel }}
                    </span>
                  </td>
                  <td>{{ service.host }}:{{ service.port }}</td>
                  <td>{{ formats.formatTime(service.lastHeartbeat) }}</td>
                  <td>{{ formats.formatPercent(service.cpuUsage) }}</td>
                  <td>{{ formats.formatPercent(service.memoryUsage) }}</td>
                  <td>{{ formats.formatPercent(service.diskUsage) }}</td>
                  <td>{{ formats.formatLatency(service.networkLatency) }}</td>
                  <td>
                    <button class="ghost-button small" @click.stop="actions.requestCommandForService(service.id, 'RESTART')">重启</button>
                    <button class="ghost-button small" @click.stop="actions.requestCommandForService(service.id, 'FORCE_OFFLINE')">下线</button>
                    <button
                      v-if="service.status === 'OFFLINE'"
                      class="ghost-button small"
                      @click.stop="actions.purgeOfflineService(service.id)"
                    >
                      清理历史
                    </button>
                    <button class="ghost-button small" @click="actions.openService(service.id)">详情</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </template>
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

// 使用 computed 包装 props 属性，避免 Vue 3 解构后丢失响应式
const view = computed(() => props.view)
const formats = computed(() => props.formats)
const actions = computed(() => props.actions)
</script>