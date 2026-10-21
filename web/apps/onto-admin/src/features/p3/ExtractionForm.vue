<script setup lang="ts">
/** 三种抽取只生成待确认批次，来源只能选当前空间的快照或受管文件。 */
import { computed, reactive, ref } from 'vue'
import type { Datasource, ExtractionRequest, ManagedFile } from '@onto/api-client'
import { useMetadataSelection } from './useMetadataSelection'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
const props = defineProps<{
  sources: Datasource[]
  files: ManagedFile[]
  canWrite: boolean
  pending: boolean
  stale: boolean
}>()
const emit = defineEmits<{ extract: [value: ExtractionRequest] }>()
const state = reactive({
  sourceKind: 'TABLE' as ExtractionRequest['sourceKind'],
  datasourceId: '',
  metaSnapshotId: '',
  resource: '',
  fileId: '',
})
const meta = useMetadataSelection(
    () => state.datasourceId,
    () => state.metaSnapshotId,
  ),
  error = ref('')
const disabled = computed(() => !props.canWrite || props.pending || props.stale)
const files = computed(() =>
  props.files.filter((item) =>
    state.sourceKind === 'FILE'
      ? /\.(csv|json|xlsx)$/i.test(item.name)
      : /\.(pdf|docx|txt)$/i.test(item.name),
  ),
)
function sourceChanged() {
  state.metaSnapshotId = ''
  state.resource = ''
}
function preview() {
  error.value = ''
  if (disabled.value) return
  if (state.sourceKind === 'TABLE') {
    if (
      !state.datasourceId ||
      !state.metaSnapshotId ||
      !meta.snapshot.data.value?.resources.some((item) => item.name === state.resource)
    ) {
      error.value = '请选择已授权的源、快照与资源。'
      return
    }
    emit('extract', {
      sourceKind: 'TABLE',
      datasourceId: state.datasourceId,
      metaSnapshotId: state.metaSnapshotId,
      resource: state.resource,
    })
  } else {
    if (!files.value.some((item) => item.id === state.fileId)) {
      error.value = '请选择当前空间内符合来源类型的受管文件。'
      return
    }
    emit('extract', { sourceKind: state.sourceKind, fileId: state.fileId })
  }
}
</script>
<template>
  <el-form label-position="top" data-editor="extraction" @submit.prevent="preview">
    <el-form-item label="抽取来源">
      <el-select v-model="state.sourceKind" :disabled="disabled" @change="state.fileId = ''">
        <el-option value="TABLE" label="表结构" />
        <el-option value="FILE" label="CSV / JSON / XLSX 文件" />
        <el-option value="DOCUMENT" label="PDF / DOCX / TXT 文档" />
      </el-select>
    </el-form-item>
    <template v-if="state.sourceKind === 'TABLE'">
      <el-form-item label="数据源">
        <el-select v-model="state.datasourceId" :disabled="disabled" @change="sourceChanged">
          <el-option v-for="item in sources" :key="item.id" :value="item.id" :label="item.name" />
        </el-select>
      </el-form-item>
      <AsyncState
        :loading="meta.summaries.loading.value"
        :error="meta.summaries.error.value"
        @retry="meta.loadSummaries"
      >
        <el-form-item label="元数据快照">
          <el-select
            v-model="state.metaSnapshotId"
            :disabled="disabled"
            @change="state.resource = ''"
          >
            <el-option
              v-for="item in meta.summaries.data.value"
              :key="item.id"
              :value="item.id"
              :label="`${item.capturedAt} · ${item.sha256}`"
            />
          </el-select>
        </el-form-item>
      </AsyncState>
      <AsyncState
        :loading="meta.snapshot.loading.value"
        :error="meta.snapshot.error.value"
        @retry="meta.loadSnapshot"
      >
        <el-form-item label="来源资源">
          <el-select v-model="state.resource" :disabled="disabled">
            <el-option
              v-for="item in meta.snapshot.data.value?.resources"
              :key="item.name"
              :value="item.name"
              :label="item.name"
            />
          </el-select>
        </el-form-item>
      </AsyncState>
    </template>
    <el-form-item v-else label="受管文件">
      <el-select v-model="state.fileId" :disabled="disabled">
        <el-option
          v-for="item in files"
          :key="item.id"
          :value="item.id"
          :label="`${item.name} (${item.id})`"
        />
      </el-select>
    </el-form-item>
    <p class="muted">
      规则式抽取仅生成 PENDING 预览，不直接改变本体模型。请逐项审阅名称、关联和来源证据后批量确认。
    </p>
    <p v-if="error" class="error-text" role="alert">{{ error }}</p>
    <el-button type="primary" native-type="submit" :disabled="disabled">生成抽取预览</el-button>
  </el-form>
</template>
