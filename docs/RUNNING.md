# 本机运行说明

## Docker Compose

1. 复制 `deploy/compose.env.example` 为 `deploy/compose.env`。
2. 填入 `LLM_API_KEY`、`DB_PASSWORD`、`MYSQL_ROOT_PASSWORD`。数据库密码分别使用不同随机值，并保留配置文件。
3. 在仓库根目录执行：

```bash
docker compose --env-file deploy/compose.env up -d --build --wait
docker compose --env-file deploy/compose.env ps
```

入口为 https://localhost:8443。数据保存在命名卷中。首次访问需要信任本机 Caddy CA：

```bash
docker compose --env-file deploy/compose.env cp web:/data/caddy/pki/authorities/local/root.crt ./localhost-root.crt
```

将导出的公开证书安装到当前用户信任根证书后重新打开页面。

停止服务并保留数据：

```bash
docker compose --env-file deploy/compose.env down
```

## Windows 辅助入口

复制 `travel-java-server/.env.example` 为同目录 `.env`，填写模型 Key，然后：

```powershell
.\deploy.ps1 -Build
.\deploy.ps1 -Action status
.\deploy.ps1 -Action cert
```

该入口将随机数据库密码保存在 `.runtime/compose.env`，使用同一入口管理后续启停。两种配置方式选择一种，持续使用相同配置和数据卷。

## 开发与测试

开发环境需要 Java 21（编译目标 17）、Node.js 24 与 MySQL。通过后端 `.env` 设置 `DB_URL`、`DB_TEST_URL`、`DB_USERNAME`、`DB_PASSWORD`，测试库与业务库分别配置，测试账号应拥有测试库权限。

前端执行 `npm ci`、`npm test`、`npm run dev`；后端使用 Maven Wrapper。原 Windows `start.ps1` 支持本机 WSL MySQL 的开发启动。

模型 API Key、数据库密码、浏览器会话与本机账号文件均通过忽略规则保存在本地。
