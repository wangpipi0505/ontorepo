// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { setAuthorizedSession } from '../../test/authorizedSession'
import ElementPlus from 'element-plus'
import { ApiError, type Candidate, type ImportPreview, type Space } from '@onto/api-client'
import { useSession } from '../../stores/session'
import { useWorkspace } from '../../stores/workspace'
import ImportsView from './ImportsView.vue'
import CandidateChecksPanel from './CandidateChecksPanel.vue'
import CandidatesView from '../../views/CandidatesView.vue'
const space = { id: 's', role: 'OWNER', status: 'ACTIVE', draftRev: 5 } as Space
const candidate = {
  id: 'c',
  draftRev: 5,
  sha256: 'frozen-sha',
  createdAt: '2026-10-20T00:00:00Z',
} as Candidate
const preview: ImportPreview = {
  id: 'preview',
  status: 'PREVIEW',
  targetDraftRev: 5,
  added: 2,
  updated: 0,
  errors: [],
  conflicts: [],
  unsupported: [],
}
const global = { plugins: [ElementPlus], stubs: { teleport: true, ElSelect: true, ElOption: true } }
function button(wrapper: VueWrapper, text: string) {
  return wrapper.findAll('button').find((item) => item.text() === text)!
}
describe('P1 导入确认与冻结候选边界', () => {
  beforeEach(() => {
    setAuthorizedSession()
    useWorkspace().space = { ...space }
    document.body.innerHTML = ''
  })
  it('导入预览不写草稿，只有显式人工确认才应用预览修订', async () => {
    const request = vi
      .spyOn(useSession().api, 'post')
      .mockImplementation(
        async <T>(path: string): Promise<T> =>
          (path.endsWith('/preview') ? preview : { ...space, draftRev: 6 }) as T,
      )
    const wrapper = mount(ImportsView, { global, attachTo: document.body })
    await wrapper.get('textarea').setValue('@prefix ex: <https://example.org/> .')
    wrapper.get<HTMLButtonElement>('button[type=submit]').element.click()
    await flushPromises()
    expect(request).toHaveBeenCalledTimes(1)
    expect(request).toHaveBeenCalledWith('/spaces/s/imports/preview', {
      kind: 'TTL',
      content: '@prefix ex: <https://example.org/> .',
      expectedDraftRev: 5,
    })
    expect(useWorkspace().space?.draftRev).toBe(5)
    expect(button(wrapper, '确认应用导入').attributes('disabled')).toBeDefined()
    await wrapper.get('input[type=checkbox]').setValue(true)
    await button(wrapper, '确认应用导入').trigger('click')
    await flushPromises()
    expect(request).toHaveBeenCalledWith('/imports/preview/apply', { expectedDraftRev: 5 })
    expect(useWorkspace().space?.draftRev).toBe(6)
    wrapper.unmount()
  })
  it('修改原文撤销预览确认，409保留原文并暂停写入', async () => {
    const request = vi.spyOn(useSession().api, 'post').mockResolvedValue(preview)
    const wrapper = mount(ImportsView, { global })
    await wrapper.get('textarea').setValue('原始内容')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await wrapper.get('input[type=checkbox]').setValue(true)
    await wrapper.get('textarea').setValue('修改后的原文')
    expect(wrapper.find('input[type=checkbox]').exists()).toBe(false)
    request.mockRejectedValueOnce(new ApiError('DRAFT_REV_CONFLICT', '草稿已更新', 409))
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get<HTMLTextAreaElement>('textarea').element.value).toBe('修改后的原文')
    expect(useWorkspace().stale).toBe(true)
    expect(wrapper.text()).toContain('预览修订已过期')
    wrapper.unmount()
  })
  it('语法失败预览永远不能应用', async () => {
    const request = vi.spyOn(useSession().api, 'post').mockResolvedValue({
      ...preview,
      status: 'FAILED',
      errors: [{ line: 1, message: '语法错误' }],
    })
    const wrapper = mount(ImportsView, { global })
    await wrapper.get('textarea').setValue('非法内容')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(button(wrapper, '确认应用导入').attributes('disabled')).toBeDefined()
    expect(wrapper.get('input[type=checkbox]').attributes('disabled')).toBeDefined()
    expect(request).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
  it('旧候选操作读取失败不回退草稿操作清单', async () => {
    const read = vi
      .spyOn(useSession().api, 'get')
      .mockImplementation(async <T>(path: string): Promise<T> => {
        if (path === '/spaces/s/candidates') return [candidate] as T
        if (path === '/spaces/s') return space as T
        throw new ApiError('CANDIDATE_OPERATIONS_UNAVAILABLE', '冻结操作不可读', 404)
      })
    const wrapper = mount(CandidatesView, { global })
    await flushPromises()
    expect(read).toHaveBeenCalledWith('/candidates/c/operations')
    expect(read.mock.calls.some(([path]) => path.startsWith('/operations?'))).toBe(false)
    expect(wrapper.text()).toContain('冻结操作不可读')
    wrapper.unmount()
  })
  it('检查结果摘要错误时展示失败，不显示成功门禁', async () => {
    vi.spyOn(useSession().api, 'post').mockResolvedValue({
      candidateSha256: 'wrong-sha',
      eligible: true,
      checks: [],
    })
    const wrapper = mount(CandidateChecksPanel, { props: { candidate }, global })
    await button(wrapper, '运行冲突 / 一致性 / 用例门禁').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('摘要与当前候选不符')
    expect(wrapper.text()).not.toContain('初期检查通过')
    wrapper.unmount()
  })
  it('OWNER真实超范围允许留痕豁免并重新运行检查', async () => {
    const check = {
      candidateSha256: 'frozen-sha',
      eligible: false,
      checks: [
        {
          kind: 'CONSISTENCY',
          conclusion: 'NOT_CHECKED',
          reason: 'SUBSET_EXCEEDED',
          message: '超范围',
          details: {},
        },
      ],
    }
    const request = vi.spyOn(useSession().api, 'post').mockImplementation(
      async <T>(path: string): Promise<T> =>
        (path.endsWith('/waivers')
          ? {
              id: 'w',
              candidateSha256: 'frozen-sha',
              kind: 'SUBSET_EXCEEDED',
              reason: '保留人工审查',
              approver: 'owner',
            }
          : check) as T,
    )
    const wrapper = mount(CandidateChecksPanel, { props: { candidate }, global })
    await button(wrapper, '运行冲突 / 一致性 / 用例门禁').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('form')).toHaveLength(1)
    await wrapper.get('textarea').setValue('保留人工审查')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(request).toHaveBeenCalledWith('/candidates/c/waivers', {
      kind: 'SUBSET_EXCEEDED',
      reason: '保留人工审查',
    })
    expect(request.mock.calls.filter(([path]) => path.endsWith('/checks'))).toHaveLength(2)
    wrapper.unmount()
  })
  it('VIEWER不发起候选写操作', async () => {
    useWorkspace().space = { ...space, role: 'VIEWER' }
    const request = vi.spyOn(useSession().api, 'post')
    const wrapper = mount(CandidateChecksPanel, { props: { candidate }, global })
    expect(wrapper.find('button').exists()).toBe(false)
    expect(wrapper.find('form').exists()).toBe(false)
    expect(request).not.toHaveBeenCalled()
    wrapper.unmount()
  })
})
