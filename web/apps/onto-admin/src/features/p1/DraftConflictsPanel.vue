<script setup lang="ts">
import { watch } from 'vue'
import { RouterLink } from 'vue-router'
import {
  conflictEntityPaths,
  entityLocations,
  type EntityLocation,
  type LocationRecord,
} from './entityLocations'
import type { Conflicts } from '@onto/api-client'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import { useResource } from '../../composables/useResource'
import { readDraftSnapshot } from '../../composables/readDraftSnapshot'
import { useSession } from '../../stores/session'
import { useWorkspace } from '../../stores/workspace'
const workspace = useWorkspace(),
  session = useSession(),
  api = session.api,
  resource = useResource<{
    conflicts: Conflicts
    locations: Record<string, EntityLocation>
    locationWarnings: string[]
  } | null>(null)
const names = { REFERENCE: '引用', TYPE: '类型', CONSTRAINT: '约束', LOGIC: '逻辑' }
const locationPolicies = {
  classes: { name: '对象', permission: 'model.read' },
  properties: { name: '属性', permission: 'model.read' },
  rules: { name: '规则', permission: 'rule.read' },
  constraints: { name: '约束', permission: 'rule.read' },
  operations: { name: '操作', permission: 'operation.read' },
  strategies: { name: '策略', permission: 'rule.read' },
}
async function load() {
  const id = workspace.space?.id
  if (!id) return
  await resource.load(
    async () =>
      (
        await readDraftSnapshot(api, id, async () => {
          const locationWarnings: string[] = []
          // 定位目录是可选信息；无相应权限或目录读取失败不能抹掉已授权的冲突报告。
          // 主检查和前后草稿修订仍须成功，避免把旧报告与新目录拼成一个快照。
          const [conflicts, pairs] = await Promise.all([
            api.get<Conflicts>(`/spaces/${id}/conflicts`),
            Promise.all(
              conflictEntityPaths.map(async (path) => {
                const policy = locationPolicies[path]
                if (!session.has(policy.permission)) {
                  locationWarnings.push(`${policy.name}目录未授权，相关条目暂不可定位。`)
                  return [path, []] as const
                }
                try {
                  return [
                    path,
                    await api.get<LocationRecord[]>(
                      path === 'properties' ? `/spaces/${id}/properties` : `/${path}?spaceId=${id}`,
                    ),
                  ] as const
                } catch {
                  locationWarnings.push(
                    `${policy.name}目录读取失败，相关条目暂不可定位；可重新检查重试。`,
                  )
                  return [path, []] as const
                }
              }),
            ),
          ])
          return {
            conflicts,
            locations: entityLocations(Object.fromEntries(pairs)),
            locationWarnings,
          }
        })
      ).items,
  )
}
// 新修订使旧检查失效，页面不会继续把旧结论呈现为当前草稿状态。
watch(() => workspace.space?.draftRev, resource.reset)
</script>
<template>
  <section class="panel stack conflicts-panel">
    <div class="row spread">
      <div>
        <h2>草稿冲突检查</h2>
        <p class="muted">检查引用、类型、约束和逻辑。结果来自服务端条件分析。</p>
      </div>
      <el-button :loading="resource.loading.value" @click="load">检查当前草稿</el-button>
    </div>
    <div class="row">
      <span v-for="(name, kind) in names" :key="kind" class="pill">{{ name }}</span>
    </div>
    <AsyncState :loading="resource.loading.value" :error="resource.error.value" @retry="load">
      <template v-if="resource.data.value">
        <p
          v-for="warning in resource.data.value.locationWarnings"
          :key="warning"
          class="readonly-note"
        >
          {{ warning }}
        </p>
        <section v-for="(name, kind) in names" :key="kind">
          <h3>{{ name }}</h3>
          <template
            v-for="group in [
              {
                name: '错误',
                items: resource.data.value.conflicts.errors.filter((item) => item.kind === kind),
              },
              {
                name: '警告',
                items: resource.data.value.conflicts.warnings.filter((item) => item.kind === kind),
              },
            ]"
            :key="group.name"
          >
            <h4>{{ group.name }} · {{ group.items.length }}</h4>
            <article v-for="(item, index) in group.items" :key="index" class="conflict-record">
              <p>{{ item.message }}</p>
              <div class="stack">
                <template v-for="id in item.entityIds" :key="id">
                  <RouterLink
                    v-if="resource.data.value.locations[id]"
                    :to="{
                      path: `/spaces/${workspace.space?.id}/${resource.data.value.locations[id].path}`,
                      query: {
                        entityId: id,
                        ...(resource.data.value.locations[id].classId
                          ? { classId: resource.data.value.locations[id].classId }
                          : {}),
                      },
                    }"
                  >
                    定位 {{ resource.data.value.locations[id].name }}
                    <span class="mono">{{ id }}</span>
                  </RouterLink>
                  <span v-else class="muted mono">{{ id }} · 条目不存在或不可定位</span>
                </template>
              </div>
            </article>
            <p v-if="!group.items.length" class="muted">无{{ group.name }}</p>
          </template>
        </section>
      </template>
      <p v-else class="muted">尚未检查。点击“检查当前草稿”获取真实结论。</p>
    </AsyncState>
  </section>
</template>
<style scoped>
.conflict-record {
  padding: 12px;
  margin: 10px 0;
  border: 1px solid var(--line);
  border-radius: 6px;
  overflow-wrap: anywhere;
}
.conflicts-panel {
  margin-top: 20px;
}
.conflicts-panel h2 {
  margin-bottom: 6px;
}
</style>
