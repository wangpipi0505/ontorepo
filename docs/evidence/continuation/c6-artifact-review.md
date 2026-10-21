# C6 对象存储基础独立审查

日期：2026-10-20。依据：`c6-artifact-report.md`、`ArtifactStore` / `ArtifactRef` / `ArtifactProblem` 三个实现类及 `ArtifactStoreTest`。范围为独立制品存取模块；发布事务、候选就绪屏障和事件集成属于后续工作。

**结论：在当前对象存储基础范围内符合要求，未发现必须修改的正确性或安全问题，可进入发布架构接入阶段。** 条件写不可覆盖、未知传输结果核验、可信 SHA256 与长度验证、最大大小限制及错误脱敏均通过源码复核和独立反例。下述 S3 500 行为是明确的可用性限制，不作为本阶段阻断项。

## 关键边界结论

| 边界 | 源码定位 | 独立核验 |
| --- | --- | --- |
| 原子不可覆盖 | `ArtifactStore.java:70–85` | 服务端 `If-None-Match:*`；不依赖“先查再写”；原并发测试单胜者；最大 16 MiB 的重复写、异内容写均按要求处理 |
| 输入字节证明 | `ArtifactStore.java:64–68` | 非空且 ≤16 MiB；上传前复制调用数组；SHA256 和长度取自同一份副本 |
| 上传响应丢失 | `ArtifactStore.java:88–107` | 同字节读回才成功；不同字节拒绝覆盖；读不到或对象超限继续失败 |
| 成功响应也校验 | `ArtifactStore.java:93,119–124` | 注入 PUT 200、GET 不同字节，返回 `ARTIFACT_CORRUPT` |
| 可信摘要与长度 | `ArtifactStore.java:111–124` | 错误可信长度、错误可信摘要均拒绝；格式非法摘要提前拒绝，不相信对象 metadata |
| 有界读取及关闭流 | `ArtifactStore.java:128–143` | 最多读取 16 MiB + 1 字节，超限拒绝；`try-with-resources` 关闭成功和异常路径响应流 |
| 路径与错误边界 | `ArtifactStore.java:146–164` | 限定相对路径，拒绝 `.` / `..`、绝对路径及 URL；公开错误不传播 S3 响应体、凭据或原异常堆栈 |

条件写语义核对：[Amazon S3 官方条件写说明](https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html)。`If-None-Match:*` 将同键检查放在写入操作内；已存在对象返回 412。实际 MinIO 并发及大对象测试验证了本实现使用的路径。

## 独立执行结果

2026-10-20 21:43:29 +08:00，执行：

```powershell
. ./deploy/Environment.ps1
$null = Import-OntoEnvironment
Set-OntoJava
$env:MAVEN_OPTS = '-Xmx512m'
mvn -B -o -f onto-artifact/pom.xml '-DargLine=-Xmx512m' test
```

真实独立 MinIO 测试 **6 条，0 失败、0 错误、0 跳过，BUILD SUCCESS**。没有运行完整 reactor。日志 `.runtime/artifact-review-maven.log`。

另新增临时审查探针 `.runtime/ArtifactReviewProbe.java`，未加入业务模块或正式测试目录。真实 MinIO 部分仅操作随机 `test/review-<UUID>/` 前缀：

```text
MAX_SIZE_ROUNDTRIP=true
MAX_SIZE_IDEMPOTENT=true
MAX_SIZE_CONFLICT=ARTIFACT_EXISTS
MAX_SIZE_ORIGINAL_PRESERVED=true
NULL_BYTES=INVALID_ARTIFACT_SIZE
EMPTY_BYTES=INVALID_ARTIFACT_SIZE
TOO_LARGE_BYTES=INVALID_ARTIFACT_SIZE
WRONG_TRUSTED_LENGTH=ARTIFACT_CORRUPT
WRONG_TRUSTED_DIGEST=ARTIFACT_CORRUPT
INVALID_TRUSTED_DIGEST=INVALID_ARTIFACT_REF
OVERSIZED_REMOTE_OBJECT=ARTIFACT_CORRUPT
```

`MAX_SIZE` 为恰好 16 MiB；额外确认写入后将物理对象替换成 16 MiB + 1 字节时读取拒绝。该物理替换仅使用审查生成的随机测试对象，未触碰业务制品。输出 `.runtime/artifact-review-boundary-probe.log`。

故障注入使用独立回环 HTTP 服务及虚拟凭据，不把该服务冒充真实 MinIO；它验证客户端错误分支和实际发送的条件头：

```text
DROP_MATCH=ACCEPT,PUT=3,GET=1,conditional=true
DROP_DIFFERENT=ARTIFACT_EXISTS,PUT=3,GET=1,conditional=true
DROP_MISSING=ARTIFACT_STORAGE_UNAVAILABLE,PUT=3,GET=1,conditional=true
DROP_OVERSIZE=ARTIFACT_CORRUPT,PUT=3,GET=1,conditional=true
HTTP500_MATCH=ARTIFACT_STORAGE_UNAVAILABLE,PUT=1,GET=0,conditional=true
OK_DIFFERENT=ARTIFACT_CORRUPT,PUT=1,GET=1,conditional=true
```

DROP 表示接收 PUT 请求体后关闭响应连接；客户端的 SDK 重试仍逐次带条件头。读回相同内容才确认成功，缺失/不一致/超限均未误报成功。日志 `.runtime/artifact-review-fault-probe.log`。

探针运行时最大堆 `-Xmx512m`。回环 HTTP 服务因本机 Windows JDK 的 Unix-domain 临时路径问题，使用进程参数 `-Djdk.net.unixdomain.tmpdir=C:/Windows/Temp` 后运行；该参数未写入工程配置，也不改变业务实现。

## 明确的限制

`ArtifactStore.java:82–87` 对收到合法 S3 `InternalError` XML 的 HTTP 500 直接返回 `ARTIFACT_STORAGE_UNAVAILABLE`，不立即 GET 核验。故障服务记录 `GET=0`，即使服务端已接收并保存相同字节，也不会在这次调用中恢复为幂等成功。

这是可用性范围：不确认实际对象就不宣称上传成功；调用者重试仍受条件写保护，不存在覆写或错误证明。调用方应保持候选未就绪并允许重试，不能因“可能已上传”跳过制品核验。本轮不要求为所有 S3 业务错误增加无差别读回。

存取逻辑保持小而明确，资源消耗有显式对象大小和 HTTP 超时限制。正式集成仍需按可信数据库中的 `ArtifactRef` 调用 `loadVerified`，基础模块通过不等于发布事务或 P4 出口通过。

本轮未修改业务文件、未提交 Git、未修改相邻工程或复用其存储。
