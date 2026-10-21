<script setup lang="ts">
/** 全局模块的本体选择页：模块独立于某一个空间，先选本体或全部，再进入明细。 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { Space } from '@onto/api-client'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import { useSession } from '../stores/session'
import { useResource } from '../composables/useResource'
const props = defineProps<{ title: string; note: string; base: string }>()
const router = useRouter(),
  api = useSession().api,
  spaces = useResource<Space[]>([]),
  keyword = ref('')
const visible = computed(() =>
  spaces.data.value.filter((space) =>
    `${space.name} ${space.code} ${space.domain || ''}`
      .toLocaleLowerCase()
      .includes(keyword.value.trim().toLocaleLowerCase()),
  ),
)
async function load() {
  await spaces.load(() => api.get<Space[]>('/spaces'))
}
onMounted(load)
</script>
<template>
  <section class="module-page">
    <header class="module-heading">
      <div>
        <h1>{{ title }}</h1>
        <p class="muted">{{ note }}</p>
      </div>
    </header>
    <section class="panel space-picker">
      <div class="row spread">
        <el-input v-model="keyword" placeholder="按本体名称 / 编码 / 领域筛选" clearable />
        <el-button text @click="load">刷新</el-button>
      </div>
      <AsyncState
        :loading="spaces.loading.value"
        :error="spaces.error.value"
        :empty="!spaces.data.value.length"
        empty-text="还没有可见的本体空间。"
        @retry="load"
      >
        <el-table :data="visible" row-key="id" @row-click="(row: Space) => router.push(`${base}?spaceId=${row.id}`)">
          <el-table-column label="本体空间" min-width="240">
            <template #default="{ row }">
              <b>{{ row.name }}</b>
              <div class="mono muted">{{ row.code }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="domain" label="领域" width="140" />
          <el-table-column label="规模" width="160">
            <template #default="{ row }">
              {{ row.classCount }} 对象 · {{ row.propertyCount }} 属性
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              {{ row.status === 'ACTIVE' ? '使用中' : '已归档' }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="140">
            <template #default="{ row }">
              <el-button link type="primary" @click.stop="router.push(`${base}?spaceId=${row.id}`)">
                进入模块
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </AsyncState>
    </section>
  </section>
</template>
<style scoped>
.space-picker {
  padding: 12px;
}
</style>
