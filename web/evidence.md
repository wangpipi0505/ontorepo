# 前端验证记录

2026-10-20 21:35 C2 审查修复：属性和共享列表删除均在首次 await 前固定写入修订；真实数值控件清空 null 移除上下界并保留 0；关系页提示只读超范围标记与 OWNER 豁免。新反例先 RED 后 GREEN，定向 2 文件 9 条通过；完整回归 11 文件 50 条通过，typecheck / format:check 通过。未重复构建，详见 [报告补充](../docs/evidence/continuation/c2-frontend-report.md)。

2026-10-20 21:21 C2：P1 剩余前端页面、属性分面、四类操作绑定、导入人工确认、候选检查 / 豁免及用例已实现。先执行关键输入边界、提交与旧属性修订 RED，再完成实现；最终 `pnpm test --maxWorkers=2` 共 10 文件 43 条通过，`pnpm typecheck`、`pnpm format:check`、`pnpm build` 全部退出 0。原有 18 条回归保留。真实服务和浏览器联合验收由主执行者统一进行。完整范围与证据见 [C2 前端报告](../docs/evidence/continuation/c2-frontend-report.md)。

2026-10-20 RED：在 API 客户端与树逻辑实现前运行 `pnpm test`，测试套件因缺少 `packages/api-client/src/index` 失败（退出 1）。预先写入 401 清理、409 冲突、网络失败、树防环及输入不变性断言。后续 GREEN 与构建结果见本文末尾。

2026-10-20 20:29 RED：新增非法信封测试后，`Response('null')` 导致原生 TypeError，预期统一 INVALID_RESPONSE，测试失败；补充信封结构校验后通过。

2026-10-20 20:33 RED：新增 deferred 双会话测试，旧请求迟到 401 清空新会话，断言失败；补充请求令牌与当前令牌一致性检查后通过。同时先加入表单修订锁测试，再实现 `useDraftRevision`。

2026-10-20 20:35 RED：先加入完整草稿一致性测试，缺少 `readDraftSnapshot` 失败；实现空间修订前后夹读，禁止旧实体配新修订，随后通过。

2026-10-20 20:37 GREEN：`pnpm test` 5 文件、13 条全部通过；`pnpm typecheck` 退出 0；`pnpm build` Vite 7.3.7 编译 1673 模块、退出 0；`pnpm format:check` 全部通过。

测试覆盖：401 清会话与迟到 401 隔离；403 保留会话；409 原始冲突码；网络/非法响应可恢复；树自父级/后代/不存在父级拒绝及输入不变；资源最新请求获胜与离页失败隔离；表单 409 后不能借新修订再次写入；属性冲突后旧对象暂停写入；读取期间修订变化拒绝提交快照。

`pnpm api:generate` 使用锁定的 openapi-typescript 7.13.0，从主执行者由真实服务导出的 `contracts/openapi.json` 生成 `packages/api-client/src/generated.ts`。业务类型以生成的 components 为结构来源，按已批准契约补充必填、枚举与 null 语义；生成器原始 springdoc 可空标记不完整，已明确细化。

前端开发命令为 `pnpm dev`，端口 15184，`/onto` 代理独立后端 18182。依赖安装于本 web workspace，不引用相邻工程 node_modules。内存令牌、API 业务数据不写浏览器存储。未提交 Git。

真实服务浏览器闭环、桌面/窄屏截图与 UI 角色场景由主执行者统一联调；本记录不把单元测试替代浏览器验收。当前不包含完整 P1 后续属性约束、关系、发布及外部实例查询功能。

2026-10-20 20:41 浏览器阻断与组件 RED：主执行者实测登录无 POST，DOM 中存在 2 个 form 和 1 个 `form form`。使用 Vue Test Utils 2.4.6 + jsdom 26.1.0、真实 Element Plus el-form 复现；登录、空间、操作的单表单断言失败（实际 2，预期 1）。原因是 el-form 本身渲染 form，外层原生 form 造成嵌套。对象与属性选择器在 jsdom 中另有递归更新，组件测试将下拉控件 stub，保留真实表单、输入和提交按钮；下拉真实交互继续由浏览器验收。

2026-10-20 20:43 修复后：登录和四个编辑对话框统一仅保留一个 `el-form @submit.prevent`。新增五条组件用例验证唯一 form、默认提交被阻止，并实际填写输入、点击 submit 按钮，断言调用登录/空间/对象/属性/操作 API；草稿写入携带打开时修订 5。`pnpm test` 6 文件 18 条全部通过，`pnpm typecheck` 退出 0。本组件测试使用 API spy，不替代真实后端浏览器 POST 验收。

2026-10-20 20:52 浏览器最终联调：真实父级与属性类型下拉、必填选择、全部提交、冻结和试执行成功；桌面与窄屏均返回三个领域对象。修复共享 stack 网格的内容最小宽度后，390px 页面内候选卡片 client/scroll 同为 364px，内部同为 332px；属性表可横向滚动并打开编辑框。CSS 修复后再次运行 `pnpm format:check` 和 `pnpm build` 均通过。完整证据见 [浏览器结果](../docs/evidence/browser-smoke.json)和[实现记录](../docs/evidence/p1-first-slice-20261020.md)。
