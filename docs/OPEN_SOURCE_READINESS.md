# 开源准备记录

日期：2026-10-22。Tianmu 远端已公开，原 DokiTV URL 重定向到同一仓库。本次完成本地公开预览版整理，没有修改远端可见性、推送、创建 Release 或改写历史。

## 已完成

- 维护者确认有权统一授权，补齐 MIT LICENSE、NOTICE 和来源/第三方组件说明，保留 Git 作者记录。
- 数据库、Redis、Elasticsearch、MinIO、Canal、SMTP、JWT 配置改为环境变量；删除 Redisson 的写死密码。JWT 没有公开备用签名密钥，最少 256 bit。
- `.env.example` 提供变量清单；忽略私有环境、日志、运行产物、Python 缓存和凭据文件。Java 不自动加载 .env。
- README 改为免 Key Agent Demo 入口，纠正版本、接口用法、FAQ 顺序、classpath 更新方式，移除无验收依据的性能数字。
- 新增贡献指南、私密安全反馈说明、Issue/PR 模板、任务分工与部署说明。
- 新增 GitHub Actions：凭据检查、两个 Java 工程独立 MySQL/Redis 集成验证、默认 Demo HTTP/SSE 冒烟；Action 引用固定 commit，使用只读仓库权限，不调用收费模型。
- 同步 P0 业务接入进度与脱敏验收摘要，没有复制私有 fixture、环境文件或原始 Token。

## 本地验证

| 检查 | 结果 |
|---|---|
| Agent Java 测试 | 114 项，失败/错误/跳过均 0，打包成功 |
| 业务 Java 测试 | 14 项，失败/错误/跳过均 0，包含新增 JWT 配置测试，打包成功 |
| 发布扫描回归 | 4 项通过，检查匹配结果不泄露凭据值 |
| 默认 Demo 进程冒烟 | 3 项通过：认证拒绝、FAQ 零模型调用、Harness 模拟指标；两条答案均检查 SSE 唯一 DONE |
| YAML 语法 | 9 个配置/工作流文件通过 SnakeYAML 解析；这不等于 GitHub 托管运行已通过 |
| 当前工作区扫描 | PASS，范围为 tracked 与非忽略 untracked 文件；启发式检查，不是完整安全审计 |
| Git 历史扫描 | REVIEW_REQUIRED：787 个可达 blob，65 个需核对的位置；不输出原始值 |
| GitHub Actions | 已配置，尚未推送触发 |

本地集成运行使用隔离的 MySQL 8.0 / Redis 测试实例；CI 定义使用 MySQL 8.4 / Redis 7.4，Linux 容器组合待远端实际运行。签名密钥在测试进程里随机生成，未提交真实凭据。本次没有新增付费模型调用；此前 P0 的 13 项双服务与一次真实模型验收见 Agent 文档，不宣称其在配置整理后全部重跑。

机器摘要见 [OPEN_SOURCE_READINESS.json](OPEN_SOURCE_READINESS.json)。历史扫描的详细位置与原配置备份保存在仓库外的私有工作目录，不纳入发布内容。

## 发布前仍需维护者完成

1. **更换历史暴露的凭据**：数据库、Redis、Elasticsearch、MinIO、SMTP、JWT，以及其他曾使用相同值的服务；检查 Canal 是否实际配置了认证。旧配置曾进入已公开的 Git 历史，删除当前文件不能撤销别人已有的副本。
2. 登录服务与桥接同步注入新的 JWT_SECRET_BASE64；Redis 地址和数据库索引与实际登录一致。密钥轮换使旧 JWT 失效，安排重新登录。
3. 决定历史清理范围。在独立备份/克隆中形成计划，协调协作者后再执行 git-filter-repo 与强推；本次未操作。历史清理不能替代凭据撤销，也无法收回他人的 clone/fork。
4. 推送清理后的工作区，观察 GitHub CI；启用私密漏洞反馈和适用的 secret scanning/push protection、分支保护。CI 变绿后再创建预览版标签和 Release。
5. 正式部署前补业务建表迁移、真实账号/现有库验收、平台安全审查和可复现性能基准。不要把本地预览版称为全平台生产就绪。

## 来源说明

[GitHub 凭据泄露处理](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository) 建议首先撤销或轮换已暴露的凭据；历史改写会改变 commit 并需要与协作者协调。本次优先生成可评审的清理结果，保留原历史供维护者决策。
