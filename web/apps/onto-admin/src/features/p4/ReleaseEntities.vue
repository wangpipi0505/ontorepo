<script setup lang="ts">
/** 实体从已核验的不可变 bundle 投影读取；历史版本仍可管理浏览。 */
import { ref, watch } from 'vue'
import type {
  ReleaseEntity,
  ReleaseEntityKind,
} from '../../../../../packages/api-client/src/release'
import { useSession } from '../../stores/session'
import { useResource } from '../../composables/useResource'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
const props = defineProps<{ releaseId: string }>(),
  api = useSession().api
const kind = ref<ReleaseEntityKind | ''>(''),
  state = useResource<ReleaseEntity[]>([])
const kinds: ReleaseEntityKind[] = [
  'CLASS',
  'PROPERTY',
  'RELATION',
  'OPERATION',
  'RULE',
  'STRATEGY',
  'KNOWLEDGE',
  'MAPPING',
]
function load() {
  return state.load(() =>
    api.get<ReleaseEntity[]>(
      `/releases/${props.releaseId}/entities${kind.value ? `?kind=${kind.value}` : ''}`,
    ),
  )
}
watch(
  () => `${props.releaseId}:${kind.value}`,
  () => {
    state.reset()
    void load()
  },
  { immediate: true },
)
</script>
<template>
  <section class="stack">
    <h3>不可变实体投影</h3>
    <label>
      实体类别
      <select v-model="kind">
        <option value="">全部类别</option>
        <option v-for="item in kinds" :key="item" :value="item">{{ item }}</option>
      </select>
    </label>
    <AsyncState
      :loading="state.loading.value"
      :error="state.error.value"
      :empty="!state.data.value.length"
      empty-text="此版本无匹配实体。"
      @retry="load"
    >
      <details v-for="item in state.data.value" :key="`${item.kind}:${item.key}`" class="record">
        <summary>{{ item.kind }} · {{ item.key }}</summary>
        <pre>{{ JSON.stringify(item.value, null, 2) }}</pre>
      </details>
    </AsyncState>
  </section>
</template>
