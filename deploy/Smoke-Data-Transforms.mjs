/**
 * 经真实 HTTP 验证映射转换在授权后、过滤与结果上限前执行。
 * 使用新建空间和受管 CSV，不修改其他验收空间或底层业务表；原始秘密仅在内存使用。
 * 同时验证冻结码表隔离、映射引用阻删和失败执行无残留结果。
 */
import assert from 'node:assert/strict'
import { readFile, writeFile } from 'node:fs/promises'

const root = new URL('../', import.meta.url)
const config = JSON.parse((await readFile(new URL('.runtime/local-env.json', root), 'utf8')).replace(/^\uFEFF/, ''))
const base = process.env.ONTO_API_BASE ?? 'http://127.0.0.1:18182/onto'
let token, space
const checks = [], candidates = [], resources = []
async function request(path, method = 'GET', body, status = 200) {
  const multipart = body instanceof FormData
  const response = await fetch(base + path, {
    method,
    headers: {
      ...(body !== undefined && !multipart ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : multipart ? body : JSON.stringify(body),
    signal: AbortSignal.timeout(60000),
  })
  const envelope = await response.json()
  assert.equal(response.status, status, `${method} ${path}: ${envelope.code} ${envelope.message}`)
  if (status === 200) assert.equal(envelope.code, 'OK')
  return envelope
}
const data = async (...args) => (await request(...args)).data
async function mutate(path, body, method = 'POST') {
  space = await data(path, method, { spaceId: space.id, expectedDraftRev: space.draftRev, ...body })
}
async function check(name, action) {
  await action()
  checks.push({ name, status: 'PASSED' })
}

try {
  token = (await data('/auth/login', 'POST', {
    username: config.ONTO_BOOTSTRAP_USERNAME, password: config.ONTO_BOOTSTRAP_PASSWORD,
  })).token
  const code = `transform-${Date.now().toString(36)}`
  space = await data('/spaces', 'POST', {
    code, name: '转换过滤真实接口验收', namespaceUri: `https://example.org/${code}/`, domain: '转换验证',
  })
  await mutate('/classes', { name: '任务', description: '转换后的任务', parentId: null, deprecated: false })
  const task = (await data(`/classes?spaceId=${space.id}`))[0]
  for (const [name, rangeType] of [['任务编号', 'STRING'], ['数值', 'INTEGER'], ['启用', 'BOOLEAN'], ['分组', 'STRING']]) {
    await mutate('/properties', { name, rangeType, domainClassId: task.id, required: true, description: name })
  }
  const properties = await data(`/spaces/${space.id}/properties`)
  const prop = name => properties.find(x => x.name === name).id
  const codebookBody = {
    code: 'buckets', name: '冻结分组', kind: 'VALUE',
    entries: [{ code: 'low', label: 'LOW' }, { code: 'high', label: 'HIGH' }],
  }
  await mutate('/codebooks', codebookBody)
  const codebook = (await data(`/codebooks?spaceId=${space.id}`))[0]
  const owner = (await data(`/spaces/${space.id}/grants`)).find(x => x.username === config.ONTO_BOOTSTRAP_USERNAME)
  await mutate(`/spaces/${space.id}/grants/${owner.userId}`, {
    role: 'OWNER', dataScope: { attr: 'region', in: ['A'] }, attributes: { region: 'A' },
  }, 'PUT')
  const input = { threshold: 2, enabled: true, bucket: 'HIGH' }
  const goodRows = ['T-B,B,bad,invalid,missing', 'T-NULL,,bad,invalid,missing', 'T-1,A,01,false,low', 'T-2,A,02,TRUE,high']
  // 已授权坏行位于命中行之后或其他谓词不匹配的行，都不能因 LIMIT / 谓词短路隐藏错误。
  for (const [suffix, rows, expectedFailure] of [
    ['valid', goodRows, false],
    ['late_bad', [...goodRows, 'T-3,A,bad,TRUE,high'], true],
    ['filtered_bad', ['T-0,A,bad,false,low', ...goodRows], true],
  ]) {
    const form = new FormData()
    form.append('file', new Blob([['task_id,region,raw_number,raw_bool,raw_code', ...rows].join('\n') + '\n'], { type: 'text/csv' }), `${suffix}.csv`)
    const uploaded = await data(`/data-files?spaceId=${space.id}&expectedDraftRev=${space.draftRev}`, 'POST', form)
    space = uploaded.space
    await mutate('/datasources', {
      code: suffix, name: `转换-${suffix}`, kind: 'FILE',
      connection: { dialect: 'csv', fileId: uploaded.file.id }, accessScope: [uploaded.file.resource],
    })
    const source = (await data(`/datasources?spaceId=${space.id}`)).find(x => x.code === suffix)
    const synced = await data(`/datasources/${source.id}/sync`, 'POST', { spaceId: space.id, expectedDraftRev: space.draftRev })
    space = synced.space
    await mutate('/mappings/objects', {
      datasourceId: source.id, sourceResource: uploaded.file.resource, targetClassId: task.id,
      objectKeyExpr: 'task:${task_id}', filter: null, metaSnapshotId: synced.snapshot.id,
    })
    const object = (await data(`/mappings?spaceId=${space.id}`)).objects.find(x => x.datasourceId === source.id)
    for (const [sourceField, name, kind] of [
      ['task_id', '任务编号', 'IDENTITY'], ['raw_number', '数值', 'TO_NUMBER'],
      ['raw_bool', '启用', 'TO_BOOLEAN'], ['raw_code', '分组', 'CODEBOOK'],
    ]) {
      await mutate('/mappings/properties', {
        objectMappingId: object.id, sourceField, targetPropertyId: prop(name), required: true,
        transform: { kind, ...(kind === 'CODEBOOK' ? { codebookId: codebook.id } : {}) },
      })
    }
    const opCode = `convert_${suffix}`
    await mutate('/operations', {
      code: opCode, name: `转换-${suffix}`,
      queryTemplate: 'PREFIX owl:<http://www.w3.org/2002/07/owl#> SELECT ?s WHERE { ?s a owl:Class } ORDER BY ?s',
      maxRows: 1, timeoutMs: 5000,
    })
    const operation = (await data(`/operations?spaceId=${space.id}`)).find(x => x.code === opCode)
    await mutate(`/operations/${operation.id}/data-query`, {
      dataQuery: {
        mode: 'OBJECT', objectMappingId: object.id, propertyIds: properties.map(x => x.id),
        // 布尔过滤排在数值转换前，以覆盖不匹配谓词不得跳过后续转换校验的反例。
        filters: [
          { propertyId: prop('启用'), operator: 'EQ', inputParam: 'enabled' },
          { propertyId: prop('数值'), operator: 'GE', inputParam: 'threshold' },
          { propertyId: prop('分组'), operator: 'EQ', inputParam: 'bucket' },
        ],
      },
    }, 'PUT')
    resources.push(uploaded.file.resource)
    await mutate(`/spaces/${space.id}/guards`, {
      guardCfg: { region: { type: 'STRING', filter_bindings: resources.map(table => ({ table, column: 'region', operator: 'IN' })) } },
    }, 'PUT')
    await mutate(`/operations/${operation.id}/assistant`, {
      inputSchema: {
        type: 'object', required: ['threshold', 'enabled', 'bucket'], additionalProperties: false,
        properties: { threshold: { type: 'integer' }, enabled: { type: 'boolean' }, bucket: { type: 'string' } },
      },
      outputSchema: { type: 'object', required: ['rows'], properties: { rows: { type: 'array', items: { type: 'object' } } } },
      assistantTool: {
        name: '转换验收', description: '授权后使用冻结转换做业务过滤', guard_attrs: ['region'], info_attrs: [], param_examples: [input],
        exec: { readonly: true, side_effect: false, idempotent: true, cancellable: true },
        risk_level: 'READ_ONLY', error_semantics: [{ match: 'HTTP 403', semantic: 'NO_PERMISSION' }],
        return_spec: { modelSummary: { mode: 'TEMPLATE', template: '转换结果' }, structuredPath: '$.data', fileRefPaths: [], cardTemplate: '转换结果' },
      },
    }, 'PUT')
    const candidate = await data(`/spaces/${space.id}/candidates`, 'POST', { expectedDraftRev: space.draftRev })
    const trialBody = { operationId: operation.id, input }
    await check(`${suffix}转换授权与LIMIT语义`, async () => {
      const result = await data(`/candidates/${candidate.id}/trial`, 'POST', trialBody)
      if (expectedFailure) {
        assert.equal(result.status, 'FAILED')
        assert.equal(result.errorCode, 'TRANSFORM_FAILED')
        assert.deepEqual(result.rows, [])
      } else {
        assert.equal(result.status, 'SUCCEEDED', result.errorCode)
        assert.equal(result.rows.length, 1)
        assert.equal(result.rows[0][prop('任务编号')], 'T-2')
        assert.equal(result.rows[0][prop('数值')], 2)
        assert.equal(result.rows[0][prop('启用')], true)
        assert.equal(result.rows[0][prop('分组')], 'HIGH')
        const response = await request(`/internal/candidates/${candidate.id}/ops/${opCode}`, 'POST', input)
        assert.deepEqual(response.data.rows, result.rows)
        assert.equal(response.meta.contentDigest, candidate.sha256)
      }
      candidates.push({ candidateId: candidate.id, candidateSha256: candidate.sha256, operationId: operation.id, opCode, expectedFailure, executionId: result.id })
    })
  }
  await check('映射引用中的码表删除拒绝', async () => {
    const result = await request(`/codebooks/${codebook.id}?spaceId=${space.id}&expectedDraftRev=${space.draftRev}`, 'DELETE', undefined, 409)
    assert.equal(result.code, 'CODEBOOK_IN_USE')
  })
  await check('更新草稿码表后旧候选仍使用冻结分组', async () => {
    await mutate(`/codebooks/${codebook.id}`, {
      ...codebookBody, entries: [{ code: 'low', label: 'LOW' }, { code: 'high', label: 'CHANGED' }],
    }, 'PUT')
    const original = candidates[0]
    const result = await data(`/candidates/${original.candidateId}/trial`, 'POST', { operationId: original.operationId, input })
    assert.equal(result.status, 'SUCCEEDED', result.errorCode)
    assert.equal(result.rows[0][prop('分组')], 'HIGH')
    assert.equal(result.candidateSha256, original.candidateSha256)
  })
  await writeFile(new URL('docs/evidence/continuation/p3-transform-http-smoke.json', root), JSON.stringify({
    checkedAt: new Date().toISOString(), spaceId: space.id, candidates, checks,
  }, null, 2) + '\n')
  console.log(`${checks.length} 项转换映射真实 HTTP 验收通过。`)
} finally {
  if (token) await request('/auth/logout', 'POST').catch(() => {})
}
