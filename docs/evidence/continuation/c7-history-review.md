# C7 / P4 历史恢复与追溯独立切片审查

日期：2026-10-21。审查者沿用 C7 独立 reviewer 身份，只读生产源码、迁移和测试。遵循本工程 `AGENTS.md`、requesting-code-review 与 java-spring-boot。先阅读契约、详设、当前稳定源码，再核对实施者测试。工程无提交历史，不虚构 commit SHA。

## 结论

- **Spec：APPROVE（本切片）。** H1 已修复并通过独立原探针复核；保留下文原发现与 RED。
- **Quality：APPROVE（本切片）。** H1/H2 均关闭，没有遗留的本切片发现。
- 本次仅批准或指出历史四类与相关迁移的具体行为，**不替代完整 P4 发布门禁、服务网关、凭据、outbox 和真实 HTTP 的后续独立审查**。

## 范围与依据

生产主范围：

- `onto-release/.../application/ReleaseHistoryService.java`
- `onto-release/.../application/DraftBundleRestorer.java`
- `onto-release/.../application/HistorySafety.java`
- `onto-release/.../application/TraceAuditService.java`
- `onto-designer-api/.../db/migration/V10__release_runtime.sql`

同时读取 `FrozenBundleLoader`、`CanonicalPayload`、`Db`、`SpaceService`、实际冻结字段和执行事实写入路径，以及 V1–V9 与本次恢复有关的表/外键。测试只读：`HistoryDatabase`、`ReleaseHistoryServiceTest`、`ReleaseRestoreTest`、`TraceAuditServiceTest`。

依据为 `contracts/p4-release.md` 的版本浏览/恢复、审计/追溯及展示结构，`contracts/p3-data.md` 的敏感配置边界，详设 v2.6 §7.1、§8.3、§8.6，以及开发计划 v1.6 P4 出口。与 `p4_backend` 已协调，本轮不运行 Maven、PG、MinIO 或服务；该实施者确认四类自其 GREEN 后保持稳定。

## H1 · P2 · 候选 diff 选择已损坏或已弃用的最新版本，未选上一 READY

定位：`ReleaseHistoryService.java:135–138`。查询为：

```sql
SELECT * FROM ontology_release
WHERE space_id=? AND published_at<=?::timestamptz
ORDER BY version_no DESC LIMIT 1
```

详设 §7.1 第 389 行规定人工复核展示“候选投影 vs 上一 READY release 投影”。当前 SQL 没有 `status='READY'`，随后第 145 行直接核验并加载所选版本包。

触发：

1. 同空间 v1 为 READY，v2 发布后被标记 CORRUPTED，且 v2 实际 bundle 摘要不匹配。
2. 冻结正常的新候选 C，读取 `/candidates/{C}/diff`。
3. SQL 选中 v2；加载 v2 抛出 `ARTIFACT_CORRUPT`，未对 v1 与 C 生成 diff。
4. 当前前端 `PublicationPanel.vue:134` 规定没有 diff 就不能复核，新候选无法走正常人工复核路径。不能通过绕过损坏核验解决，应正确限定基线。

若 v2 仅为 DEPRECATED 且制品正常，响应仍会把 v2 作为 `fromReleaseId`，同样不符合上一 READY 的定义。没有任何 READY 历史时应按首次发布空集处理。显式 `/releases/diff` 的管理浏览仍可以比较用户明确选择的历史状态，不需要一并改成 READY 限制。

独立无 IO 逻辑探针分别观察到 CORRUPTED 的异常及 DEPRECATED v2 被返回。该探针用只读 fake Db 模拟 SQL 选中最高版本号的记录，并执行真实当前 `candidateDiff`、投影和比较；**没有把它表述为真实 PG SQL 或 MinIO 集成证据**。实施者应补真实 PG 的 READY v1 + 非 READY v2 + C 反例，修正后复核。

## H2 · P3 · 时间线按字符串排序会逆置不同小数精度的真实时间

定位：`TraceAuditService.java:100`，`Comparator.comparing(m -> Objects.toString(m.get("at"), "\uffff"))`；`Db.java:66` 使用 `Instant.toString()` 输出时间。

例如执行事实 `2026-10-21T00:00:00.123Z`，稍后的步骤 `2026-10-21T00:00:00.123456Z`。字符 `'4'` 小于 `'Z'`，目前步骤被排到执行之前。独立探针传入这两条合法持久事实时间，真实 `trace()` 返回的第一个节点是 `QUERY_EXEC`，正确应为 `EXECUTION`。

建议按 `Instant` 比较非空值，null 继续置后并保留原有稳定顺序。影响只在精度前缀重合时发生。当前主执行写入通常在同一事务使用 PostgreSQL `now()`，因此本轮**未声称通过正常 HTTP 执行复现该情形**；它是对合法历史事实输入的排序边界，列为 P3。

## 已核对且未发现具体缺陷的边界

| 边界 | 核对结果 |
| --- | --- |
| 恢复授权/事务 | 网络 bundle 实际摘要核验在事务外；短事务内空间行锁/CAS/ACTIVE 检查，重新核对 OWNER，恢复全部草稿后递增一次修订并追加来源版本/摘要/理由审计。任一步异常由同一个 `TransactionTemplate` 回滚。 |
| 恢复字段范围 | 对照真实 freeze：类、属性、关系、码表、约束、规则、策略、操作（含 P2/P3 扩展）、用例、原 RDF、知识、受管文件、源密文配置、元数据快照和三层映射均恢复；空间恢复可编辑的 name/domain/guardCfg。空间身份、code/namespace、权限、版本流水不倒退。 |
| 外键和重建顺序 | 删除先清映射/用例/操作/知识/规则/关系/属性，再清类和策略；插入按依赖顺序。同表整批 INSERT 支持任意 UUID 顺序的父子类；关系逆引用为延迟外键；生成列不被恢复器写入。SQL 表名来自固定表清单，列名来自 PG 元数据，不接受 bundle SQL 标识。 |
| 历史不可变 | 恢复器没有 UPDATE/DELETE 旧候选、release、执行、证据、导入事实。release 内容更新触发器限制状态/完整性字段以外的修改；候选内容字段与原字节/摘要受触发器保护。本切片没有把直接 DBA 越过应用的操作当成应用漏洞。 |
| 快照归档身份 | 删除当前快照前归档原 id/spaceId/datasourceId/sha/resources/capturedAt；ON CONFLICT 前后均逐字段核对，拒绝同 id 异空间或异事实；归档表拒绝 UPDATE/DELETE。恢复历史快照也先核对归档，冲突与此前全表写入同事务回滚。 |
| 快照追溯 | 按证据原 id + 执行所属空间查当前/归档快照，核对原 SHA；不根据当前映射补造来源，不借用另一空间快照或新摘要。缺证据时间为 null，缺授权历史为空数组。 |
| 跨空间读取 | 列表、详情、实体、候选 diff、版本 diff、trace、audit 均调用服务端空间成员授权；两个版本 diff 另核对 requested space；trace 授权来自 execution 所属空间。恢复另加 OWNER 和锁内复核。 |
| 投影与敏感字段 | 从已验证 bundle 派生投影；三层映射稳定 key 加层前缀，避免相同 id 相互覆盖；移除易变存储时间及草稿状态；源 connCfg 替换为 SHA，不向管理响应暴露密文/明文配置。详情只返回制品类型/大小/摘要/就绪，不返回对象引用。 |
| 审计安全和游标 | detail 使用业务字段允许清单，未知原始源输出不透传；递归删除已知敏感字段并对明显凭据文本隐藏。列表空间过滤、id 倒序、`id < beforeId`、多读一条判断下一页、上限 200，无新增行导致前页覆盖的逻辑。 |
| 组件职责和注释 | 恢复器、历史投影、追溯与安全输出分离；恢复事务、快照身份核对、网络与锁边界、缺失历史不补造均有必要中文注释。未用文件长度或主观样式列阻断。 |

## 独立证据和未运行范围

自有目录 `.runtime/c7-history-review/`，`HistoryReviewProbe.java` 只编译到本目录 `classes/`。classpath 从已有 surefire XML 的 `java.class.path` 属性读取，未输出凭据。编译当前四个生产 Java 源文件以避免只验证陈旧 target；依赖只读现有 classpath。

```powershell
# 工作目录：G:/207/项目/算法重组工具/代码/ontorepo
$reviewClasspath = Get-Content .runtime/c7-history-review/classpath.txt -Raw
$reviewJdk = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot/bin'
& "$reviewJdk/javac.exe" -encoding UTF-8 -cp $reviewClasspath `
  -d .runtime/c7-history-review/classes `
  onto-release/src/main/java/cn/ontorepo/release/application/ReleaseHistoryService.java `
  onto-release/src/main/java/cn/ontorepo/release/application/DraftBundleRestorer.java `
  onto-release/src/main/java/cn/ontorepo/release/application/TraceAuditService.java `
  onto-release/src/main/java/cn/ontorepo/release/application/HistorySafety.java `
  .runtime/c7-history-review/HistoryReviewProbe.java
& "$reviewJdk/java.exe" -Xmx512m `
  -cp ".runtime/c7-history-review/classes;$reviewClasspath" `
  cn.ontorepo.release.application.HistoryReviewProbe
```

结果：编译成功；5 项无 IO 逻辑断言中 2 PASS、3 RED（H1 两种状态 + H2 时间边界），进程退出 1。原日志 `.runtime/c7-history-review/probe-original-red.log`。PASS 覆盖三层投影 key/源配置摘要和审计未知原始源数据排除。

只读的实施者测试涵盖真实全迁移恢复、全域重建、快照归档冲突回滚、无效外键全回滚、实际 MinIO 字节损坏拒绝，以及追溯缺历史/空间授权/游标。它们是实施者运行证据，本轮没有独立重跑，不将其声明为本审查执行。

未运行 Maven、真实 PG/JUnit、MinIO、HTTP、浏览器、服务启停或相邻工程。未改生产实现/测试/迁移/合同/其他报告。H1 修复后需保留原 RED 并对当前源码独立复核；完整发布网关与 UI R3/R4 仍各自另行审查。

## 2026-10-21 01:27 修复复核

重新按上文命令编译当前四个生产源文件，再运行**未经修改的** `HistoryReviewProbe.java`。编译退出 0，运行退出 0，5/5 PASS。新日志 `.runtime/c7-history-review/compile-recheck.log` 与 `.runtime/c7-history-review/probe-recheck.log`（01:27:32）；原 `probe-original-red.log` 保留。

- H1：`ReleaseHistoryService.java:135` 查询增加 `status='READY'`，CORRUPTED/DEPRECATED 最新版本均不再替代可用的 v1 基线。
- H2：`TraceAuditService.java:101` 以 `Instant.parse` 比较真实时刻，null 使用 `nullsLast`；原两种小数精度时间的执行/步骤顺序正确。
- 既有投影层 key、源凭据隐藏和审计允许清单对照仍 PASS。

本次复核源码 SHA-256：

| 文件 | SHA-256 |
| --- | --- |
| `ReleaseHistoryService.java` | `15737508916068D255C6BC1435D3AD135C0F21B2EF3D89DDE290E8C764558181` |
| `TraceAuditService.java` | `F67559F98593A040F99F1DEAF87A9CEE5E16AAAF4B3356B7A4FC503698CCC356` |

同时只读核对实施者 `.runtime/c6-terminal-history-green.log`：18 项、0 failure/error、BUILD SUCCESS，其中 `ReleaseRestoreTest` 6、`TraceAuditServiceTest` 3、`P4FailureTest` 9。该真实 PG/MinIO/API 结果属于实施者运行证据，本 reviewer 的独立运行仍仅为上述无 IO 探针，未混称为独立集成执行。后续纯格式化可能改变文件哈希，语义修改需另行审查。

### 最终格式化与定义证据增量复核

随后独立阅读最终 `TraceAuditService.publicReference/evidenceDetail` 增量：RULE_VER/SERVICE_VER 分别定位冻结规则/服务 id，release/bundle 身份保留为 detail，没有查询当前草稿来补造定义。已读实施者新增真实正式规则 trace 测试及 `.runtime/c6-version-evidence-green2.log`（9 项通过）；该完整定义证据增量还见 `c7-publication-review.md`。

最终重新编译这四个历史生产类并运行未修改原 5 项探针，编译/运行退出均为 0，仍 **5/5 PASS**，日志 `.runtime/c7-history-review/compile-final.log`、`probe-final.log`。当前源码 SHA-256：

- `ReleaseHistoryService.java`：`CC1A0D3A814BF63D6364DC703BAFD7FF1BB2D4DE0958D72B1169A072AA8B6F11`
- `TraceAuditService.java`：`DA32531B8D4FC73C4BC5E8B23F342F25A1DCC16AD5B216C2A8BC08E66AE681CC`

本切片 Spec/Quality APPROVE 维持，H1/H2 关闭；原发现、原 RED、首次 GREEN 和最终 GREEN 均保留。

实施者第二遍纯格式化后，重新编译当前历史四类并运行原 5 项，仍 5/5 PASS；日志 `compile-postformat.log` / `probe-postformat.log`，编译/运行退出 0，上述两个历史类指纹未变。V11 追加审查见 `c7-integration-review.md`，不改变本切片的已关闭结论。
