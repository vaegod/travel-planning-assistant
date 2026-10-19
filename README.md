# 旅游规划智能助手

把目的地、天数和预算，变成一份可以保存、查看和继续咨询的旅行计划。

面向自由行用户，提供结构化行程生成、行程版本管理和 AI 多轮咨询，覆盖从出发前规划到行程细节确认的完整流程。

**技术栈：Java · Spring Boot · Spring Security · MyBatis · MySQL · Vue 3 · TypeScript · Qwen API · SSE · Docker Compose**

[功能演示](#功能演示) · [技术架构](#技术架构) · [快速开始](#快速开始) · [测试](#测试)

## 功能演示

**北京 · 两日游 · 预算 2000 元**：输入出行要求，生成每日安排，保存行程后继续咨询景点预约与出行建议。

### 输入要求，开始规划

![北京两日游规划首页](docs/screenshots/01-home.png)

### 保存行程，继续咨询

![围绕北京行程咨询景点预约](docs/screenshots/03-chat.png)

<details>
<summary><strong>展开查看：北京行程安排与预算明细</strong></summary>

![北京两日行程安排](docs/screenshots/02-itinerary.png)

</details>

<details>
<summary><strong>展开查看：我的行程</strong></summary>

![已保存的北京行程](docs/screenshots/04-trips.png)

</details>

## 核心功能

| 能力 | 用户体验 | 实现要点 |
| --- | --- | --- |
| 行程规划 | 输入城市、天数、预算，获得每日早中晚安排 | Qwen 结构化输出，校验目的地、天数、预算和时段完整性 |
| 保存与回看 | 确认后保存行程，查看列表与历史版本 | MySQL 持久化，事务、行锁和版本检查处理保存冲突 |
| 多轮咨询 | 围绕行程连续提问，流式查看回复 | SSE、近期成功问答与行程摘要组织上下文 |
| 生成控制 | 停止生成，确认结束后重新发送 | 请求取消、服务端状态核对与发送锁 |
| 账号管理 | 登录后管理自己的行程与对话 | Spring Security、会话认证、CSRF 校验与账号数据隔离 |
| 刷新恢复 | 重新打开页面后继续查看行程和聊天 | 消息与会话持久化、历史分页、失败状态恢复 |

## 技术架构

```text
Vue 3 + TypeScript + Vant
         │ HTTP / SSE
         ▼
Caddy：HTTPS、静态资源、同源 /api 代理
         │
         ▼
Spring Boot + Spring Security
  ├── 行程生成与结构校验 ─────────── Qwen API
  ├── 多轮上下文与流式咨询 ───────── Qwen API
  └── MyBatis：事务、行锁、版本检查
                  │
                  ▼
                MySQL
       账号 / 行程 / 版本 / 会话 / 消息
```

前端负责规划表单、行程展示和聊天交互；后端负责模型调用、数据校验和业务流程；MySQL 保存用户资料、行程版本与对话记录。应用与数据库通过 Docker Compose 在本机运行，模型通过 API 接入。

## 快速开始

准备 Docker Engine、Docker Compose 和模型 API Key。

```bash
git clone https://github.com/vaegod/travel-planning-assistant.git
cd travel-planning-assistant
cp deploy/compose.env.example deploy/compose.env
```

在 `deploy/compose.env` 中填写 `LLM_API_KEY`、`DB_PASSWORD`、`MYSQL_ROOT_PASSWORD`，然后启动：

```bash
docker compose --env-file deploy/compose.env up -d --build --wait
```

打开 [https://localhost:8443](https://localhost:8443)，注册账号即可开始规划。首次访问时导出并信任 Caddy 本地 CA，操作步骤见 [运行说明](docs/RUNNING.md)。

## 测试

| 范围 | 工具 | 覆盖场景 |
| --- | --- | --- |
| 前端 · 109 项 | Node Test Runner + assert | 行程校验、SSE 解析、取消请求、停止后重发 |
| 后端 · 145 项 | JUnit Jupiter + Spring Boot Test、Mockito | HTTP 接口、账号隔离、行程保存、异常处理与独立 MySQL 测试库 |
| 完整流程 | 浏览器操作 + HTTP 验证脚本 | 生成、确认保存、多轮咨询、刷新恢复与重新登录 |

前端测试与构建，在 `travel-h5-java` 中运行：

```bash
npm ci
npm test
npm run build
```

后端配置独立测试库后，在 `travel-java-server` 中运行：

```bash
./mvnw verify -B -ntp
```

[GitHub Actions](https://github.com/vaegod/travel-planning-assistant/actions) 执行前端测试与构建、后端测试与打包，以及容器部署验证。

## 项目结构

```text
travel-h5-java/       Vue 前端
travel-java-server/   Spring Boot 后端与数据库迁移
scripts/             开发与验证脚本
deploy/              Dockerfile、Caddy 与配置示例
docs/screenshots/    功能演示截图
compose.yaml         本机容器编排
```

## 开发工作

基于公开旅游项目二次开发，重点完善账号隔离、MySQL 持久化、行程版本管理、流式多轮问答、取消与重发控制，以及 Docker Compose 部署。
