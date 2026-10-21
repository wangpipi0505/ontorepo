import { ref } from 'vue'
import { ApiError } from '@onto/api-client'
import { useSession } from '../../stores/session'
import { useMutation } from '../../composables/useMutation'
/** 系统修订冲突保留输入但锁住保存，只有重新读取并打开表单才能取得新基线。 */
export function useSystemEditor(permission: string) {
  const session = useSession(),
    mutation = useMutation(),
    stale = ref(false)
  async function save(action: () => Promise<unknown>, done: () => void) {
    if (!session.has(permission) || stale.value) return
    await mutation.run(async ({ isCurrent }) => {
      try {
        await action()
        if (!isCurrent()) return
        // 管理写入已提交后先收起表单，防止权限刷新失败导致误重提成功的写操作。
        done()
        await session.refreshPermissions()
      } catch (cause) {
        if (isCurrent() && cause instanceof ApiError && cause.code === 'IAM_REVISION_CONFLICT')
          stale.value = true
        throw cause
      }
    })
  }
  return { session, mutation, stale, save }
}
