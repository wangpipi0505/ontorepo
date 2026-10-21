import { getCurrentScope, onScopeDispose, ref } from 'vue'
import { ApiError } from '@onto/api-client'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useWorkspace } from '../stores/workspace'
import { errorMessage } from './useResource'
/** 写请求的后续副作用需先检查此上下文，避免旧成功响应回写已重新加载的同名空间。 */
interface MutationContext {
  isCurrent: () => boolean
}
/** 单表单串行提交；冲突仅暂停仍有效的发起上下文，已离开的页面不能污染新空间。 */
export function useMutation() {
  const workspace = useWorkspace()
  const pending = ref(false)
  const error = ref('')
  let active = true
  // 非组件单元测试可直接调用；实际页面销毁时即失去处理迟到响应的资格。
  if (getCurrentScope())
    onScopeDispose(() => {
      active = false
    })
  async function run(action: (context: MutationContext) => Promise<void>) {
    if (pending.value || !active) return false
    const workspaceContext = workspace.captureContext()
    const isCurrent = () => active && workspace.isCurrentContext(workspaceContext)
    pending.value = true
    error.value = ''
    try {
      await action({ isCurrent })
      return isCurrent()
    } catch (cause) {
      if (!isCurrent()) return false
      error.value = errorMessage(cause)
      if (cause instanceof ApiError && cause.code === 'DRAFT_REV_CONFLICT') {
        workspace.markStale(workspaceContext)
        error.value += ' 输入可复制保留。请关闭并刷新本页，再重新打开编辑以载入最新内容。'
      }
      return false
    } finally {
      pending.value = false
    }
  }
  return { pending, error, run }
}
export async function confirmAction(message: string) {
  try {
    await ElMessageBox.confirm(message, '确认操作', {
      confirmButtonText: '确认',
      cancelButtonText: '取消',
      type: 'warning',
    })
    return true
  } catch {
    return false
  }
}
export function notifySaved() {
  ElMessage.success('已保存')
}
