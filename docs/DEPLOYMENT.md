# 启动与配置

## 免 Key Demo

见根 README。仅需 Java 17 / Maven，构建 `tianmu-agent/pom.xml` 后直接运行 JAR。不需要先启动视频平台、数据库或 Docker；默认数据是模拟值，内存任务在重启后丢失。

`.env.example` 是配置清单，Spring 和 Java 工具类不会自动加载 `.env`。使用操作系统进程环境、IDE Run Configuration 或部署平台的密钥注入；不要使用 `source .env` 加载来源不明的文件。

## 真实模型

在当前终端注入 `DEEPSEEK_API_KEY`，必要时设置 `DEEPSEEK_BASE_URL`、`PREPARATION_MODEL`、`HARNESS_MODEL`。模型 ID 取决于账户实际可用范围；默认值仅为本项目已验证的配置。

```sh
java -jar tianmu-agent/target/operations-agent-0.7.0-SNAPSHOT.jar --spring.profiles.active=demo,deepseek
```

此时业务数据和身份仍为演示配置，模型请求是真实收费请求。不得将不允许外发的数据放进提问或工具结果。

## Agent 持久化

可选容器配置在 `tianmu-agent/infra/storage/compose.yml`。为 `AGENT_DB_PASSWORD` 和 `AGENT_MYSQL_ROOT_PASSWORD` 分别生成私有值，再启动：

```sh
docker compose -f tianmu-agent/infra/storage/compose.yml up -d
```

设置 `AGENT_JDBC_URL=jdbc:mysql://127.0.0.1:3307/Tianmu_agent?serverTimezone=UTC`、`AGENT_DB_USER=agent`、对应密码及 `AGENT_REDIS_PORT=6380`，再运行：

```sh
java -jar tianmu-agent/target/operations-agent-0.7.0-SNAPSHOT.jar --spring.profiles.active=demo,persistent
```

Flyway 只管理 Agent 专用数据库；不要把 AGENT_JDBC_URL 指向原业务数据库。产物目录通过 AGENT_OBJECT_DIRECTORY 配置，默认 `./var/artifacts`，不可直接作为 Web 静态目录公开。持久化边界详见 [存储接入](../tianmu-agent/docs/持久化存储接入.md)。

## 真实业务桥接

先阅读 [P0 业务接入](../tianmu-agent/docs/P0业务接入.md)。两个进程分别构建和运行：

```sh
mvn -B package
mvn -B -f tianmu-agent/pom.xml package
java -jar target/tianmu-0.0.1-SNAPSHOT-agent-bridge.jar
# 另一个终端，注入对应的 Agent 配置
java -jar tianmu-agent/target/operations-agent-0.7.0-SNAPSHOT.jar --spring.profiles.active=business,deepseek,persistent
```

业务桥接需要 BUSINESS_JDBC_URL、BUSINESS_DB_USER、BUSINESS_DB_PASSWORD、BUSINESS_ACTIVE_USER_STATES、业务登录 Redis 地址及 JWT_SECRET_BASE64。BUSINESS_ACTIVE_USER_STATES 必须填写现有数据库实际正常状态值。JWT 签名密钥需要和业务登录服务一致；建议为只读桥接使用数据库 SELECT 专用账号。

`BUSINESS_REDIS_DATABASE` 必须与实际登录 Redis 的数据库索引一致；嵌入原业务进程时跟随 `TIANMU_REDIS_DATABASE`。独立只读桥接默认绑定 127.0.0.1:8202，Agent 的 BUSINESS_BASE_URL 默认为该地址。嵌入原服务时 base URL 应包含其 `/api` context-path。

生成新 JWT 密钥（Python 标准库，不会写文件）：

```sh
python -c "import base64,secrets; print(base64.b64encode(secrets.token_bytes(32)).decode())"
```

将结果私密注入 JWT_SECRET_BASE64。也支持 JVM `-Dtianmu.jwt.secret-base64=...`，但生产优先使用密钥注入，避免密钥出现在命令行。更换签名密钥后旧 Token 失效，需要用户重新登录；两侧必须协调更新。

## 原视频平台

源码位于根 `src`。需自行部署兼容的 MySQL、Redis、RocketMQ、MinIO、Elasticsearch/IK 和 Canal，并提供现有业务表、索引、Binlog 与桶配置。设置 `.env.example` 的 TIANMU_* 和 JWT_SECRET_BASE64，再从根目录运行 `mvn spring-boot:run`。

当前没有完整业务建表迁移和一键中间件编排，因此不承诺空环境下一条命令启动整个视频平台。`src/test/resources/business-schema.sql` 是裁剪过的 P0 测试结构，不可冒充全业务建表脚本。SMTP 只在发送验证码时使用，未配置 SMTP 用户和授权码会明确失败。

原平台仍有历史密码哈希与 WebSocket 身份等安全债，详见 SECURITY.md。对外部署前完成专项审查，不能把 Agent 的权限验证视为全平台审计结论。

## 集成测试

默认 `mvn verify` 跳过依赖外部服务的测试。需要隔离 MySQL 和 Redis，测试会创建/修改表及受控记录，禁止指向真实业务数据。业务 JDBC URL 要求本机且库名以 `_it` 结尾；Agent 集成测试也要求专用 `_it` 库。

业务模块设置 BUSINESS_IT=true、BUSINESS_JDBC_URL、BUSINESS_DB_USER、BUSINESS_DB_PASSWORD、BUSINESS_REDIS_HOST、BUSINESS_REDIS_PORT 与随机 JWT_SECRET_BASE64，再执行根 `mvn -B verify`。Agent 设置 AGENT_STORAGE_IT=true、AGENT_JDBC_URL、AGENT_DB_USER、AGENT_DB_PASSWORD、AGENT_REDIS_HOST、AGENT_REDIS_PORT，再执行 `mvn -B -f tianmu-agent/pom.xml verify`。

当前测试 Redis 适配器使用无密码连接；仅使用绑定本机的隔离实例。CI 中的数据库密码是临时测试容器专用值，不能用于部署。两套数据库和 Redis 可以使用不同端口；参考 `.github/workflows/ci.yml`。

双服务与真实模型验收脚本见 P0 文档。生成的环境 JSON、fixture Token、日志和输出目录必须放在仓库外。真实模型验收不会加入默认 CI。
