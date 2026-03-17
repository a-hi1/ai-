# Monitor Server (Netty)

Netty-based monitor server for service registration, realtime metrics ingestion, alerting, WebSocket fanout and console control commands.

## Responsibilities
- Service registration and status transitions
- Heartbeat and metrics ingestion over TCP line-delimited JSON
- Offline detection and transport error capture
- JVM / thread / GC / heap detail aggregation
- Threshold alerting with duplicate suppression
- HTTP + WebSocket APIs for the monitor console

## TCP Protocol
Each line is one JSON object.

### Supported message types
- `REGISTER`
- `HEARTBEAT`
- `METRICS`
- `ALERT`
- `OFFLINE`
- `COMMAND_ACK`

### Example payload
```json
{
	"type": "METRICS",
	"serverId": "ecommerce-backend-1",
	"serverType": "product",
	"serviceName": "ecommerce-backend",
	"host": "127.0.0.1",
	"port": 8080,
	"startupTime": "2026-03-11T13:00:00Z",
	"cpuUsage": 42.6,
	"memoryUsage": 68.1,
	"jvmHeapUsage": 54.2,
	"diskUsage": 71.4,
	"networkLatency": 12,
	"networkThroughputMbps": 8.4,
	"threadCount": 96,
	"daemonThreadCount": 28,
	"gcCount": 14,
	"gcPauseMs": 6.5,
	"heapUsedMb": 512.0,
	"heapMaxMb": 1024.0,
	"systemLoad": 47.2,
	"jvmStackSummary": "RUNNABLE 18 | BLOCKED 1 | WAITING 24",
	"lastGcAt": "2026-03-11T13:09:51Z",
	"status": "ONLINE"
}
```

## Alert Policies
Built-in policy evaluation happens server-side.

- `CPU_USAGE`: warning `85%`, critical `95%`
- `MEMORY_USAGE`: warning `82%`, critical `90%`
- `DISK_USAGE`: warning `85%`, critical `93%`

Repeated alerts for the same node + policy + severity are suppressed inside a policy-specific window and accumulated into the next emitted alert.

## HTTP API
- Default HTTP port: `9091`
- `GET /health`
- `GET /api/monitor/list`
- `GET /api/monitor/overview`
- `GET /api/monitor/detail/{serverId}`
- `GET /api/monitor/alerts`
- `GET /api/monitor/notifications`
- `GET /api/monitor/alert-policies`
- `POST /api/monitor/command`
- `POST /api/monitor/alerts/ack`
- `POST /api/monitor/notifications`

## WebSocket
- Endpoint: `/ws/monitor`
- Client can send `SNAPSHOT`
- Server pushes snapshot events containing services, recent alerts and active alert count

## Monitor Frontend Routes
- `#/` total overview page
- `#/node/{id}` node detail page
- `#/alerts` alert center page

## Start
- `java -cp target/... com.example.monitor.MonitorServer <tcpPort> <httpPort> <controlToken>`
- Example: `MonitorServer 9999 9091 monitor-dev-token`
