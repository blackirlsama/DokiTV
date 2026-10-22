# 来源与第三方组件

本仓库原名 DokiTV，原地址重定向到 blackirlsama/Tianmu，GitHub 元数据未标为 fork；这说明仓库改名关系，不单独构成第三方代码权属证明。维护者已于 2026-10-22 确认有权为现有项目代码采用 MIT；Git 作者记录与已有声明保留。

项目不把依赖重新授权为 MIT。Maven 依赖版本由两个 pom.xml 及 Spring Boot BOM 管理：

| 组件 | 用途与上游 |
|---|---|
| Spring Boot / Spring Framework / Spring Data | Web、配置与存储：[spring.io](https://spring.io/projects) |
| MyBatis-Plus | 业务 ORM：[baomidou.com](https://baomidou.com/) |
| MySQL Connector/J / Flyway / HikariCP | JDBC、迁移、连接池；关注分发场景与各自条款 |
| Netty / RocketMQ / Canal | 长连接、消息与 Binlog 同步 |
| MinIO Java SDK | 对象存储客户端；服务端镜像有独立授权条款 |
| Elasticsearch / Redis | 搜索与缓存；不同版本、服务方式可能适用不同条款 |
| OpenTelemetry / Kafka client | Trace 与消息输出 |
| JJWT / Commons Email / Hutool / Lombok | 认证、邮件和工具 |
| JUnit / Mockito / Spring Boot Test | 测试依赖 |

此表是组件导航，不是逐版本许可证审计结论。发布二进制、容器或引入新依赖前，应从实际 Maven 解析结果核对各依赖 LICENSE/NOTICE，并保留适用声明；基础服务镜像不是本项目自有代码。当前仓库不分发中间件二进制。

DeepSeek 等模型服务适用供应商的服务、费用和数据条款；用户自行提供 Key。项目 MIT 许可证不授予第三方模型、商标、视频或数据的权利。文档图片应由贡献者确认有权分发；发现来源不明内容请先报告并移除或补授权。
