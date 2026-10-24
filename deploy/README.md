# 单机可复现部署

部署独立 Agent、专用 MySQL 和 Redis，适合先建立可验收的测试环境。默认是 **demo,persistent**：模拟身份、模型和指标，真实存储；不会调用收费模型。

真实业务模式需显式启用，并提供已部署的 HTTPS 业务桥接。本方案不自动初始化原视频平台的业务表，也不把测试数据当成正式数据。

## 环境

- Linux x86_64 / ARM64，Docker Engine 与 Compose 2.20+，Python 3。
- 至少 1.8 GiB 内存、5 GiB 可用磁盘；建议 4 GiB 内存和更多持久化空间。
- 生产使用受支持的操作系统。CentOS 7 已停止维护，仅允许本方案的 staging 验收模式。

数据库与 Redis 无宿主机端口；Agent 只映射到 **127.0.0.1:18201**。公网访问应在完成正式认证后接入 HTTPS 网关。Demo 固定凭证不适合公网开放。

## 1. 初始化私有配置

将源码放入独立目录，例如 `/opt/tianmu-staging/source`。从仓库根目录运行：

```sh
sudo python3 deploy/manage.py init --directory /opt/tianmu-staging/config
```

生成数据库、Redis、ClickHouse 的独立随机凭据和非秘密配置文件，全部位于源码目录之外：

```text
/opt/tianmu-staging/config/
├── runtime.env
└── secrets/
    ├── agent-db-password
    ├── mysql-root-password
    ├── redis-password
    └── clickhouse-password
```

配置目录仅 root 可进入，凭据文件由容器 UID 10001 读取。初始化不会覆盖已有配置或旋转已使用的数据库密码。凭据以只读文件挂载，入口脚本读取后放入进程环境，不写进镜像或命令行。

可在 `runtime.env` 修改独立栈名、回环端口和资源上限。配置不是 Shell 脚本，不使用 `source` 或 `eval` 加载。

## 2. 检查并构建版本

```sh
sudo python3 deploy/manage.py preflight --env /opt/tianmu-staging/config/runtime.env
sudo python3 deploy/manage.py build --env /opt/tianmu-staging/config/runtime.env
```

构建器先将 Maven、JRE、MySQL 和 Redis 标签解析为实际镜像 digest，再构建非 root Agent 镜像。构建完成后输出该版本的 `release.env` 路径，并保存源码摘要、镜像 ID、依赖镜像 digest 和 Compose 配置摘要。

运行容器使用只读根文件系统、受限临时目录、能力裁剪和资源上限；MySQL、对象目录与 Trace 使用持久卷。所有服务日志默认每文件 10 MiB、最多 3 个文件。

## 3. 启动与验证

将路径替换为构建结果：

```sh
sudo python3 deploy/manage.py up --env /opt/tianmu-staging/config/releases/RELEASE_ID/release.env
sudo python3 deploy/manage.py status --env /opt/tianmu-staging/config/releases/RELEASE_ID/release.env
sudo python3 deploy/manage.py verify --env /opt/tianmu-staging/config/releases/RELEASE_ID/release.env
```

`up` 校验镜像和配置未被替换，等待依赖及 Agent 健康，再验证探针、认证、FAQ、指标证据和 SSE。成功后更新 `current-release` 文件。

Flyway 仅迁移 Agent 专用数据库；不清库、不自动 baseline 非空业务库。不要执行 `docker compose down -v`，它会删除持久卷。

当前数据库只允许一个 Agent 写实例。版本切换先停止旧 Agent，再启动新版本，会产生短暂停机。复用旧版本的 `release.env` 可以切回保留的镜像与部署配置；数据库迁移和数据格式的回退兼容性仍需单独审查，脚本不会自动逆向迁移数据库。

## 4. 健康检查

```sh
curl -f http://127.0.0.1:18201/actuator/health/liveness
curl -f http://127.0.0.1:18201/actuator/health/readiness
```

- **Liveness** 仅反映应用存活，不因外部依赖故障触发重启风暴。
- **Readiness** 检查应用就绪、MySQL 写实例锁与查询连接，以及所选私有对象存储的实际写入 / 读取校验 / 清理能力。staging显式使用文件，business使用既有MinIO。
- Redis 可回源 MySQL，Trace 故障也允许降级，因此不作为存活或就绪的硬依赖。
- 探针匿名响应仅提供状态，不包含凭据、异常正文或文件路径。只暴露 health，不开放 env、heapdump 等管理端点。

Compose 的健康状态不会自动重启仍在运行但不健康的进程；`unless-stopped` 负责进程退出后的重启。就绪故障应由运维检查依赖和存储，不以无限重启代替修复。

## 5. 从本机访问

```sh
ssh -N -L 18201:127.0.0.1:18201 USER@SERVER
```

之后使用本机 `http://127.0.0.1:18201`。不需要开放服务器的数据库、Redis 或 Demo HTTP 公网端口。

## 6. 显式切换真实业务模式

先完成业务桥接、正式账号和数据口径验收，在 `runtime.env` 中配置 `BUSINESS_BASE_URL=https://...` 和容器能访问的既有 `AGENT_MINIO_URL`。私有bucket默认 `tianmu-agent-artifacts`，可选配置 `AGENT_MINIO_BUCKET` / `AGENT_MINIO_PREFIX`。将模型Key放在私有 `secrets/model-api-key`，既有MinIO凭据分别放在 `secrets/minio-access-key` 与 `secrets/minio-secret-key`，设置与其他凭据相同的所有者和权限，再构建：

```sh
sudo python3 deploy/manage.py build --business --env /opt/tianmu-staging/config/runtime.env
sudo python3 deploy/manage.py up --env /opt/tianmu-staging/config/releases/NEW_RELEASE_ID/release.env
```

真实业务模式使用 `business,deepseek,persistent`，不会回退 Demo。此时 `verify` 只检查存活和就绪，不冒用 Demo 身份，也不自动发出收费模型查询。正式登录与业务查询需要使用授权账号另行验收。

报告和证据对象写入同一MinIO实例的私有bucket，业务部署不会新建MinIO服务。已有staging文件对象切换前按[存储迁移要求](../tianmu-agent/docs/持久化存储接入.md)复制并核对，未迁移时继续使用原staging配置。

本方案先解决环境复现与部署边界；备份恢复、业务指标水位、生产限流、告警、完整压力测试与多实例属于后续生产化工作。

Kafka Trace 与 ClickHouse 落库使用可选部署叠加文件；启用、查询、重复语义及保留策略见 [Kafka → ClickHouse Trace](KAFKA_TRACE.md)。
