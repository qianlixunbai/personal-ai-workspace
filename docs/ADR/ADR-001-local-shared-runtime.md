# ADR-001 — Independent local shared runtime

Date: 2026-10-01

Status: Accepted

## 背景

长期产品统一为 Personal AI Workspace / Personal AI Assistant，但当前以第三个独立仓库
建立 Shared Runtime Foundation。Finance 与 Local AI Assistant 都存在各自稳定性与状态边界；
Finance 还需与学校笔记本的实际工作区进行 Reality Sync。
M0 需要真实 Translate 执行链和未来客户端可以复用的有限任务/Provider 边界。

## 决策

使用 Java 21 + Spring Boot 4.1.1，单应用、独立进程、清晰 package 边界。
该正式版本由 Spring 官方文档、Initializr 和 Maven Central 核实，支持 Java 21；
不机械继承 Finance 的旧 Spring Boot 版本。
Java Runtime 负责编排、安全、策略与执行控制；Ollama 负责推理。
不引入 Spring AI/Agent framework，当前单一 text-generation adapter 不需要它们。

独立于 Finance Backend，不复制旧代码、不接 PostgreSQL。
未来 Workspace 对 Finance 的调用必须经过认证 Tool Gateway，而不能访问 Finance DB；本轮不实现。

Local-first / private-by-default，Translate 固定 LOCAL_ONLY。
LOCAL_PREFERRED、CLOUD_OPTIONAL 仅建立明确策略概念，M0 同样拒绝 cloud。
本地失败是显式错误，最终模型出站再次检查策略；无隐式 cloud fallback。
URL 限制为固定 loopback、禁用 proxy/redirect，保障真实出站边界。

客户端提交 Model Profile `translate.fast`，不提交模型名。
配置持有 provider/model/locality/budgets/generation/version；public response 仅给安全 profile 信息。
翻译规则使用独立 prompt version，可分别追踪配置与规则变化。
业务 capability 不知道 GPU 型号；不建立硬件 profile 的空实现。

采用异步 task API：先 202 创建 UUID，GET 查询，DELETE 取消。
并发 1 + 队列 4，任务短期内存保留，有界容量与分类 deadline。
取消与结果提交串行化；不能强制抢占 GPU，不引入 daemon/process manager。

使用应用生成的 256-bit local Bearer token，private file permissions，stateless API。
不实现 OAuth/pairing；当前拒绝所有带 Origin 的 capability 请求。
未来 Browser/Windows pairing 可引入单独客户端 credential、权限和明确 origin allowlist，
但须同时保持最终出站策略和取消身份语义。

## 影响与限制

小而可构建的 M0 不保存长期个人数据，因此无 Memory/Conversation/Backup engine。
使用短期内存 task records，不提供 durable replay；重启后客户端 taskId 失效。
同一 token 内共享任务访问权限，token 持有人属于同一本地信任域。
默认 2 分钟结果保留需要客户端及时获取；满载安全拒绝，低吞吐是保守默认。
模型输出仍需真实质量测试；provider health 是 tags/model 可用性，不等于完整质量或 GPU 就绪证明。

## 参考

- [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Maven Wrapper](https://maven.apache.org/tools/wrapper/)
- [Ollama chat API](https://docs.ollama.com/api/chat)
