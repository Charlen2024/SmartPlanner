# web-front

SmartPlanner 前端（Vue3 + Vite + Vuetify）。默认通过 Nginx/网关转发访问后端接口。

## 本地运行

```bash
npm install
npm run dev
```

默认 dev server 会走 `vite.config.js` 的代理配置（将 `/api` 转发到网关）。

## 构建

```bash
npm run build
```

## Docker

项目根目录执行：

```bash
docker compose up -d --build web-front
```
