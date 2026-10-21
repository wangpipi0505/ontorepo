// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import type { Component } from 'vue'
import ElementPlus from 'element-plus'
import { setAuthorizedSession } from '../../test/authorizedSession'
import { ApiError, type Space } from '@onto/api-client'
import { useSession } from '../../stores/session'
import { useWorkspace } from '../../stores/workspace'

const modules = import.meta.glob<{ default: Component }>('./*.vue')
const wrappers: VueWrapper[] = []
const candidate = { id: 'c', sha256: 'sha-c', draftRev: 5, status: 'REVIEWING' }
const kinds = ['CONFLICT', 'CONSISTENCY', 'CONTRACT', 'TEST', 'REVIEW']
const publication = {
  candidateId: 'c',
  candidateSha256: 'sha-c',
  draftRev: 5,
  status: 'REVIEWING',
  preReleaseId: 'r',
  preVersionNo: 1,
  waivers: [],
  releaseId: null,
  review: { actorId: 'owner', reason: '已复核', reviewedAt: '2026-10-20T00:00:00Z' },
  checks: kinds.map((kind) => ({
    kind,
    conclusion: 'PASSED',
    reason: '',
    candidateSha256: 'sha-c',
    checkedAt: null,
    report: {},
  })),
  artifacts: ['BUNDLE', 'MANIFEST', 'CONTEXT_PACK', 'KNOWLEDGE_INDEX'].map((kind) => ({
    kind,
    sha256: kind === 'BUNDLE' ? 'sha-c' : 'sha',
    size: 10,
    ready: true,
  })),
}
const release = {
  id: 'r',
  spaceId: 's',
  versionNo: 1,
  candidateId: 'c',
  status: 'READY',
  bundleSha256: 'sha-c',
  manifestSha256: 'sha-m',
  publishedAt: '',
  publishedBy: 'owner',
}
const space = {
  id: 's',
  code: 'demo',
  name: '测试空间',
  role: 'OWNER',
  status: 'ACTIVE',
  draftRev: 5,
} as Space
function button(wrapper: VueWrapper, text: string) {
  const found = wrapper.findAll('button').find((item) => item.text() === text)
  expect(found, text).toBeDefined()
  return found!
}
async function page(name: string, props: Record<string, unknown> = {}) {
  expect(modules[`./${name}.vue`], `${name} 应提供独立业务组件`).toBeDefined()
  const component = (await modules[`./${name}.vue`]()).default
  const wrapper = mount(component, {
    props,
    global: { plugins: [ElementPlus], stubs: { teleport: true } },
  })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
beforeEach(() => {
  setAuthorizedSession()
  useWorkspace().space = { ...space }
  vi.spyOn(useSession().api, 'get').mockImplementation(async <T>(path: string): Promise<T> => {
    if (path === '/candidates/c/publication') return structuredClone(publication) as T
    if (path === '/candidates/c/diff') return { added: [], removed: [], changed: [] } as T
    if (path === '/spaces/s/grant-users')
      return [{ userId: 'u', username: 'user', displayName: '用户' }] as T
    if (path === '/api-credentials?spaceId=s') return [] as T
    if (path === '/spaces/s') return { ...space } as T
    throw new Error(`未知读取：${path}`)
  })
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.restoreAllMocks()
})

describe('P4 发布与消费关键交互', () => {
  it('FREEZING制品仍在归档时只能读取，不能提前运行门禁或取消', async () => {
    vi.mocked(useSession().api.get).mockResolvedValueOnce({
      ...publication,
      status: 'FREEZING',
      checks: [],
      artifacts: [],
    })
    const post = vi.spyOn(useSession().api, 'post')
    const wrapper = await page('PublicationPanel', { candidate })
    await wrapper.get('[data-testid="publication-reason"]').setValue('尚在冻结')
    for (const text of [
      '运行三项机器检查',
      '运行冻结验收用例',
      '确认人工复核',
      '取消此候选',
      '准备发布制品',
      '提交正式发布',
    ])
      expect(button(wrapper, text).attributes('disabled')).toBeDefined()
    expect(post).not.toHaveBeenCalled()
  })
  it('版本恢复409后显式刷新草稿，再重新确认才能使用新修订恢复', async () => {
    let serverRev = 5
    const get = vi
      .mocked(useSession().api.get)
      .mockImplementation(async <T>(path: string): Promise<T> => {
        if (path === '/spaces/s') return { ...space, draftRev: serverRev } as T
        if (path === '/releases?spaceId=s') return [release] as T
        if (path === '/releases/r')
          return { release, artifacts: [], endpoints: [], checks: [] } as T
        if (path === '/releases/r/entities') return [] as T
        throw new Error(`未知读取：${path}`)
      })
    const post = vi
      .spyOn(useSession().api, 'post')
      .mockRejectedValueOnce(new ApiError('DRAFT_REV_CONFLICT', '草稿已变化', 409))
      .mockResolvedValueOnce({ ...space, draftRev: 7 })
    const wrapper = await page('ReleasesView')
    await button(wrapper, '恢复到草稿').trigger('click')
    await wrapper.get('[data-testid="restore-reason"]').setValue('旧确认保留')
    await wrapper.get('[data-testid="restore-confirm"]').setValue(true)
    serverRev = 6
    await button(wrapper, '确认覆盖并恢复草稿').trigger('click')
    await flushPromises()
    expect(post).toHaveBeenLastCalledWith('/releases/r/restore-draft', {
      expectedDraftRev: 5,
      reason: '旧确认保留',
    })
    expect(useWorkspace().stale).toBe(true)
    expect(button(wrapper, '确认覆盖并恢复草稿').attributes('disabled')).toBeDefined()
    expect(button(wrapper, '刷新版本').attributes('disabled')).toBeDefined()
    expect(
      (wrapper.get('[data-testid="restore-reason"]').element as HTMLTextAreaElement).value,
    ).toBe('旧确认保留')

    await button(wrapper, '关闭恢复面板').trigger('click')
    await button(wrapper, '刷新版本').trigger('click')
    await flushPromises()
    expect(get).toHaveBeenCalledWith('/spaces/s')
    expect(useWorkspace().space?.draftRev).toBe(6)
    expect(useWorkspace().stale).toBe(false)
    expect(wrapper.find('[data-testid="restore-confirm"]').exists()).toBe(false)
    expect(post).toHaveBeenCalledTimes(1)
    await button(wrapper, '恢复到草稿').trigger('click')
    expect(
      (wrapper.get('[data-testid="restore-confirm"]').element as HTMLInputElement).checked,
    ).toBe(false)
    expect(button(wrapper, '确认覆盖并恢复草稿').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-testid="restore-reason"]').setValue('重新核对 r6 后确认')
    await wrapper.get('[data-testid="restore-confirm"]').setValue(true)
    serverRev = 7
    await button(wrapper, '确认覆盖并恢复草稿').trigger('click')
    await flushPromises()
    expect(post).toHaveBeenLastCalledWith('/releases/r/restore-draft', {
      expectedDraftRev: 6,
      reason: '重新核对 r6 后确认',
    })
    expect(post).toHaveBeenCalledTimes(2)
  })
  it('版本刷新中的空间读取失败保留stale，不恢复管理权限', async () => {
    let failSpace = false
    vi.mocked(useSession().api.get).mockImplementation(async <T>(path: string): Promise<T> => {
      if (path === '/spaces/s') {
        if (failSpace) throw new ApiError('NETWORK_ERROR', '空间修订读取失败', 0)
        return { ...space } as T
      }
      if (path === '/releases?spaceId=s') return [release] as T
      if (path === '/releases/r') return { release, artifacts: [], endpoints: [], checks: [] } as T
      if (path === '/releases/r/entities') return [] as T
      throw new Error(`未知读取：${path}`)
    })
    const wrapper = await page('ReleasesView')
    useWorkspace().markStale(useWorkspace().captureContext())
    failSpace = true
    await button(wrapper, '刷新版本').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('空间修订读取失败')
    expect(useWorkspace().stale).toBe(true)
    expect(useWorkspace().space?.draftRev).toBe(5)
    expect(wrapper.findAll('button').some((item) => item.text() === '恢复到草稿')).toBe(false)
  })
  it('五门禁通过仍须 prepare；未 READY 时不能 commit', async () => {
    const post = vi
      .spyOn(useSession().api, 'post')
      .mockResolvedValue({ ...publication, status: 'READY_TO_COMMIT' })
    const wrapper = await page('PublicationPanel', { candidate })
    expect(button(wrapper, '提交正式发布').attributes('disabled')).toBeDefined()
    await button(wrapper, '准备发布制品').trigger('click')
    await flushPromises()
    expect(post).toHaveBeenCalledWith('/candidates/c/prepare', {
      expectedDraftRev: 5,
      candidateSha256: 'sha-c',
    })
    expect(post).toHaveBeenCalledTimes(1)
    expect(button(wrapper, '提交正式发布').attributes('disabled')).toBeUndefined()
  })
  it('守卫错误没有豁免入口，状态字符串不能代替五门禁', async () => {
    vi.mocked(useSession().api.get).mockResolvedValueOnce({
      ...publication,
      status: 'READY_TO_COMMIT',
      checks: publication.checks.map((check) =>
        check.kind === 'CONTRACT'
          ? { ...check, conclusion: 'FAILED', reason: 'MISSING_GUARD' }
          : check,
      ),
    })
    const wrapper = await page('PublicationPanel', { candidate })
    expect(button(wrapper, '准备发布制品').attributes('disabled')).toBeDefined()
    expect(button(wrapper, '提交正式发布').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).not.toContain('确认超范围豁免')
  })
  it('409 保留理由并锁定原候选修订，不借新修订重试', async () => {
    const post = vi
      .spyOn(useSession().api, 'post')
      .mockRejectedValue(new ApiError('DRAFT_REV_CONFLICT', '草稿已变化', 409))
    const wrapper = await page('PublicationPanel', { candidate })
    await wrapper.get('[data-testid="publication-reason"]').setValue('确认冻结内容')
    await button(wrapper, '准备发布制品').trigger('click')
    await flushPromises()
    useWorkspace().space = { ...space, draftRev: 6 }
    await flushPromises()
    expect(
      (wrapper.get('[data-testid="publication-reason"]').element as HTMLTextAreaElement).value,
    ).toBe('确认冻结内容')
    expect(button(wrapper, '准备发布制品').attributes('disabled')).toBeDefined()
    expect(post).toHaveBeenCalledTimes(1)
  })
  it('旧候选提示重新冻结且不能发布', async () => {
    vi.mocked(useSession().api.get).mockResolvedValueOnce({
      ...publication,
      preReleaseId: null,
      preVersionNo: null,
    })
    const wrapper = await page('PublicationPanel', { candidate })
    expect(wrapper.text()).toContain('重新冻结')
    expect(button(wrapper, '准备发布制品').attributes('disabled')).toBeDefined()
  })
  it('恢复要求明确覆盖确认，且恢复请求绑定打开时修订', async () => {
    const post = vi.spyOn(useSession().api, 'post').mockResolvedValue({ ...space, draftRev: 6 })
    const wrapper = await page('RestoreDraftPanel', { release })
    await wrapper.get('[data-testid="restore-reason"]').setValue('恢复到稳定版本')
    expect(button(wrapper, '确认覆盖并恢复草稿').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-testid="restore-confirm"]').setValue(true)
    await button(wrapper, '确认覆盖并恢复草稿').trigger('click')
    await flushPromises()
    expect(post).toHaveBeenCalledWith('/releases/r/restore-draft', {
      expectedDraftRev: 5,
      reason: '恢复到稳定版本',
    })
    expect(post).toHaveBeenCalledTimes(1)
  })
  it('API key 仅创建响应显示，关闭后清除明文', async () => {
    vi.spyOn(useSession().api, 'post').mockResolvedValue({
      credential: {
        id: 'k',
        spaceId: 's',
        principalId: 'u',
        name: '机器人',
        status: 'ACTIVE',
        createdAt: '',
        expiresAt: null,
      },
      key: 'secret-once',
    })
    const wrapper = await page('CredentialsPanel')
    await wrapper.get('[data-testid="credential-principal"]').setValue('u')
    await wrapper.get('[data-testid="credential-name"]').setValue('机器人')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('secret-once')
    await button(wrapper, '已保存，关闭明文').trigger('click')
    expect(wrapper.text()).not.toContain('secret-once')
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
  })
  it('真实 trace 图只画响应节点与边，未知引用不生成虚构节点', async () => {
    const wrapper = await page('TraceGraph', {
      graph: {
        nodes: [
          { id: 'a', label: '请求真实 A', kind: 'REQUEST' },
          { id: 'b', label: '证据真实 B', kind: 'EVIDENCE' },
        ],
        edges: [
          { id: 'e', from: 'a', to: 'b', label: '查询' },
          { id: 'broken', from: 'a', to: 'missing', label: '历史缺失' },
        ],
      },
    })
    expect(wrapper.findAll('svg [data-node-id]')).toHaveLength(2)
    expect(wrapper.findAll('svg [data-edge-id]')).toHaveLength(1)
    expect(wrapper.text()).toContain('历史缺失')
    expect(wrapper.text()).not.toContain('missing节点')
  })
  it('DEAD 重放保留原 eventId，其他状态不给重放入口', async () => {
    const event = {
      id: 42,
      releaseId: 'r',
      spaceId: 's',
      type: 'RELEASED',
      boundStatus: 'READY',
      releaseStatus: 'DEPRECATED',
      status: 'DEAD',
      retryCount: 5,
      nextRetryAt: null,
      lastError: '接收失败',
      createdAt: '',
    }
    vi.mocked(useSession().api.get).mockImplementation(
      async <T>(path: string): Promise<T> =>
        (path === '/events?spaceId=s' ? [event, { ...event, id: 43, status: 'SENT' }] : []) as T,
    )
    const post = vi
      .spyOn(useSession().api, 'post')
      .mockResolvedValue({ ...event, status: 'PENDING' })
    const wrapper = await page('EventsView')
    expect(wrapper.findAll('button').filter((item) => item.text() === '重放此事件')).toHaveLength(1)
    expect(wrapper.text()).toContain('DEPRECATED')
    await wrapper.get('[data-testid="replay-reason-42"]').setValue('接收端已恢复')
    await button(wrapper, '重放此事件').trigger('click')
    await flushPromises()
    expect(post).toHaveBeenCalledWith('/events/42/replay', { reason: '接收端已恢复' })
    expect(wrapper.text()).toContain('PENDING')
  })
  it('审计下一页追加且保留前页，使用服务端游标', async () => {
    const item = {
      id: 20,
      spaceId: 's',
      actorId: 'u',
      action: 'RELEASE_COMMIT',
      subjectId: 'r',
      detail: {},
      createdAt: '',
    }
    const get = vi
      .mocked(useSession().api.get)
      .mockResolvedValueOnce({ items: [item], nextBeforeId: 20 })
      .mockResolvedValueOnce({
        items: [{ ...item, id: 19, action: 'CANDIDATE_REVIEW' }],
        nextBeforeId: null,
      })
    const wrapper = await page('AuditView')
    await button(wrapper, '加载更早记录').trigger('click')
    await flushPromises()
    expect(get).toHaveBeenLastCalledWith('/audit?spaceId=s&limit=50&beforeId=20')
    expect(wrapper.text()).toContain('RELEASE_COMMIT')
    expect(wrapper.text()).toContain('CANDIDATE_REVIEW')
  })
  it('正式调用只提交业务参数，拒绝候选preview元数据作为发布证据', async () => {
    const call = vi.spyOn(useSession().api, 'requestEnvelope').mockResolvedValue({
      code: 'OK',
      data: { count: 3 },
      meta: {
        spaceCode: 'demo',
        releaseId: 'r',
        version: 'v1',
        contentDigest: 'sha-c',
        preview: true,
      },
    })
    const wrapper = await page('ServiceCallPanel', {
      release,
      endpoints: [
        {
          id: 'ep',
          releaseId: 'r',
          opCode: 'lookup',
          route: '/onto/s/demo/ops/lookup?releaseId=r',
          status: 'ACTIVE',
          execAttrs: {},
        },
      ],
    })
    await wrapper.get('[data-testid="service-operation"]').setValue('lookup')
    await button(wrapper, '添加业务参数').trigger('click')
    await wrapper.get('[data-testid="parameter-name-0"]').setValue('limit')
    await wrapper.get('[data-testid="parameter-type-0"]').setValue('number')
    await wrapper.get('[data-testid="parameter-value-0"]').setValue('3')
    await button(wrapper, '调用正式服务').trigger('click')
    await flushPromises()
    expect(call).toHaveBeenCalledWith('/internal/releases/r/ops/lookup', 'POST', { limit: 3 })
    expect(wrapper.text()).toContain('正式版本证据')
    expect(wrapper.find('[data-testid="service-result"]').exists()).toBe(false)
  })
  it('真实零验收用例阻断准备，不将空集合当通过', async () => {
    vi.spyOn(useSession().api, 'post').mockResolvedValue({
      candidateSha256: 'sha-c',
      passed: true,
      results: [],
    })
    const wrapper = await page('PublicationPanel', { candidate })
    await button(wrapper, '运行冻结验收用例').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('缺少验收用例')
    expect(button(wrapper, '准备发布制品').attributes('disabled')).toBeDefined()
  })
  it('冻结用例完成后刷新发布事实并解除忙碌状态，可继续准备制品', async () => {
    vi.spyOn(useSession().api, 'post').mockResolvedValue({
      candidateSha256: 'sha-c',
      passed: true,
      results: [
        {
          caseId: 'case',
          name: '冻结用例',
          passed: true,
          executionId: 'execution',
          message: '已通过',
        },
      ],
    })
    const wrapper = await page('PublicationPanel', { candidate })
    await button(wrapper, '运行冻结验收用例').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('冻结用例 · 通过')
    expect(button(wrapper, '准备发布制品').attributes('disabled')).toBeUndefined()
    expect(wrapper.emitted('busy')?.at(-1)).toEqual([false])
  })
  it('没有授权记录不会显示为不限范围，选择null只写端点授权', async () => {
    vi.mocked(useSession().api.get).mockImplementation(
      async <T>(path: string): Promise<T> =>
        (path.endsWith('grant-users')
          ? [{ userId: 'u', username: 'user', displayName: '用户' }]
          : []) as T,
    )
    const put = vi.spyOn(useSession().api, 'put').mockResolvedValue({
      userId: 'u',
      username: 'user',
      displayName: '用户',
      dataScope: null,
      expiresAt: null,
      status: 'ACTIVE',
    })
    const wrapper = await page('EndpointGrantsPanel', {
      endpoint: {
        id: 'ep',
        releaseId: 'r',
        opCode: 'lookup',
        route: '',
        status: 'ACTIVE',
        execAttrs: {},
      },
    })
    await wrapper.get('[data-testid="endpoint-user"]').setValue('u')
    expect(wrapper.text()).toContain('缺少记录：拒绝消费')
    expect(wrapper.text()).toContain('缺少空间授权：拒绝消费')
    await wrapper.get('[data-testid="endpoint-scope"]').setValue('unlimited')
    await button(wrapper, '保存端点授权').trigger('click')
    await flushPromises()
    expect(put).toHaveBeenCalledWith('/endpoints/ep/grants/u', {
      dataScope: null,
      expiresAt: null,
      status: 'ACTIVE',
    })
    expect(wrapper.text()).toContain('缺少空间授权：拒绝消费')
  })
  it('一次性密钥迟到响应在切空间后不展示', async () => {
    let resolve!: (value: unknown) => void
    vi.spyOn(useSession().api, 'post').mockImplementation(
      () =>
        new Promise((done) => {
          resolve = done
        }),
    )
    const wrapper = await page('CredentialsPanel')
    await wrapper.get('[data-testid="credential-principal"]').setValue('u')
    await wrapper.get('[data-testid="credential-name"]').setValue('机器人')
    await wrapper.get('form').trigger('submit')
    useWorkspace().space = { ...space, id: 'another' }
    resolve({ credential: { id: 'k', name: '机器人' }, key: 'late-secret' })
    await flushPromises()
    expect(wrapper.text()).not.toContain('late-secret')
  })
  it('准备失败保留原门禁和理由，可在同一修订重试且不会自行READY', async () => {
    vi.spyOn(useSession().api, 'post').mockRejectedValue(
      new ApiError('ARTIFACT_UNAVAILABLE', '对象存储暂不可达', 503),
    )
    const wrapper = await page('PublicationPanel', { candidate })
    await wrapper.get('[data-testid="publication-reason"]').setValue('审查通过')
    await button(wrapper, '准备发布制品').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('对象存储暂不可达')
    expect(button(wrapper, '准备发布制品').attributes('disabled')).toBeUndefined()
    expect(button(wrapper, '提交正式发布').attributes('disabled')).toBeDefined()
    expect(
      (wrapper.get('[data-testid="publication-reason"]').element as HTMLTextAreaElement).value,
    ).toBe('审查通过')
  })
  it('正式成功只显示返回data与真实meta，不注入额外版本证据', async () => {
    vi.spyOn(useSession().api, 'requestEnvelope').mockResolvedValue({
      code: 'OK',
      data: { count: 3 },
      meta: {
        spaceCode: 'demo',
        releaseId: 'r',
        version: 'v1',
        contentDigest: 'sha-c',
        preview: false,
      },
    })
    const wrapper = await page('ServiceCallPanel', {
      release,
      endpoints: [
        {
          id: 'ep',
          releaseId: 'r',
          opCode: 'lookup',
          route: '/onto/s/demo/ops/lookup?releaseId=r',
          status: 'ACTIVE',
          execAttrs: {},
        },
      ],
    })
    await wrapper.get('[data-testid="service-operation"]').setValue('lookup')
    await button(wrapper, '调用正式服务').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="service-result"]').text()).toContain('"preview": false')
    expect(wrapper.get('[data-testid="service-result"]').text()).toContain('"count": 3')
  })
  it('正式调用支持Schema允许的数组根，非法JSON错误不回显原输入', async () => {
    const call = vi
      .spyOn(useSession().api, 'requestEnvelope')
      .mockRejectedValue(new ApiError('SCHEMA_ERROR', '输入不匹配', 400))
    const wrapper = await page('ServiceCallPanel', {
      release,
      endpoints: [
        { id: 'ep', releaseId: 'r', opCode: 'lookup', route: '', status: 'ACTIVE', execAttrs: {} },
      ],
    })
    await wrapper.get('[data-testid="service-operation"]').setValue('lookup')
    await wrapper.get('[data-testid="service-input-mode"]').setValue('json')
    await wrapper.get('[data-testid="service-json-input"]').setValue('[1, "设备"]')
    await button(wrapper, '调用正式服务').trigger('click')
    await flushPromises()
    expect(call).toHaveBeenCalledWith('/internal/releases/r/ops/lookup', 'POST', [1, '设备'])
    await wrapper.get('[data-testid="service-json-input"]').setValue('{"sensitive": secret-invalid')
    await button(wrapper, '调用正式服务').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).not.toContain('secret-invalid')
    expect(call).toHaveBeenCalledTimes(1)
  })
  it('制品缺失或bundle摘要错配时，即使READY仍禁用提交', async () => {
    vi.mocked(useSession().api.get).mockResolvedValueOnce({
      ...publication,
      status: 'READY_TO_COMMIT',
      artifacts: publication.artifacts.map((item) =>
        item.kind === 'BUNDLE' ? { ...item, sha256: 'other' } : item,
      ),
    })
    const wrapper = await page('PublicationPanel', { candidate })
    expect(button(wrapper, '提交正式发布').attributes('disabled')).toBeDefined()
  })
  it('恢复面板409后保持理由，空间刷新也不能重用旧确认', async () => {
    const post = vi
      .spyOn(useSession().api, 'post')
      .mockRejectedValue(new ApiError('DRAFT_REV_CONFLICT', '修订冲突', 409))
    const wrapper = await page('RestoreDraftPanel', { release })
    await wrapper.get('[data-testid="restore-reason"]').setValue('恢复原因保留')
    await wrapper.get('[data-testid="restore-confirm"]').setValue(true)
    await button(wrapper, '确认覆盖并恢复草稿').trigger('click')
    await flushPromises()
    useWorkspace().refreshed({ ...space, draftRev: 9 })
    await flushPromises()
    expect(button(wrapper, '确认覆盖并恢复草稿').attributes('disabled')).toBeDefined()
    expect(
      (wrapper.get('[data-testid="restore-reason"]').element as HTMLTextAreaElement).value,
    ).toBe('恢复原因保留')
    expect(post).toHaveBeenCalledTimes(1)
  })
  it('审计刷新失败保留旧页，并重试同一次刷新而非错误翻页', async () => {
    const item = {
      id: 20,
      spaceId: 's',
      actorId: 'u',
      action: 'OLD',
      subjectId: 'r',
      detail: {},
      createdAt: '',
    }
    const get = vi
      .mocked(useSession().api.get)
      .mockResolvedValueOnce({ items: [item], nextBeforeId: null })
      .mockRejectedValueOnce(new ApiError('NETWORK_ERROR', '读取失败', 0))
      .mockResolvedValueOnce({ items: [{ ...item, id: 21, action: 'NEW' }], nextBeforeId: null })
    const wrapper = await page('AuditView')
    await button(wrapper, '刷新审计').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('OLD')
    await button(wrapper, '重试读取').trigger('click')
    await flushPromises()
    expect(get).toHaveBeenCalledTimes(3)
    expect(get).toHaveBeenLastCalledWith('/audit?spaceId=s&limit=50')
    expect(wrapper.text()).toContain('NEW')
  })
})
