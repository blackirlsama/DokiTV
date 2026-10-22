# 参与 Tianmu

项目使用 Java 17 / Spring Boot 3.5。首次参与请先运行根 README 的免 Key Demo，再阅读 [工程目录与扩展规范](tianmu-agent/docs/工程目录与扩展规范.md)。目前是预览阶段，接口仍可能调整。

## 开始一个改动

1. 在 Issue 里说明场景、输入输出、验收条件；较大的接口变更先讨论设计。
2. Fork 仓库，从最新 master 创建 `feat/...`、`fix/...` 或 `docs/...` 分支。
3. 修改一个清晰的范围。新增业务能力优先扩展 Tool/Skill，不把业务 SQL、Prompt 或模型供应商代码塞进循环。
4. 提交 PR，说明行为变化、验证结果、配置或数据库迁移影响。

## 模块边界

| 改动 | 位置与要求 |
|---|---|
| Skill | `tianmu-agent/src/main/resources/skills/<name>/`；提供 SKILL.md 和版本元数据 |
| Prompt | `tianmu-agent/src/main/resources/prompts/`；不在 Java 字符串中堆业务提示词 |
| Tool | `tianmu-agent/src/main/java/com/tianmu/agent/tool/` 与 resources/tools；声明参数、权限和结果口径 |
| 业务适配器 | `.../adapters/business/`；通过业务接口鉴权、查数，不接受客户端自报身份 |
| Hook/Trace | `.../harness/`；遵守现有执行顺序、错误策略与取消语义 |
| 存储 | `.../adapters/storage/`；注意幂等、拥有者校验、迁移兼容性 |

Skill/Prompt 当前从 classpath 加载，修改后需要构建和重启；不要把热更新当成已有能力。

## 提交前检查

```sh
python scripts/test_publication.py
python scripts/check_publication.py
mvn -B verify
mvn -B -f tianmu-agent/pom.xml verify
python scripts/smoke_demo.py --jar tianmu-agent/target/operations-agent-0.7.0-SNAPSHOT.jar
git diff --check
```

默认 Maven 会跳过需要 MySQL/Redis 的集成测试。CI 为两个模块分别准备隔离服务，显式打开 `BUSINESS_IT` / `AGENT_STORAGE_IT`，不调用收费模型。重现完整集成测试见 [部署说明](docs/DEPLOYMENT.md)。改动权限、取消、事务或并发边界时，补充有实际失效意义的测试。

真实模型验收单独执行，调用前确认费用和数据范围；不要把模型 Key 放入 PR、日志或测试报告。生成的 fixture、环境文件和运行目录放在仓库之外。

## 贡献授权与交流

提交贡献表示你有权提供这些内容，并同意按本仓库 MIT 许可证分发。保留第三方版权及授权声明，不提交无权分发的视频、截图或数据。

围绕代码和证据讨论，尊重不同经验的贡献者。漏洞请走 [安全反馈](SECURITY.md)，不要在公开 Issue 上传凭据或利用细节。
