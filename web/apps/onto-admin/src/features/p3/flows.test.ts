// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h, type Component } from 'vue'
import { setAuthorizedSession } from '../../test/authorizedSession'
import ElementPlus from 'element-plus'
import {
  ApiError,
  type Datasource,
  type MetadataSnapshot,
  type SourceSync,
  type Space,
} from '@onto/api-client'
import { useSession } from '../../stores/session'
import { useWorkspace } from '../../stores/workspace'
import SourcesView from './SourcesView.vue'
import SuggestionsView from './SuggestionsView.vue'

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
const space = { id: 's', name: '测试空间', role: 'OWNER', status: 'ACTIVE', draftRev: 5 } as Space
const source: Datasource = {
  id: 'd',
  spaceId: 's',
  code: 'devices',
  name: '设备库',
  kind: 'RELATIONAL',
  dialect: 'postgresql',
  accessScope: ['public.devices'],
  status: 'ACTIVE',
  lastTestAt: null,
}
const snapshot: MetadataSnapshot = {
  id: 'm',
  sha256: 'sha-m',
  capturedAt: '2026-10-20T00:00:00Z',
  resources: [
    {
      name: 'public.devices',
      fields: [{ name: 'id', type: 'INTEGER', nullable: false }],
      primaryKey: ['id'],
      relations: [],
      indexes: [],
    },
  ],
}
const modules = import.meta.glob<{ default: Component }>('./*.vue')
const wrappers: VueWrapper[] = []
function button(wrapper: VueWrapper, text: string) {
  const result = wrapper.findAll('button').find((item) => item.text() === text)
  expect(result, text).toBeDefined()
  return result!
}
function field(wrapper: VueWrapper, label: string) {
  const result = wrapper
    .findAll('.el-form-item')
    .find((item) => item.find('.el-form-item__label').text() === label)
  expect(result, label).toBeDefined()
  return result!
}
function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (cause: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
async function page(component: Component) {
  const wrapper = mount(component, { global })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
function response(path: string): unknown {
  if (path === '/spaces/s') return { ...space, role: useWorkspace().space?.role ?? 'OWNER' }
  if (path === '/datasources?spaceId=s') return [source]
  if (path === '/datasources/capabilities?spaceId=s')
    return [
      {
        kind: 'WAREHOUSE',
        dialect: 'clickhouse',
        implemented: true,
        verifiedLocally: false,
        capabilities: ['SELECT'],
        limits: ['仅 MergeTree'],
        verificationNote: '待真实 ClickHouse 外部实例验收',
      },
    ]
  if (path === '/data-files?spaceId=s')
    return [
      {
        id: 'f',
        name: 'devices.csv',
        resource: 'safe.csv',
        mediaType: 'text/csv',
        size: 20,
        sha256: 'file-sha',
      },
    ]
  if (path === '/datasources/d/snapshots?spaceId=s')
    return [{ id: 'm', sha256: snapshot.sha256, capturedAt: snapshot.capturedAt }]
  if (path === '/datasources/d/snapshots/m?spaceId=s') return snapshot
  if (path === '/classes?spaceId=s')
    return [{ id: 'c', name: '设备', spaceId: 's', parentId: null }]
  if (path.startsWith('/suggestions?spaceId=s'))
    return [
      {
        id: 'q',
        batchId: 'b',
        sourceKind: 'TABLE',
        kind: 'CLASS',
        payload: { name: '设备候选', description: '', parentId: null },
        confidence: 0.8,
        location: { sourceName: '设备库', resource: 'public.devices' },
        status: 'PENDING',
      },
    ]
  if (path === '/mappings?spaceId=s') return { objects: [], properties: [], relations: [] }
  if (path === '/spaces/s/grant-users')
    return [{ userId: 'u', username: 'owner', displayName: '所有者' }]
  if (
    path === '/spaces/s/properties' ||
    path === '/relations?spaceId=s' ||
    path === '/codebooks?spaceId=s'
  )
    return []
  if (path === '/mappings/validate?spaceId=s')
    return {
      valid: false,
      checks: [],
      coverage: { mapped: 0, pending: 1, unmappable: 0 },
      coverageBasis: '当前活动类',
      affectedOperations: [],
    }
  throw new Error(`未定义真实契约路径：${path}`)
}
beforeEach(() => {
  setAuthorizedSession()
  useWorkspace().space = { ...space }
  vi.spyOn(useSession().api, 'get').mockImplementation(
    async <T>(path: string): Promise<T> => response(path) as T,
  )
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.restoreAllMocks()
})

describe('P3 页面修订与异步隔离', () => {
  it('数据源 409 保留输入，旧表单不得借空间新修订提交', async () => {
    const write = vi
      .spyOn(useSession().api, 'put')
      .mockRejectedValue(new ApiError('DRAFT_REV_CONFLICT', '草稿已变化', 409))
    const wrapper = await page(SourcesView)
    await button(wrapper, '编辑').trigger('click')
    await field(wrapper, '源名称').get('input').setValue('我的保留输入')
    useWorkspace().space = { ...space, draftRev: 99 }
    await wrapper.get('form[data-editor=source]').trigger('submit')
    await flushPromises()
    expect(write).toHaveBeenCalledWith(
      '/datasources/d',
      expect.objectContaining({
        spaceId: 's',
        expectedDraftRev: 5,
        name: '我的保留输入',
        connection: null,
      }),
    )
    expect(field(wrapper, '源名称').get<HTMLInputElement>('input').element.value).toBe(
      '我的保留输入',
    )
    expect(useWorkspace().stale).toBe(true)
    useWorkspace().refreshed({ ...space, draftRev: 100 })
    await wrapper.get('form[data-editor=source]').trigger('submit')
    await flushPromises()
    expect(write).toHaveBeenCalledTimes(1)
  })
  it('源目录权限失败可重试，失败时不回退演示源', async () => {
    const get = vi.mocked(useSession().api.get)
    get.mockImplementation(async <T>(path: string): Promise<T> => {
      if (path.startsWith('/datasources?'))
        throw new ApiError('FORBIDDEN', '没有当前空间来源读取权限', 403)
      return response(path) as T
    })
    const wrapper = await page(SourcesView)
    expect(wrapper.text()).toContain('没有当前空间来源读取权限')
    expect(wrapper.text()).not.toContain('设备库')
    get.mockImplementation(async <T>(path: string): Promise<T> => response(path) as T)
    await button(wrapper, '重新加载').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('设备库')
    expect(wrapper.text()).toContain('真实实例待外部验收')
  })
  it('VIEWER 只能读取源和能力，不能配置或上传', async () => {
    useWorkspace().space = { ...space, role: 'VIEWER' }
    const wrapper = await page(SourcesView)
    expect(
      wrapper
        .findAll('button')
        .some((item) => ['编辑', '删除', '新增数据源'].includes(item.text())),
    ).toBe(false)
    expect(wrapper.get<HTMLInputElement>('input[type=file]').element.disabled).toBe(true)
    expect(wrapper.text()).toContain('设备库')
  })
  it('旧同步成功不能污染同空间重新进入后的修订', async () => {
    const pending = deferred<SourceSync>()
    vi.spyOn(useSession().api, 'post').mockImplementation(() => pending.promise)
    const wrapper = await page(SourcesView)
    await button(wrapper, '元数据').trigger('click')
    await flushPromises()
    await button(wrapper, '同步元数据').trigger('click')
    await flushPromises()
    wrapper.unmount()
    await useWorkspace().load('s')
    pending.resolve({
      space: { ...space, draftRev: 6 },
      snapshot,
      diff: { added: [], removed: [], changed: [], affectedMappings: [] },
    })
    await flushPromises()
    expect(useWorkspace().space?.draftRev).toBe(5)
    expect(useWorkspace().stale).toBe(false)
  })
  it('切源迟到快照列表不能覆盖当前源', async () => {
    const pending = deferred<unknown>()
    vi.mocked(useSession().api.get).mockImplementation(async <T>(path: string): Promise<T> => {
      if (path === '/datasources?spaceId=s')
        return [source, { ...source, id: 'd2', name: '第二数据源' }] as T
      if (path === '/datasources/d/snapshots?spaceId=s') return (await pending.promise) as T
      if (path === '/datasources/d2/snapshots?spaceId=s')
        return [{ id: 'm2', sha256: 'sha-second', capturedAt: 'second' }] as T
      return response(path) as T
    })
    const wrapper = await page(SourcesView)
    const actions = wrapper.findAll('button').filter((item) => item.text() === '元数据')
    await actions[0]!.trigger('click')
    await flushPromises()
    await actions[1]!.trigger('click')
    await flushPromises()
    pending.resolve([{ id: 'm-old', sha256: 'sha-old', capturedAt: 'old' }])
    await flushPromises()
    expect(wrapper.text()).toContain('sha-second')
    expect(wrapper.text()).not.toContain('sha-old')
  })
  it('抽取预览只请求候选批次，不自动接受；人工决定可编辑名称', async () => {
    const post = vi
      .spyOn(useSession().api, 'post')
      .mockResolvedValue({ space: { ...space, draftRev: 6 }, batchId: 'b', count: 1 })
    const wrapper = await page(SuggestionsView)
    await field(wrapper, '抽取来源').get('select').setValue('FILE')
    await flushPromises()
    await field(wrapper, '受管文件').get('select').setValue('f')
    await wrapper.get('form[data-editor=extraction]').trigger('submit')
    await flushPromises()
    expect(post).toHaveBeenCalledTimes(1)
    expect(post).toHaveBeenCalledWith('/suggestions/extract', {
      spaceId: 's',
      expectedDraftRev: 5,
      sourceKind: 'FILE',
      fileId: 'f',
    })
    await field(wrapper, '候选名称').get('input').setValue('人工修正名称')
    await field(wrapper, '人工决定').get('select').setValue('ACCEPT')
    post.mockResolvedValue({ ...space, draftRev: 7 })
    await wrapper.get('form[data-editor=suggestion-review]').trigger('submit')
    await flushPromises()
    expect(post).toHaveBeenLastCalledWith(
      '/suggestions/decide',
      expect.objectContaining({
        decisions: [
          {
            id: 'q',
            decision: 'ACCEPT',
            payload: { name: '人工修正名称', description: '', parentId: null },
          },
        ],
      }),
    )
  })
  it('映射工作台使用同空间真实选择器，缺快照时不能提交', async () => {
    const wrapper = await page((await modules['./MappingsView.vue']!()).default)
    await button(wrapper, '新增对象映射').trigger('click')
    await flushPromises()
    await wrapper.get('form[data-editor=mapping]').trigger('submit')
    expect(wrapper.text()).toContain('请先选择并读取同源元数据快照')
    expect(wrapper.text()).toContain('分母 1')
  })
})
