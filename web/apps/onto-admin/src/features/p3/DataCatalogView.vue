<script setup lang="ts">
/** 数据目录为服务端同空间读取视图，映射计数与关联不由客户端猜测。 */
import { computed, onMounted, reactive, ref } from 'vue'
import type {
  DataCatalog,
  MappingCollection,
  OntProperty,
  Space,
} from '@onto/api-client'
import { useSession } from '../../stores/session'
import { useWorkspace } from '../../stores/workspace'
import { useResource } from '../../composables/useResource'
import { useMutation } from '../../composables/useMutation'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
const api = useSession().api,
  workspace = useWorkspace(),
  catalog = useResource<DataCatalog | null>(null),
  mappings = useResource<MappingCollection | null>(null),
  properties = useResource<OntProperty[]>([]),
  mappingMutation = useMutation()
const mappingOpen = ref(false),
  mappingField = reactive({ datasourceId: '', resource: '', field: '', type: '' }),
  mappingForm = reactive({ objectMappingId: '', targetPropertyId: '' })
const candidateObjects = computed(
  () =>
    mappings.data.value?.objects.filter(
      (item) =>
        item.datasourceId === mappingField.datasourceId &&
        item.sourceResource === mappingField.resource,
    ) ?? [],
)
const selectedObject = computed(() =>
  candidateObjects.value.find((item) => item.id === mappingForm.objectMappingId),
)
const candidateProperties = computed(() =>
  properties.data.value.filter(
    (item) => item.domainClassId === selectedObject.value?.targetClassId,
  ),
)
function openMapping(datasourceId: string, resource: string, field: string, type: string) {
  if (!workspace.canWrite) return
  mappingField.datasourceId = datasourceId
  mappingField.resource = resource
  mappingField.field = field
  mappingField.type = type
  mappingForm.objectMappingId = candidateObjects.value[0]?.id ?? ''
  mappingForm.targetPropertyId = ''
  mappingMutation.error.value = ''
  mappingOpen.value = true
}
async function saveMapping() {
  const space = workspace.space
  if (!space || !workspace.canWrite || !mappingForm.objectMappingId || !mappingForm.targetPropertyId)
    return
  await mappingMutation.run(async ({ isCurrent }) => {
    const next = await api.post<Space>('/mappings/properties', {
      spaceId: space.id,
      expectedDraftRev: space.draftRev,
      objectMappingId: mappingForm.objectMappingId,
      sourceField: mappingField.field,
      targetPropertyId: mappingForm.targetPropertyId,
      transform: { kind: 'IDENTITY' },
      required: false,
    })
    if (isCurrent()) workspace.accept(next)
  })
  if (!mappingMutation.error.value) {
    mappingOpen.value = false
    await load()
  }
}
async function load() {
  const id = workspace.space?.id
  if (!id) return
  await catalog.load(() => api.get<DataCatalog>(`/data-catalog?spaceId=${id}`))
  await mappings.load(() => api.get<MappingCollection>(`/mappings?spaceId=${id}`))
  await properties.load(() => api.get<OntProperty[]>(`/spaces/${id}/properties`))
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
                    <el-table-column label="操作" width="120">
                      <template #default="{ row: field }">
                        <el-button
                          v-if="workspace.canWrite"
                          link
                          type="primary"
                          @click="openMapping(row.id, resource.name, field.name, field.type)"
                        >
                          新建映射
                        </el-button>
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
  <el-dialog v-model="mappingOpen" title="从字段新建属性映射" width="560px">
    <p class="muted">
      来源字段：{{ mappingField.resource }}.{{ mappingField.field }}
      · {{ mappingField.type }}
    </p>
    <el-form label-position="top">
      <el-form-item label="对象映射" required>
        <el-select v-model="mappingForm.objectMappingId" style="width: 100%">
          <el-option
            v-for="item in candidateObjects"
            :key="item.id"
            :value="item.id"
            :label="`${item.sourceResource} → ${item.targetClassId}`"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="目标属性" required>
        <el-select
          v-model="mappingForm.targetPropertyId"
          style="width: 100%"
          :disabled="!selectedObject"
        >
          <el-option
            v-for="item in candidateProperties"
            :key="item.id"
            :value="item.id"
            :label="item.displayName || item.name"
          />
        </el-select>
      </el-form-item>
      <p v-if="!candidateObjects.length" class="muted">
        该资源尚无对象映射，请先在"三层映射"建立对象映射。
      </p>
      <p v-if="mappingMutation.error.value" class="error-text" role="alert">
        {{ mappingMutation.error.value }}
      </p>
    </el-form>
    <template #footer>
      <el-button @click="mappingOpen = false">取消</el-button>
      <el-button
        type="primary"
        :loading="mappingMutation.pending.value"
        :disabled="!mappingForm.objectMappingId || !mappingForm.targetPropertyId"
        @click="saveMapping"
      >
        保存属性映射
      </el-button>
    </template>
  </el-dialog>
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
