import { createRouter, createWebHistory } from 'vue-router'

import AccountView from '../views/AccountView.vue'
import DataAdminView from '../views/DataAdminView.vue'
import HomeView from '../views/HomeView.vue'
import ChatView from '../views/ChatView.vue'
import ChatHistoryView from '../views/ChatHistoryView.vue'
import CartView from '../views/CartView.vue'
import LoginView from '../views/LoginView.vue'
import OrderDetailView from '../views/OrderDetailView.vue'
import OrdersView from '../views/OrdersView.vue'
import PaymentCallbackView from '../views/PaymentCallbackView.vue'
import ProductDetailView from '../views/ProductDetailView.vue'
import ProductListView from '../views/ProductListView.vue'
import RegisterView from '../views/RegisterView.vue'

const routes = [
  { path: '/', component: HomeView },
  { path: '/shop', component: ProductListView },
  { path: '/products/:id', component: ProductDetailView },
  { path: '/chat', component: ChatView },
  { path: '/cart', component: CartView },
  { path: '/account', component: AccountView },
  { path: '/data-admin', component: DataAdminView },
  { path: '/history', component: ChatHistoryView },
  { path: '/orders', component: OrdersView },
  { path: '/orders/:id', component: OrderDetailView },
  { path: '/payment/callback', component: PaymentCallbackView },
  { path: '/login', component: LoginView },
  { path: '/register', component: RegisterView }
]

export const router = createRouter({
  history: createWebHistory(),
  routes
})
