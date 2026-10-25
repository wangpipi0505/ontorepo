/**
 * 纯前端演示模式（与 agent-platform 的 mock-transport 同口径）。
 *
 * VITE_MOCK=true 时 ApiClient 注入本模块的 mockFetcher——不发真实 HTTP，
 * 按路径正则匹配返回中文示例数据，全部页面脱离后端可用；业务代码零感知。
 * 登录也走真实 /auth/login 路径，会话与权限流与联调环境一致。
 *
 * 退出方式：不带 VITE_MOCK（或 .env.mock 缺席）构建即恢复真实接口；
 * 生产构建中该分支为死代码，会被摇树移除。
 */

/** 是否处于演示模式：环境变量存在且不为 false 时开启。 */
export function isMockMode(): boolean {
  return typeof import.meta.env?.VITE_MOCK === 'string' && import.meta.env.VITE_MOCK !== 'false'
}

/** 信封工具：与真实服务端相同的 {code,message,data} 结构。 */
const ok = (data: unknown) =>
  new Response(JSON.stringify({ code: 'OK', message: '', data }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  })
const fail = (status: number, code: string, message: string) =>
  new Response(JSON.stringify({ code, message, data: null }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })

/** 模拟网络延迟，让加载态可见且接近真实体感。 */
const latency = () => new Promise((resolve) => setTimeout(resolve, 90 + Math.random() * 130))

// ── 会话 ──
const MOCK_TOKEN = 'mock-token-demo'
const MOCK_USER = { id: 'u-demo', username: 'demo', displayName: '演示管理员' }
/** 覆盖 pagePermissions 全部读写码，路由守卫与写按钮在演示态全部可用。 */
const MOCK_PERMISSIONS = [
  'space.read', 'space.write',
  'model.read', 'model.write',
  'rule.read', 'rule.write',
  'operation.read', 'operation.write',
  'knowledge.read', 'knowledge.write',
  'grant.read', 'grant.write',
  'datasource.read', 'datasource.write',
  'release.read', 'release.write',
  'trace.read',
  'mapping.read', 'mapping.write',
  'ingest.read', 'ingest.write',
  'test.read', 'test.write',
  'candidate.read', 'candidate.write', 'candidate.execute',
  'service.execute',
  'system.user.read', 'system.user.write',
  'system.role.read', 'system.role.write',
  'system.audit.read',
]

// ── 空间 ──
const now = '2026-10-25T09:30:00Z'
const spaces = [
  {
    id: 'sp-sat', code: 'satellite', name: '卫星本体', namespaceUri: 'https://example.org/satellite#',
    domain: '航天', status: 'ACTIVE', draftRev: 3, role: 'OWNER',
    classCount: 4, propertyCount: 4, relationCount: 2, operationCount: 2,
    createdAt: '2026-09-12T08:00:00Z', description: '卫星型号本体：平台、载荷与轨道运行域。',
  },
  {
    id: 'sp-gnd', code: 'ground-equip', name: '地面设备台账', namespaceUri: 'https://example.org/ground#',
    domain: '装备', status: 'ACTIVE', draftRev: 2, role: 'EDITOR',
    classCount: 2, propertyCount: 3, relationCount: 1, operationCount: 1,
    createdAt: '2026-09-28T02:10:00Z', description: '测控站与地面装备的资产编目。',
  },
]
const spaceById = (id: string) => spaces.find((s) => s.id === id)
/** 空间内资源写操作统一返回草稿修订 +1 的空间，与真实服务的写响应模式一致。 */
const spaceWritten = (id: string) => {
  const space = { ...spaceById(id) ?? spaces[0] }
  return { ...space, draftRev: space.draftRev + 1 }
}

// ── 模型：类 / 属性 / 关系 ──
const classes = [
  { id: 'cls-satellite', spaceId: 'sp-sat', name: 'Satellite', displayName: '卫星', parentId: null, description: '在轨运行的航天器平台。', draftRev: 3 },
  { id: 'cls-orbit', spaceId: 'sp-sat', name: 'Orbit', displayName: '轨道', parentId: null, description: '轨道根数描述的运行轨道。', draftRev: 3 },
  { id: 'cls-payload', spaceId: 'sp-sat', name: 'Payload', displayName: '载荷', parentId: 'cls-satellite', description: '执行任务的有效载荷。', draftRev: 3 },
  { id: 'cls-subsystem', spaceId: 'sp-sat', name: 'Subsystem', displayName: '分系统', parentId: 'cls-satellite', description: '电源、热控等平台分系统。', draftRev: 3 },
  { id: 'cls-station', spaceId: 'sp-gnd', name: 'Station', displayName: '测控站', parentId: null, description: '地面测控站。', draftRev: 2 },
]
const properties = [
  { id: 'prop-name', spaceId: 'sp-sat', name: 'name', displayName: '名称', rangeType: 'STRING', classId: 'cls-satellite', facets: { primaryKey: true } },
  { id: 'prop-launch', spaceId: 'sp-sat', name: 'launchDate', displayName: '发射日期', rangeType: 'DATE', classId: 'cls-satellite' },
  { id: 'prop-mass', spaceId: 'sp-sat', name: 'massKg', displayName: '质量', rangeType: 'DECIMAL', classId: 'cls-satellite', facets: { unitCodebookId: 'cb-unit', unitCode: 'kg', minimum: 0 } },
  { id: 'prop-altitude', spaceId: 'sp-sat', name: 'altitudeKm', displayName: '轨道高度', rangeType: 'INTEGER', classId: 'cls-orbit', facets: { minimum: 100, maximum: 50000 } },
  { id: 'prop-station-name', spaceId: 'sp-gnd', name: 'stationName', displayName: '站名', rangeType: 'STRING', classId: 'cls-station' },
]
const relations = [
  { id: 'rel-in-orbit', spaceId: 'sp-sat', name: 'inOrbit', fromClassId: 'cls-satellite', toClassId: 'cls-orbit', cardinality: 'ONE_TO_MANY', inverseOf: null, transitive: false, symmetric: false, description: '卫星运行的轨道。' },
  { id: 'rel-carries', spaceId: 'sp-sat', name: 'carries', fromClassId: 'cls-satellite', toClassId: 'cls-payload', cardinality: 'ONE_TO_MANY', inverseOf: null, transitive: false, symmetric: false, description: '卫星搭载的载荷。' },
]

// ── 规则域：约束 / 规则 / 策略 / 码本 ──
const constraints = [
  { id: 'cst-mass-range', spaceId: 'sp-sat', name: '质量下限', kind: 'RANGE', targetType: 'PROPERTY', targetRef: 'prop-mass', expr: { min: 0, max: 20000 } },
]
const rules = [
  { id: 'rule-launch-check', spaceId: 'sp-sat', name: '发射要素齐套', applicableClassId: 'cls-satellite', kind: 'VALIDATION', condition: 'launchDate != null && massKg > 0', action: { severity: 'ERROR', message: '发射日期与质量必须已登记' }, scoring: null, severity: 'ERROR' },
  { id: 'rule-health-score', spaceId: 'sp-sat', name: '平台健康评分', applicableClassId: 'cls-subsystem', kind: 'SCORING', condition: 'true', action: { expression: '0.6*power + 0.4*thermal' }, scoring: { weights: { power: 0.6, thermal: 0.4 } }, severity: 'WARN' },
]
const strategies = [
  { id: 'stg-readonly', spaceId: 'sp-sat', name: '只读查询策略', policy: { maxRows: 1000, timeoutMs: 5000, resultLimit: 200, failureMode: 'FAIL', readScope: { domains: ['航天'] } }, boundOps: ['op-orbit-query'] },
]
const codebooks = [
  { id: 'cb-unit', spaceId: 'sp-sat', code: 'unit', name: '计量单位', kind: 'UNIT', entries: [{ code: 'kg', label: '千克' }, { code: 'km', label: '千米' }] },
  { id: 'cb-status', spaceId: 'sp-sat', code: 'sat-status', name: '在轨状态', kind: 'VALUE', entries: [{ code: 'ACTIVE', label: '在轨运行' }, { code: 'DEORBIT', label: '已离轨' }] },
]

// ── 操作与测试 ──
const operations = [
  { id: 'op-orbit-query', spaceId: 'sp-sat', code: 'orbit-query', name: '轨道要素查询', kind: 'OBJECT_QUERY', description: '按卫星名称查询其运行轨道要素。', precondition: '卫星已关联轨道', strategyRef: 'stg-readonly', ruleIds: [], inputSchema: { type: 'object', properties: { satelliteName: { type: 'string' } }, required: ['satelliteName'] } },
  { id: 'op-health-score', spaceId: 'sp-sat', code: 'health-score', name: '平台健康评分', kind: 'SCORING', description: '按分系统遥测加权评分。', precondition: '', strategyRef: null, ruleIds: ['rule-health-score'], inputSchema: { type: 'object', properties: { subsystem: { type: 'string' } } } },
]
const testCases = [
  { id: 'tc-orbit-01', spaceId: 'sp-sat', name: '高分一号轨道查询', question: '查询高分一号的轨道高度', operationId: 'op-orbit-query', input: { satelliteName: '高分一号' }, expected: { altitudeKm: 645 }, matchMode: 'CONTAINS', enabled: true, mode: 'DIRECT' },
]

// ── 候选与发布 ──
const CANDIDATE_SHA = 'sha256-mock-candidate-9f2c'
const candidates = [
  { id: 'cand-001', spaceId: 'sp-sat', draftRev: 3, sha256: CANDIDATE_SHA, status: 'TESTING', createdAt: '2026-10-24T11:00:00Z', formatVersion: 1, capability: 'CONTRACT' },
]
const publication = {
  capability: 'CONTRACT', candidateId: 'cand-001', candidateSha256: CANDIDATE_SHA, draftRev: 3,
  status: 'COMMITTED', preReleaseId: 'rel-003', preVersionNo: 3,
  checks: [
    { kind: 'CONFLICT', conclusion: 'PASSED', reason: null, candidateSha256: CANDIDATE_SHA, checkedAt: now, report: {} },
    { kind: 'CONSISTENCY', conclusion: 'PASSED', reason: null, candidateSha256: CANDIDATE_SHA, checkedAt: now, report: {} },
    { kind: 'CONTRACT', conclusion: 'PASSED', reason: null, candidateSha256: CANDIDATE_SHA, checkedAt: now, report: {} },
    { kind: 'TEST', conclusion: 'PASSED', reason: null, candidateSha256: CANDIDATE_SHA, checkedAt: now, report: { passed: 1, failed: 0 } },
    { kind: 'REVIEW', conclusion: 'PASSED', reason: null, candidateSha256: CANDIDATE_SHA, checkedAt: now, report: { actorId: 'u-demo' } },
  ],
  waivers: [], releaseId: 'rel-003',
  artifacts: [
    { kind: 'BUNDLE', sha256: 'sha256-bundle-a1', size: 48112, ready: true },
    { kind: 'MANIFEST', sha256: 'sha256-manifest-b2', size: 2048, ready: true },
    { kind: 'CONTEXT_PACK', sha256: 'sha256-pack-c3', size: 15400, ready: true },
    { kind: 'KNOWLEDGE_INDEX', sha256: 'sha256-kidx-d4', size: 8210, ready: true },
  ],
  review: { actorId: 'u-demo', reason: '门禁全绿，同意发布。', reviewedAt: now },
}
const releases = [
  { id: 'rel-003', spaceId: 'sp-sat', versionNo: 3, candidateId: 'cand-001', status: 'READY', bundleSha256: 'sha256-bundle-a1', manifestSha256: 'sha256-manifest-b2', publishedAt: now, publishedBy: '演示管理员' },
  { id: 'rel-002', spaceId: 'sp-sat', versionNo: 2, candidateId: 'cand-000', status: 'DEPRECATED', bundleSha256: 'sha256-old', manifestSha256: 'sha256-old-m', publishedAt: '2026-09-30T10:00:00Z', publishedBy: '演示管理员' },
]
const endpoints = [
  { id: 'ep-orbit', releaseId: 'rel-003', opCode: 'orbit-query', route: '/onto/s/sp-sat/ops/orbit-query/lookup', description: '轨道要素查询服务端点', status: 'ACTIVE', execAttrs: { timeoutMs: 5000 } },
  { id: 'ep-health', releaseId: 'rel-003', opCode: 'health-score', route: '/onto/s/sp-sat/ops/health-score/lookup', description: '平台健康评分服务端点', status: 'ACTIVE', execAttrs: { timeoutMs: 8000 } },
]
const releaseEntities = [
  { kind: 'CLASS', key: 'cls-satellite', value: { name: 'Satellite' } },
  { kind: 'CLASS', key: 'cls-orbit', value: { name: 'Orbit' } },
  { kind: 'PROPERTY', key: 'prop-mass', value: { name: 'massKg' } },
  { kind: 'RELATION', key: 'rel-in-orbit', value: { name: 'inOrbit' } },
  { kind: 'OPERATION', key: 'op-orbit-query', value: { code: 'orbit-query' } },
]
const releaseEvents = [
  { id: 301, spaceId: 'sp-sat', releaseId: 'rel-003', type: 'RELEASE_COMMITTED', boundStatus: 'BOUND', releaseStatus: 'READY', status: 'SENT', retryCount: 0, nextRetryAt: null, lastError: null, createdAt: now },
  { id: 302, spaceId: 'sp-sat', releaseId: 'rel-002', type: 'RELEASE_DEPRECATED', boundStatus: 'BOUND', releaseStatus: 'DEPRECATED', status: 'SENT', retryCount: 0, nextRetryAt: null, lastError: null, createdAt: '2026-10-01T10:00:00Z' },
]
const apiCredentials = [
  { id: 'cred-01', spaceId: 'sp-sat', principalId: 'u-demo', name: '消费方演示凭据', status: 'ACTIVE', createdAt: now, expiresAt: '2027-10-25T00:00:00Z' },
]
const endpointGrants = [
  { userId: 'u-demo', username: 'demo', displayName: '演示管理员', dataScope: null, expiresAt: null, status: 'ACTIVE' },
]

// ── 执行与溯源 ──
const executionRows = [
  { satellite: '高分一号', orbit: '太阳同步', altitudeKm: '645' },
  { satellite: '高分二号', orbit: '太阳同步', altitudeKm: '611' },
]
const executions = [
  {
    id: 'exe-101', traceId: 'trc-101', subjectType: 'CANDIDATE', subjectId: 'cand-001', opCode: 'orbit-query',
    caller: 'CONSOLE', status: 'SUCCEEDED', errorCode: null, elapsedMs: 148, createdAt: now,
    columns: ['satellite', 'orbit', 'altitudeKm'], rows: executionRows, candidateSha256: CANDIDATE_SHA,
    steps: [
      { kind: 'GUARD', status: 'PASSED', detail: '行级过滤已应用：domains ⊆ 航天' },
      { kind: 'QUERY', status: 'PASSED', detail: 'SPARQL 查询返回 2 行' },
    ],
  },
  {
    id: 'exe-102', traceId: 'trc-102', subjectType: 'RELEASE', subjectId: 'rel-003', opCode: 'health-score',
    caller: 'API', status: 'FAILED', errorCode: 'QUERY_TIMEOUT', elapsedMs: 5021, createdAt: '2026-10-24T16:20:00Z',
    columns: [], rows: [], candidateSha256: CANDIDATE_SHA,
    steps: [{ kind: 'GUARD', status: 'PASSED', detail: '只读策略校验通过' }, { kind: 'QUERY', status: 'FAILED', detail: '查询超时 5000ms' }],
  },
]
/** UIF-O5 泳道血缘：节点 kind 决定 Agent/Activity/Entity 三泳道归属。 */
const executionTrace = (id: string) => {
  const execution = executions.find((e) => e.id === id) ?? executions[0]
  return {
    execution: { ...execution, caller: execution.caller ?? 'CONSOLE' },
    authorization: [
      { order: 1, kind: 'ENDPOINT_STATUS', passed: true, detail: { route: endpoints[0].route } },
      { order: 2, kind: 'GRANT', passed: true, detail: { userId: 'u-demo' } },
      { order: 3, kind: 'ROW_FILTER', passed: true, detail: { domains: ['航天'] } },
    ],
    steps: execution.steps,
    evidence: [
      { id: 'ev-1', type: 'QUERY_TEXT', ref: null, sha256: 'sha256-q1', detail: { text: 'SELECT ?satellite ?orbit ?altitudeKm …' } },
      { id: 'ev-2', type: 'RESULT_DIGEST', ref: null, sha256: 'sha256-r2', detail: { rows: execution.rows.length } },
    ],
    timeline: [
      { id: 't1', at: execution.createdAt, kind: 'REQUEST', title: '收到服务调用', detail: { opCode: execution.opCode, caller: execution.caller } },
      { id: 't2', at: execution.createdAt, kind: 'AUTHZ', title: '授权与行级过滤通过', detail: {} },
      { id: 't3', at: execution.createdAt, kind: 'QUERY', title: '执行只读查询', detail: { elapsedMs: execution.elapsedMs } },
      { id: 't4', at: execution.createdAt, kind: 'RESPONSE', title: execution.status === 'SUCCEEDED' ? '返回结果集' : '返回错误码', detail: { errorCode: execution.errorCode } },
    ],
    graph: {
      nodes: [
        { id: 'n-user', label: '演示管理员', kind: 'AGENT', ref: 'u-demo' },
        { id: 'n-exec', label: `执行 ${execution.id}`, kind: 'EXECUTION', ref: execution.id },
        { id: 'n-step-guard', label: '守卫校验', kind: 'STEP', ref: 'GUARD' },
        { id: 'n-step-query', label: '数据查询', kind: 'STEP', ref: 'QUERY' },
        { id: 'n-release', label: '发布 v3', kind: 'RELEASE', ref: 'rel-003' },
        { id: 'n-result', label: '结果集', kind: 'RESULT', ref: execution.traceId },
      ],
      edges: [
        { id: 'e1', from: 'n-user', to: 'n-exec', label: '发起' },
        { id: 'e2', from: 'n-exec', to: 'n-step-guard', label: '步骤1' },
        { id: 'e3', from: 'n-step-guard', to: 'n-step-query', label: '步骤2' },
        { id: 'e4', from: 'n-release', to: 'n-exec', label: '消费版本' },
        { id: 'e5', from: 'n-step-query', to: 'n-result', label: '产出' },
      ],
    },
  }
}

// ── 数据接入：数据源 / 快照 / 映射 ──
const datasources = [
  { id: 'ds-pg-01', spaceId: 'sp-sat', name: '卫星编目库', kind: 'POSTGRESQL', status: 'ACTIVE', config: { host: '127.0.0.1', port: 25432, database: 'sat_catalog' }, createdAt: '2026-09-15T08:00:00Z' },
]
const sourceCapabilities = [
  { kind: 'POSTGRESQL', supported: true, features: ['SNAPSHOT', 'INCREMENTAL'] },
  { kind: 'CSV_FILE', supported: true, features: ['FULL'] },
  { kind: 'ORACLE', supported: false, features: [] },
]
const metadataSnapshots = [
  { id: 'snap-01', datasourceId: 'ds-pg-01', resource: 'public.satellite', capturedAt: now, columnCount: 8, rowCount: 126 },
]
const metadataSnapshotDetail = {
  ...metadataSnapshots[0],
  columns: [
    { name: 'sat_id', type: 'varchar', nullable: false },
    { name: 'sat_name', type: 'varchar', nullable: false },
    { name: 'launch_date', type: 'date', nullable: true },
    { name: 'mass_kg', type: 'numeric', nullable: true },
  ],
}
const managedFiles = [
  { id: 'file-01', name: '测控站清单.csv', mediaType: 'text/csv', size: 24800, sha256: 'sha256-file-01', resource: 'upload/stations.csv' },
]
const mappings = {
  objects: [
    { id: 'om-sat', datasourceId: 'ds-pg-01', sourceResource: 'public.satellite', targetClassId: 'cls-satellite', objectKeyExpr: 'sat_id', filter: null, metaSnapshotId: 'snap-01' },
  ],
  properties: [
    { id: 'pm-name', objectMappingId: 'om-sat', sourceField: 'sat_name', targetPropertyId: 'prop-name', transform: { kind: 'IDENTITY' }, required: true },
    { id: 'pm-launch', objectMappingId: 'om-sat', sourceField: 'launch_date', targetPropertyId: 'prop-launch', transform: null, required: false },
    { id: 'pm-mass', objectMappingId: 'om-sat', sourceField: 'mass_kg', targetPropertyId: 'prop-mass', transform: { kind: 'TO_NUMBER' }, required: false },
  ],
  relations: [],
}
const mappingValidation = {
  valid: true,
  checks: [
    { kind: 'CLASS_OBJECT', passed: true, issues: [] },
    { kind: 'REQUIRED_PROPERTY', passed: true, issues: [] },
    { kind: 'RELATION_KEYS', passed: true, issues: [] },
    { kind: 'REACHABLE_REFERENCE', passed: true, issues: [] },
  ],
  coverage: { mapped: 3, pending: 0, unmappable: 0 },
  coverageBasis: 'sp-sat 全部必填属性',
  affectedOperations: [{ id: 'op-orbit-query', code: 'orbit-query', name: '轨道要素查询' }],
}
const dataCatalog = {
  spaceId: 'sp-sat',
  objects: [{ objectMappingId: 'om-sat', classId: 'cls-satellite', className: '卫星', resource: 'public.satellite', fields: [{ sourceField: 'sat_name', propertyId: 'prop-name', propertyName: '名称', required: true }] }],
}
const catalogProperties = [{ objectMappingId: 'om-sat', classId: 'cls-satellite', className: '卫星', propertyId: 'prop-name', propertyName: '名称', sourceField: 'sat_name', required: true }]

// ── 知识与建议 ──
const knowledge = [
  { id: 'kb-01', spaceId: 'sp-sat', kind: 'TERM', term: '太阳同步轨道', aliases: ['SSO', '极轨'], definition: '轨道平面进动与地球公转同步的近地轨道，高度约 600~800km。', conceptRef: 'cls-orbit', sourceRef: 'doc/orbit-handbook' },
  { id: 'kb-02', spaceId: 'sp-sat', kind: 'ENTITY', term: '高分一号', aliases: ['GF-1'], definition: '2013 年发射的国产光学遥感卫星，运行于太阳同步轨道。', conceptRef: 'cls-satellite', sourceRef: 'doc/sat-catalog', subjectRef: 'sat-gf1', predicate: null, objectRef: null },
]
const suggestions: unknown[] = []

// ── 空间管理：守卫 / 授权 / 就绪度 ──
const guards = {
  guardCfg: {
    domain: { type: 'STRING', filter_binding: { table: 'public.satellite', column: 'domain', operator: 'EQ' } },
    ownerId: { type: 'STRING', authorization_rule: { kind: 'PRINCIPAL_ATTRIBUTE', attribute: 'userId' } },
  },
}
const grantUsers = [
  { userId: 'u-demo', username: 'demo', displayName: '演示管理员' },
  { userId: 'u-editor', username: 'editor01', displayName: '本体编辑员' },
]
const spaceGrants = [
  { userId: 'u-demo', username: 'demo', displayName: '演示管理员', role: 'OWNER', dataScope: null, attributes: {} },
  { userId: 'u-editor', username: 'editor01', displayName: '本体编辑员', role: 'EDITOR', dataScope: { domains: ['航天'] }, attributes: {} },
]
const readiness = {
  classCount: 4, propertyCount: 4, relationCount: 2, ruleCount: 2, assistantCount: 1, guardCount: 2, caseCount: 1, candidateCount: 1,
}
const conflicts = { errors: [], warnings: [{ kind: 'LOGIC', message: '规则 rule-health-score 的评分权重建议在冻结前复核。', entityIds: ['rule-health-score'] }] }

// ── 全局视图 ──
const globalTrace = {
  executions: executions.map((e) => ({ ...e, spaceId: 'sp-sat', spaceName: '卫星本体' })),
  audit: [
    { id: 9001, spaceId: 'sp-sat', actorId: 'u-demo', actorName: '演示管理员', action: 'RELEASE_COMMIT', subjectId: 'rel-003', subjectType: 'RELEASE', versionRefs: ['rel-003'], inputDigest: 'sha256-in', outputDigest: 'sha256-out', result: 'SUCCESS', detail: { versionNo: 3 }, createdAt: now, spaceName: '卫星本体' },
    { id: 9002, spaceId: 'sp-sat', actorId: 'u-demo', actorName: '演示管理员', action: 'MODEL_SAVE', subjectId: 'cls-orbit', subjectType: 'CLASS', versionRefs: [], inputDigest: null, outputDigest: null, result: 'SUCCESS', detail: {}, createdAt: '2026-10-20T08:00:00Z', spaceName: '卫星本体' },
  ],
}
const globalData = {
  spaces: [{ id: 'sp-sat', name: '卫星本体', datasourceCount: 1, fileCount: 1, mappingCount: 1 }],
  datasources,
  files: managedFiles,
}
const globalDelivery = {
  spaces: [{ id: 'sp-sat', name: '卫星本体', releaseCount: 2, endpointCount: 2 }],
  releases,
  endpoints,
  events: releaseEvents,
}
const globalServices = { services: endpoints.map((e) => ({ ...e, spaceName: '卫星本体', releaseVersion: 3 })) }

// ── 系统管理 ──
const systemUsers = [
  { id: 'u-demo', username: 'demo', displayName: '演示管理员', status: 'ACTIVE', revision: 4, createdAt: '2026-08-01T00:00:00Z', roles: [{ id: 'r-admin', code: 'ADMIN', name: '系统管理员' }] },
  { id: 'u-editor', username: 'editor01', displayName: '本体编辑员', status: 'ACTIVE', revision: 2, createdAt: '2026-09-01T00:00:00Z', roles: [{ id: 'r-editor', code: 'EDITOR', name: '本体编辑' }] },
  { id: 'u-viewer', username: 'viewer01', displayName: '只读用户', status: 'DISABLED', revision: 1, createdAt: '2026-09-10T00:00:00Z', roles: [{ id: 'r-viewer', code: 'VIEWER', name: '只读' }] },
]
const systemRoles = [
  { id: 'r-admin', code: 'ADMIN', name: '系统管理员', description: '全部功能', builtIn: true, status: 'ACTIVE', revision: 1, permissions: MOCK_PERMISSIONS, memberCount: 1 },
  { id: 'r-editor', code: 'EDITOR', name: '本体编辑', description: '模型与数据维护', builtIn: true, status: 'ACTIVE', revision: 1, permissions: ['space.read', 'model.read', 'model.write', 'rule.read', 'rule.write', 'mapping.read', 'mapping.write', 'test.read'], memberCount: 1 },
  { id: 'r-viewer', code: 'VIEWER', name: '只读', description: '仅查看', builtIn: true, status: 'ACTIVE', revision: 1, permissions: ['space.read', 'model.read', 'trace.read'], memberCount: 1 },
]
const permissionDefinitions = [
  { code: 'space.read', group: '空间', name: '空间查看', description: '查看空间列表与详情' },
  { code: 'space.write', group: '空间', name: '空间维护', description: '创建、修改与归档空间' },
  { code: 'model.read', group: '模型', name: '模型查看', description: '查看类、属性与关系' },
  { code: 'model.write', group: '模型', name: '模型编辑', description: '维护类、属性与关系' },
  { code: 'rule.read', group: '规则', name: '规则查看', description: '查看约束、规则与策略' },
  { code: 'rule.write', group: '规则', name: '规则维护', description: '维护约束、规则与策略' },
  { code: 'operation.read', group: '操作', name: '操作查看', description: '查看操作与助手声明' },
  { code: 'operation.write', group: '操作', name: '操作维护', description: '维护操作与助手声明' },
  { code: 'candidate.read', group: '测试', name: '候选查看', description: '查看候选与门禁' },
  { code: 'candidate.write', group: '测试', name: '候选操作', description: '创建候选并执行门禁' },
  { code: 'release.read', group: '发布', name: '发布查看', description: '查看发布与服务端点' },
  { code: 'release.write', group: '发布', name: '发布操作', description: '冻结、评审与提交发布' },
  { code: 'trace.read', group: '溯源', name: '溯源查看', description: '查看执行与审计流水' },
  { code: 'system.audit.read', group: '系统', name: '身份审计查看', description: '查看身份权限审计' },
]
const iamAuditPage = {
  items: [
    { id: 7001, actorId: 'u-demo', actorName: '演示管理员', action: 'ROLE_PERMISSION_UPDATE', targetId: 'r-editor', beforeState: { permissions: ['space.read'] }, afterState: { permissions: ['space.read', 'model.write'] }, createdAt: '2026-10-21T09:00:00Z' },
    { id: 7002, actorId: 'u-demo', actorName: '演示管理员', action: 'USER_STATUS_UPDATE', targetId: 'u-viewer', beforeState: { status: 'ACTIVE' }, afterState: { status: 'DISABLED' }, createdAt: '2026-10-18T14:30:00Z' },
  ],
  page: 1, pageSize: 30, total: 2,
}
const account = { ...MOCK_USER, permissions: MOCK_PERMISSIONS }

/** 路由表：按方法 + 正则匹配 /onto 前缀后的路径；参数捕获组交给处理器。 */
type Handler = (m: RegExpMatchArray, body: any, q: URLSearchParams) => unknown
const routes: [string, RegExp, Handler][] = [
  // 会话
  ['POST', /^\/auth\/login$/, () => ({ token: MOCK_TOKEN, expiresAt: '2026-10-26T00:00:00Z', user: MOCK_USER })],
  ['POST', /^\/auth\/logout$/, () => null],
  ['GET', /^\/auth\/permissions$/, () => MOCK_PERMISSIONS],
  ['GET', /^\/account$/, () => account],
  ['PUT', /^\/account\/password$/, () => null],

  // 空间
  ['GET', /^\/spaces$/, () => spaces],
  ['POST', /^\/spaces$/, (_m, body) => ({ ...spaceWritten('sp-sat'), id: 'sp-new', code: body?.code ?? 'new-space' })],
  ['GET', /^\/spaces\/([^/]+)$/, (m) => spaceById(m[1]) ?? fail(404, 'NOT_FOUND', '空间不存在')],
  ['PUT', /^\/spaces\/([^/]+)$/, (m) => spaceWritten(m[1])],
  ['POST', /^\/spaces\/([^/]+)\/archive$/, (m) => ({ ...spaceWritten(m[1]), status: 'ARCHIVED' })],
  ['GET', /^\/spaces\/([^/]+)\/readiness$/, () => readiness],
  ['GET', /^\/spaces\/([^/]+)\/conflicts$/, () => conflicts],
  ['GET', /^\/spaces\/([^/]+)\/export$/, () => ({ content: '# mock 导出：卫星本体 TTL 草稿\n@prefix : <https://example.org/satellite#> .\n', format: 'TTL' })],
  ['POST', /^\/spaces\/([^/]+)\/imports\/preview$/, () => ({ id: 'imp-01', status: 'PREVIEW', targetDraftRev: 3, added: 2, updated: 1, conflicts: [], unsupported: [], errors: [] })],
  ['POST', /^\/imports\/([^/]+)\/apply$/, () => spaceWritten('sp-sat')],
  ['GET', /^\/spaces\/([^/]+)\/grant-users$/, () => grantUsers],
  ['GET', /^\/spaces\/([^/]+)\/grants$/, () => spaceGrants],
  ['POST', /^\/spaces\/([^/]+)\/grants\/new$/, (m) => spaceWritten(m[1])],
  ['PUT', /^\/spaces\/([^/]+)\/grants\/([^/]+)$/, (m) => spaceWritten(m[1])],
  ['GET', /^\/spaces\/([^/]+)\/guards$/, () => guards],
  ['PUT', /^\/spaces\/([^/]+)\/guards$/, (m) => spaceWritten(m[1])],
  ['GET', /^\/spaces\/([^/]+)\/properties$/, (m) => properties.filter((p) => p.spaceId === m[1])],
  ['GET', /^\/spaces\/([^/]+)\/candidates$/, (m) => candidates.filter((c) => c.spaceId === m[1])],
  ['GET', /^\/spaces\/([^/]+)\/executions$/, () => executions],
  ['GET', /^\/spaces\/([^/]+)\/executions\/([^/]+)\/trace$/, (_m, _b, q) => executionTrace(q.get('executionId') ?? 'exe-101')],
  ['GET', /^\/executions\/([^/]+)\/trace$/, (m) => executionTrace(m[1])],
  ['GET', /^\/spaces\/([^/]+)\/model$/, (m) => ({ classes: classes.filter((c) => c.spaceId === m[1]), properties: properties.filter((p) => p.spaceId === m[1]) })],
  ['POST', /^\/spaces\/([^/]+)\/model$/, (m) => spaceWritten(m[1])],

  // 模型资源集合与条目
  ['GET', /^\/classes$/, (_m, _b, q) => classes.filter((c) => !q.get('spaceId') || c.spaceId === q.get('spaceId'))],
  ['POST', /^\/classes$/, (_m, body) => spaceWritten(body?.spaceId ?? 'sp-sat')],
  ['PUT', /^\/classes\/([^/]+)$/, (_m, body) => spaceWritten(body?.spaceId ?? 'sp-sat')],
  ['GET', /^\/properties$/, (_m, _b, q) => properties.filter((p) => !q.get('spaceId') || p.spaceId === q.get('spaceId'))],
  ['POST', /^\/properties$/, (_m, body) => spaceWritten(body?.spaceId ?? 'sp-sat')],
  ['PUT', /^\/properties\/([^/]+)$/, (_m, body) => spaceWritten(body?.spaceId ?? 'sp-sat')],
  ['GET', /^\/properties\/([^/]+)\/impact$/, () => ({ operations: [{ id: 'op-orbit-query', code: 'orbit-query', name: '轨道要素查询' }], issues: [] })],
  ['GET', /^\/relations$/, (_m, _b, q) => relations.filter((r) => !q.get('spaceId') || r.spaceId === q.get('spaceId'))],
  ['POST', /^\/relations$/, (_m, body) => spaceWritten(body?.spaceId ?? 'sp-sat')],
  ['PUT', /^\/relations\/([^/]+)$/, (_m, body) => spaceWritten(body?.spaceId ?? 'sp-sat')],
  ['GET', /^\/constraints/, () => constraints],
  ['POST', /^\/constraints/, (_m, body) => spaceWritten(body?.spaceId ?? 'sp-sat')],
  ['GET', /^\/rules/, (_m, _b, q) => rules.filter((r) => !q.get('spaceId') || r.spaceId === q.get('spaceId'))],
  ['POST', /^\/rules/, (_m, body) => spaceWritten(body?.spaceId ?? 'sp-sat')],
  ['GET', /^\/rules\/([^/]+)\/references$/, () => ({ operations: [], testCases: [] })],
  ['GET', /^\/strategies/, () => strategies],
  ['POST', /^\/strategies/, (_m, body) => spaceWritten(body?.spaceId ?? 'sp-sat')],
  ['GET', /^\/codebooks/, (_m, _b, q) => codebooks.filter((c) => !q.get('spaceId') || c.spaceId === q.get('spaceId'))],
  ['POST', /^\/codebooks/, (_m, body) => spaceWritten(body?.spaceId ?? 'sp-sat')],

  // 操作与测试
  ['GET', /^\/operations$/, (_m, _b, q) => operations.filter((o) => !q.get('spaceId') || o.spaceId === q.get('spaceId'))],
  ['POST', /^\/operations$/, (_m, body) => spaceWritten(body?.spaceId ?? 'sp-sat')],
  ['PUT', /^\/operations\/([^/]+)$/, (_m, body) => spaceWritten(body?.spaceId ?? 'sp-sat')],
  ['GET', /^\/operations\/([^/]+)\/assistant$/, () => ({ operationId: 'op-orbit-query', inputSchema: operations[0].inputSchema, outputSchema: null, assistantTool: { name: 'orbit_query', description: '查询卫星轨道要素', guard_attrs: ['domain'], info_attrs: [], param_examples: [{ satelliteName: '高分一号' }], exec: { readonly: true, side_effect: false, idempotent: true, cancellable: false }, risk_level: 'READ_ONLY', error_semantics: [], return_spec: {} } })],
  ['PUT', /^\/operations\/([^/]+)\/assistant$/, (_m, body) => spaceWritten(body?.spaceId ?? 'sp-sat')],
  ['POST', /^\/operations\/([^/]+)\/data-query$/, (_m, body) => spaceWritten(body?.spaceId ?? 'sp-sat')],
  ['GET', /^\/test-cases/, (_m, _b, q) => testCases.filter((t) => !q.get('spaceId') || t.spaceId === q.get('spaceId'))],
  ['POST', /^\/test-cases/, (_m, body) => spaceWritten(body?.spaceId ?? 'sp-sat')],
  ['PUT', /^\/test-cases\/([^/]+)/, (_m, body) => spaceWritten(body?.spaceId ?? 'sp-sat')],

  // 候选与发布
  ['GET', /^\/candidates\/([^/]+)\/checks$/, () => ({ candidateSha256: CANDIDATE_SHA, eligible: true, checks: [{ kind: 'CONFLICT', conclusion: 'PASSED', reason: '无冲突', message: '冲突检查通过', details: {} }] })],
  ['GET', /^\/candidates\/([^/]+)\/tests$/, () => ({ candidateSha256: CANDIDATE_SHA, passed: true, results: [{ caseId: 'tc-orbit-01', name: '高分一号轨道查询', passed: true, executionId: 'exe-101', message: '1/1 通过' }] })],
  ['GET', /^\/candidates\/([^/]+)\/diff$/, () => ({ added: [{ kind: 'CLASS', key: 'cls-subsystem' }], removed: [], changed: [] })],
  ['GET', /^\/candidates\/([^/]+)\/publication$/, () => publication],
  ['POST', /^\/candidates\/([^/]+)\/publication/, () => publication],
  ['GET', /^\/candidates\/([^/]+)\/contracts$/, () => ({ candidateId: 'cand-001', candidateSha256: CANDIDATE_SHA, manifests: [{ opCode: 'orbit-query', kind: 'OBJECT_QUERY', input: operations[0].inputSchema }] })],
  ['GET', /^\/candidates\/([^/]+)\/context-pack$/, () => ({ candidateId: 'cand-001', candidateSha256: CANDIDATE_SHA, conceptCards: [], termDictionary: [{ term: '太阳同步轨道', definition: '近地遥感常用轨道。' }], relationSummaries: [], exampleQa: [] })],
  ['GET', /^\/candidates\/([^/]+)\/knowledge-search/, () => ({ candidateId: 'cand-001', candidateSha256: CANDIDATE_SHA, hits: [{ entryId: 'kb-01', term: '太阳同步轨道', definition: knowledge[0].definition, score: 0.92, matchedAlias: 'SSO' }] })],
  ['GET', /^\/candidates\/([^/]+)\/trial$/, () => executions[0]],
  ['POST', /^\/candidates\/([^/]+)\/trial$/, () => executions[0]],
  ['GET', /^\/candidates\/([^/]+)\/waivers$/, () => []],
  ['GET', /^\/candidates\/([^/]+)\/operations$/, () => operations],
  ['POST', /^\/candidates\/([^/]+)\/operations\/query$/, () => ({ columns: ['satellite', 'altitudeKm'], rows: executionRows })],
  ['GET', /^\/releases$/, (_m, _b, q) => releases.filter((r) => !q.get('spaceId') || r.spaceId === q.get('spaceId'))],
  ['GET', /^\/releases\/proposal-comments$/, (_m, _b, q) => [
    { id: 'pc-01', candidateId: q.get('candidateId') ?? 'cand-001', actorName: '本体编辑员', content: '轨道高度单位确认使用千米，与码本一致。', createdAt: '2026-10-24T15:00:00Z' },
    { id: 'pc-02', candidateId: q.get('candidateId') ?? 'cand-001', actorName: '演示管理员', content: '门禁报告已复核，同意进入评审。', createdAt: '2026-10-25T09:00:00Z' },
  ]],
  ['POST', /^\/releases\/proposal-comments$/, (_m, body) => ({ id: 'pc-new', candidateId: body?.candidateId ?? 'cand-001', actorName: '演示管理员', content: body?.content ?? '', createdAt: now })],
  ['GET', /^\/releases\/diff$/, () => ({ fromReleaseId: 'rel-002', toReleaseId: 'rel-003', added: [{ kind: 'CLASS', key: 'cls-subsystem', after: { name: 'Subsystem' } }], removed: [], changed: [{ kind: 'OPERATION', key: 'op-orbit-query', before: { timeoutMs: 3000 }, after: { timeoutMs: 5000 } }] })],
  ['POST', /^\/releases\/([^/]+)\/restore-draft$/, () => spaceWritten('sp-sat')],
  ['GET', /^\/releases\/([^/]+)\/entities$/, () => releaseEntities],
  ['GET', /^\/releases\/([^/]+)\/endpoints$/, () => endpoints],
  ['GET', /^\/releases\/([^/]+)\/knowledge-search/, () => ({ candidateId: 'cand-001', candidateSha256: CANDIDATE_SHA, hits: [] })],
  ['GET', /^\/releases\/([^/]+)$/, (m) => ({ release: releases.find((r) => r.id === m[1]) ?? releases[0], artifacts: publication.artifacts, endpoints, checks: publication.checks })],
  ['GET', /^\/endpoints\/([^/]+)\/grants$/, () => endpointGrants],
  ['POST', /^\/endpoints\/([^/]+)\/grants$/, () => endpointGrants],
  ['DELETE', /^\/endpoints\/([^/]+)\/grants\/([^/]+)$/, () => endpointGrants],
  ['GET', /^\/endpoints\/([^/]+)\/role-grants$/, () => []],
  ['POST', /^\/endpoints\/([^/]+)\/role-grants$/, () => []],
  ['DELETE', /^\/endpoints\/([^/]+)\/role-grants\/([^/]+)$/, () => []],
  ['POST', /^\/endpoints\/([^/]+)\/status$/, () => endpoints[0]],
  ['GET', /^\/api-credentials/, (_m, _b, q) => apiCredentials.filter((c) => !q.get('spaceId') || c.spaceId === q.get('spaceId'))],
  ['POST', /^\/api-credentials$/, () => apiCredentials[0]],
  ['POST', /^\/api-credentials\/([^/]+)\/revoke$/, () => apiCredentials[0]],
  ['GET', /^\/events/, (_m, _b, q) => releaseEvents.filter((e) => !q.get('spaceId') || e.spaceId === q.get('spaceId'))],
  ['POST', /^\/events\/([^/]+)\/replay$/, () => releaseEvents[0]],

  // 数据接入
  ['GET', /^\/datasources\/capabilities/, () => sourceCapabilities],
  ['GET', /^\/datasources$/, (_m, _b, q) => datasources.filter((d) => !q.get('spaceId') || d.spaceId === q.get('spaceId'))],
  ['POST', /^\/datasources$/, (_m, body) => spaceWritten(body?.spaceId ?? 'sp-sat')],
  ['PUT', /^\/datasources\/([^/]+)$/, (_m, body) => spaceWritten(body?.spaceId ?? 'sp-sat')],
  ['POST', /^\/datasources\/([^/]+)\/probe$/, () => ({ ok: true, message: '连接成功', latencyMs: 23 })],
  ['POST', /^\/datasources\/([^/]+)\/sync$/, () => ({ space: spaceWritten('sp-sat'), snapshot: metadataSnapshots[0] })],
  ['GET', /^\/datasources\/([^/]+)\/snapshots\/([^/]+)$/, () => metadataSnapshotDetail],
  ['GET', /^\/datasources\/([^/]+)\/snapshots/, () => metadataSnapshots],
  ['GET', /^\/mappings$/, () => mappings],
  ['POST', /^\/mappings\/([^/]+)$/, (_m, body) => spaceWritten(body?.spaceId ?? 'sp-sat')],
  ['PUT', /^\/mappings\/([^/]+)\/([^/]+)$/, (_m, body) => spaceWritten(body?.spaceId ?? 'sp-sat')],
  ['DELETE', /^\/mappings\/([^/]+)\/([^/]+)$/, (_m, body) => spaceWritten(body?.spaceId ?? 'sp-sat')],
  ['GET', /^\/mappings\/validate/, () => mappingValidation],
  ['GET', /^\/mappings\/properties$/, () => catalogProperties],
  ['GET', /^\/mappings\/impact/, () => ({ operations: mappingValidation.affectedOperations, issues: [] })],
  ['POST', /^\/mappings\/properties$/, (_m, body) => spaceWritten(body?.spaceId ?? 'sp-sat')],
  ['GET', /^\/data-catalog/, () => dataCatalog],
  ['GET', /^\/data-files/, () => managedFiles],
  ['POST', /^\/data-files/, () => ({ space: spaceWritten('sp-sat'), file: managedFiles[0] })],
  ['GET', /^\/suggestions/, () => suggestions],
  ['POST', /^\/suggestions\/extract$/, () => ({ space: spaceWritten('sp-sat'), batchId: 'batch-01', count: 0 })],
  ['POST', /^\/suggestions\/decide$/, (_m, body) => spaceWritten(body?.spaceId ?? 'sp-sat')],

  // 知识
  ['GET', /^\/knowledge$/, (_m, _b, q) => knowledge.filter((k) => !q.get('spaceId') || k.spaceId === q.get('spaceId'))],
  ['POST', /^\/knowledge$/, (_m, body) => spaceWritten(body?.spaceId ?? 'sp-sat')],
  ['PUT', /^\/knowledge\/([^/]+)$/, (_m, body) => spaceWritten(body?.spaceId ?? 'sp-sat')],

  // 全局视图与审计
  ['GET', /^\/global\/trace$/, () => globalTrace],
  ['GET', /^\/global\/data$/, () => globalData],
  ['GET', /^\/global\/delivery$/, () => globalDelivery],
  ['GET', /^\/global\/services$/, () => globalServices],
  ['GET', /^\/audit/, () => ({ items: globalTrace.audit, nextBeforeId: null })],
  ['GET', /^\/trace/, () => globalTrace],

  // 系统管理
  ['GET', /^\/system\/users$/, () => systemUsers],
  ['POST', /^\/system\/users$/, () => systemUsers[0]],
  ['PUT', /^\/system\/users\/([^/]+)\/password$/, () => null],
  ['PUT', /^\/system\/users\/([^/]+)$/, (_m, body) => ({ ...systemUsers[0], ...(body ?? {}) })],
  ['DELETE', /^\/system\/users\/([^/]+)$/, () => null],
  ['GET', /^\/system\/roles$/, () => systemRoles],
  ['POST', /^\/system\/roles$/, () => systemRoles[0]],
  ['PUT', /^\/system\/roles\/([^/]+)$/, (_m, body) => ({ ...systemRoles[0], ...(body ?? {}) })],
  ['GET', /^\/system\/permissions$/, () => permissionDefinitions],
  ['GET', /^\/system\/audit/, (_m, _b, q) => ({ ...iamAuditPage, page: Number(q.get('page') ?? 1) })],
]

/**
 * mock 传输器：签名与原生 fetch 一致，直接注入 ApiClient 第三参数。
 * 未匹配的读请求返回空集合、写请求返回当前空间草稿修订 +1——
 * 保证演示模式下任何页面可进、任何按钮可点，不出现未定义崩溃。
 */
export const mockFetcher: typeof fetch = async (input, init) => {
  await latency()
  const url = typeof input === 'string' ? input : (input as Request).url
  const method = (init?.method ?? 'GET').toUpperCase()
  const parsed = new URL(url, 'http://mock.local')
  const path = parsed.pathname.replace(/^\/onto/, '') || '/'
  const body = init?.body && typeof init.body === 'string' ? JSON.parse(init.body) : {}
  const query = parsed.searchParams
  if (method === 'GET' && path === '/') return ok(spaces)
  for (const [routeMethod, pattern, handler] of routes) {
    if (routeMethod !== method) continue
    const match = path.match(pattern)
    if (!match) continue
    const result = handler(match, body, query)
    return result instanceof Response ? result : ok(result)
  }
  // 兜底：读返回空集合；写返回草稿修订推进，维持页面写回链路。
  if (method === 'GET') return ok([])
  const spaceId = String(body?.spaceId ?? query.get('spaceId') ?? 'sp-sat')
  return ok(spaceWritten(spaceId))
}
