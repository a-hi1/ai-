# Ecommerce Backend (Spring Boot + WebFlux + LangChain4j)

Skeleton project for AI-enabled ecommerce backend.

## Modules
- API for users, products, carts, orders
- AI chat service with RAG and skills
- Payment callbacks (Alipay sandbox)

## Run
1. Ensure PostgreSQL is running and create database `ecommerce`.
2. Set database environment variables (PowerShell):

```powershell
$env:DB_R2DBC_URL = "r2dbc:postgresql://localhost:5432/ecommerce"
$env:DB_USER = "postgres"
$env:DB_PASSWORD = "<your_postgres_password>"
```

3. Start backend:

```powershell
mvn spring-boot:run
```

Optional: if you only want to start service without initializing schema, set:

```powershell
$env:DB_INIT_MODE = "never"
```

## Database
- PostgreSQL: `ecommerce`
- Schema is in `src/main/resources/schema.sql`

## AI Providers
- Default provider: DeepSeek
- Supported providers: `deepseek`, `chatgpt`, `openai`, `openai-compatible`, `siliconflow`
- DeepSeek env vars: `DEEPSEEK_API_KEY`, `DEEPSEEK_BASE_URL`, `DEEPSEEK_MODEL_NAME`
- OpenAI-compatible env vars: `OPENAI_API_KEY`, `OPENAI_BASE_URL`, `OPENAI_MODEL_NAME`
- SiliconFlow env vars: `SILICONFLOW_API_KEY`, `SILICONFLOW_BASE_URL`, `SILICONFLOW_MODEL_NAME`
- SiliconFlow model name should use the full provider model ID, for example `Pro/zai-org/GLM-5`, not `GLM-5`

## Retrieval Gateway
- AI chat retrieval now goes through gateway service: `RetrievalGatewayService`
- Unified retrieval API: `GET /api/retrieval/search?q=...&limit=6`
- Optional filters: `category`, `maxPrice`
- Returned chunks always include metadata lines:
	- `product_id=...`
	- `update_time=...`

## Vector Catalog Maintenance
- Manual refresh: `POST /api/products/vector/refresh`
- Stats: `GET /api/products/vector/stats`

## Monitor Client (Netty)
- Env vars: `MONITOR_HOST`, `MONITOR_PORT`
- Advertise: `MONITOR_ADVERTISE_HOST`, `MONITOR_ADVERTISE_PORT`
- Optional `MONITOR_SERVICE_ID`, `MONITOR_SERVICE_NAME`

## API (skeleton)
- `POST /api/users`
- `GET /api/users`
- `GET /api/users/{id}`
- `POST /api/products`
- `GET /api/products`
- `GET /api/products/{id}`
- `GET /api/products?q=keyword`
- `POST /api/products/vector/refresh`
- `GET /api/products/vector/stats`
- `GET /api/retrieval/search?q=keyword&limit=6`
- `GET /api/cart/{userId}`
- `POST /api/cart/add`
- `DELETE /api/cart/{itemId}`
- `POST /api/orders`
- `GET /api/orders/user/{userId}`
- `GET /api/orders/{id}`
- `POST /api/chat/send`
- `GET /api/chat/history/{userId}`
- `POST /api/payments/alipay/notify`
- `POST /api/monitor/register`
- `POST /api/monitor/heartbeat`
- `POST /api/monitor/offline`
- `GET /api/monitor/list`
