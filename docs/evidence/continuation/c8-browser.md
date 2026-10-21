# C8 真实浏览器集成验收

日期：2026-10-21，最终复验及清理完成于 10:52，记录更新至 10:55。浏览器使用 Playwright CLI，管理员会话 `onto-p1`，独立只读会话 `onto-c8-reader`。前端 `http://127.0.0.1:15184`，后端 API 18182。

## 运行版本与范围

前期阶段后端 SHA-256：`50CF88E132B5C1D4D69C030A402858BE95975AE54A99567C34EE54890A027ECE`。此阶段未包含后续 Outbox 与候选测试阶段修复，前期结果与最终复验分开记录。

最终后端 SHA-256：`63B3A1456339FF2CAC24665E87EA5C055D524E3B6DBB112C1BDFE3CA715A674C`，由根代理完成全量验证及安全重启，运行记录见 `.runtime/c8-runtime.json`。本代理在最终包上恢复原候选、IAM 和停用撤权验证，结果见末节。

所有写操作通过真实界面完成，只使用 `c8_ui_*` 独立测试数据；未修改初始管理员、历史空间、历史候选或原有溯源证据。登录与密码重置的随机凭据只写忽略目录中的短生命周期脚本，CLI 回显被屏蔽，执行后清除脚本及敏感临时日志。浏览器仅在内存保存会话，整页刷新后回到登录页符合既定行为。

## 真实资源

| 资源 | 标识 |
| --- | --- |
| 空间 `c8_ui_20261021` | `70e3507c-d99d-4471-ba89-9659e66a7a1d` |
| 对象 `c8_ui_Device` | `bb80b2c5-491e-4c2f-ba5c-b3f87aeeaa74` |
| 保留的接受规则 | `02f39670-c3cc-4a28-bdad-6f466e91df34` |
| 曾用于制造冲突的拒绝规则（已通过界面删除） | `ff0c2893-eb21-488c-97f2-908675cf5a25` |
| 操作 `c8_ui_check` | `d0177ee7-2499-4652-9502-8db187d2cfda` |
| 确定性测试用例 | `2b992405-222f-4d07-a88e-6621cf82a0e5` |
| 冻结候选 r7 | `5f692982-3ada-421f-a1bc-41481820e534` |
| 候选 SHA-256 | `5e23b945e7ac216f9b36f6eb573a57cb4069c3f4c84682aeb3470f571c807260` |
| 规则试执行记录 | `b1891d95-64d5-4d38-a164-369a57eea0c5` |
| 规则试执行 trace | `b4dc5e94-86c5-4646-ae76-a0964b0d787a` |
| 批量测试产生的执行记录 | `984d7037-92ff-4091-a3aa-ad21b09e699e` |
| 只读角色 `c8_ui_reader_20261021` | `ef2257c7-505c-4c27-a852-2c68ca8d930b` |
| 只读用户 `c8_ui_reader_20261021` | `e267d900-c102-4d2f-986d-479c0d181014` |
| 临时 CRUD 角色（已删除） | `85e54600-9509-457b-8655-f056774d7b06` |

## C12 验收结果

1. 真实建立两条空条件规则，分别 ACCEPT/REJECT。独立冲突页返回约束类错误“规则结论互斥且条件存在包含关系”，其余引用、类型、逻辑组分别列出错误/警告数量。关联规则链接跳转到 `rules?entityId=02f39670-c3cc-4a28-bdad-6f466e91df34`，显示“已定位”，对应行高亮、详情展开。之后删除拒绝规则，复查四组均无错误/警告。
2. 规则页“冻结试执行”先选择 r7 候选，随后仅列出绑定该规则的冻结操作。网络实录 POST `/onto/candidates/5f692982-3ada-421f-a1bc-41481820e534/trial`，请求只有 `operationId` 和 `input:{}`，没有 `ruleIds`。200/OK/SUCCEEDED，完整候选摘要一致，结果为一行 `passed:"true"`，包含 VERIFY_PAYLOAD、RULE_FIRE、RULE_EXECUTION 和 RULE_VER 证据。原始安全结果 `.runtime/c8-ui-trial-result.txt`。
3. 用例页“批量运行冻结用例”选择同一候选，POST `/tests` 返回 `passed:true`，一条启用用例的 EXACT 断言通过，消息“操作命中且结果断言匹配”。页面显示候选标识、完整摘要、修订、逐条结果和真实 executionId。原始结果 `.runtime/c8-ui-batch-result.txt`。
4. 发布页读到同候选 TEST=PASSED，但前期阶段发现后端状态缺口：先运行测试会进入 REVIEWING，而冲突/一致性尚未执行，validate 的前置阶段不允许此时补检查。该候选无助手契约，CONTRACT=FAILED/CONTRACT_NOT_AVAILABLE，界面没有把它显示为可发布。已将候选 ID 和复现链交后端修复；最终包已恢复同候选检查，见末节。合法助手的新候选 tests 先行完整发布链由根代理公开 HTTP 验证覆盖，本记录不冒充浏览器完成该条正向链。

## IAM、真实权限变化与会话

- 新建只读自定义角色，最初 12 项权限；新建用户并绑定该角色。空间另外授予该用户 OWNER，系统功能权限仍只读。用户只看到这个授权空间，空间新建/编辑/归档按钮计数均为 0。
- 临时无成员角色经真实界面完成创建、改名、删除。ROLE_CREATE/ROLE_UPDATE/ROLE_DELETE 审计保留操作者、目标、revision 和前后安全快照。只读角色已有 1 名成员时删除按钮禁用；内置角色仅查看，没有保存入口。
- 只读用户可以读系统用户与角色目录；用户新建/编辑/密码重置按钮均为 0，35 项权限复选框均禁用，角色保存按钮为 0。安全断言记录 `.runtime/c8-ui-reader-result.txt`。
- 管理员停用测试角色，另一个已登录浏览器下一次点击“用户管理”即转 `/account`，显示“当前未取得可用功能权限”，侧栏只保留品牌与当前账号链接。随后管理员恢复角色，该账号通过“重新读取权限”恢复授权导航。记录 `.runtime/c8-ui-revoked-green.txt`。
- 管理员通过带明确确认的重置密码弹窗重置测试用户密码；旧浏览器下一次导航被 401 拒绝并转登录页，出现一次“登录状态已失效，请重新登录后继续操作”。刷新登录页不重复提示；记录 `.runtime/c8-ui-expired-result.txt`。新密码登录成功。
- 真实只读业务页面验证：规则新建/编辑/冻结试执行均为 0；用例新建/批量执行均为 0。为进入以 `release.read` 为前置的服务消费页面，后续给测试角色增加 `release.read`，成为 13 项只读权限；仍未授予任何 write 或 execute。凭据安全目录可以读取，创建 API key/撤销按钮均为 0。记录 `.runtime/c8-ui-readonly-green.txt`。该独立空间尚无正式端点，端点授权只读清单的内容级回归仍以独立 9 探针及永久回归为证，未将空目录冒充已有端点验证。

## 390px 布局与样式修正

已实际查看用户管理、权限详情、批量冻结用例弹窗、冲突页和只读凭据目录的桌面/390px 截图。用户表格在容器内横向滚动；页面测量 viewport/body/document 均为 390px。候选摘要会换行，弹窗主操作可见。

真实浏览器发现 `PermissionPicker.vue` 的分组继承 Element Plus `el-checkbox-group` 的零字号和零行高，分组标题不可见、说明与权限编码重叠。修复仅在 `.permission-group` 恢复 `font-size:0.875rem;line-height:1.6` 并补中文说明，不改变权限判断。修复前 `.muted`/`code` 高度为 0；修复后分别为 20.796875/17.59375px，行高 20.8/17.6px。前后截图已逐一目检。

- 修复前 computedStyle 记录：`.runtime/c8-ui-permission-style-before.json`；修复后真实结果：`.runtime/c8-ui-permission-style-green.log`。
- 当前 PermissionPicker SHA-256：`2550B96E5B2089CD52FC98CBEA9EB39A7822D6F0A9688CE6646A166862E510C5`。
- CSS 修复后 typecheck、format、build 通过：`.runtime/c8-ui-style-{typecheck,format,build}.log`。
- 最终前端代码冻结后完整回归 **31 文件、259 项通过**，2026-10-21 10:32:50 开始，57.60 秒；日志 `.runtime/c8-web-final-frozen-full.log`。没有在无变更时再次重复检查。

10:34 对试执行与批量结果补拍时滚动到弹窗真实结果区，避免固定弹窗被整页截图裁切；再次执行均通过，安全文本见 `.runtime/c8-ui-final-shots.txt`。该次试执行 trace 为 `d0d70949-7800-4d7b-aafd-e25c1bebe647`，该次批量 executionId 为 `568b9e5b-221e-4682-b5da-39a8954fd145`；上文保留首次网络实录的资源标识。

截图绝对路径根目录：`G:/207/项目/算法重组工具/代码/ontorepo/output/playwright/`。

| 截图 | 文件绝对路径 |
| --- | --- |
| 冲突分组与定位入口 | `G:/207/项目/算法重组工具/代码/ontorepo/output/playwright/c8-conflicts-desktop.png` |
| 定位后的规则高亮与展开 | `G:/207/项目/算法重组工具/代码/ontorepo/output/playwright/c8-rule-focused-desktop.png` |
| 规则冻结试执行结果 | `G:/207/项目/算法重组工具/代码/ontorepo/output/playwright/c8-rule-trial-desktop.png` |
| 批量冻结测试结果 | `G:/207/项目/算法重组工具/代码/ontorepo/output/playwright/c8-batch-tests-desktop.png` |
| 发布门禁真实状态 | `G:/207/项目/算法重组工具/代码/ontorepo/output/playwright/c8-publication-gates-desktop.png` |
| 系统审计前后快照 | `G:/207/项目/算法重组工具/代码/ontorepo/output/playwright/c8-iam-audit-desktop.png` |
| 只读用户目录 | `G:/207/项目/算法重组工具/代码/ontorepo/output/playwright/c8-reader-users-desktop.png` |
| 撤权后的账号页 | `G:/207/项目/算法重组工具/代码/ontorepo/output/playwright/c8-reader-revoked-desktop.png` |
| 401 一次性中文提示 | `G:/207/项目/算法重组工具/代码/ontorepo/output/playwright/c8-session-expired-desktop.png` |
| 390px 权限修复前 | `G:/207/项目/算法重组工具/代码/ontorepo/output/playwright/c8-reader-permissions-390.png` |
| 390px 权限修复后 | `G:/207/项目/算法重组工具/代码/ontorepo/output/playwright/c8-reader-permissions-390-fixed.png` |
| 桌面权限修复后 | `G:/207/项目/算法重组工具/代码/ontorepo/output/playwright/c8-reader-permissions-desktop-fixed.png` |
| 390px 用户页 | `G:/207/项目/算法重组工具/代码/ontorepo/output/playwright/c8-iam-users-390.png` |
| 390px 批量用例入口 | `G:/207/项目/算法重组工具/代码/ontorepo/output/playwright/c8-batch-entry-390.png` |
| 390px 冲突页 | `G:/207/项目/算法重组工具/代码/ontorepo/output/playwright/c8-conflicts-390.png` |
| 390px 只读凭据目录 | `G:/207/项目/算法重组工具/代码/ontorepo/output/playwright/c8-reader-credentials-390.png` |

## 诚实记录与后续边界

CLI 过程中有旧快照 ref、实际标题/定位参数、Element Plus 隐藏 input 与可点击容器差异造成的定位超时，均依据新快照修正；没有把这些脚本失败作为产品失败，也没有据此重复创建已成功数据。一次尝试整页 `goto` 会因内存会话设计转登录，已按真实 UI 导航重新验证撤权。控制台包含 favicon.ico 404、只读账号缺少 mapping.read/release.read 时概览可选模块的 403，以及密码重置后的预期 401；没有将其报告为无错误日志。

## 最终 JAR 复验与测试资源清理（10:49—10:52）

最终包 SHA-256 为 `63B3A1456339FF2CAC24665E87EA5C055D524E3B6DBB112C1BDFE3CA715A674C`。继续使用原 r7 候选 `5f692982-3ada-421f-a1bc-41481820e534`，候选摘要未变，没有重建候选来避开原状态。

1. 用例页再次批量执行，TEST=PASSED，断言“操作命中且结果断言匹配”，新 executionId 为 `06ec39bb-3ded-4b94-b430-d62bfacabe4b`。发布页真实 GET 返回 200，阶段为 FAILED，CONTRACT=FAILED/CONTRACT_NOT_AVAILABLE，`releaseId:null`，且“运行三项机器检查”可用。实录 `.runtime/c8-final-publication-state.txt`。
2. 点击机器检查后真实 POST `/validate` 返回 200。CONFLICT=PASSED，SHACL conforms=true 且错误/警告为空；CONSISTENCY=PASSED，ELK 完成 3 条公理的一致性推理；CONTRACT 仍为 FAILED/CONTRACT_NOT_AVAILABLE（MODEL_ONLY）；原 TEST=PASSED 保留。准备制品与提交正式发布按钮均禁用，未生成正式 release。实录 `.runtime/c8-final-validate-result.txt`。原 REVIEWING 卡住补检查的问题已在原候选上关闭。
3. 候选目录继续显示 FAILED/MODEL_ONLY，独立冲突页四组均无错误/警告，IAM 用户和权限目录正常读取。只读用户在最终包上再次登录后，用户新建/编辑/重置密码按钮计数均为 0。实录 `.runtime/c8-final-candidate-iam-result.txt`、`.runtime/c8-final-reader-before-cleanup.txt`。
4. 通过真实用户编辑界面将 `c8_ui_reader_20261021` 设为 DISABLED 并移除全部角色，保存后 revision=2。原初始管理员仍为 revision=0，角色保持 MEMBER/SYSTEM_ADMIN，未修改。实录 `.runtime/c8-final-user-cleanup.txt` 的 `user` 字段；其中 `roleBefore` 字段捕获到尚未完成导航的旧用户行，未将其用作角色证据。
5. 确认临时只读角色 memberCount=0 后，经删除确认将角色 `ef2257c7-505c-4c27-a852-2c68ca8d930b` 删除。ROLE_DELETE 审计时间为 2026/10/21 10:52:35，变更前 revision=3、13 项只读权限，变更后为空；角色列表仅剩内置 MEMBER/SYSTEM_ADMIN。实录 `.runtime/c8-final-role-cleanup.txt`。另一临时 CRUD 角色此前已删除。
6. 已登录的只读会话下一次点击“角色与权限”即被拒绝，转 `/login`，一次性中文过期提示计数为 1。实录 `.runtime/c8-final-disabled-session.txt`。忽略目录中的临时凭据文件 `.runtime/c8-ui-secrets.json` 已删除，`Test-Path` 返回 False。保留业务空间、规则、操作、候选、执行记录及审计供追溯。

最终浏览器响应没有暴露 `MACHINE_GATE_BLOCKED` 字面字段，本记录仅断言实际 FAILED、门禁结论及按钮状态；该错误代码的校验由根代理数据库定向回归及后端全量验证提供证据。最终批量运行后的首次导航脚本因侧栏分组折叠超时，`.runtime/c8-final-batch-result.txt` 记录了该自动化失败；批量执行已实际完成，其结果以上述发布状态响应与 executionId 为准。

| 最终截图 | 文件绝对路径 |
| --- | --- |
| 原 r7 再次批量通过 | `G:/207/项目/算法重组工具/代码/ontorepo/output/playwright/c8-final-batch-passed.png` |
| 测试后 FAILED 且机器检查可用 | `G:/207/项目/算法重组工具/代码/ontorepo/output/playwright/c8-final-publication-before-validate.png` |
| 实际机器检查结论及发布阻断 | `G:/207/项目/算法重组工具/代码/ontorepo/output/playwright/c8-final-publication-validated.png` |
| 用户停用、无角色、revision=2 | `G:/207/项目/算法重组工具/代码/ontorepo/output/playwright/c8-final-user-disabled.png` |
| 临时角色删除审计 | `G:/207/项目/算法重组工具/代码/ontorepo/output/playwright/c8-final-role-deleted-audit.png` |
| 停用后下一次导航被拒绝 | `G:/207/项目/算法重组工具/代码/ontorepo/output/playwright/c8-final-disabled-session.png` |

本轮前端及浏览器责任范围已完成。最终复验没有新增前端生产修改，仍对应上文冻结后的 259 项完整回归及 PermissionPicker 文件摘要。五类数据源外部实例验收边界保持原记录。
