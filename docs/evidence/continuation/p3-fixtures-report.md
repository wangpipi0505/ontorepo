# P3 本地联调样例准备

2026-10-20。仅使用本工程独立服务和公开合成数据，未访问相邻工程。此记录确认样例可用，**不表示P3管理、映射或查询链路已经验收**。

- PostgreSQL：`onto_sources.onto_sample.tasks/algorithms`，有A、B、null和T-CROSS（A任务关联B算法）。初始化脚本重复执行不覆盖已有行；不同于应用库和会清理夹具的测试库。
- 受管上传输入：`docs/fixtures/p3/tasks.csv`、`tasks.json`、`tasks.xlsx`、`domain.txt`、`domain.docx`、`domain.pdf`；实际上传后由后台分配UUID资源和记录归属，不能把此源码路径直接当作客户端文件根。
- HTTP：`Start-Sample-Http.ps1`启动本工程独立Node服务，监听`127.0.0.1:19180`。`/tasks?page=1`返回B/null，第二页才返回A；真实GET和JSON POST `{page:2}`均已核对。`/health`可读，源配置应为method POST、body `{}`、pageParam page、pageSize 2、maxPages 3。首次PowerShell检查误把Invoke-RestMethod数组包装为单元素，改为原始响应JSON解析后检查通过；服务数据没有因检查错误而修改。
- MinIO Parquet：`Initialize-LakeSamples.ps1`复用onto-data已验证依赖，真实Parquet writer生成980字节PAR1文件并上传随机`acceptance/<uuid>/tasks.parquet`前缀。三行B/null/A，字段task_id/task_name/region/priority；摘要和安全定位保存于`p3-lake-fixture.json`。首次源文件启动因中文路径的Java参数文件处理失败，改用已核验的O:映射及明确source21；随后修正MinIO SDK Long参数类型，最终退出0上传成功。没有改变全局Java配置或共享target。

`Sample-Http.mjs`、`Smoke-Data.mjs`通过Node语法检查，新启动/停止/湖样例PowerShell均通过AST语法检查。HTTP停止脚本按PID+可执行文件+本工程命令行标识核验归属；不按进程名称批量终止服务。

`Initialize-DocumentSamples.ps1`使用本工程已解析的POI/PDFBox依赖，实际生成XLSX/DOCX/PDF，并逐项回读工作表、空标签单元格、中文段落、PDF页数和全部文本行。PDF已渲染并人工查看，没有截断、重叠或空白输出；预览为`p3-pdf-fixture-preview.png`，各文件大小/SHA和回读结果在`p3-document-fixtures.json`。PDF用内置拉丁字体的英文样例，DOCX/TXT保留中文术语与句式；不是以文本改扩展名伪造格式。

`Smoke-Data.mjs`已准备公开API全例：真实PG关系连接/两端范围，CSV/JSON/XLSX/HTTP/Parquet资源守卫、LIMIT前过滤、三阶段probe与元数据、SOURCE类型映射查询，以及TABLE与六种文件格式的候选抽取后逐一显式确认。脚本当前待P3后端接入运行，不能提前把其断言数量计为通过。
