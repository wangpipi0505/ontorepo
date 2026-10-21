// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { setAuthorizedSession } from '../../test/authorizedSession'
import { defineComponent, h, type Component } from 'vue'
import ElementPlus from 'element-plus'
import { routeLocationKey } from 'vue-router'
import { ApiError, type Space, type Candidate } from '@onto/api-client'
import { useSession } from '../../stores/session'
import { useWorkspace } from '../../stores/workspace'
import TestCasesView from '../p1/TestCasesView.vue'
import { router } from '../../router'

// 页面使用真实表单与输入；只隔离 jsdom 不支持的浮层定位。
const select = defineComponent({
  props: { modelValue: [String, Array], multiple: Boolean, disabled: Boolean },
  emits: ['update:modelValue'],
  setup(props, { emit, slots }) {
    return () =>
      h(
        'select',
        {
          value: props.modelValue,
          multiple: props.multiple,
          disabled: props.disabled,
          onChange: (event: Event) => {
            const input = event.target as HTMLSelectElement
            emit(
              'update:modelValue',
              props.multiple
                ? Array.from(input.selectedOptions, (item) => item.value)
                : input.value,
            )
          },
        },
        slots.default?.(),
      )
  },
})
const option = defineComponent({
  props: { value: String, label: String },
  setup: (props) => () => h('option', { value: props.value }, props.label),
})
const global = {
  plugins: [ElementPlus],
  provide: { [routeLocationKey as symbol]: { query: {} } },
  stubs: { teleport: true, ElSelect: select, ElOption: option },
}
const modules = import.meta.glob<{ default: Component }>('./*.vue')
const space = { id: 's', role: 'OWNER', status: 'ACTIVE', draftRev: 5 } as Space
const candidate = {
  id: 'c',
  draftRev: 5,
  sha256: 'frozen-sha',
  createdAt: '2026-10-20T00:00:00Z',
} as Candidate
let wrappers: VueWrapper[] = []
async function page(name: string, props = {}) {
  const load = modules[`./${name}.vue`]
  expect(load, `${name} 应提供独立业务界面`).toBeDefined()
  const component = (await load!()).default
  const wrapper = mount(component, { global, props, attachTo: document.body })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
function button(wrapper: VueWrapper, text: string) {
  const result = wrapper.findAll('button').find((item) => item.text() === text)
  expect(result, `应显示操作：${text}`).toBeDefined()
  return result!
}
function field(wrapper: VueWrapper, label: string) {
  const item = wrapper.findAll('.el-form-item').find((item) => {
    const title = item.find('.el-form-item__label')
    return title.exists() && title.text() === label
  })
  expect(item, `应提供字段：${label}`).toBeDefined()
  return item!
}
beforeEach(() => {
  setAuthorizedSession()
  useWorkspace().space = { ...space }
  vi.spyOn(useSession().api, 'get').mockImplementation(async <T>(path: string): Promise<T> => {
    const value: unknown =
      path === '/spaces/s'
        ? space
        : path.startsWith('/operations?')
          ? [{ id: 'o', code: 'query', name: '对象查询' }]
          : path === '/operations/o/assistant?spaceId=s'
            ? { operationId: 'o', inputSchema: null, outputSchema: null, assistantTool: null }
            : path === '/spaces/s/guards'
              ? { guardCfg: {} }
              : path === '/spaces/s/grants'
                ? [
                    {
                      userId: 'u',
                      username: 'editor',
                      displayName: '编辑者',
                      role: 'EDITOR',
                      dataScope: null,
                      attributes: {},
                    },
                  ]
                : path === '/spaces/s/grant-users'
                  ? [
                      { userId: 'u', username: 'editor', displayName: '编辑者' },
                      { userId: 'new', username: 'new', displayName: '新用户' },
                    ]
                  : path.startsWith('/classes?')
                    ? [{ id: 'a', spaceId: 's', name: '设备' }]
                    : []
    return value as T
  })
})
afterEach(() => {
  wrappers.forEach((wrapper) => wrapper.unmount())
  wrappers = []
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

describe('P2 助手声明与草稿并发', () => {
  it('独立供给功能具备可直接访问的工作台路由', () => {
    const paths = router.getRoutes().map((route) => route.path)
    for (const path of ['assistant', 'guards', 'grants', 'knowledge', 'supply'])
      expect(paths).toContain(`/spaces/:spaceId/${path}`)
  })
  it('独立表单提交原协议字段，保留参数示例和 Schema 默认值 JSON 类型', async () => {
    const write = vi.spyOn(useSession().api, 'put').mockResolvedValue({ ...space, draftRev: 6 })
    const wrapper = await page('AssistantView')
    await field(wrapper, '草稿操作').get('select').setValue('o')
    await flushPromises()
    await field(wrapper, '助手名称').get('input').setValue('设备查询')
    await field(wrapper, '输入 Schema')
      .get('textarea')
      .setValue('{"type":"object","properties":{"active":{"type":"boolean","default":false}}}')
    await field(wrapper, '参数示例（JSON 数组）')
      .get('textarea')
      .setValue('[{"active":false,"limit":3,"ids":["a","b"]}]')
    await field(wrapper, '必需守卫属性').get('textarea').setValue('region\ntenant_label')
    useWorkspace().space = { ...space, draftRev: 99 }
    await wrapper.get('form[data-editor=assistant]').trigger('submit')
    await flushPromises()
    expect(write).toHaveBeenCalledWith(
      '/operations/o/assistant',
      expect.objectContaining({
        spaceId: 's',
        expectedDraftRev: 5,
        inputSchema: {
          type: 'object',
          properties: { active: { type: 'boolean', default: false } },
        },
        assistantTool: expect.objectContaining({
          name: '设备查询',
          guard_attrs: ['region', 'tenant_label'],
          param_examples: [{ active: false, limit: 3, ids: ['a', 'b'] }],
          risk_level: 'READ_ONLY',
          exec: { readonly: true, side_effect: false, idempotent: true, cancellable: true },
        }),
      }),
    )
  })
  it('参数示例格式错误定位字段；409保留输入并禁止借新修订再次保存', async () => {
    const write = vi
      .spyOn(useSession().api, 'put')
      .mockRejectedValue(new ApiError('DRAFT_REV_CONFLICT', '草稿已变化', 409))
    const wrapper = await page('AssistantView')
    await field(wrapper, '草稿操作').get('select').setValue('o')
    await flushPromises()
    await field(wrapper, '助手名称').get('input').setValue('保留我的声明')
    await field(wrapper, '参数示例（JSON 数组）').get('textarea').setValue('{}')
    await wrapper.get('form[data-editor=assistant]').trigger('submit')
    expect(write).not.toHaveBeenCalled()
    await vi.waitFor(() =>
      expect(field(wrapper, '参数示例（JSON 数组）').get('.el-form-item__error').text()).toContain(
        'JSON 数组',
      ),
    )
    await field(wrapper, '参数示例（JSON 数组）').get('textarea').setValue('[{}]')
    await wrapper.get('form[data-editor=assistant]').trigger('submit')
    await flushPromises()
    expect(field(wrapper, '助手名称').get<HTMLInputElement>('input').element.value).toBe(
      '保留我的声明',
    )
    expect(useWorkspace().stale).toBe(true)
    useWorkspace().refreshed({ ...space, draftRev: 12 })
    await wrapper.get('form[data-editor=assistant]').trigger('submit')
    await flushPromises()
    expect(write).toHaveBeenCalledTimes(1)
  })
  it('VIEWER 可以读取声明但没有保存入口', async () => {
    useWorkspace().space = { ...space, role: 'VIEWER' }
    vi.mocked(useSession().api.get).mockImplementation(
      async <T>(path: string): Promise<T> =>
        (path === '/spaces/s'
          ? { ...space, role: 'VIEWER' }
          : path.startsWith('/operations?')
            ? [{ id: 'o', name: '查询' }]
            : {
                operationId: 'o',
                inputSchema: null,
                outputSchema: null,
                assistantTool: null,
              }) as T,
    )
    const wrapper = await page('AssistantView')
    await field(wrapper, '草稿操作').get('select').setValue('o')
    await flushPromises()
    expect(wrapper.find('button[type=submit]').exists()).toBe(false)
  })
  it('服务端Schema错误显示在对应声明字段下', async () => {
    vi.spyOn(useSession().api, 'put').mockRejectedValue(
      new ApiError('INVALID_SCHEMA', 'inputSchema.properties.active.type: 不支持的类型', 400),
    )
    const wrapper = await page('AssistantView')
    await field(wrapper, '草稿操作').get('select').setValue('o')
    await flushPromises()
    await wrapper.get('form[data-editor=assistant]').trigger('submit')
    await flushPromises()
    await vi.waitFor(() =>
      expect(field(wrapper, '输入 Schema').text()).toContain('inputSchema.properties.active.type'),
    )
  })
})

describe('P2 守卫与主体授权', () => {
  it('守卫表单生成明确的主体属性和源列过滤绑定', async () => {
    const write = vi.spyOn(useSession().api, 'put').mockResolvedValue({ ...space, draftRev: 6 })
    const wrapper = await page('GuardsView')
    await button(wrapper, '添加守卫').trigger('click')
    await wrapper.get('[data-field=guard-name] input').setValue('region')
    await wrapper.get('[data-field=binding-kind] select').setValue('PRINCIPAL_ATTRIBUTE')
    await wrapper.get('[data-field=principal-attribute] input').setValue('region')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(write).toHaveBeenCalledWith('/spaces/s/guards', {
      expectedDraftRev: 5,
      guardCfg: {
        region: {
          type: 'STRING',
          authorization_rule: { kind: 'PRINCIPAL_ATTRIBUTE', attribute: 'region' },
        },
      },
    })
    await button(wrapper, '添加守卫').trigger('click')
    await wrapper.get('[data-field=guard-name] input').setValue('tenant_label')
    await wrapper.get('[data-field=binding-kind] select').setValue('FILTER')
    await wrapper.get('[data-field=filter-table] input').setValue('task_table')
    await wrapper.get('[data-field=filter-column] input').setValue('tenant_col')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(write).toHaveBeenLastCalledWith(
      '/spaces/s/guards',
      expect.objectContaining({
        guardCfg: {
          tenant_label: {
            type: 'STRING',
            filter_binding: { table: 'task_table', column: 'tenant_col', operator: 'IN' },
          },
        },
      }),
    )
  })
  it('EDITOR 无守卫写权限且不能读取主体授权目录', async () => {
    useWorkspace().space = { ...space, role: 'EDITOR' }
    vi.mocked(useSession().api.get).mockImplementation(
      async <T>(path: string): Promise<T> =>
        (path === '/spaces/s' ? { ...space, role: 'EDITOR' } : { guardCfg: {} }) as T,
    )
    const guards = await page('GuardsView')
    expect(guards.find('button[type=submit]').exists()).toBe(false)
    expect(guards.text()).toContain('仅空间所有者')
    await page('GrantsView')
    expect(useSession().api.get).not.toHaveBeenCalledWith('/spaces/s/grants')
    expect(useSession().api.get).not.toHaveBeenCalledWith('/spaces/s/grant-users')
  })
  it('授权只选择后端本地用户，并提交可信属性与不限范围null', async () => {
    const write = vi.spyOn(useSession().api, 'put').mockResolvedValue({ ...space, draftRev: 6 })
    const wrapper = await page('GrantsView')
    await field(wrapper, '本地用户').get('select').setValue('new')
    await field(wrapper, '主体属性（JSON 对象）')
      .get('textarea')
      .setValue('{"region":["华东"],"level":2,"active":false}')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(write).toHaveBeenCalledWith('/spaces/s/grants/new', {
      expectedDraftRev: 5,
      role: 'VIEWER',
      dataScope: null,
      attributes: { region: ['华东'], level: 2, active: false },
    })
  })
})

describe('P2 知识管理与确定性用例', () => {
  it('知识新增保留同空间概念引用和别名数组，冲突保留输入', async () => {
    const write = vi
      .spyOn(useSession().api, 'post')
      .mockRejectedValue(new ApiError('DRAFT_REV_CONFLICT', '草稿已变化', 409))
    const wrapper = await page('KnowledgeView')
    await button(wrapper, '新建知识').trigger('click')
    await flushPromises()
    await field(wrapper, '名称/术语').get('input').setValue('设备')
    await field(wrapper, '别名（每行一个）').get('textarea').setValue('器材\n装备\n器材')
    await field(wrapper, '定义').get('textarea').setValue('完成业务任务的设施')
    await field(wrapper, '来源').get('input').setValue('业务规范')
    await field(wrapper, '关联概念').get('select').setValue('a')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(write).toHaveBeenCalledWith('/knowledge', {
      spaceId: 's',
      expectedDraftRev: 5,
      kind: 'TERM',
      term: '设备',
      aliases: ['器材', '装备'],
      definition: '完成业务任务的设施',
      sourceRef: '业务规范',
      conceptRef: 'a',
      subjectRef: null,
      predicate: null,
      objectRef: null,
    })
    expect(field(wrapper, '名称/术语').get<HTMLInputElement>('input').element.value).toBe('设备')
    expect(useWorkspace().stale).toBe(true)
  })
  it('用例允许显式NL模式，期望操作和EXACT断言仍提交', async () => {
    const write = vi.spyOn(useSession().api, 'post').mockResolvedValue({ ...space, draftRev: 6 })
    const wrapper = mount(TestCasesView, { global, attachTo: document.body })
    wrappers.push(wrapper)
    await flushPromises()
    await button(wrapper, '新建用例').trigger('click')
    await flushPromises()
    await field(wrapper, '名称').get('input').setValue('自然语言查询')
    await field(wrapper, '问题').get('textarea').setValue('列出设备')
    await field(wrapper, '预期命中操作').get('select').setValue('o')
    await field(wrapper, '执行模式').get('select').setValue('NL')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(write).toHaveBeenCalledWith(
      '/test-cases',
      expect.objectContaining({ mode: 'NL', operationId: 'o', matchMode: 'EXACT' }),
    )
  })
})

describe('P2 冻结供给预览', () => {
  it('契约包装摘要正确但单个manifest带正式版本证据时仍拒绝展示', async () => {
    vi.mocked(useSession().api.get).mockResolvedValue({
      candidateId: 'c',
      candidateSha256: 'frozen-sha',
      manifests: [
        {
          tool_code: '伪正式版本',
          ontology_meta: {
            preview: false,
            contentDigest: 'frozen-sha',
            releaseId: 'release',
            version: '1',
          },
        },
      ],
    })
    const wrapper = await page('CandidateSupplyPanel', { candidate })
    await button(wrapper, '读取契约预览').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('伪正式版本')
    expect(wrapper.text()).toContain('候选预览元数据')
  })
  it('契约和知识包展示冻结摘要及preview，搜索请求真实接口并显示分数和来源', async () => {
    vi.mocked(useSession().api.get).mockImplementation(async <T>(path: string): Promise<T> => {
      const value: unknown = path.endsWith('/contracts')
        ? {
            candidateId: 'c',
            candidateSha256: 'frozen-sha',
            manifests: [
              {
                tool_code: 'query',
                ontology_meta: {
                  preview: true,
                  spaceCode: null,
                  contentDigest: null,
                  releaseId: null,
                  version: null,
                },
              },
            ],
          }
        : path.endsWith('/context-pack')
          ? {
              candidateId: 'c',
              candidateSha256: 'frozen-sha',
              conceptCards: [{ name: '冻结设备' }],
              termDictionary: [{ id: 'k', term: '设备', sourceRef: '冻结来源' }],
              relationSummaries: [],
              exampleQa: [],
            }
          : {
              candidateId: 'c',
              candidateSha256: 'frozen-sha',
              hits: [
                {
                  entryId: 'k',
                  term: '设备',
                  definition: '冻结定义',
                  score: 2.73,
                  matchedAlias: '器材',
                },
              ],
            }
      return value as T
    })
    const wrapper = await page('CandidateSupplyPanel', { candidate })
    await button(wrapper, '读取契约预览').trigger('click')
    await button(wrapper, '读取知识包').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('frozen-sha')
    expect(wrapper.text()).toContain('preview')
    expect(wrapper.text()).toContain('冻结设备')
    await field(wrapper, '搜索冻结知识').get('input').setValue('器材 & 设备')
    await wrapper.get('form[data-search=knowledge]').trigger('submit')
    await flushPromises()
    expect(useSession().api.get).toHaveBeenCalledWith(
      '/candidates/c/knowledge-search?q=%E5%99%A8%E6%9D%90%20%26%20%E8%AE%BE%E5%A4%87&limit=10',
    )
    expect(wrapper.text()).toContain('2.73')
    expect(wrapper.text()).toContain('冻结来源')
  })
  it('摘要不匹配时拒绝显示契约，不以当前草稿补结果', async () => {
    vi.mocked(useSession().api.get).mockResolvedValue({
      candidateId: 'c',
      candidateSha256: 'other',
      manifests: [{ tool_code: '不可展示' }],
    })
    const wrapper = await page('CandidateSupplyPanel', { candidate })
    await button(wrapper, '读取契约预览').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('摘要')
    expect(wrapper.text()).not.toContain('不可展示')
  })
})

describe('P2 内部候选调用', () => {
  it('仅发送操作输入，显示真实服务器拒绝和输入原文', async () => {
    vi.mocked(useSession().api.get).mockResolvedValue([
      { id: 'o', code: 'query', name: '冻结查询' },
    ])
    const wrapper = await page('InternalCallPanel', { candidate })
    const api = useSession().api
    const request = vi
      .spyOn(api, 'requestEnvelope')
      .mockRejectedValue(
        new ApiError('GUARD_BINDING_NOT_APPLICABLE', '数据源列过滤不能用于模型执行', 403),
      )
    await field(wrapper, '冻结操作').get('select').setValue('query')
    await field(wrapper, '操作输入（JSON）').get('textarea').setValue('{"limit":3}')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(request).toHaveBeenCalledWith('/internal/candidates/c/ops/query', 'POST', { limit: 3 })
    expect(wrapper.text()).toContain('GUARD_BINDING_NOT_APPLICABLE')
    expect(wrapper.text()).toContain('数据源列过滤不能用于模型执行')
    expect(
      field(wrapper, '操作输入（JSON）').get<HTMLTextAreaElement>('textarea').element.value,
    ).toBe('{"limit":3}')
  })
  it('内部结果摘要不符时隐藏业务结果', async () => {
    vi.mocked(useSession().api.get).mockResolvedValue([
      { id: 'o', code: 'query', name: '冻结查询' },
    ])
    const wrapper = await page('InternalCallPanel', { candidate })
    const api = useSession().api
    vi.spyOn(api, 'requestEnvelope').mockResolvedValue({
      code: 'OK',
      data: { secret: '错误候选结果' },
      meta: { preview: true, releaseId: null, version: null, contentDigest: 'other' },
    })
    await field(wrapper, '冻结操作').get('select').setValue('query')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('摘要')
    expect(wrapper.text()).not.toContain('错误候选结果')
  })
})
