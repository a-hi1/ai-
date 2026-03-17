# 项目完整交接总结（可直接给新 Copilot 继承）

更新时间：2026-03-13
工作区：D:\zhuomian\workspace

## 1. 项目整体结构

本工作区是一个“电商业务 + AI导购 + 监控平台”的组合工程，主要包含四个子项目：

1. ecommerce-backend
- 技术：Spring Boot 3.x + WebFlux + R2DBC + PostgreSQL + LangChain4j
- 作用：电商核心后端（用户、商品、购物车、订单、支付、AI导购、监控上报客户端）

2. ecommerce-front
- 技术：Vue 3 + TypeScript + Vite
- 作用：电商前台（商城、AI聊天导购、购物车、订单、账户、历史会话）

3. monitor-server
- 技术：Java + Netty + JDBC
- 作用：监控中心服务（TCP采集、HTTP接口、WebSocket推送、告警、控制命令）

4. monitor-front
- 技术：Vue 3 + TypeScript + Vite + Chart.js
- 作用：监控控制台前端（总览、节点详情、告警、控制操作）

## 2. 运行拓扑与端口

默认端口约定：

- 电商后端：8080（也可通过脚本起 8081/8082/8083 多实例）
- 监控 TCP：9999
- 监控 HTTP：9091
- 电商前端：通常 5173（取决于 Vite）
- 监控前端：固定脚本为 5176

关键启动脚本：

- start-ecommerce-backend.ps1
- start-monitor-server.ps1
- start-monitor-module.ps1
- start-domain-services.ps1
- start-monitor-node-instance.ps1（通用新增节点：serviceName/serverType/port）

说明：
- start-monitor-module.ps1 会串起监控存储初始化、监控服务、监控前端，并可拉起电商后端。
- 电商后端脚本内集成了 DB 环境设置、健康检查、日志输出、PID 管理与占口进程处理。

## 3. ecommerce-backend 架构与实现

### 3.1 入口与配置

- 启动类：src/main/java/com/example/ecommerce/Application.java
- 主配置：src/main/resources/application.yml
- 表结构：src/main/resources/schema.sql
- 示例数据：src/main/resources/sample-data.sql

application.yml 关键点：
- server.port 默认 8080
- spring.r2dbc 指向 PostgreSQL
- DB_INIT_MODE 默认 never
- AI provider 默认 deepseek
- payment 前端回调基础地址默认 http://127.0.0.1:5174
- monitor client 默认向 127.0.0.1:9999 上报

### 3.2 控制器分层

控制器目录：src/main/java/com/example/ecommerce/controller

主要接口簇：
- UsersController：/api/users（注册、登录、健康、资料更新）
- ProductsController：/api/products（列表、搜索、详情、相关商品）
- CartController：/api/cart（购物车增删改查、清空）
- OrdersController：/api/orders（下单、列表、详情）
- PaymentController：/api/payments（支付宝创建、通知、回调校验、查询）
- ChatController：/api/chat（发送、历史）
- AccountController：/api/account（账户总览、浏览轨迹）
- DataAdminController：/api/admin/data（数据概览、清理、重建）
- AiProviderConfigController：/api/admin/ai-config（AI配置查看与更新）
- MonitorController：/api/monitor（注册、心跳、离线、列表）

### 3.3 AI 导购核心链路

核心服务：
- src/main/java/com/example/ecommerce/service/AiShoppingAdvisorService.java

实现要点：
1. 输入解析
- 提取预算（正则）
- 识别意图品类（包含生活用品等）
- 构建需求摘要（预算/场景/偏好）

2. 候选召回与排序
- 向量检索 + 商品库合并
- 根据意图过滤
- 预算内优先
- 融合历史销量打分排序

3. 实时 AI 与回退
- 优先实时模型（DeepSeek）
- 若工具调用不可用或失败，走 Direct AI
- 再失败走规则兜底（真实商品库）
- 返回 payload 带 fallback 标识、insights、推荐清单、相关推荐

4. 严格意图约束
- 通过 preferStrictMainRecommendations 等逻辑收紧主推荐品类，避免“文案与卡片品类不一致”

### 3.4 LangChain4j + DeepSeek 兼容策略

关键文件：
- src/main/java/dev/langchain4j/model/openai/Http11OpenAiChatModel.java
- src/main/java/com/example/ecommerce/service/ai/DirectShoppingAdvisorAiService.java
- src/main/java/com/example/ecommerce/service/ai/ShoppingAdvisorAiService.java
- src/main/java/com/example/ecommerce/service/ai/AdvisorToolContext.java

说明：
- 项目保留 LangChain4j 架构，同时使用 DeepSeek 为默认 provider。
- 引入 HTTP/1.1 侧定制模型路径，降低实时调用中断影响。

## 4. ecommerce-front 架构与实现

### 4.1 入口与路由

- 入口：src/main.ts
- 路由：src/router/index.ts

主要页面路由：
- /
- /shop
- /products/:id
- /chat
- /cart
- /account
- /history
- /orders
- /orders/:id
- /payment/callback
- /login
- /register
- /data-admin

### 4.2 API 层

- 文件：src/services/api.ts

特点：
- 统一 request 封装（超时、重试、错误处理）
- 覆盖 auth、products、chat、cart、orders、payments、account、admin、ai-config
- Chat 发送超时拉长到 130s，支持一次重试

### 4.3 会话与认证

- 认证：src/composables/useAuth.ts
  - localStorage 键 auth_user
  - 登录/注册/刷新资料/退出

- 导购会话本地存储：src/services/chatSessions.ts
  - 存储键前缀 ecommerce-chat-sessions:{userId}
  - 按 userId 隔离，不同账号会话互不覆盖
  - 支持从后端历史导入到本地 session 结构

### 4.4 导购页实现

- 核心组件：src/components/ChatShopping.vue

实现要点：
1. 聊天消息结构：user/assistant + goodsList + insights + fallback
2. 发送流程：
- 推入用户消息
- 调 /api/chat/send
- 成功后显示推荐与洞察
- 失败则切换本地兜底推荐并提示
3. 商品联动：
- 推荐卡片支持详情跳转、加购物车
- 加购有显式成功提示
4. AI Provider 面板：
- 可读取/更新 DeepSeek 配置
5. 会话管理：
- 多会话切换、创建、持久化

### 4.5 最近已完成的前端改动（重要）

文件：src/App.vue

悬浮导购气泡已简化为聊天框模式：
- 移除“示例语句/填入示例/步骤提示”等引导块
- 保留单输入 + 提交按钮
- 用户输入后直接带 prompt 进入 /chat 并 autoSend
- 样式改为更紧凑简洁

## 5. monitor-server 架构与实现

### 5.1 启动与协议

- 启动类：src/main/java/com/example/monitor/MonitorServer.java

启动参数：
- tcpPort（默认9999）
- httpPort（默认9091）
- controlToken（默认 monitor-dev-token）

### 5.2 TCP 处理

- 文件：src/main/java/com/example/monitor/handler/MonitorTcpHandler.java

支持消息类型：
- REGISTER
- HEARTBEAT
- METRICS
- ALERT
- OFFLINE
- COMMAND_ACK

每行一个 JSON；解析后委派给 MonitorRegistry。

### 5.3 HTTP + WebSocket

- 文件：src/main/java/com/example/monitor/handler/MonitorHttpWebSocketHandler.java

HTTP 关键接口：
- GET /health
- GET /api/monitor/list
- GET /api/monitor/overview
- GET /api/monitor/detail/{serverId}
- GET /api/monitor/alerts
- GET /api/monitor/notifications
- GET /api/monitor/alert-policies
- POST /api/monitor/command
- POST /api/monitor/alerts/ack
- POST /api/monitor/notifications

WebSocket：
- /ws/monitor
- 支持 PING/PONG、SNAPSHOT 拉取

### 5.4 注册中心能力

- 文件：src/main/java/com/example/monitor/registry/MonitorRegistry.java

能力要点：
- 服务状态维护（在线/超时/离线）
- 指标历史与裁剪
- 阈值告警（CPU/MEMORY/DISK/网络延迟）
- 告警抑制与累计
- 命令下发和 ACK 结果归档
- WebSocket 广播快照
- JDBC 历史落库恢复

## 6. monitor-front 架构与实现

### 6.1 路由与入口

- 入口：src/main.ts
- 路由：src/router.ts（Hash 路由）
  - /
  - /node/:id

### 6.2 核心控制台

- 主组件：src/components/MonitorConsoleView.vue

功能覆盖：
- 总览与详情模式切换
- 搜索、类型过滤、状态过滤、排序、轮询间隔
- 实时连接状态（API / WS）
- Chart.js 图表展示
- 节点控制命令确认弹窗
- CSV 导出
- Toast 反馈

## 7. 数据与持久化说明

1. 电商业务数据：PostgreSQL（ecommerce）
- users/products/cart_items/orders/order_items/chat_messages/product_views

2. 会话数据：
- 后端 chat_messages 按 user_id 存储
- 前端 chatSessions 本地 localStorage 按 userId 分桶

3. 监控历史：
- monitor-server 通过 JDBC 历史库持久化

## 8. 当前运行健康状态（来自最近执行）

- ecommerce-backend 已构建成功并健康：http://127.0.0.1:8080
- monitor-server 与 monitor-front 有启动脚本联动
- 前端构建链路可运行

已知编译告警：
- LangChain4j OpenAI 相关 API 有 deprecated 警告
- DataAdminService 存在 unchecked 警告
- 不影响当前启动

## 9. 待继续事项（下一账号接手优先级）

1. 修正“生活用品文本意图 vs 电子产品卡片”一致性
- 检查 strict intent 过滤是否覆盖所有推荐出口（主推荐与related）

2. 监控前端下拉组件视觉整洁优化
- 主要在 MonitorConsoleView 的 toolbar 区

3. 顶部导航与账户入口简化确认
- 目标：仅头像进入个人空间（当前 App.vue 已接近）

4. 账户页去除购物车快照模块
- 检查 AccountView.vue 是否仍有快照区域

## 10. 新 Copilot 会话建议提示词（可直接粘贴）

请基于以下项目状态继续工作：
- 工作区是 D:\zhuomian\workspace，含 ecommerce-backend、ecommerce-front、monitor-server、monitor-front。
- 后端是 Spring Boot WebFlux + R2DBC + PostgreSQL；AI 使用 LangChain4j + DeepSeek，带 HTTP/1.1 定制模型路径。
- 前端是 Vue3+TS；导购悬浮气泡已改成“简化聊天框直发”模式（在 ecommerce-front/src/App.vue）。
- 监控端是 Netty monitor-server + Vue monitor-front，HTTP 9091，TCP 9999。
- 请先验证当前待办：
  1) 生活用品意图与推荐卡片品类一致性；
  2) monitor-front 下拉组件和顶部工具栏 UI 整洁优化；
  3) Account 页面去掉购物车快照；
  4) 顶部导航账户提示仅保留头像入口。
- 注意：不要破坏已稳定的 DeepSeek 实时调用链路与 fallback 机制。

## 11. 关键文件索引

后端：
- ecommerce-backend/src/main/resources/application.yml
- ecommerce-backend/src/main/resources/schema.sql
- ecommerce-backend/src/main/java/com/example/ecommerce/service/AiShoppingAdvisorService.java
- ecommerce-backend/src/main/java/com/example/ecommerce/controller/ChatController.java
- ecommerce-backend/src/main/java/com/example/ecommerce/controller/ProductsController.java
- ecommerce-backend/src/main/java/dev/langchain4j/model/openai/Http11OpenAiChatModel.java

电商前端：
- ecommerce-front/src/App.vue
- ecommerce-front/src/components/ChatShopping.vue
- ecommerce-front/src/services/api.ts
- ecommerce-front/src/services/chatSessions.ts
- ecommerce-front/src/composables/useAuth.ts
- ecommerce-front/src/composables/useCart.ts
- ecommerce-front/src/router/index.ts

监控后端：
- monitor-server/src/main/java/com/example/monitor/MonitorServer.java
- monitor-server/src/main/java/com/example/monitor/registry/MonitorRegistry.java
- monitor-server/src/main/java/com/example/monitor/handler/MonitorTcpHandler.java
- monitor-server/src/main/java/com/example/monitor/handler/MonitorHttpWebSocketHandler.java

监控前端：
- monitor-front/src/router.ts
- monitor-front/src/components/MonitorConsoleView.vue

脚本：
- start-monitor-module.ps1
- start-monitor-server.ps1
- start-ecommerce-backend.ps1
- start-domain-services.ps1
- start-monitor-node-instance.ps1

---

如果需要“完全继承”体验，建议在新账号第一条消息直接贴本文件全文，然后再追加你当前要做的单一任务。