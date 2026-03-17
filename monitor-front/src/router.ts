import { createRouter, createWebHashHistory } from 'vue-router'
import MonitorDashboardPage from './pages/MonitorDashboardPage.vue'
import MonitorNodeDetailPage from './pages/MonitorNodeDetailPage.vue'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    {
      path: '/',
      name: 'dashboard',
      component: MonitorDashboardPage
    },
    {
      path: '/node/:id',
      name: 'node-detail',
      component: MonitorNodeDetailPage,
      props: route => ({
        routeServiceId: typeof route.params.id === 'string' ? decodeURIComponent(route.params.id) : ''
      })
    }
  ]
})

export default router
