import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import type { Space } from '@onto/api-client'
import { useSession } from './session'
/** 一次工作区进入/重载的身份；同一空间离开后再次进入也必须取得新代次。 */
interface WorkspaceContext {
  readonly spaceId: string | null
  readonly generation: number
}
/** 路由切换使用序号隔离旧请求；写入返回的新修订是下一次写入的唯一基线。 */
export const useWorkspace = defineStore('workspace', () => {
  const space = ref<Space | null>(null)
  const stale = ref(false)
  const writePermission = ref('space.write')
  let sequence = 0
  let contextGeneration = 0
  // 同步观察 ID 变化，避免同一事件循环中的 A→B→A 被批处理合并成“未切换”。
  watch(
    () => space.value?.id ?? null,
    () => {
      contextGeneration++
    },
    { flush: 'sync' },
  )
  function canWriteFor(permission: string) {
    return (
      !stale.value &&
      space.value?.status === 'ACTIVE' &&
      space.value.role !== 'VIEWER' &&
      useSession().has(permission)
    )
  }
  const canWrite = computed(() => canWriteFor(writePermission.value))
  async function load(id: string) {
    const ticket = ++sequence
    contextGeneration++
    if (space.value?.id !== id) space.value = null
    const result = await useSession().api.get<Space>(`/spaces/${id}`)
    if (ticket === sequence) {
      space.value = result
      stale.value = false
    }
  }
  function accept(value: Space) {
    if (space.value?.id === value.id) space.value = value
  }
  /** 页面完整实体与空间重读成功后恢复写权限，局部属性刷新不能解锁。 */
  function refreshed(value: Space) {
    if (space.value?.id === value.id) {
      space.value = value
      stale.value = false
    }
  }
  function captureContext(): WorkspaceContext {
    return { spaceId: space.value?.id ?? null, generation: contextGeneration }
  }
  function isCurrentContext(context: WorkspaceContext) {
    return context.generation === contextGeneration && context.spaceId === (space.value?.id ?? null)
  }
  function markStale(context: WorkspaceContext) {
    // 旧请求只能暂停其发起时的工作区；没有空间的管理页不写全局草稿状态。
    if (context.spaceId && isCurrentContext(context)) stale.value = true
  }
  function clear() {
    sequence++
    contextGeneration++
    space.value = null
    stale.value = false
  }
  return {
    space,
    stale,
    canWrite,
    canWriteFor,
    writePermission,
    load,
    accept,
    refreshed,
    captureContext,
    isCurrentContext,
    markStale,
    clear,
  }
})
