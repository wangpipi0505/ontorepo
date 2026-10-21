<script setup lang="ts">
import { onMounted, reactive } from 'vue'
import type { Codebook } from '@onto/api-client'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import { useDraftCollection } from './useDraftCollection'
import { parseEntries } from './inputs'
import EditorActions from './EditorActions.vue'
import EntityDetails from './EntityDetails.vue'
const page = useDraftCollection<Codebook>('/codebooks')
const form = reactive({ code: '', name: '', kind: 'VALUE' as Codebook['kind'], entries: '' })
function edit(item: Codebook | null) {
  page.edit(item)
  Object.assign(form, {
    code: item?.code || '',
    name: item?.name || '',
    kind: item?.kind || 'VALUE',
    entries: item?.entries.map((entry) => `${entry.code}=${entry.label}`).join('\n') || '',
  })
}
async function save() {
  try {
    if (!form.code.trim() || !form.name.trim()) throw new Error('请填写编码和名称。')
    await page.save({ ...form, entries: parseEntries(form.entries) })
  } catch (cause) {
    page.mutation.error.value = cause instanceof Error ? cause.message : '条目无法读取。'
  }
}
onMounted(page.load)
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>码表管理</h1>
      <p class="muted">统一维护单位和取值编码，属性仅引用同空间码表。</p>
    </div>
    <div class="row">
      <el-button @click="page.load">刷新</el-button>
      <el-button v-if="page.workspace.canWrite" type="primary" @click="edit(null)">
        新建码表
      </el-button>
    </div>
  </div>
  <p v-if="page.mutation.error.value && !page.open.value" class="error-text" role="alert">
    {{ page.mutation.error.value }}
  </p>
  <AsyncState
    :loading="page.items.loading.value"
    :error="page.items.error.value"
    :empty="!page.items.data.value.length"
    empty-text="暂无码表。新建单位或取值码表以规范属性。"
    @retry="page.load"
  >
    <section class="panel overflow">
      <el-table :data="page.items.data.value">
        <el-table-column type="expand">
          <template #default="{ row }"><EntityDetails :item="row" /></template>
        </el-table-column>
        <el-table-column prop="code" label="编码" min-width="120" />
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column label="用途" width="100">
          <template #default="{ row }">{{ row.kind === 'UNIT' ? '单位' : '取值' }}</template>
        </el-table-column>
        <el-table-column label="条目" min-width="220">
          <template #default="{ row }">
            {{
              row.entries
                .map((entry: { code: string; label: string }) => `${entry.code} · ${entry.label}`)
                .join('，')
            }}
          </template>
        </el-table-column>
        <el-table-column v-if="page.workspace.canWrite" label="操作" width="120">
          <template #default="{ row }">
            <el-button link type="primary" @click="edit(row)">编辑</el-button>
            <el-button
              link
              type="danger"
              :disabled="page.mutation.pending.value"
              @click="page.remove(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </AsyncState>
  <el-dialog
    destroy-on-close
    v-model="page.open.value"
    :title="page.editing.value ? '编辑码表' : '新建码表'"
    width="620px"
    :close-on-click-modal="false"
    :show-close="!page.mutation.pending.value"
    :close-on-press-escape="!page.mutation.pending.value"
  >
    <el-form label-position="top" @submit.prevent="save">
      <el-form-item label="编码" required>
        <el-input v-model="form.code" maxlength="80" />
      </el-form-item>
      <el-form-item label="名称" required>
        <el-input v-model="form.name" maxlength="200" />
      </el-form-item>
      <el-form-item label="码表用途">
        <el-select v-model="form.kind">
          <el-option value="UNIT" label="单位" />
          <el-option value="VALUE" label="取值" />
        </el-select>
      </el-form-item>
      <el-form-item label="条目（每行 编码=名称）" required>
        <el-input v-model="form.entries" type="textarea" :rows="8" placeholder="kg=千克&#10;m=米" />
      </el-form-item>
      <EditorActions
        :pending="page.mutation.pending.value"
        :error="page.mutation.error.value"
        :stale="page.draft.stale.value"
        :can-write="page.workspace.canWrite"
        @cancel="page.open.value = false"
      />
    </el-form>
  </el-dialog>
</template>
