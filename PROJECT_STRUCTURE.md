# 项目结构说明

## 核心模块

### 1. **ecommerce-backend/**
Spring Boot 3.3.3 + R2DBC 反应式后端
- **src/main/java/com/example/ecommerce/**
  - `model/` — 数据模型（ChatMessage、ChatSession）
  - `service/` — 业务逻辑（ChatService、AIAdvisorService）
  - `controller/` — REST API（ChatController 等）
  - `repository/` — 数据访问层（R2DBC）
- **src/main/resources/**
  - `schema.sql` — 数据库初始化脚本
  - `ai/advisor-knowledge-base.json` — AI 知识库配置
  - `application.properties` — 应用配置

### 2. **ecommerce-front/**
Vue 3 + TypeScript 前端应用
- **src/components/** — UI 组件
- **src/composables/** — Vue 组合式 API（useAuth、useChat）
- **src/services/** — API 客户端
- **src/views/** — 页面
- **src/App.vue** — 根组件（含用户在线状态同步）

### 3. **monitor-server/**
Netty TCP + HTTP 监控服务器
- **src/main/java/com/example/monitor/**
  - `handler/` — HTTP/TCP 请求处理器
  - `registry/` — 监控节点注册表（ONLINE/OFFLINE 状态）
  - `controller/` — 监控 API
- **sql/init-monitor-postgres.sql** — 监控数据库初始化

### 4. **monitor-front/**
Vue 3 监控前端
- 实时显示账户在线状态
- 性能监控概览
- 系统拓扑展示

### 5. **data-crawler/**
数据爬虫工具
- Python 脚本用于商品数据导入
- `requirements.txt` — 依赖配置

### 6. **docs/**
项目文档
- 架构设计文档
- API 文档
- 部署指南

## 启动脚本

- `start-ecommerce-backend.ps1` — 启动后端服务
- `start-monitor-server.ps1` — 启动监控服务
- `start-monitor-module.ps1` — 启动监控模块

## 已清理的文件

✅ 删除：
- `.runtime/` — 运行时缓存
- `10000.xlsx`、`商品-2026-03-13_19-19.xlsx` — 临时数据文件
- `PROJECT_HANDOFF_SUMMARY_2026-03-13.md` — 旧文档
- `RESTART_AND_MONITORING_FIX.md` — 旧文档
- `KNOWLEDGE_BASE_SUMMARY.md` — 旧文档
- 多余启动脚本 — 保留关键脚本

## 开发建议

1. **后端开发** — 修改 Spring Boot 服务，运行 `start-ecommerce-backend.ps1` 部署
2. **前端开发** — 修改 Vue 组件，`npm run dev` 本地调试
3. **数据导入** — 运行数据爬虫更新商品库
4. **监控** — 通过 monitor-front 实时查看系统状态

## 关键特性

✨ **已实现的功能：**
- ✅ 用户认证与会话管理
- ✅ Chat 消息持久化与会话隔离
- ✅ AI 导购知识库检索
- ✅ 实时账户在线状态同步
- ✅ 监控服务器性能指标

---

*最后更新：2026-3-19*
