# C5 / P3 真实环境与页面联调记录

记录日期：2026-10-21。C5 的代码、独立审查及现有环境出口已完成；五类外部实例按用户确认单独待验。下文保留基础制品与最终修复制品的证据区别。

## 1. 基础制品

- 制品：`onto-designer-api/target/onto-designer-api-0.1.0-SNAPSHOT.jar`，2026-10-20 23:59:38 打包。
- SHA-256：`FE51FF585FE578B0B0E23CA417ACF064670D0B32737D459A817F09727188523F`。
- 基础回归：235 项，失败/错误/跳过均为 0（contract 91、data 28、API 116）；见 `c5-regression.log`、`c5-regression-summary.json`。
- 环境：独立 PostgreSQL 16、MinIO、受控 HTTP 样例服务与真实文件。没有启动 Docker，没有操作相邻 agent-platform 工程。
- 此制品尚未包含 `ValueTransform` 增量及本轮独立审查修复；它们的源码与分轮测试不得合并冒称该制品已验证。

## 2. HTTP 联调

`deploy/Smoke-Data.mjs` 于北京时间 2026-10-21 00:00:45 完成，19 项通过。原始结果已另存为 `p3-http-smoke-base235.json`（重启补验前的 `p3-http-smoke.json` 同内容），运行日志为 `c5-http-smoke-run.log`。

| 项目 | 实际结果 |
| --- | --- |
| PostgreSQL 任务使用算法 | 冻结的同源关系连接返回 T-A / ALG-A；B、空标签及 A→B 连接不进入结果 |
| CSV、JSON、XLSX | 管理上传、源登记、探测、元数据、映射、冻结、执行成功；样例按 B / 空标签 / A 排列，结果上限为 1 时仍取得最后的 A |
| HTTP | POST 请求与分页样例真实执行；授权筛选后取得 A |
| MinIO Parquet | 真实 Parquet 对象读取、元数据与映射执行成功，不以 JSON 文件模拟 Parquet |
| 来源证据 | 返回并检查 SOURCE_RECORD、META_SNAPSHOT、MAPPING_SNAPSHOT、ONTOLOGY_VERSION；关系两端原始主键可定位 |
| 冻结隔离 | 缩小当前草稿源范围后，旧冻结候选仍按原冻结范围执行；未修改候选字节及摘要 |
| 三来源建模 | TABLE 和 CSV/JSON/XLSX/PDF/DOCX/TXT 的预览均只生成 PENDING；经明确人工接受后才写入模型 |

联合空间：`f7c92d1c-6d97-4aba-b74a-d2b022a7ca68`。原始关系候选：`a9181315-316f-4f4e-a6ab-98fcbf1dc8b5`，摘要 `a437963c6cf4c59e39fa384b860a4c1ded678ed8ac79699e128f46ff05083772`。各协议候选与执行标识均保存在 JSON 证据中。

文档样例由 `Initialize-DocumentSamples.ps1` 生成真实 XLSX/DOCX/PDF 并回读验证；PDF 实际渲染检查见 `p3-pdf-fixture-preview.png`。对象存储样例信息见 `p3-lake-fixture.json`；文件摘要见 `p3-document-fixtures.json`。

## 3. 浏览器联调

真实 Chromium 会话 `onto-p1`，访问 Vite 15184 与 API 18182。下列操作均通过实际页面控件完成：

1. Excel 数据源三阶段探测全部通过，重新同步元数据返回零差异。新快照 `036454cc-f790-4701-a10f-e565b8801e9c`，界面显示可读取字段、主键与历史快照。
2. 新增 `ui_http_sample`，设置 POST、`/tasks`、分页参数及批准范围，保存、三阶段探测和元数据同步均成功。
3. 打开关系查询配置，页面同时列出任务端任务编号与算法端标准码，并注明来源资源及起终点；未覆盖为冻结隔离验证而缩小的当前源范围。
4. DOCX 生成 18 个 PENDING 候选；将首个类名称修正为“页面确认的区域”，只勾选该项并提交接受。仅此项变为 ACCEPTED，随后在对象与属性树中确认类已出现。批次 `c6927209-427b-4644-9ea5-259c69eee116`，候选 `04c5321a-649f-4c25-bc07-eef6a78fa7a8`，来源段落及摘录可见。
5. 打开三层映射的对象、属性、关系列表与关系编辑，显示冻结快照、连接字段、标识模板和变更前受影响操作。
6. 数据目录显示七个源、当前最新快照及模型绑定。基础制品仍存在“关系终点目录遗漏相关操作”的已知审查问题，不能据截图判定此问题关闭。

页面此时显示部分映射完整性未通过是预期数据状态：人工接受了尚未配置映射的新类，且草稿 PostgreSQL 范围已被故意缩小。没有将未通过状态改成成功。

截图已实际查看：

- `p3-metadata-desktop.png`
- `p3-relation-query-desktop.png`、`p3-relation-query-mobile.png`
- `p3-suggestion-confirm-desktop.png`
- `p3-mapping-desktop.png`
- `p3-catalog-desktop.png`

390px 下关系配置页面宽度为 390。首轮目录截图 `p3-catalog-mobile.png` 虽无整页溢出，但长标识被挤得过于零碎；首轮抽取截图 `p3-suggestion-mobile.png` 处于加载中，不能证明真实内容布局。

加载完成后追加检查复现抽取卡片复选框标签撑宽至 401px，已在 `SuggestionReview.vue` 允许完整标识换行；`DataCatalogView.vue` 为列设置可读最小宽度并使用表格内部横向滚动。修复后真实 DOM：目录整页 390px、表格容器 332px、表内容 955px、首行高 86px；抽取整页 390px，越界元素 0。已实际查看修复截图 `p3-catalog-mobile-readable.png`、`p3-catalog-mobile-associations.png`、`p3-suggestion-mobile-readable.png`，确认可滚动读取关联且卡片完整。两文件格式检查通过；类型检查日志 `c5-mobile-typecheck.log`，最终全量及独立总审继续随 C7 进行。

## 4. 最终修复制品与独立复审

最终 JAR 于 2026-10-21 00:43:54 打包，188087936 字节，SHA-256 为 `E2D5852EAD14145E767BD50D0F9A9E55DB8D317C76DB6F19ED4C8C31B5DE30C8`。主执行者启动该制品后，独立审查者再次核对实际文件摘要。

- 00:39:37 根工程 `mvn verify`：contract 91、data 42、API 126，共 259 项，失败/错误/跳过均为 0；此轮早于最后的非空 ZIP 目录补丁，见 `c5-final-verify-summary.json`。
- 目录补丁后，完整 `P3IngestTest` 8 项全部通过，再打包上述最终制品，见 `c5-delivery-summary.json`。不将分轮结果相加或表述为最后补丁后整套 260 项重跑；C7 执行最终统一回归。
- 后端原五项及 ZIP 非空目录载荷遗漏全部关闭，Spec / Quality 均为 APPROVE。新制品独立公开 HTTP 11+3 项正反断言全部通过，包含码表引用阻删、非主键对象重复、500 行未穷尽拒绝/499 行成功、关系基数先于结果截断、双端依赖和正常 DOCX 对照。见 `c5-backend-review.md`。
- 转换过滤五项独立发现全部关闭，保留原文件/HTTP/MinIO/PostgreSQL 探针及九类 typed IDENTITY 前置拒绝证明，见 `c5-transform-review.md`。前端窄屏与发布状态增量独立 29 项通过，见 `ui-increment-review.md`。

## 5. 最终制品真实接口与持久化补验

以下检查按顺序执行，先重放旧候选，再创建新验收空间：

| 检查 | 结果与可定位证据 |
| --- | --- |
| 原候选重启重放 | `Check-Data-Persistence.mjs` 的 6 组通过：原 PostgreSQL、CSV、JSON、XLSX、HTTP、MinIO Parquet 候选在 TRIAL / SERVICE 下保持原摘要、结果及来源证据；当前草稿源范围仍被故意收窄。`p3-persistence-smoke.json`、`c5-persistence-run.log` |
| 转换过滤公开 HTTP | `Smoke-Data-Transforms.mjs` 5 项通过：授权先于转换；布尔/数值/冻结码表组合过滤；LIMIT 后及不命中行的授权数据转换错误仍拒绝；引用码表阻删；改草稿码表不改变旧冻结结果。此前基础制品 RED 保留。`p3-transform-http-smoke.json`、`c5-transform-http-green.log` |
| 缺 tenant_label 的三入口 | `Smoke-Data-Guards.mjs` 先证明受控 HTTP 源可访问，再清零计数。TRIAL / SERVICE 均 HTTP 400 `GUARD_ATTR_UNRESOLVED`；LOCAL_TEST 返回报告但 `passed=false`，实际用例失败；三类入口真实源请求均为 0。未把测试报告 HTTP 200 误称业务通过。`p3-guards-http-smoke.json` |
| 完整业务闭环复跑 | 最终制品 `Smoke-Data.mjs` 19 项通过，覆盖第2节全部业务。新空间 `7249675c-670b-4b9f-8856-169b8bd8e309`；原空间证据另存，未用新候选替代上项重启验收。`p3-http-smoke.json`、`c5-final-http-smoke-run.log` |

## 6. 保留的验收边界

最终制品浏览器补验：原 HTTP 属性映射 `01806ddc-7172-4c5c-b6c3-6e7ad32474f8` 原值保存 HTTP 200，当前草稿到 r85；关系终点 `onto_sample.algorithms` 的目录行明确显示关联操作 `adf05182-0bf5-4b7a-b649-0583cdadf8ff`。见 `c5-browser-confirmed.log`，截图 `p3-property-update-final.png` / `p3-catalog-final.png` 已实际查看。首次自动化误以操作 code 核对显示的 UUID，失败日志 `c5-browser-final.log` 保留；改为核对实际操作 id 后通过，没有更改产品行为以迎合断言。

五类真实实例 ClickHouse、MongoDB、Redis、Cassandra、Neo4j 按用户确认保留待外部验收，记录于 OM-P3-EXT。代码或拒绝边界测试不能写成这些实例已连接。P4 正式发布及正式消费入口的映射执行另随 C6 验收，跨模块智能体上架不在本轮本地证明范围。
