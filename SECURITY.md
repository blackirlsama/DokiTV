# 安全反馈与部署边界

项目处于预览阶段，安全修复以当前 master 为主，尚未承诺历史版本的支持期限。

## 私密报告

优先使用 GitHub 仓库 Security → Advisories → Report a vulnerability（需要维护者先开启 Private vulnerability reporting）。如果入口未启用，只在普通 Issue 留下“希望建立私密安全反馈渠道”，不要上传漏洞利用细节、真实用户信息、Token 或密码。维护者应先提供私密渠道再接收细节。

## 当前边界

- Demo 使用公开的 `demo-alice` / `demo-bob` 演示身份，仅在本机运行，不可作为公网认证方案。
- business profile 验证业务 JWT、Redis 在线状态和视频归属；该实现不等于整个旧业务平台都完成安全改造。
- 旧业务密码仍使用历史 MD5 加固定盐逻辑，尚未实现现代密码哈希的平滑迁移。旧 WebSocket 身份绑定、平台级限流和其他业务接口权限仍需独立安全审查。
- 真实指标首版只统计当前仍保留的已入库弹幕，不保证 MQ 消息都已到达。
- 配置脱敏不代表历史凭据已失效。公开历史中出现过的数据库、Redis、MinIO、SMTP、JWT 等凭据必须分别撤销或轮换；重写历史不能收回他人的 clone/fork。

## 凭据规则

真实凭据通过进程环境或部署密钥服务注入，`.env.example` 仅为变量清单，Java 不会自动加载 `.env`。不要使用示例值部署，也不要把 JWT 签名密钥与模型 Key 混用。业务登录与只读桥接必须使用同一套新 JWT 签名密钥；轮换会使旧 JWT 无效，用户需要重新登录。

本地扫描：`python scripts/check_publication.py`。历史检查：`python scripts/check_publication.py --history --report <仓库外路径>`。检查只输出位置，不输出匹配到的值；这是启发式检查，不替代专用扫描、依赖审计或渗透测试。
