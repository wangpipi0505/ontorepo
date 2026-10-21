<script setup lang="ts">
import { ref, watch } from 'vue'
import type { Release, ReleaseDiff } from '../../../../../packages/api-client/src/release'
import { useSession } from '../../stores/session'
import { useResource } from '../../composables/useResource'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import ProjectionDiff from './ProjectionDiff.vue'
const props = defineProps<{ releases: Release[]; spaceId: string }>(),
  api = useSession().api
const from = ref(''),
  to = ref(''),
  state = useResource<ReleaseDiff | null>(null)
watch(
  () => props.spaceId,
  () => {
    from.value = ''
    to.value = ''
    state.reset()
  },
)
watch([from, to], () => state.reset())
function compare() {
  if (!from.value || !to.value || from.value === to.value) return
  const fromId = from.value,
    toId = to.value
  return state.load(async () => {
    const value = await api.get<ReleaseDiff>(
      `/releases/diff?spaceId=${props.spaceId}&fromReleaseId=${fromId}&toReleaseId=${toId}`,
    )
    if (value.fromReleaseId !== fromId || value.toReleaseId !== toId)
      throw new Error('差异响应版本不符，请重试比较。')
    return value
  })
}
</script>
<template>
  <section class="panel stack">
    <h2>版本投影差异</h2>
    <div class="form-columns">
      <label>
        原版本
        <select v-model="from">
          <option value="">请选择</option>
          <option v-for="item in releases" :key="item.id" :value="item.id">
            v{{ item.versionNo }} · {{ item.status }}
          </option>
        </select>
      </label>
      <label>
        目标版本
        <select v-model="to">
          <option value="">请选择</option>
          <option v-for="item in releases" :key="item.id" :value="item.id">
            v{{ item.versionNo }} · {{ item.status }}
          </option>
        </select>
      </label>
    </div>
    <div>
      <el-button
        :disabled="!from || !to || from === to"
        :loading="state.loading.value"
        @click="compare"
      >
        比较版本投影
      </el-button>
    </div>
    <AsyncState :loading="state.loading.value" :error="state.error.value" @retry="compare">
      <ProjectionDiff v-if="state.data.value" :diff="state.data.value" />
    </AsyncState>
  </section>
</template>
