# Vue3 Full Frontend Implementation Guide

## 1. Scope
This guide covers full frontend delivery in VS Code for:
- `ecommerce-front`: chat shopping UI, cart, login/register, payment jump
- `monitor-front`: monitor dashboard for online/heartbeat status

Workspace file:
- `workspace.code-workspace`

## 2. Tooling and Environment
- Browser: Chrome + Immersive Translate extension (install manually from Chrome Web Store)
- Frontend IDE: VS Code + Trae + Vue extensions
- Backend IDE: IntelliJ IDEA + Trae
- Node.js: 20+
- Package manager: npm

Chrome plugin install steps:
1. Open Chrome Web Store
2. Search `Immersive Translate`
3. Click Add to Chrome
4. Pin extension for development and API doc translation

Recommended VS Code extensions in workspace:
- `vue.volar`
- `vue.vscode-typescript-vue-plugin`
- `dbaeumer.vscode-eslint`
- `esbenp.prettier-vscode`
- `trae.ai-ide`
- `doubao.doubao-app-share-vscode-plugin`

## 3. Project Start Commands
From each project root:

```bash
npm install
npm run dev
```

Default dev ports are Vite-managed per project.

Suggested startup order:
1. `monitor-server` (Netty)
2. `ecommerce-backend` (Spring Boot)
3. `monitor-front`
4. `ecommerce-front`

## 4. Environment Variables
Create `.env` from `.env.example`:

- `ecommerce-front/.env.example`
- `monitor-front/.env.example`

Current variables:
- `VITE_API_BASE` for ecommerce frontend
- `VITE_MONITOR_API_BASE` for monitor dashboard

## 5. Ecommerce Frontend Architecture
Key files:
- App shell + nav: `ecommerce-front/src/App.vue`
- Router: `ecommerce-front/src/router/index.ts`
- Views:
  - `ecommerce-front/src/views/ChatView.vue`
  - `ecommerce-front/src/views/CartView.vue`
  - `ecommerce-front/src/views/LoginView.vue`
  - `ecommerce-front/src/views/RegisterView.vue`
- Chat component: `ecommerce-front/src/components/ChatShopping.vue`
- State composables:
  - `ecommerce-front/src/composables/useAuth.ts`
  - `ecommerce-front/src/composables/useCart.ts`
- API layer: `ecommerce-front/src/services/api.ts`

Creation workflow (from empty Vue app):
1. Create router and base views (`/chat`, `/cart`, `/login`, `/register`)
2. Add composables for `useAuth` and `useCart`
3. Add API module with centralized `request()` wrapper
4. Build chat UI component and wire:
  - user message append
  - backend chat request
  - product semantic query
  - fallback local recommendations
5. Build cart page with payment jump placeholder and backend notify endpoint
6. Apply responsive UI theme and desktop/mobile adaptation

Implemented features:
- Login/register local auth persistence
- Chat UI with backend-first flow:
  - `POST /api/chat/send`
  - `GET /api/products?q=...`
- Cart add/remove/clear with local storage
- Payment flow placeholder:
  - create order `POST /api/orders`
  - sandbox URL jump
  - notify callback `POST /api/payments/alipay/notify`

Current API contract expected by frontend:
- `POST /api/chat/send` -> `{ reply, timestamp }`
- `GET /api/products?q=keyword` -> `Product[]`
- `POST /api/orders` -> `{ id, status }`
- `POST /api/payments/alipay/notify` -> `string`

## 6. Monitor Frontend Architecture
Key files:
- App shell: `monitor-front/src/App.vue`
- Dashboard: `monitor-front/src/components/MonitorDashboard.vue`

Implemented features:
- Poll monitor backend every 5s
- Status cards: total/online/offline
- Service list table
- Endpoint: `GET /api/monitor/list`

## 7. AI Integration Notes
当前 AI 能力已经收敛到 `ecommerce-front` 与 `ecommerce-backend` 的联动，不再单独维护独立 AI 控制台前端。

如果后续需要恢复独立的模型配置面板，建议以新的业务目标重新创建，而不是继续保留未接入主流程的独立前端目录。

## 8. Backend Contract Alignment
前后端接口契约、联调顺序和回退策略统一以 `docs/frontend-backend-integration.md` 为准，这里不再重复维护一份详细清单。

## 9. UI Direction
Current UI is intentionally non-boilerplate:
- warm neutral palette with green accent
- responsive layouts for desktop/mobile
- dashboard card+table hierarchy

## 10. Next Integration Steps
1. Add centralized toast/error component and replace `alert`.
2. Add route guard for `/chat` and `/cart`.
3. Add order history page and payment result page.
4. Add websocket/SSE monitor stream to replace polling.
5. Add i18n support for Chinese/English switching.

## 11. Build Validation Snapshot
Validated with `npm run build`:
- `ecommerce-front`: success
- `monitor-front`: success
