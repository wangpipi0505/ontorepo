// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { setAuthorizedSession } from '../../test/authorizedSession'
import ElementPlus from 'element-plus'
import type { Space } from '@onto/api-client'
import { useSession } from '../../stores/session'
import { useWorkspace } from '../../stores/workspace'
import CodebooksView from './CodebooksView.vue'
import RelationsView from './RelationsView.vue'
import ConstraintsView from './ConstraintsView.vue'
import RulesView from './RulesView.vue'
import StrategiesView from './StrategiesView.vue'
import TestCasesView from './TestCasesView.vue'
import OperationDialog from '../../components/OperationDialog.vue'
import PropertyDialog from '../../components/PropertyDialog.vue'

// 下拉使用原生select隔离jsdom浮层限制；el-form、输入和提交按钮保持真实组件。
const select = defineComponent({
  props: { modelValue: [String, Array], multiple: Boolean },
  emits: ['update:modelValue'],
  setup(props, { emit, slots }) {
    return () =>
      h(
        'select',
        {
          multiple: props.multiple,
          value: props.modelValue,
          onChange: (event: Event) => {
            const target = event.target as HTMLSelectElement
            emit(
              'update:modelValue',
              props.multiple
                ? Array.from(target.selectedOptions, (item) => item.value)
                : target.value,
            )
          },
        },
        slots.default?.(),
      )
  },
})
const option = defineComponent({
  props: { value: String, label: String },
  setup(props) {
    return () => h('option', { value: props.value }, props.label)
  },
})
const space = { id: 's', role: 'OWNER', status: 'ACTIVE', draftRev: 5 } as Space
async function choose(wrapper: VueWrapper, index: number, value: string) {
  await wrapper.findAll('select')[index]!.setValue(value)
}
describe('P1 独立业务表单提交', () => {
  it('旧属性列表必须使用列表读取修订，不能借工作区新修订覆盖', async () => {
    const request = vi.spyOn(useSession().api, 'post').mockResolvedValue({ ...space, draftRev: 6 })
    const wrapper = mount(PropertyDialog, {
      props: { modelValue: false, item: null, classId: 'a', revision: 4 },
      attachTo: document.body,
      global: {
        plugins: [ElementPlus],
        stubs: { teleport: true, ElSelect: select, ElOption: option },
      },
    })
    await wrapper.setProps({ modelValue: true })
    await flushPromises()
    await wrapper.findAll('input[type=text]')[0]!.setValue('属性')
    wrapper.get<HTMLButtonElement>('button[type=submit]').element.click()
    await flushPromises()
    expect(request).toHaveBeenCalledWith(
      '/properties',
      expect.objectContaining({ expectedDraftRev: 4 }),
    )
    wrapper.unmount()
  })
  beforeEach(() => {
    setAuthorizedSession()
    useWorkspace().space = { ...space }
    document.body.innerHTML = ''
    vi.spyOn(useSession().api, 'get').mockImplementation(async <T>(path: string): Promise<T> => {
      const result: unknown =
        path === '/spaces/s'
          ? space
          : path.startsWith('/classes?')
            ? [
                { id: 'a', name: '设备' },
                { id: 'b', name: '部件' },
              ]
            : path === '/spaces/s/properties'
              ? [{ id: 'p', name: '功率' }]
              : path.startsWith('/operations?')
                ? [{ id: 'o', name: '查询' }]
                : []
      return result as T
    })
  })
  it.each([
    {
      name: '码表',
      component: CodebooksView,
      path: '/codebooks',
      prepare: async (wrapper: VueWrapper) => {
        await wrapper.findAll('input[type=text]')[0]!.setValue('units')
        await wrapper.findAll('input[type=text]')[1]!.setValue('示例')
        await wrapper.get('textarea').setValue('kg=千克')
      },
      expected: { code: 'units', entries: [{ code: 'kg', label: '千克' }] },
    },
    {
      name: '关系',
      component: RelationsView,
      path: '/relations',
      prepare: async (wrapper: VueWrapper) => {
        await wrapper.findAll('input[type=text]')[0]!.setValue('示例')
        await choose(wrapper, 0, 'a')
        await choose(wrapper, 1, 'b')
      },
      expected: { fromClassId: 'a', toClassId: 'b', cardinality: 'ONE_TO_MANY' },
    },
    {
      name: '约束',
      component: ConstraintsView,
      path: '/constraints',
      prepare: async (wrapper: VueWrapper) => {
        await wrapper.findAll('input[type=text]')[0]!.setValue('示例')
        await choose(wrapper, 1, 'p')
      },
      expected: { kind: 'RANGE', targetType: 'PROPERTY', targetRef: 'p', expr: { min: 0, max: 1 } },
    },
    {
      name: '规则',
      component: RulesView,
      path: '/rules',
      prepare: async (wrapper: VueWrapper) => {
        await wrapper.findAll('input[type=text]')[0]!.setValue('示例')
        await choose(wrapper, 0, 'a')
      },
      expected: {
        kind: 'VALIDATION',
        applicableClassId: 'a',
        action: { type: 'REJECT', message: '' },
        scoring: null,
      },
    },
    {
      name: '策略',
      component: StrategiesView,
      path: '/strategies',
      prepare: async (wrapper: VueWrapper) => {
        await wrapper.findAll('input[type=text]')[0]!.setValue('示例')
      },
      expected: {
        policy: {
          maxRows: 100,
          timeoutMs: 3000,
          resultLimit: 100,
          failureMode: 'FAIL',
          readScope: null,
        },
      },
    },
    {
      name: '用例',
      component: TestCasesView,
      path: '/test-cases',
      prepare: async (wrapper: VueWrapper) => {
        await wrapper.findAll('input[type=text]')[0]!.setValue('示例')
        await wrapper.findAll('textarea')[0]!.setValue('列出对象')
        await choose(wrapper, 0, 'o')
      },
      expected: { operationId: 'o', matchMode: 'EXACT', input: {}, expected: {}, enabled: true },
    },
  ])(
    '$name保持唯一form并提交结构化字段和打开时修订',
    async ({ component, path, prepare, expected }) => {
      const request = vi
        .spyOn(useSession().api, 'post')
        .mockResolvedValue({ ...space, draftRev: 6 })
      const wrapper = mount(component, {
        attachTo: document.body,
        global: {
          plugins: [ElementPlus],
          stubs: { teleport: true, ElSelect: select, ElOption: option },
        },
      })
      await flushPromises()
      await wrapper
        .findAll('button')
        .find((button) => button.text().startsWith('新建'))!
        .trigger('click')
      await flushPromises()
      expect(wrapper.findAll('form')).toHaveLength(1)
      expect(wrapper.find('form form').exists()).toBe(false)
      await prepare(wrapper)
      wrapper.get<HTMLButtonElement>('button[type=submit]').element.click()
      await flushPromises()
      expect(request).toHaveBeenCalledWith(
        path,
        expect.objectContaining({ ...expected, spaceId: 's', expectedDraftRev: 5 }),
      )
      wrapper.unmount()
    },
  )
  it.each(['OBJECT_QUERY', 'RELATION_QUERY', 'RULE_CHECK', 'SCORING'] as const)(
    '四类操作 %s 提交规则策略绑定，规则执行不要求SELECT',
    async (kind) => {
      vi.spyOn(useSession().api, 'get').mockImplementation(async <T>(path: string): Promise<T> => {
        const value: unknown =
          path === '/spaces/s'
            ? space
            : path.startsWith('/rules?')
              ? [{ id: 'r', name: '冻结规则' }]
              : path.startsWith('/strategies?')
                ? [{ id: 't', name: '资源策略' }]
                : []
        return value as T
      })
      const request = vi
        .spyOn(useSession().api, 'post')
        .mockResolvedValue({ ...space, draftRev: 6 })
      const wrapper = mount(OperationDialog, {
        props: { modelValue: false, item: null },
        attachTo: document.body,
        global: {
          plugins: [ElementPlus],
          stubs: { teleport: true, ElSelect: select, ElOption: option },
        },
      })
      await wrapper.setProps({ modelValue: true })
      await flushPromises()
      await wrapper.findAll('input[type=text]')[0]!.setValue('test_operation')
      await wrapper.findAll('input[type=text]')[1]!.setValue('测试操作')
      await choose(wrapper, 0, kind)
      await choose(wrapper, 1, 't')
      await wrapper.findAll('select')[2]!.setValue(['r'])
      wrapper.get<HTMLButtonElement>('button[type=submit]').element.click()
      await flushPromises()
      expect(request).toHaveBeenCalledWith(
        '/operations',
        expect.objectContaining({ kind, strategyRef: 't', ruleIds: ['r'], expectedDraftRev: 5 }),
      )
      const body = request.mock.calls[0]?.[1] as { queryTemplate: string }
      if (kind === 'RULE_CHECK' || kind === 'SCORING') expect(body.queryTemplate).toBe('')
      wrapper.unmount()
    },
  )
})
