# web-front

SmartPlanner 前端 — Vue 3 + Vite + Vuetify 3 + Pinia。通过 Nginx/网关转发访问后端微服务接口。

## 技术栈

| 类别 | 技术 |
|------|------|
| 框架 | Vue 3 (Composition API, `<script setup>`) |
| 构建 | Vite |
| UI 库 | Vuetify 3 (Material Design) |
| 状态管理 | Pinia |
| 路由 | Vue Router 4 |
| HTTP | Axios |
| Markdown | marked |
| 实时通信 | SSE (Server-Sent Events) |

## 页面结构

| 路由 | 页面 | 说明 |
|------|------|------|
| `/login` | LoginView | 登录/注册 |
| `/plan` | PlanView | 学习计划向导（课表导入 + 目标创建） |
| `/` | DashboardView | 仪表盘 — 指标卡片、天气、日程时间轴、目标进度 |
| `/goals` | GoalsView | 目标管理 — AI 拆解、任务查看、排程生成 |
| `/schedule` | ScheduleView | 日程 — 日期切换、时间轴、课表管理、周视图 |
| `/punch` | PunchView | 打卡计时 — 番茄钟、AI 建议、资源推荐 |
| `/resources` | ResourcesView | 资源检索 — kNN 向量搜索、平台课程卡片 |
| `/journals` | JournalsView | 随笔 — 心情选择、时间分组 |
| `/profile` | ProfileView | 学习画像 — 习惯指标、AI 建议、计算明细 |
| `/games/2048` | Game2048View | 2048 小游戏 |

## 本地运行

```bash
npm install
npm run dev
```

Dev server 默认通过 `vite.config.js` 代理将 `/api` 转发到网关 `http://localhost:8088`。

## 构建

```bash
npm run build
```

产物输出到 `dist/`，由 Nginx 静态托管。

## Docker

项目根目录执行：

```bash
docker compose up -d --build web-front
```

## 项目结构

```
web-front/src/
├── App.vue                    # 根组件
├── style.css                  # 全局样式（滚动条、主题变量）
├── main.js                    # 入口（Vuetify 注册、路由挂载）
├── plugins/
│   └── api.js                 # Axios 实例（JWT 拦截、刷新、401 处理）
├── router/
│   └── index.js               # 路由配置（导航守卫、scrollBehavior）
├── stores/
│   ├── auth.js                # 认证状态
│   ├── assistant.js           # Agent 对话（SSE 流、Markdown 渲染、工具调用）
│   ├── notify.js              # 通知中心（SSE 事件、铃铛列表、去重）
│   ├── decompose.js           # 目标拆解动画面板
│   └── schedule.js            # 排程进度面板
├── layouts/
│   └── DefaultLayout.vue      # 主布局（导航抽屉、顶栏、Agent 浮窗、通知）
├── components/
│   ├── DecomposePanel.vue     # AI 拆解进度浮动面板
│   └── SchedulePanel.vue      # 排程进度浮动面板
└── views/
    ├── LoginView.vue          # 登录/注册
    ├── PlanView.vue           # 学习计划向导
    ├── DashboardView.vue      # 仪表盘
    ├── GoalsView.vue          # 目标管理
    ├── ScheduleView.vue       # 日程管理
    ├── PunchView.vue          # 打卡计时
    ├── ResourcesView.vue      # 资源检索
    ├── JournalsView.vue       # 随笔
    └── ProfileView.vue        # 学习画像
```

## SSR / 预渲染（Electron 可选）

默认未启用 SSR，如需 Electron 或预渲染方案请参考 Vite SSR 文档。

## Nginx 配置要点

生产部署时 Nginx 需注意：

- 静态文件 `try_files $uri $uri/ /index.html`（SPA 回退）
- `/api/agent/chat/stream` 关闭缓冲（`proxy_buffering off`）确保流式输出
- `/api/user/notifications/stream` 适当延长 `proxy_read_timeout`（如 600s）
