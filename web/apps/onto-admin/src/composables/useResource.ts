import { onScopeDispose, ref, shallowRef } from 'vue'
import { ApiError } from '@onto/api-client'
/** 最新请求获胜：切对象、切空间或离开页面后，迟到结果和错误均不能覆盖当前界面。 */
export function useResource<T>(initial: T) {
  const data = shallowRef<T>(initial)
  const loading = ref(false)
  const error = ref('')
  let sequence = 0
  function reset() {
    sequence++
    data.value = initial
    error.value = ''
    loading.value = false
  }
  async function load(loader: () => Promise<T>) {
    const ticket = ++sequence
    data.value = initial
    loading.value = true
    error.value = ''
    try {
      const result = await loader()
      if (ticket === sequence) {
        data.value = result
        return result
      }
    } catch (cause) {
      if (ticket === sequence) error.value = errorMessage(cause)
    } finally {
      if (ticket === sequence) loading.value = false
    }
  }
  onScopeDispose(reset)
  return { data, loading, error, load, reset }
}
export function errorMessage(cause: unknown) {
  return cause instanceof ApiError || cause instanceof Error ? cause.message : '操作失败，请重试。'
}
