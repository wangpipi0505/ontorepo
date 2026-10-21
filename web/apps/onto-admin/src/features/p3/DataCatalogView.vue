<script setup lang="ts">
/** 数据目录为服务端同空间读取视图，映射计数与关联不由客户端猜测。 */
import { onMounted } from 'vue'
import type { DataCatalog } from '@onto/api-client'
import { useSession } from '../../stores/session'
import { useWorkspace } from '../../stores/workspace'
import { useResource } from '../../composables/useResource'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
const api = useSession().api,
  workspace = useWorkspace(),
  catalog = useResource<DataCatalog | null>(null)
async function load() {
  const id = workspace.space?.id
  if (id) await catalog.load(() => api.get<DataCatalog>(`/data-catalog?spaceId=${id}`))
}
onMounted(load)
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>数据目录</h1>
      <p class="muted">数据源、快照、映射、本体类与操作的可追溯关系。</p>
    </div>
    <el-button @click="load">刷新目录</el-button>
  </div>
  <AsyncState :loading="catalog.loading.value" :error="catalog.error.value" @retry="load">
    <div v-if="catalog.data.value" class="stack">
      <section class="panel">
        <h2>源与最新快照</h2>
        <p class="catalog-scroll-hint muted">可左右滚动表格查看完整快照与关联。</p>
        <!-- 保留标识列的可读宽度，由表格内部滚动；窄屏不能把 UUID 挤成逐字换行。 -->
        <el-table :data="catalog.data.value.datasources" row-key="id" empty-text="尚无数据源">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div v-if="row.resources?.length" class="resource-list">
                <section v-for="resource in row.resources" :key="resource.name" class="resource">
                  <b class="mono">{{ resource.name }}</b>
                  <el-table :data="resource.fields" size="small" empty-text="快照无字段">
                    <el-table-column prop="name" label="字段" min-width="150" />
                    <el-table-column prop="type" label="源类型" min-width="110" />
                    <el-table-column label="本体绑定" min-width="240">
                      <template #default="{ row: field }">
                        <span v-if="field.bindings.length">
                          <span
                            v-for="binding in field.bindings"
                            :key="binding.objectMappingId + binding.propertyId"
                            class="binding"
                          >
                            {{ binding.className }}.{{ binding.propertyName }}
                          </span>
                        </span>
                        <span v-else class="muted">未绑定</span>
                      </template>
                    </el-table-column>
                  </el-table>
                </section>
              </div>
              <p v-else class="muted">该数据源暂无快照资源；先完成元数据同步。</p>
            </template>
          </el-table-column>
          <el-table-column prop="name" label="数据源" min-width="160" />
          <el-table-column prop="dialect" label="协议" min-width="115" />
          <el-table-column prop="latestSnapshotId" label="最新快照" min-width="270" />
          <el-table-column prop="mappingCount" label="映射计数" min-width="100" />
          <el-table-column label="本体类 / 操作" min-width="310">
            <template #default="{ row }">
              类：{{ row.classIds.join('、') || '无' }}
              <br />
              操作：{{ row.operationIds.join('、') || '无' }}
            </template>
          </el-table-column>
        </el-table>
      </section>
      <section class="panel">
        <h2>资源与模型绑定</h2>
        <p class="catalog-scroll-hint muted">可左右滚动表格查看完整映射与相关操作。</p>
        <el-table :data="catalog.data.value.bindings" empty-text="尚无映射绑定">
          <el-table-column prop="datasourceId" label="源 ID" min-width="220" />
          <el-table-column prop="resource" label="来源资源" min-width="220" />
          <el-table-column prop="objectMappingId" label="对象映射" min-width="220" />
          <el-table-column prop="className" label="目标类" min-width="110" />
          <el-table-column prop="propertyCount" label="属性数" min-width="90" />
          <el-table-column prop="relationCount" label="关系数" min-width="90" />
          <el-table-column label="相关操作" min-width="260">
            <template #default="{ row }">{{ row.operationIds.join('、') || '无' }}</template>
          </el-table-column>
        </el-table>
      </section>
    </div>
  </AsyncState>
</template>
<style scoped>
.resource-list {
  padding: 8px 16px 16px;
}
.resource {
  margin-bottom: 14px;
}
.resource > b {
  display: block;
  margin-bottom: 6px;
}
.binding {
  display: inline-block;
  margin-right: 10px;
  padding: 2px 6px;
  border-radius: 4px;
  background: #eef4ff;
  color: #1d4ed8;
  font-size: 12px;
}
.catalog-scroll-hint {
  display: none;
}
@media (max-width: 700px) {
  .catalog-scroll-hint {
    display: block;
  }
}
</style>
