// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { setAuthorizedSession } from '../../test/authorizedSession'
import { defineComponent, type Component } from 'vue'
import ElementPlus from 'element-plus'
import type {
  Datasource,
  MappingCollection,
  MetadataSnapshot,
  OntClass,
  OntProperty,
  Relation,
  Space,
} from '@onto/api-client'
import { useSession } from '../../stores/session'
import { useWorkspace } from '../../stores/workspace'
import MappingsView from './MappingsView.vue'
import MappingForm from './MappingForm.vue'

const modules = import.meta.glob<{ default: Component }>('./MappingWorkbench.vue')
const space = { id: 's', role: 'OWNER', status: 'ACTIVE', draftRev: 5 } as Space
const source = { id: 'd', name: '设备库', spaceId: 's' } as Datasource
const classes = [
  { id: 'c', name: 'device', parentId: null },
  { id: 'c2', name: '任务', parentId: 'c' },
] as OntClass[]
const properties = [
  { id: 'p', name: 'id', domainClassId: 'c', rangeType: 'INTEGER' },
  { id: 'p2', name: '编号', domainClassId: 'c2', rangeType: 'INTEGER' },
  { id: 'p3', name: '说明', domainClassId: 'c', rangeType: 'STRING' },
] as OntProperty[]
const snapshot: MetadataSnapshot = {
  id: 'm',
  sha256: 'sha-m',
  capturedAt: '2026-10-21',
  resources: [
    {
      name: 'device',
      fields: [{ name: 'id', type: 'INTEGER', nullable: false }],
      primaryKey: ['id'],
      relations: [],
      indexes: [],
    },
  ],
}
const mappings: MappingCollection = {
  objects: [
    {
      id: 'o',
      datasourceId: 'd',
      metaSnapshotId: 'm',
      sourceResource: 'device',
      targetClassId: 'c',
      objectKeyExpr: 'device:${id}',
      filter: null,
    },
  ],
  properties: [
    {
      id: 'pm',
      objectMappingId: 'o',
      sourceField: 'id',
      targetPropertyId: 'p',
      required: true,
      transform: { kind: 'IDENTITY' },
    },
  ],
  relations: [],
}
const wrappers: VueWrapper[] = []
const props = {
  sources: [source],
  mappings,
  classes,
  properties,
  relations: [] as Relation[],
  canWrite: true,
  disabled: false,
}
async function page(overrides = {}) {
  const module = modules['./MappingWorkbench.vue']
  const component = module ? (await module()).default : defineComponent({ template: '<div />' })
  const wrapper = mount(component, {
    props: { ...props, ...overrides },
    global: { plugins: [ElementPlus] },
  })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
async function choose(wrapper: VueWrapper) {
  await wrapper.get('[aria-label="图形映射数据源"]').setValue('d')
  await flushPromises()
  await wrapper.get('[aria-label="图形映射快照"]').setValue('m')
  await flushPromises()
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((yes) => {
    resolve = yes
  })
  return { promise, resolve }
}
beforeEach(() => {
  setAuthorizedSession()
  useWorkspace().space = { ...space }
  vi.spyOn(useSession().api, 'get').mockImplementation(async <T>(path: string) => {
    if (path === '/datasources/d/snapshots?spaceId=s')
      return [{ id: 'm', sha256: 'sha-m', capturedAt: 'now' }] as T
    if (path === '/datasources/d/snapshots/m?spaceId=s') return snapshot as T
    throw new Error(path)
  })
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.restoreAllMocks()
})

describe('真实双树映射交互', () => {
  it('工作台连线进入原有可编辑表单，人工保存才携带读取时修订写入', async () => {
    const read = vi.mocked(useSession().api.get).getMockImplementation()!
    vi.mocked(useSession().api.get).mockImplementation(async <T>(path: string): Promise<T> => {
      if (path === '/spaces/s') return space as T
      if (path === '/mappings?spaceId=s') return mappings as T
      if (path === '/datasources?spaceId=s') return [source] as T
      if (path === '/classes?spaceId=s') return classes as T
      if (path === '/spaces/s/properties') return properties as T
      if (path === '/relations?spaceId=s' || path === '/codebooks?spaceId=s') return [] as T
      if (path === '/mappings/validate?spaceId=s')
        return {
          valid: true,
          checks: [],
          coverage: { mapped: 1, pending: 1, unmappable: 0 },
          coverageBasis: '当前类',
          affectedOperations: [],
        } as T
      return read(path) as Promise<T>
    })
    const write = vi.spyOn(useSession().api, 'post').mockResolvedValue({ ...space, draftRev: 6 })
    const wrapper = mount(MappingsView, { global: { plugins: [ElementPlus] } })
    wrappers.push(wrapper)
    await flushPromises()
    expect(wrapper.find('[aria-label="图形映射数据源"]').exists()).toBe(true)
    await choose(wrapper)
    await wrapper.get('[data-source-resource="device"]').trigger('click')
    await wrapper.get('[data-target-class="c2"]').trigger('click')
    await flushPromises()
    expect(wrapper.findComponent(MappingForm).exists()).toBe(true)
    expect(wrapper.text()).toContain('连线预填')
    expect(write).not.toHaveBeenCalled()
    await wrapper.get('form[data-editor=mapping]').trigger('submit')
    await flushPromises()
    expect(write).toHaveBeenCalledWith(
      '/mappings/objects',
      expect.objectContaining({
        spaceId: 's',
        expectedDraftRev: 5,
        datasourceId: 'd',
        metaSnapshotId: 'm',
        sourceResource: 'device',
        targetClassId: 'c2',
        objectKeyExpr: '${id}',
      }),
    )
    await choose(wrapper)
    await wrapper.get('[data-source-field="o:id"]').trigger('click')
    await wrapper.get('[data-target-property="p"]').trigger('click')
    await flushPromises()
    expect(write).toHaveBeenCalledTimes(1)
    await wrapper.get('form[data-editor=mapping]').trigger('submit')
    await flushPromises()
    expect(write).toHaveBeenLastCalledWith(
      '/mappings/properties',
      expect.objectContaining({
        spaceId: 's',
        expectedDraftRev: 5,
        objectMappingId: 'o',
        sourceField: 'id',
        targetPropertyId: 'p',
        transform: { kind: 'IDENTITY' },
      }),
    )
  })
  it('点选与拖拽只预填对象表单，不发送写入；字段映射保留所属对象', async () => {
    const post = vi.spyOn(useSession().api, 'post')
    const wrapper = await page()
    expect(wrapper.find('[aria-label="图形映射数据源"]').exists()).toBe(true)
    await choose(wrapper)
    await wrapper.get('[data-source-resource="device"]').trigger('click')
    await wrapper.get('[data-target-class="c2"]').trigger('click')
    expect(wrapper.emitted('prefill')?.[0]?.[0]).toMatchObject({
      kind: 'objects',
      value: {
        datasourceId: 'd',
        metaSnapshotId: 'm',
        sourceResource: 'device',
        targetClassId: 'c2',
      },
    })
    const transfer = { setData: vi.fn(), getData: vi.fn(), effectAllowed: '' }
    await wrapper.get('[data-source-field="o:id"]').trigger('dragstart', { dataTransfer: transfer })
    await wrapper.get('[data-target-property="p"]').trigger('drop', { dataTransfer: transfer })
    expect(wrapper.emitted('prefill')?.[1]?.[0]).toMatchObject({
      kind: 'properties',
      value: { objectMappingId: 'o', sourceField: 'id', targetPropertyId: 'p' },
    })
    expect(post).not.toHaveBeenCalled()
  })
  it('拒绝字段到类、跨对象属性与不兼容类型，提供人工转换路径', async () => {
    const wrapper = await page()
    expect(wrapper.find('[aria-label="图形映射数据源"]').exists()).toBe(true)
    await choose(wrapper)
    await wrapper.get('[data-source-field="o:id"]').trigger('click')
    await wrapper.get('[data-target-class="c"]').trigger('click')
    expect(wrapper.text()).toContain('字段只能连接本体属性')
    await wrapper.get('[data-target-property="p2"]').trigger('click')
    expect(wrapper.text()).toContain('所属对象')
    await wrapper.get('[data-target-property="p3"]').trigger('click')
    expect(wrapper.text()).toContain('类型不兼容')
    expect(wrapper.emitted('prefill')).toBeUndefined()
  })
  it('VIEWER可定位已有映射，不能拖拽或预填，窄屏仍有等价点选说明', async () => {
    useWorkspace().space = { ...space, role: 'VIEWER' }
    const wrapper = await page({ canWrite: false })
    expect(wrapper.find('[aria-label="图形映射数据源"]').exists()).toBe(true)
    await choose(wrapper)
    expect(wrapper.get('[data-source-field="o:id"]').attributes('draggable')).toBe('false')
    await wrapper.get('[data-source-field="o:id"]').trigger('click')
    await wrapper.get('[data-target-property="p"]').trigger('click')
    expect(wrapper.emitted('prefill')).toBeUndefined()
    await wrapper.get('[data-mapping-id="pm"]').trigger('click')
    expect(wrapper.text()).toContain('pm')
    expect(wrapper.text()).toContain('先点来源，再点目标')
    expect(wrapper.find('.mapping-viewport').exists()).toBe(true)
  })
  it('切源、快照和空间会隔离迟到结果，旧节点不能再连接', async () => {
    const late = deferred<MetadataSnapshot>()
    vi.mocked(useSession().api.get).mockImplementation(async <T>(path: string) => {
      if (path === '/datasources/d/snapshots?spaceId=s') return [{ id: 'm' }, { id: 'm2' }] as T
      if (path === '/datasources/d/snapshots/m?spaceId=s') return (await late.promise) as T
      if (path === '/datasources/d/snapshots/m2?spaceId=s')
        return { ...snapshot, id: 'm2', resources: [] } as T
      if (path === '/datasources/d2/snapshots?spaceId=s') return [] as T
      throw new Error(path)
    })
    const wrapper = await page({ sources: [source, { ...source, id: 'd2', name: '第二源' }] })
    expect(wrapper.find('[aria-label="图形映射数据源"]').exists()).toBe(true)
    await choose(wrapper)
    await wrapper.get('[aria-label="图形映射快照"]').setValue('m2')
    await flushPromises()
    await wrapper.get('[aria-label="图形映射数据源"]').setValue('d2')
    useWorkspace().space = { ...space, id: 'other' }
    late.resolve(snapshot)
    await flushPromises()
    expect(wrapper.find('[data-source-resource="device"]').exists()).toBe(false)
    expect(wrapper.emitted('prefill')).toBeUndefined()
  })
  it('推荐显示规则置信度和快照证据，逐条审查不自动保存', async () => {
    const wrapper = await page({ mappings: { ...mappings, properties: [] } })
    expect(wrapper.find('[aria-label="图形映射数据源"]').exists()).toBe(true)
    await choose(wrapper)
    const recommend = wrapper.findAll('button').find((button) => button.text() === '生成映射推荐')
    expect(recommend).toBeDefined()
    await recommend!.trigger('click')
    expect(wrapper.text()).toContain('100%')
    expect(wrapper.text()).toContain('名称完全匹配')
    expect(wrapper.text()).toContain('sha-m')
    expect(wrapper.emitted('prefill')).toBeUndefined()
    await wrapper.get('[data-review-recommendation]').trigger('click')
    expect(wrapper.emitted('prefill')).toHaveLength(1)
  })
  it('同空间重新进入尚未返回时，旧画布不能用已缓存的可写权限继续预填', async () => {
    const wrapper = await page()
    await choose(wrapper)
    await wrapper.get('[data-source-resource="device"]').trigger('click')
    const pending = deferred<Space>()
    const read = vi.mocked(useSession().api.get).getMockImplementation()!
    vi.mocked(useSession().api.get).mockImplementation(async <T>(path: string) =>
      path === '/spaces/s' ? ((await pending.promise) as T) : (read(path) as Promise<T>),
    )
    const reenter = useWorkspace().load('s')
    await wrapper.get('[data-target-class="c2"]').trigger('click')
    expect(wrapper.emitted('prefill')).toBeUndefined()
    pending.resolve({ ...space })
    await reenter
  })
  it('只切快照也隔离旧详情成功返回', async () => {
    const late = deferred<MetadataSnapshot>()
    vi.mocked(useSession().api.get).mockImplementation(async <T>(path: string) => {
      if (path.endsWith('/snapshots?spaceId=s')) return [{ id: 'm' }, { id: 'm2' }] as T
      if (path.includes('/snapshots/m?')) return (await late.promise) as T
      return { ...snapshot, id: 'm2', sha256: 'sha-current', resources: [] } as T
    })
    const wrapper = await page()
    await choose(wrapper)
    await wrapper.get('[aria-label="图形映射快照"]').setValue('m2')
    await flushPromises()
    late.resolve(snapshot)
    await flushPromises()
    expect(wrapper.text()).toContain('sha-current')
    expect(wrapper.find('[data-source-resource="device"]').exists()).toBe(false)
  })
  it('已有关系连接键和可点击 SVG 均可定位，查看者仍可读取', async () => {
    vi.spyOn(window, 'requestAnimationFrame').mockImplementation((callback) => {
      callback(0)
      return 1
    })
    const wrapper = await page({
      canWrite: false,
      mappings: {
        ...mappings,
        relations: [
          {
            id: 'rm',
            datasourceId: 'd',
            metaSnapshotId: 'm',
            relationId: 'rel',
            fromObjectMappingId: 'o',
            toObjectMappingId: 'o2',
            fromKeyExpr: '${from_id}',
            toKeyExpr: '${to_id}',
            join: { fromField: 'from_id', toField: 'to_id' },
            cardinalityCheck: true,
          },
        ],
      },
      relations: [
        {
          id: 'rel',
          name: '设备归属',
          fromClassId: 'c',
          toClassId: 'c2',
          cardinality: 'MANY_TO_ONE',
        },
      ],
    })
    await choose(wrapper)
    expect(wrapper.findAll('.edge-line').length).toBeGreaterThanOrEqual(3)
    await wrapper.get('[data-mapping-id="rm"]').trigger('click')
    expect(wrapper.text()).toContain('连接键：from_id ↔ to_id')
    expect(wrapper.text()).toContain('${from_id}')
    expect(wrapper.findAll('button').some((button) => button.text() === '影响 / 编辑此映射')).toBe(
      false,
    )
  })
})
