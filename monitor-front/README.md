# monitor-front

监控前端使用 Vue 3 + TypeScript + Vite。

## 启动

推荐直接使用内置脚本，不要再额外拼 `-- --host ...` 参数：

```powershell
npm run dev
```

行为说明：

- 默认绑定到 `localhost`
- 优先尝试 `5173`
- 如果端口被占用，会自动递增寻找可用端口
- 启动后自动打开根路径，避免因为错误参数导致根路径 404

如果你需要固定端口并且端口不可用时直接失败，使用：

```powershell
npm run dev:fixed
```

这个脚本固定使用 `http://localhost:5176/`。

## 路由

项目使用 hash 路由：

- 总览页：`/`
- 告警页：`/#/alerts`
- 节点详情页：`/#/node/:id`

例如：

- `http://localhost:5173/`
- `http://localhost:5173/#/alerts`

如果 Vite 自动切换了端口，把上面的 `5173` 替换成实际启动日志里的端口即可。
