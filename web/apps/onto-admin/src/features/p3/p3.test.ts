// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h, type Component } from 'vue'
import { setAuthorizedSession } from '../../test/authorizedSession'
import ElementPlus from 'element-plus'
import { ApiClient } from '@onto/api-client'
import { router } from '../../router'

const modules = import.meta.glob<{ default: Component }>('./*.vue')
const wrappers: VueWrapper[] = []
// 与既有 P2 测试一致：仅隔离 jsdom 不支持的下拉浮层，保留真实业务表单。
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
beforeEach(() => setAuthorizedSession())
async function form(name: string, props: Record<string, unknown>) {
  const load = modules[`./${name}.vue`]
  expect(load, `${name} 应是独立组件`).toBeDefined()
  const wrapper = mount((await load!()).default, {
    props,
    global: {
      plugins: [ElementPlus],
      stubs: { teleport: true, ElSelect: select, ElOption: option },
    },
  })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.restoreAllMocks()
})
describe('P3 数据管理关键反例', () => {
  it('受管文件上传保留 FormData 和浏览器 multipart boundary', async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValue(new Response(JSON.stringify({ code: 'OK', data: {} })))
    const body = new FormData()
    body.append('file', new File(['id,name\n1,a'], 'devices.csv'))
    await new ApiClient(
      () => 'token',
      () => {},
      fetcher,
    ).post('/data-files?spaceId=s&expectedDraftRev=5', body)
    const options = fetcher.mock.calls[0]![1]!
    expect(options.body).toBe(body)
    expect(new Headers(options.headers).has('Content-Type')).toBe(false)
  })
  it('五个数据工作台页面均有可直接访问路由', () => {
    const paths = router.getRoutes().map((route) => route.path)
    for (const path of ['datasources', 'mappings', 'data-query', 'suggestions', 'data-catalog'])
      expect(paths).toContain(`/spaces/:spaceId/${path}`)
  })
  it('编辑源不回显凭据，未明确更改连接时提交 null', async () => {
    const wrapper = await form('SourceForm', {
      value: {
        id: 'd',
        code: 'devices',
        name: '设备库',
        kind: 'RELATIONAL',
        accessScope: ['public.devices'],
      },
      canWrite: true,
      pending: false,
      stale: false,
    })
    expect(
      wrapper
        .findAll<HTMLInputElement>('input[type=password]')
        .every((item) => item.element.value === ''),
    ).toBe(true)
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('save')?.[0]?.[0]).toMatchObject({ connection: null, code: 'devices' })
  })
  it('新源缺连接配置时禁止写入并显示可恢复错误', async () => {
    const wrapper = await form('SourceForm', {
      value: null,
      canWrite: true,
      pending: false,
      stale: false,
    })
    await wrapper.findAll('input')[0]!.setValue('devices')
    await wrapper.findAll('input')[1]!.setValue('设备库')
    await wrapper.get('textarea').setValue('public.devices')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('save')).toBeUndefined()
    expect(wrapper.text()).toContain('JDBC 地址必填')
  })
  it('候选默认不接受，拒绝不要求名称，来源摘录可见', async () => {
    const wrapper = await form('SuggestionReview', {
      items: [
        {
          id: 'q',
          batchId: 'b',
          sourceKind: 'DOCUMENT',
          kind: 'CLASS',
          payload: { name: '设备' },
          confidence: 0.7,
          location: { page: 1, excerpt: '设备用于测量。' },
          status: 'PENDING',
        },
      ],
      canWrite: true,
      pending: false,
      stale: false,
    })
    expect(wrapper.text()).toContain('设备用于测量。')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('decide')).toBeUndefined()
    expect(wrapper.text()).toContain('选择')
  })
  it('实例查询必须拒绝不属于当前对象映射的关系映射', async () => {
    const wrapper = await form('DataQueryForm', {
      value: {
        mode: 'RELATION',
        objectMappingId: 'o',
        relationMappingId: 'foreign',
        propertyIds: ['p'],
        filters: [],
      },
      mappings: {
        objects: [{ id: 'o', sourceResource: 'devices', targetClassId: 'c' }],
        properties: [{ objectMappingId: 'o', targetPropertyId: 'p' }],
        relations: [],
      },
      properties: [{ id: 'p', name: '名称' }],
      inputParams: [],
      canWrite: true,
      pending: false,
      stale: false,
    })
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('save')).toBeUndefined()
    expect(wrapper.text()).toContain('关系映射')
  })
  it('零覆盖分母不显示全部完成，并展示定位问题', async () => {
    const wrapper = await form('MappingCoverage', {
      value: {
        valid: false,
        coverage: { mapped: 0, pending: 0, unmappable: 0 },
        coverageBasis: '当前活动类与属性',
        affectedOperations: [],
        checks: [
          {
            kind: 'CLASS_OBJECT',
            passed: false,
            issues: [{ code: 'MISSING', message: '缺少对象映射', entityId: 'class-a' }],
          },
        ],
      },
    })
    expect(wrapper.text()).toContain('无可统计项')
    expect(wrapper.text()).toContain('class-a')
    expect(wrapper.text()).not.toContain('100%')
  })
  it('候选批量拒绝可保留空名称且不提交载荷', async () => {
    const wrapper = await form('SuggestionReview', {
      items: [
        {
          id: 'q',
          batchId: 'b',
          sourceKind: 'FILE',
          kind: 'CLASS',
          payload: { name: '' },
          confidence: 0.6,
          location: { sourceName: 'devices.csv', column: 'id' },
          status: 'PENDING',
        },
      ],
      canWrite: true,
      pending: false,
      stale: false,
    })
    await wrapper.get('input[type=checkbox]').setValue(true)
    await wrapper
      .findAll('button')
      .find((item) => item.text() === '将勾选项设为拒绝')!
      .trigger('click')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('decide')?.[0]?.[0]).toEqual([{ id: 'q', decision: 'REJECT' }])
  })
  it('过期候选表单保留编辑名称且不提交', async () => {
    const wrapper = await form('SuggestionReview', {
      items: [
        {
          id: 'q',
          batchId: 'b',
          sourceKind: 'TABLE',
          kind: 'CLASS',
          payload: { name: '原名' },
          confidence: 0.8,
          location: { sourceName: 'devices', resource: 'public.devices' },
          status: 'PENDING',
        },
      ],
      canWrite: true,
      pending: false,
      stale: false,
    })
    await wrapper.get('input:not([type=checkbox])').setValue('我编辑的名称')
    await wrapper.get('select').setValue('ACCEPT')
    await wrapper.setProps({ stale: true })
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('decide')).toBeUndefined()
    expect(wrapper.get<HTMLInputElement>('input:not([type=checkbox])').element.value).toBe(
      '我编辑的名称',
    )
  })
  it('上传前拒绝可执行扩展名和超过 8 MiB 文件', async () => {
    const wrapper = await form('ManagedUpload', {
      canWrite: true,
      pending: false,
      stale: false,
      files: [],
    })
    const input = wrapper.get<HTMLInputElement>('input[type=file]')
    Object.defineProperty(input.element, 'files', {
      value: [new File(['x'], 'script.exe')],
      configurable: true,
    })
    await input.trigger('change')
    expect(wrapper.emitted('upload')).toBeUndefined()
    expect(wrapper.text()).toContain('仅允许')
    const huge = new File(['x'], 'big.csv')
    Object.defineProperty(huge, 'size', { value: 8 * 1024 * 1024 + 1 })
    Object.defineProperty(input.element, 'files', { value: [huge], configurable: true })
    await input.trigger('change')
    expect(wrapper.emitted('upload')).toBeUndefined()
    expect(wrapper.text()).toContain('不能超过 8 MiB')
  })
  it('连接密码保留用户输入的首尾空格', async () => {
    const wrapper = await form('SourceForm', {
      value: null,
      canWrite: true,
      pending: false,
      stale: false,
    })
    const field = (label: string) =>
      wrapper
        .findAll('.el-form-item')
        .find((item) => item.find('.el-form-item__label').text() === label)!
    await field('源编码').get('input').setValue('devices')
    await field('源名称').get('input').setValue('设备库')
    await field('访问范围（资源名，每行一个）').get('textarea').setValue('public.devices')
    await field('JDBC 地址').get('input').setValue('jdbc:postgresql://localhost/onto')
    await field('用户名').get('input').setValue('onto')
    await field('密码').get('input').setValue(' secret ')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('save')?.[0]?.[0]).toMatchObject({
      connection: { password: ' secret ' },
    })
  })
  it('受管文件的访问范围必须精确匹配服务端资源名', async () => {
    const wrapper = await form('SourceForm', {
      value: null,
      files: [{ id: 'f', name: 'devices.json', resource: 'safe.json' }],
      canWrite: true,
      pending: false,
      stale: false,
    })
    await wrapper.findAll('input')[0]!.setValue('devices')
    await wrapper.findAll('input')[1]!.setValue('设备库')
    await wrapper.get('select').setValue('FILE')
    await flushPromises()
    await wrapper.findAll('select')[2]!.setValue('f')
    await flushPromises()
    await wrapper.get('textarea').setValue('other.json')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.emitted('save')).toBeUndefined()
    expect(wrapper.text()).toContain('访问范围')
  })
})
