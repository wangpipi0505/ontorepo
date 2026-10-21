# C7 / P4 验收脚本与 V11 独立追加审查

2026-10-21。只读审查五个 `deploy/` 脚本（含 `--defer-events/--deferred`），对照 P4 契约和实际后端。采用 requesting-code-review / verification-before-completion；未改 root 脚本、生产源码、测试或迁移。

**Spec：APPROVE；Quality：APPROVE（脚本断言与 V11 静态兼容性范围）。** 两项证据盲点已修复关闭。没有将隔离探针写成真实 HTTP 通过，也不替代服务重启、MinIO 停机或后端全量验收。

## I1 · P2 · 重启检查未固定原证据/事件身份（已关闭）

原 `Check-Release-Data-Persistence.mjs:75–95` 只检查 evidence 的类型存在、六项授权通过，以及当前 event/inbox 自洽；原 `Smoke-Release-Data.mjs:221–226` 未保存原证据/授权、事件 id 或第二版 SHA。因此下列异常仍会产生“5 项 PASSED”报告：

- 原 META_SNAPSHOT 被替换为另一快照 id/SHA，ONTOLOGY_VERSION 被替换为另一 release/SHA。
- 原事件/收件记录被删除后，以新 eventId 重建。
- 第二版 SHA 改变、原执行授权事实被改写。

独立探针在 VM 中运行**原检查脚本全文**，只替换 HTTP、配置读取和写报告三个边界，不读真实凭据、不访问服务。正确事实对照通过；首轮 1 PASS / 2 RED，扩展为同一 fixture 的 1 PASS / 4 RED。五个原脚本保存于 `.runtime/c7-integration-review/originals/`。

修复后 producer 保存两版身份/版本/SHA、原 snapshot id/SHA、完整安全 evidence/authorization、两条 RELEASED 的不可变公开身份；恢复当次和重启后分别精确比较，另显式核对快照及版本证据绑定。当前原检查器对四种异常全部拒绝，正确对照仍通过：**5/5 PASS**。

证据：`probe-original-red.log`、`probe-expanded-original-red.log`、`probe-recheck-green.log`。增强 fixture 仅补齐 producer 新增的基线字段，未放宽异常断言；同一增强 fixture 已分别跑保存原脚本和当前脚本。

## I2 · P3 · “manifest 摘要不变”原先只有可读取断言（已关闭）

原 `Smoke-Release.mjs:157–164` 使用 `assert.ok(GET manifest/context-pack)`，名称却称摘要不变；返回任意非空新文档仍通过。

现在第 158–171 行在改草稿前后比较两份文档和 artifacts，并以原发布 `manifestSha256` 校对详情；该 SHA 写入初始基线，正常/deferred 重启检查也比较原值。

独立提取脚本中的**实际 check 代码块**在隔离环境运行：原块对文档变化/摘要变化均错误通过（1 PASS / 2 RED）；当前块拒绝两种变化，正常对照通过（**3/3 PASS**）。证据：`manifest-original-red.log`、`manifest-recheck-green.log`。这验证断言本身，未执行完整 HTTP 主线。

## 其余核对与准确覆盖边界

| 范围 | 结论 |
| --- | --- |
| 新候选 CONTRACT | 五脚本只构造合法契约，失败会让正向主线停止，不存在“失败还预期 tests 成功”的错误断言。它们没有独立覆盖非法新候选的零执行负例。该边界由已只读查看的 `P4CompilationTest` 两种真实 PG/MinIO 场景负责：无定义守卫/混合缺声明→FAILED/VOID，tests/prepare 409，test_run、LOCAL_TEST、release 均为零。其运行由后端实施者负责，不能从本五脚本推导 HTTP 负例已通过。 |
| 真实范围 | CSV 的 A/B/C/空标签和转换期望为固定常量，没有用实际响应生成预期。分别改变 endpoint A、NULL、endpoint B × space A，再用策略 A/B 排除 C，能识别对应范围被放宽。源 accessScope 是实际批准文件，仅正例；正式源白名单拒绝负例由 root 另补 HTTP 增量，不能称此五脚本包揽四层所有负例。 |
| 重启基线 | 初始与 persistence 输出文件不同，checker 不重跑冻结、不创建新 release、不覆盖初始报告。正常/deferred 分支都使用原 release/candidate/event 身份；deferred 初始必须确实 PENDING 且空 inbox，重启后核对原 id、SENT、收件唯一和时间先后。实际停机/重启由外部执行步骤证明，脚本本身不伪造该动作。 |
| 存储不可达 | `Check-Release-Availability.mjs:37–61` 精确要求 UNAVAILABLE + READY + 原 SHA、消费 503/ARTIFACT_STORAGE_UNAVAILABLE、无 meta/版本头、零 CORRUPTED_ALERT；恢复要求 PASSED 和原版本结果。没有把超时当损坏，也没有修改对象或结束进程。此脚本检查操作消费；manifest/context-pack 的不可达负例不能凭该脚本单独声称已测。 |
| 事件证据范围 | 公开 events 不暴露原 payload 字节，当前报告仅比较公开不可变身份和实际收件证据。不能称 HTTP 已核对 payload 字节不变；该边界有后端 outbox 集成证据。 |

五脚本当前 `node --check` 均退出 0，结果 `.runtime/c7-integration-review/syntax-results.json`。文件时间/SHA 在同目录 `reviewed-files.json`。

## V11 静态追加复核

`V11__candidate_content_write_barrier.sql:3–7` 保留 `candidate_no_update` 名称，改为 `BEFORE UPDATE OF` 以下 10 个内容列，使用 V1 已存在的无条件抛错 `immutable_candidate()`：

`id, space_id, draft_rev, sha256, payload, format_version, capability, created_at, pre_release_id, pre_version_no`。

因此显式 `payload=payload` 也触发拒绝。只删去已解除引用的 V10 比较函数，不改旧候选字节。静态提取实际 Java SQL：`PublicationService` 的 8 个 UPDATE 与 `CandidateService` 的 2 个 UPDATE 均只写状态、错误、review、allocation、releaseId、manifest preview 或制品引用，不与这 10 列相交；`FrozenBundleLoader` 没有写入。冻结的内容/预分配列在 INSERT 时设置，V11 不拦 INSERT。

自有 `v11-compatibility.mjs` **3/3 静态检查通过**，日志 `v11-static-green.log`；没有执行 PG 迁移或声称触发器真实运行通过，真实兼容性还由实施者 Workflow/P4/完整 verify 确认。

## 复跑与未运行范围

```powershell
node --max-old-space-size=512 --experimental-vm-modules .runtime/c7-integration-review/persistence-probe.mjs
node --max-old-space-size=512 .runtime/c7-integration-review/manifest-probe.mjs
node --max-old-space-size=512 .runtime/c7-integration-review/v11-compatibility.mjs
```

传入第二参数 `.runtime/c7-integration-review/originals/对应脚本.mjs` 可对原脚本重现 RED。本次总计 8 项隔离行为断言、3 项静态兼容性检查、5 文件语法通过。

没有运行 Maven、PG、MinIO、真实 HTTP、服务启停、共享浏览器或相邻工程。报告批准的是修后脚本断言与迁移静态兼容性，不代表新一轮 P4 实际环境验收已完成。
