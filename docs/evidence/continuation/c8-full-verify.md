# 独立审核整改最终后端构建（2026-10-21）

## 本轮结果

2026-10-21 10:48:27 完成，耗时 20:32，根 reactor 的 14 个模块全部 SUCCESS。按本轮启动时间之后、日志结束之前实际生成的 Surefire XML 汇总：**407 项，0 失败，0 错误，0 跳过**。

| 模块 | 测试数 |
| --- | ---: |
| onto-contract | 92 |
| onto-artifact | 6 |
| onto-data | 43 |
| onto-release | 53 |
| onto-designer-api | 213 |
| 合计 | 407 |

完整日志：`.runtime/c8-full-verify.log`。逐测试类、时间、环境及制品信息：`c8-full-verify-summary.json`。汇总脚本 `.runtime/C8SummarizeVerify.ps1` 只统计本轮新报告，不混入之前定向测试的残留 XML。

## 实际命令和环境

使用本工程独立 PostgreSQL `onto_test_p4`、MinIO，单 Maven reactor，Maven 与测试 JVM 各限制 512 MiB。未改写相邻工程、未运行 Docker，构建过程中 Java/迁移源码冻结。

```powershell
. ./deploy/Environment.ps1
$config = Import-OntoEnvironment
Set-OntoJava 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot'
Set-OntoBuildLimits
$env:ONTO_TEST_DB_URL = $env:ONTO_TEST_DB_URL -replace '/onto_test(?:\?.*)?$', '/onto_test_p4'
$asciiRoot = Get-OntoAsciiRoot $config.ONTO_LOCAL_DRIVE
$javaTemp = Join-Path $asciiRoot '.runtime/tmp'
mvn.cmd -B "-DargLine=-Xmx512m -Djava.io.tmpdir=$javaTemp -Djdk.net.unixdomain.tmpdir=$javaTemp" verify
```

## 最终构建制品

- 文件：`onto-designer-api/target/onto-designer-api-0.1.0-SNAPSHOT.jar`
- 大小：194922278 字节。
- SHA-256：`63B3A1456339FF2CAC24665E87EA5C055D524E3B6DBB112C1BDFE3CA715A674C`。

该 JAR 由本次完整 `verify` 成功后直接打包，不是此前用于 IAM 联调的阶段包，也不是后续跳过测试的重新打包。运行副本、真实 HTTP/浏览器及重启验收由集成记录另行核对同一摘要。

## 证据边界

本次覆盖阶段修复最终版本：先 tests 后 validate 可继续推进、真实机器失败不会被测试通过清除、NON-EL 的 OWNER 豁免保持原边界；并包括授权 gate 四连接并发/撤权顺序/异常回滚、三查询历史读取、FREEZING 恢复、守卫绑定、数据文件完整性、IAM 与 Outbox 时钟/锁等待回归。

此前浏览器发现阶段衔接问题后主动中止的批次保留为 `.runtime/c8-full-verify-interrupted.log` 及 `c8-full-verify-interrupted-summary.json`，明确标记 INTERRUPTED，其中 234 项不累加到本轮 407 项。原 RED 和分轮 GREEN 继续保留各自时点证据。

五类外部数据源与跨系统验收仍按用户既定安排等待外部环境；此处的完整后端构建成功不替代这些验收，也不替代最终制品的运行联调。
