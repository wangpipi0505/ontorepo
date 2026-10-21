import { onScopeDispose, shallowRef, watch } from 'vue'
import type { Space } from '@onto/api-client'
import { useSession } from '../../stores/session'
import { errorMessage } from '../../composables/useResource'
import { loadOverview, type SpaceOverview } from './overview'

export interface OverviewState {
  loading: boolean
  error: string
  value: SpaceOverview | null
}
/** 仅加载当前页，最多两个空间同时读取；已读同修订卡片在本次列表会话内复用。 */
export function useOverviewCards(visible: () => Space[]) {
  const api = useSession().api
  const states = shallowRef<Record<string, OverviewState>>({})
  let active = 0,
    generation = 0,
    disposed = false
  const queue: { space: Space; generation: number }[] = []
  const key = (space: Space) => `${space.id}:${space.draftRev}`
  function set(space: Space, state: OverviewState) {
    states.value = { ...states.value, [key(space)]: state }
  }
  function pump() {
    while (active < 2 && queue.length) {
      const task = queue.shift()!
      if (
        task.generation !== generation ||
        !visible().some((item) => key(item) === key(task.space))
      ) {
        const next = { ...states.value }
        delete next[key(task.space)]
        states.value = next
        continue
      }
      active++
      void loadOverview(api, task.space.id)
        .then(
          (value) => {
            if (!disposed && task.generation === generation)
              set(task.space, { loading: false, error: '', value })
          },
          (cause: unknown) => {
            if (!disposed && task.generation === generation)
              set(task.space, { loading: false, error: errorMessage(cause), value: null })
          },
        )
        .finally(() => {
          active--
          if (!disposed) pump()
        })
    }
  }
  function ensure() {
    for (const space of visible()) {
      if (states.value[key(space)]) continue
      set(space, { loading: true, error: '', value: null })
      queue.push({ space, generation })
    }
    pump()
  }
  function retry(space: Space) {
    if (states.value[key(space)]?.loading) return
    const next = { ...states.value }
    delete next[key(space)]
    states.value = next
    ensure()
  }
  function reset() {
    generation++
    queue.length = 0
    states.value = {}
  }
  watch(() => visible().map(key).join('|'), ensure, { immediate: true })
  onScopeDispose(() => {
    disposed = true
    reset()
  })
  return { states, key, retry, reset, ensure }
}
