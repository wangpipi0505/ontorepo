// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h, type Component } from 'vue'
import { setAuthorizedSession } from '../../test/authorizedSession'
import ElementPlus from 'element-plus'
import type {
  DataQuery,
  Datasource,
  MappingCollection,
  MetadataSnapshot,
  OntProperty,
  Space,
} from '@onto/api-client'
import { useSession } from '../../stores/session'
import { useWorkspace } from '../../stores/workspace'
import DataQueryForm from './DataQueryForm.vue'
import SourceForm from './SourceForm.vue'
import SourcesView from './SourcesView.vue'
import MetadataPanel from './MetadataPanel.vue'

// 仅隔离下拉浮层；JSON 解析、分页校验和资源代次使用真实业务实现。
const select = defineComponent({
  props: { modelValue: [String, Array], multiple: Boolean, disabled: Boolean },
  emits: ['update:modelValue', 'change'],
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
            const value = props.multiple
              ? Array.from(input.selectedOptions, (item) => item.value)
              : input.value
            emit('update:modelValue', value)
            emit('change', value)
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
  stubs: { teleport: true, ElSelect: select, ElOption: option },
}
const wrappers: VueWrapper[] = []
const space = { id: 's', role: 'OWNER', status: 'ACTIVE', draftRev: 5 } as Space
const source: Datasource = {
  id: 'd',
  spaceId: 's',
  code: 'd',
  name: '来源',
  kind: 'HTTP',
  dialect: 'json',
  accessScope: ['/items'],
  status: 'ACTIVE',
  lastTestAt: null,
}
const oldSnapshot: MetadataSnapshot = {
  id: 'old',
  sha256: 'old-hash',
  capturedAt: 'old-time',
  resources: [],
}
const newSnapshot: MetadataSnapshot = {
  id: 'new',
  sha256: 'new-hash',
  capturedAt: 'new-time',
  resources: [],
}
const mappings: MappingCollection = {
  objects: [
    {
      id: 'task',
      datasourceId: 'd',
      sourceResource: 'public.tasks',
      targetClassId: 'task-class',
      objectKeyExpr: '${id}',
      filter: null,
      metaSnapshotId: 'meta',
    },
    {
      id: 'algorithm',
      datasourceId: 'd',
      sourceResource: 'public.algorithms',
      targetClassId: 'algorithm-class',
      objectKeyExpr: '${id}',
      filter: null,
      metaSnapshotId: 'meta',
    },
  ],
  properties: [
    {
      id: 'task-id-map',
      objectMappingId: 'task',
      sourceField: 'id',
      targetPropertyId: 'task-id',
      transform: null,
      required: true,
    },
    {
      id: 'algorithm-code-map',
      objectMappingId: 'algorithm',
      sourceField: 'std_code',
      targetPropertyId: 'algorithm-code',
      transform: null,
      required: true,
    },
  ],
  relations: [
    {
      id: 'uses',
      datasourceId: 'd',
      relationId: 'uses-relation',
      fromObjectMappingId: 'task',
      toObjectMappingId: 'algorithm',
      fromKeyExpr: '${id}',
      toKeyExpr: '${id}',
      join: { fromField: 'algorithm_id', toField: 'id' },
      cardinalityCheck: true,
      metaSnapshotId: 'meta',
    },
  ],
}
const properties = [
  { id: 'task-id', name: '任务编号', rangeType: 'INTEGER' },
  { id: 'algorithm-code', name: '算法标准码', rangeType: 'STRING' },
] as OntProperty[]
beforeEach(() => {
  setAuthorizedSession()
  useWorkspace().space = { ...space }
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.restoreAllMocks()
})
async function render(component: Component, props: Record<string, unknown> = {}) {
  const wrapper = mount(component, { global, props })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
function field(wrapper: VueWrapper, label: string) {
  const result = wrapper
    .findAll('.el-form-item')
    .find((item) => item.find('.el-form-item__label').text() === label)
  expect(result, `应提供 ${label}`).toBeDefined()
  return result!
}
function button(wrapper: VueWrapper, label: string) {
  const result = wrapper.findAll('button').find((item) => item.text() === label)
  expect(result, `应提供 ${label}`).toBeDefined()
  return result!
}
async function httpForm() {
  const wrapper = await render(SourceForm, {
    value: null,
    canWrite: true,
    pending: false,
    stale: false,
  })
  await field(wrapper, '源编码').get('input').setValue('http-source')
  await field(wrapper, '源名称').get('input').setValue('HTTP 来源')
  await field(wrapper, '源类别').get('select').setValue('HTTP')
  await flushPromises()
  await field(wrapper, '访问范围（资源名，每行一个）').get('textarea').setValue('/items')
  await field(wrapper, '接口根地址').get('input').setValue('http://localhost:18000')
  await field(wrapper, '允许接口路径（每行一个）').get('textarea').setValue('/items')
  return wrapper
}
function query(value: DataQuery, valueMappings = mappings, valueProperties = properties) {
  return render(DataQueryForm, {
    value,
    mappings: valueMappings,
    properties: valueProperties,
    inputParams: ['taskId', 'algorithmCode'],
    canWrite: true,
    pending: false,
    stale: false,
  })
}

describe('P3 独立审查修复回归', () => {
  it('R1 关系查询可投影与过滤终点算法标准码，并展示对象字段归属', async () => {
    const value: DataQuery = {
      mode: 'RELATION',
      objectMappingId: 'task',
      relationMappingId: 'uses',
      propertyIds: ['algorithm-code'],
      filters: [{ propertyId: 'algorithm-code', operator: 'EQ', inputParam: 'algorithmCode' }],
    }
    const wrapper = await query(value)
    expect(field(wrapper, '输出属性').text()).toContain('算法标准码')
    expect(field(wrapper, '过滤属性').text()).toContain('std_code')
    expect(field(wrapper, '输出属性').text()).toContain('终点')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('save')?.[0]?.[0]).toEqual(value)
  })
  it('R1 主对象必须为关系起点', async () => {
    const wrapper = await query({
      mode: 'RELATION',
      objectMappingId: 'algorithm',
      relationMappingId: 'uses',
      propertyIds: ['algorithm-code'],
      filters: [],
    })
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('save')).toBeUndefined()
    expect(wrapper.get('[role=alert]').text()).toContain('关系映射')
  })
  it('R1 两端绑定同一本体属性 ID 时拒绝歧义并提示拆分操作', async () => {
    const both: MappingCollection = {
      ...mappings,
      properties: [
        ...mappings.properties,
        {
          id: 'duplicate-property-map',
          objectMappingId: 'algorithm',
          sourceField: 'task_id',
          targetPropertyId: 'task-id',
          transform: null,
          required: false,
        },
      ],
    }
    const wrapper = await query(
      {
        mode: 'RELATION',
        objectMappingId: 'task',
        relationMappingId: 'uses',
        propertyIds: ['task-id'],
        filters: [],
      },
      both,
    )
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('save')).toBeUndefined()
    expect(wrapper.get('[role=alert]').text()).toContain('歧义')
    expect(wrapper.get('[role=alert]').text()).toContain('拆分操作')
  })
  it('R1 OBJECT 模式仍限制当前对象属性', async () => {
    const wrapper = await query({
      mode: 'OBJECT',
      objectMappingId: 'task',
      propertyIds: ['algorithm-code'],
      filters: [],
    })
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('save')).toBeUndefined()
  })
  it('R2 HTTP POST 固定对象和合法分页数值原样提交', async () => {
    const wrapper = await httpForm()
    await field(wrapper, '请求方法').get('select').setValue('POST')
    await flushPromises()
    await field(wrapper, '固定 POST 请求体（JSON 对象，可能含敏感值）')
      .get('input')
      .setValue('{"active":false,"ids":[1,2],"limit":5}')
    await field(wrapper, '分页参数名').get('input').setValue('page')
    await field(wrapper, '每页条数（1–1000）').get('input').setValue('200')
    await field(wrapper, '最大页数（1–100）').get('input').setValue('10')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('save')?.[0]?.[0]).toMatchObject({
      connection: {
        method: 'POST',
        body: { active: false, ids: [1, 2], limit: 5 },
        pageParam: 'page',
        pageSize: 200,
        maxPages: 10,
      },
    })
  })
  it.each([
    ['每页条数（1–1000）', '0'],
    ['每页条数（1–1000）', '1001'],
    ['每页条数（1–1000）', '1.5'],
    ['最大页数（1–100）', '0'],
    ['最大页数（1–100）', '101'],
  ])('R2 拒绝越界或非整数分页：%s=%s', async (label, value) => {
    const wrapper = await httpForm()
    await field(wrapper, label).get('input').setValue(value)
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('save')).toBeUndefined()
    expect(wrapper.get('[role=alert]').text()).toContain('整数')
  })
  it('R2 编辑 HTTP 默认保留旧 POST 与分页加密配置', async () => {
    const wrapper = await render(SourceForm, {
      value: source,
      canWrite: true,
      pending: false,
      stale: false,
    })
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('save')?.[0]?.[0]).toMatchObject({ connection: null })
    expect(wrapper.text()).not.toContain('固定 POST 请求体')
  })
  it.each(['success', 'failure'])(
    'R3 同步成功后旧快照 %s 不得覆盖新结构、选择或错误状态',
    async (outcome) => {
      let resolve!: (value: MetadataSnapshot) => void, reject!: (cause: Error) => void
      const historical = new Promise<MetadataSnapshot>((yes, no) => {
        resolve = yes
        reject = no
      })
      let rev = 5
      vi.spyOn(useSession().api, 'get').mockImplementation(async <T>(path: string): Promise<T> => {
        const value: unknown =
          path === '/spaces/s'
            ? { ...space, draftRev: rev }
            : path === '/datasources?spaceId=s'
              ? [source]
              : path === '/datasources/d/snapshots?spaceId=s'
                ? [oldSnapshot, newSnapshot]
                : path === '/datasources/d/snapshots/old?spaceId=s'
                  ? await historical
                  : path === '/datasources/d/snapshots/new?spaceId=s'
                    ? newSnapshot
                    : []
        return value as T
      })
      vi.spyOn(useSession().api, 'post').mockImplementation(async <T>(): Promise<T> => {
        rev = 6
        return {
          space: { ...space, draftRev: rev },
          snapshot: newSnapshot,
          diff: { added: [], removed: [], changed: [], affectedMappings: [] },
        } as T
      })
      const wrapper = await render(SourcesView)
      await button(wrapper, '元数据').trigger('click')
      await flushPromises()
      await field(wrapper, '不可变元数据快照').get('select').setValue('old')
      await flushPromises()
      await button(wrapper, '同步元数据').trigger('click')
      await flushPromises()
      if (outcome === 'success') resolve(oldSnapshot)
      else reject(new Error('旧历史请求失败'))
      await flushPromises()
      const displayed = wrapper
        .findAllComponents(MetadataPanel)
        .find((item) => item.props('snapshot'))
      expect(displayed?.props('snapshot')?.id).toBe('new')
      expect(
        field(wrapper, '不可变元数据快照').get<HTMLSelectElement>('select').element.value,
      ).toBe('new')
      expect(wrapper.text()).not.toContain('旧历史请求失败')
      expect(wrapper.text()).not.toContain('正在读取')
    },
  )
  it('R4 错误提示不回显秘密请求头片段', async () => {
    const wrapper = await httpForm()
    await field(wrapper, '可选请求头（JSON 对象，可能含凭据）')
      .get('input')
      .setValue('SENTINEL_SECRET')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('save')).toBeUndefined()
    expect(wrapper.get('[role=alert]').text()).not.toContain('SENTINEL')
    expect(wrapper.get('[role=alert]').text()).toContain('请求头必须是有效 JSON 对象')
  })
  it('R4 POST 模板语法错误同样不回显敏感值', async () => {
    const wrapper = await httpForm()
    await field(wrapper, '请求方法').get('select').setValue('POST')
    await flushPromises()
    await field(wrapper, '固定 POST 请求体（JSON 对象，可能含敏感值）')
      .get('input')
      .setValue('SENTINEL_BODY_SECRET')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('save')).toBeUndefined()
    expect(wrapper.get('[role=alert]').text()).not.toContain('SENTINEL')
    expect(wrapper.get('[role=alert]').text()).toContain('POST 请求体必须是有效 JSON 对象')
  })
})
