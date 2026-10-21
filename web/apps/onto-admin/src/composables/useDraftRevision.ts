import { ref } from 'vue'
import { ApiError } from '@onto/api-client'

/** 编辑基线只在打开表单时建立；409后旧输入可复制，但不能自动套用新修订覆盖他人修改。 */
export function useDraftRevision() {
  const stale = ref(false)
  let revision = 0
  function start(value: number) {
    revision = value
    stale.value = false
  }
  async function submit<T>(action: (expectedDraftRev: number) => Promise<T>): Promise<T> {
    if (stale.value)
      throw new ApiError(
        'DRAFT_FORM_STALE',
        '此表单内容已过期。请关闭并刷新本页，再重新打开编辑以载入最新内容。',
        409,
      )
    try {
      return await action(revision)
    } catch (cause) {
      if (cause instanceof ApiError && cause.code === 'DRAFT_REV_CONFLICT') stale.value = true
      throw cause
    }
  }
  return { stale, start, submit }
}
