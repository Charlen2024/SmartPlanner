# Docker 部署指南

本系统采用 Docker Compose 进行一键化编排部署。

## 1. 环境准备
- 安装 Docker 和 Docker Compose
- 确保本地 3306, 6379, 8848, 9200 等端口未被占用

## 2. 快速启动
在项目根目录下执行：
```bash
docker-compose up -d --build
```

如需启用大模型能力，请先在项目根目录创建 `.env`（可从 `.env.example` 复制），填写：
- OPENAI_API_KEY
- BASE_URL
- MODEL

该命令会自动：
1. 构建各微服务的镜像（通过多阶段构建优化体积）
2. 启动基础设施（Nacos, MySQL, Redis, RabbitMQ, ES, Seata）
3. 启动所有微服务并注册到 Nacos

## 3. 访问入口
- **Nacos 控制台**: http://localhost:8848/nacos (账号/密码: nacos/nacos)
- **RabbitMQ 管理界面**: http://localhost:15672 (账号/密码: guest/guest)
- **微服务接口**:
    - Goal Service: http://localhost:8081
    - Schedule Engine: http://localhost:8082
    - Resource Search: http://localhost:8083
    - Punch Service: http://localhost:8084
    - User Service: http://localhost:8080

## 4. 常用命令
- 查看日志: `docker-compose logs -f [service_name]`
- 停止系统: `docker-compose down`
- 重启特定服务: `docker-compose restart goal-service`
