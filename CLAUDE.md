# 本任务执行约束（自动加载）

你在隔离 Docker 容器内生成 0-1 工程（/workspace 已挂载到本容器），严格遵守以下约束：

## 1. 验证必须用 docker（宿主 daemon 已通过 TCP 提供给你）
- 写完项目后，用项目自身的 `docker compose up -d` 启动并验证。
- 宿主 docker daemon 地址已通过环境变量 `DOCKER_HOST` 提供，docker 客户端已就绪，直接可用。
- 本项目在 compose 中的项目名已由环境变量 `COMPOSE_PROJECT_NAME` 固定（如 task01），不要改它。
- 验证时**只把应用服务端口发布到宿主**，用环境变量 `CC_PUBLISH_PORT` 作为宿主机端口，compose 写法：
  `ports: ["${CC_PUBLISH_PORT}:<内部端口>"]`（例如 3001:3000）。内部端口按你的技术栈定。
- **数据库 / 缓存等依赖服务不要发布到宿主端口**，仅在 compose 内部网络互访；应用服务通过服务名访问它们。
- 验证用 `docker compose port <服务名> <内部端口>` 取实际映射端口，再用 `host.docker.internal:<端口>` 访问，
  不要依赖硬编码的 3000/5432/6379 等常见端口（并行任务会冲突）。
- 验证结束执行 `docker compose down` 释放资源。

## 2. 禁止为验证 apt-get 安装重型 SDK
- 不得为编译/启动自测而 `apt-get install` JDK、Maven、Gradle、Golang、Rust、PostgreSQL/MySQL 服务端等重型工具链。
- 运行时由你写的 Dockerfile 基础镜像（openjdk / node / postgres / redis 等）提供，你只需写正确的 Dockerfile 与 compose。
- Node 项目可正常使用 npm（已预装）；但启动/验证仍建议通过 compose。

## 3. 交付物必须 Docker 一键部署
- 必须产出：多阶段 `Dockerfile`（非 root 用户 + HEALTHCHECK）、`docker-compose.yml`（含 app + 必要依赖服务）、`.env.example`。
- README 必须给出 `docker compose up -d` 启动方式、测试账号/演示数据（逐角色：用户名/密码/权限）、以及"验证方式=宿主 docker compose up"的说明。
- **README 必须包含「原始需求 / 原始 Prompt」整段原文**（用户交付给你的任务描述，一字不漏原样粘贴，不要改写、摘要或只列要点）。放在 README 顶部独立章节（如 `## 原始需求`），便于评审对照验收。

## 4. 诚实原则
- 不要假装本地 apt 装好能跑；以"compose up 健康 + 关键业务流可在浏览器/接口走通"为验证通过标准。

## 5. 其他
- 不写与本题无关的个人 skill / memory / 全局配置；只产出本题工程文件。
- 每轮对话结束前确认：Dockerfile / compose 已就绪、README 含启动命令与测试账号。

## 6. 禁止调用任何 Skill（强制）
- 执行本任务过程中，**不得调用任何 Skill**（含内置 Skill 与用户自定义 Skill），不要通过 `/<skill>` 命令或任何 skill 触发机制把任务委托出去。
- 需求分析、任务规划、代码实现、依赖管理、验证自测等环节，一律由你直接完成，不得借助 skill 代劳。
- 即使检测到可匹配的 skill（如部署、测试、文档类），也**必须忽略并自行处理**。
- 原因：本任务目的是蒸馏你原生的 0-1 生成与验证能力，调用 skill 会绕过真实能力、使评估结果失真。
