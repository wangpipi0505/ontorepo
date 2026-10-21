# C6 对象存储基础验证

2026-10-20，主执行者实现独立 `onto-artifact`，尚未接入发布事务，不能据此关闭 P4。

- 真实独立 MinIO：端口 19100/19101，数据位于本工程 `.runtime/minio-data`，启动/停止脚本严格核对所属进程；随机凭据保存在忽略目录中。
- 本机通用 MinIO 可执行文件复制到本工程 `.tools/minio/minio.exe`，版本 `DEVELOPMENT.GOGET`，SHA256 `F14DAF33C8C114CDD11946A9608B541D251E07611A746C028EEF30610532A020`。仅用于本地开发验证；生产需固定可审计的发行版本。本工程没有复用相邻工程的数据、凭据或运行进程。
- MinIO Java SDK 9.0.3；[OkHttp 官方 Maven 说明](https://github.com/lysine-dev/okhttp#maven-and-jvm-projects)要求 JVM 变体，显式使用与 SDK 一致的 okhttp-jvm 5.3.2。Maven 直连中央仓库超时，使用 PowerShell 下载同一官方中央仓库制品并核对其 SHA1 后继续构建。
- 使用 S3 条件 PUT `If-None-Match:*` 保证同键原子不可覆盖；幂等重试比较实际字节。读取每次核对可信 SHA256 和长度；不存在、物理篡改或超限一律拒绝。公开接口没有覆盖/删除和跳过验证读取能力。

RED：缺少 ArtifactStore/ArtifactRef/ArtifactProblem，测试编译失败，记录 `.runtime/artifact-red.log`。

GREEN：`mvn -B -o -f onto-artifact/pom.xml -DargLine=-Xmx512m test`，21:26:43，5 条测试，0 失败/错误/跳过：真实上传读取与幂等、异内容覆盖拒绝、物理篡改检测、并发同键不同内容单胜者、非法键与缺失制品。日志 `.runtime/artifact-green.log`。

补充边界：6 MiB 对象重复条件上传时，服务端提前拒绝，SDK 抛出包裹 `Connection reset by peer` 的 IllegalStateException，原实现误报不可用。定向测试实际 RED 后，增加不确定上传结果的对象字节复核：实际相同返回幂等成功，不同拒绝覆盖，不存在/无法核实保持失败。21:34:37 再次运行同一命令，6 条全部通过；已统一 Java AOSP 格式，临时诊断输出已删除。

后续：独立审查、纳入 reactor、候选/manifest/pack/index 归档及 P4 提交前就绪屏障、损坏隔离和事件场景。
