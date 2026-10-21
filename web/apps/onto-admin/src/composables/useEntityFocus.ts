import { computed, inject, getCurrentInstance, nextTick, watch, type Ref } from 'vue'
import { routeLocationKey } from 'vue-router'
/** 定位是只读展示动作，VIEWER 也可查看；找不到时明确提示，绝不自动新建或打开编辑器。 */
export function useEntityFocus<T extends { id: string; name: string }>(
  items: Ref<T[]>,
  loading: Ref<boolean>,
) {
  const route = getCurrentInstance() ? inject(routeLocationKey, undefined) : undefined
  const id = computed(() => (typeof route?.query.entityId === 'string' ? route.query.entityId : ''))
  const item = computed(() => items.value.find((entry) => entry.id === id.value))
  const message = computed(() =>
    !id.value || loading.value
      ? ''
      : item.value
        ? `已定位：${item.value.name}`
        : '关联条目已删除或已变更，请返回冲突检查重新检查当前草稿。',
  )
  const expandedKeys = computed(() => (item.value ? [item.value.id] : []))
  function rowClassName({ row }: { row: T }) {
    return row.id === id.value ? 'entity-focused-row' : ''
  }
  watch(
    item,
    async (value) => {
      if (!value) return
      await nextTick()
      document
        .querySelector<HTMLElement>('.entity-focused-row')
        ?.scrollIntoView?.({ block: 'center', behavior: 'smooth' })
    },
    { flush: 'post' },
  )
  return { id, item, message, expandedKeys, rowClassName }
}
