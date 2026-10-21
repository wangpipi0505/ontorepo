// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import type { Component } from 'vue'
import { setAuthorizedSession } from '../../test/authorizedSession'
import ElementPlus from 'element-plus'
import { ApiError, type CandidateTests, type Space } from '@onto/api-client'
import { useSession } from '../../stores/session'
import { useWorkspace } from '../../stores/workspace'

const components = import.meta.glob<{ default: Component }>('./*.vue')
const wrappers: VueWrapper[] = []
const candidate = { id: 'c', spaceId: 's', sha256: 'sha-c', draftRev: 5, status: 'TESTING' }
async function page(name: string, props: Record<string, unknown>) {
  expect(components[`./${name}.vue`], `${name} 应提供复用组件`).toBeDefined()
  const wrapper = mount((await components[`./${name}.vue`]()).default, {
    props,
    global: {
      plugins: [ElementPlus],
      renderStubDefaultSlot: true,
      stubs: { teleport: true, ElSelect: true, ElOption: true },
    },
  })
  wrappers.push(wrapper)
  await flushPromises()
  return wrapper
}
function runButton(wrapper: VueWrapper) {
  return wrapper.findAll('button').find((item) => item.text() === '运行冻结验收用例')!
}
beforeEach(() => {
  setAuthorizedSession()
  useWorkspace().space = { id: 's', draftRev: 5, role: 'OWNER', status: 'ACTIVE' } as Space
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.restoreAllMocks()
})
describe('C12 冻结执行入口', () => {
  it('完成通知在解除提交锁之后发出，父页面可安全刷新并卸载组件', async () => {
    vi.spyOn(useSession().api, 'post').mockResolvedValue({
      candidateSha256: 'sha-c',
      passed: true,
      results: [],
    })
    const events: string[] = []
    const wrapper = await page('CandidateTestsPanel', {
      candidate,
      enabled: true,
      onBusy: (value: boolean) => events.push(value ? 'busy' : 'idle'),
      onCompleted: () => events.push('completed'),
    })
    await runButton(wrapper).trigger('click')
    await flushPromises()
    expect(events).toEqual(['busy', 'idle', 'completed'])
  })
  it('409 冲突通知发布向导暂停旧候选操作，错误保留便于恢复', async () => {
    vi.spyOn(useSession().api, 'post').mockRejectedValue(
      new ApiError('CANDIDATE_CONFLICT', '候选状态已变化', 409),
    )
    const wrapper = await page('CandidateTestsPanel', { candidate, enabled: true })
    await runButton(wrapper).trigger('click')
    await flushPromises()
    expect(wrapper.emitted('conflict')).toHaveLength(1)
    expect(wrapper.text()).toContain('候选状态已变化')
  })
  it('批量测试只提交冻结候选，拒绝摘要不符的成功结果', async () => {
    const post = vi.spyOn(useSession().api, 'post').mockResolvedValue({
      candidateSha256: 'other',
      passed: true,
      results: [],
    })
    const wrapper = await page('CandidateTestsPanel', { candidate, enabled: true })
    await runButton(wrapper).trigger('click')
    await flushPromises()
    expect(post).toHaveBeenCalledWith('/candidates/c/tests', {})
    expect(wrapper.text()).toContain('摘要与当前候选不符')
    expect(wrapper.emitted('completed')).toBeUndefined()
  })
  it('切换候选后旧测试结果不得回写新候选', async () => {
    let resolve!: (value: CandidateTests) => void
    vi.spyOn(useSession().api, 'post').mockReturnValue(
      new Promise((done) => {
        resolve = done
      }),
    )
    const wrapper = await page('CandidateTestsPanel', { candidate, enabled: true })
    await runButton(wrapper).trigger('click')
    await wrapper.setProps({ candidate: { ...candidate, id: 'd', sha256: 'sha-d' } })
    resolve({
      candidateSha256: 'sha-c',
      passed: true,
      results: [{ caseId: 'x', name: '旧结果', passed: true, executionId: 'e', message: '' }],
    })
    await flushPromises()
    expect(wrapper.text()).not.toContain('旧结果')
    expect(wrapper.emitted('completed')).toBeUndefined()
  })
  it('VIEWER 不能通过批量入口发起执行', async () => {
    useWorkspace().space!.role = 'VIEWER'
    const post = vi.spyOn(useSession().api, 'post')
    const wrapper = await page('CandidateTestsPanel', { candidate, enabled: true })
    expect(runButton(wrapper).attributes('disabled')).toBeDefined()
    await runButton(wrapper).trigger('click')
    expect(post).not.toHaveBeenCalled()
  })
  it('规则试执行只列冻结操作中绑定目标规则的条目，绝不读取草稿操作', async () => {
    const read = vi.spyOn(useSession().api, 'get').mockResolvedValue([
      { id: 'bound', name: '冻结评分', code: 'score', kind: 'SCORING', ruleIds: ['rule'] },
      { id: 'unbound', name: '其他操作', code: 'other', kind: 'SCORING', ruleIds: [] },
    ])
    const wrapper = await page('FrozenOperationTrialPanel', { candidate, ruleId: 'rule' })
    expect(read).toHaveBeenCalledWith('/candidates/c/operations')
    expect(read.mock.calls.some(([path]) => path.startsWith('/operations?'))).toBe(false)
    expect(
      wrapper.findAllComponents({ name: 'ElOption' }).map((option) => option.props('value')),
    ).toEqual(['bound'])
  })
  it('冻结操作读取失败时显示错误，不使用草稿操作兜底', async () => {
    const read = vi.spyOn(useSession().api, 'get').mockRejectedValue(new Error('冻结内容不可读'))
    const wrapper = await page('FrozenOperationTrialPanel', { candidate, ruleId: 'rule' })
    expect(wrapper.text()).toContain('冻结内容不可读')
    expect(read).toHaveBeenCalledTimes(1)
  })
  it('规则试执行只发送所选冻结操作和业务输入，不覆盖 ruleIds', async () => {
    vi.spyOn(useSession().api, 'get').mockResolvedValue([
      { id: 'bound', name: '评分', code: 'score', kind: 'SCORING', ruleIds: ['rule'] },
    ])
    const post = vi.spyOn(useSession().api, 'post').mockResolvedValue({
      status: 'FAILED',
      errorCode: 'GUARD_ATTR_UNRESOLVED',
      steps: [],
      rows: [],
      columns: [],
    })
    const wrapper = await page('FrozenOperationTrialPanel', { candidate, ruleId: 'rule' })
    wrapper.getComponent({ name: 'ElSelect' }).vm.$emit('update:modelValue', 'bound')
    await wrapper.get('textarea').setValue('{"limit": 3}')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(post).toHaveBeenCalledWith('/candidates/c/trial', {
      operationId: 'bound',
      input: { limit: 3 },
    })
    expect(wrapper.text()).toContain('GUARD_ATTR_UNRESOLVED')
  })
})
