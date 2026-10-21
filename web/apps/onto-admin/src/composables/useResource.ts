import { onScopeDispose, ref, shallowRef } from 'vue'
import { ApiError } from '@onto/api-client'
/** 高频错误码的可操作解释；只追加提示，不隐藏服务端原始消息。 */
const errorHints: Record<string, string> = {
  ENDPOINT_FORBIDDEN: '该服务端点未授权或已停用',
  CONTRACT_NOT_AVAILABLE: '缺少可发布的操作契约，请先维护助手声明',
  CLASS_OBJECT_MISSING: '源资源尚未建立对象映射',
  REQUIRED_PROPERTY_MISSING: '必填属性尚未建立映射',
  MAPPING_REFERENCE_INVALID: '映射引用的对象/属性已变更，请重新检查映射',
  REVISION_CONFLICT: '草稿已被其他编辑更新，请刷新后重试',
  UNSUPPORTED_DIALECT: '当前数据源方言尚未接入',
  AUTHENTICATION_FAILED: '数据源认证失败',
  QUERY_TIMEOUT: '查询超时，请缩小范围或提高超时上限',
  SUBSET_EXCEEDED: '模型超出 OWL 2 EL，需 OWNER 针对候选复核豁免',
}
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
  if (cause instanceof ApiError) {
    const hint = errorHints[cause.code]
    return hint ? `${cause.message}（${hint}）` : cause.message
  }
  return cause instanceof Error ? cause.message : '操作失败，请重试。'
}
