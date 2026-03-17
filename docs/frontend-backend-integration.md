# Frontend <-> Backend Integration Checklist

## 1. Base URLs
- Ecommerce frontend: `VITE_API_BASE` (default `http://localhost:8080`)
- Monitor frontend: direct monitor server API `http://localhost:9091`

## 2. Ecommerce APIs

### 2.1 Chat send
- Method: `POST /api/chat/send`
- Request:
```json
{
  "userId": "uuid-string",
  "message": "I want a wireless headset under 100"
}
```
- Response:
```json
{
  "reply": "Here are some products...",
  "timestamp": "2026-03-10T10:00:00Z"
}
```

### 2.2 Product semantic search
- Method: `GET /api/products?q=keyword`
- Response:
```json
[
  {
    "id": "uuid-string",
    "name": "Product name",
    "description": "...",
    "price": 99.9,
    "imageUrl": "https://...",
    "tags": "a,b"
  }
]
```

### 2.3 Create order
- Method: `POST /api/orders`
- Request:
```json
{
  "userId": "uuid-string"
}
```
- Response:
```json
{
  "id": "uuid-string",
  "status": "CREATED"
}
```

### 2.4 Payment notify (sandbox)
- Method: `POST /api/payments/alipay/notify`
- Request:
```json
{
  "orderId": "uuid-string",
  "status": "PAID",
  "gatewayTradeNo": "SANDBOX-123"
}
```
- Response: plain text (`OK` recommended)

## 3. Monitor APIs

### 3.1 Service list
- Method: `GET http://localhost:9091/api/monitor/list`
- Response:
```json
[
  {
    "id": "uuid-string",
    "serviceName": "ecommerce-backend",
    "host": "127.0.0.1",
    "port": 8080,
    "status": "ONLINE",
    "lastHeartbeat": "2026-03-10T10:00:00Z"
  }
]
```

## 4. Netty registration protocol
Backend monitor client sends TCP line commands to monitor server:
- `REGISTER <serviceId> <serviceName> <host> <port>`
- `HEARTBEAT <serviceId>`
- `OFFLINE <serviceId>`

## 5. 联调顺序 (recommended)
1. Start `monitor-server` (Netty)
2. Start `ecommerce-backend` (Spring Boot)
3. Open monitor frontend and check service list updates
4. In ecommerce frontend: login -> chat -> add to cart -> pay
5. Verify backend writes order status and chat history

## 6. 需要后端补充/确认
- CORS allow origins: frontend dev ports
- Auth/token strategy (current frontend is local auth placeholder)
- Payment signature & callback verification (currently sandbox placeholder)
- Product ID shape: UUID vs numeric (frontend already maps UUID safely)
- Unified error format: `{ code, message, traceId }`

## 7. Frontend fallback logic
- Chat: backend unavailable -> local mock products fallback
- Monitor list: backend unavailable -> empty table
- Payment: backend unavailable -> prompt message
