<script setup lang="ts">
import { onMounted, reactive } from 'vue'
import type { Strategy } from '@onto/api-client'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import { useDraftCollection } from './useDraftCollection'
import { useReferences } from './useReferences'
import { parseObject } from './inputs'
import EditorActions from './EditorActions.vue'
import EntityDetails from './EntityDetails.vue'
const page = useDraftCollection<Strategy>('/strategies'),
  references = useReferences(['operations'])
const form = reactive({
  name: '',
  maxRows: 100,
  timeoutMs: 3000,
  resultLimit: 100,
  readScope: '',
  boundOps: [] as string[],
})
async function load() {
  await Promise.all([page.load(), references.load()])
}
function edit(item: Strategy | null) {
  page.edit(item)
  Object.assign(form, {
    name: item?.name || '',
    maxRows: item?.policy.maxRows ?? 100,
    timeoutMs: item?.policy.timeoutMs ?? 3000,
    resultLimit: item?.policy.resultLimit ?? 100,
    readScope: item?.policy.readScope ? JSON.stringify(item.policy.readScope, null, 2) : '',
    boundOps: [...(item?.boundOps || [])],
  })
}
async function save() {
  try {
    if (!form.name.trim()) throw new Error('请输入策略名称。')
    if (
      ![form.maxRows, form.timeoutMs, form.resultLimit].every(
        (value) => Number.isInteger(value) && value > 0,
      )
    )
      throw new Error('资源上限须为正整数。')
    await page.save({
      name: form.name,
      policy: {
        maxRows: form.maxRows,
        timeoutMs: form.timeoutMs,
        resultLimit: form.resultLimit,
        failureMode: 'FAIL',
        readScope: form.readScope.trim() ? parseObject(form.readScope, '读取范围') : null,
      },
      boundOps: form.boundOps,
    })
  } catch (cause) {
    page.mutation.error.value = cause instanceof Error ? cause.message : '策略配置无法读取。'
  }
}
onMounted(load)
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>执行策略</h1>
      <p class="muted">策略与操作取更严格的资源上限，调用输入不能放大限制。</p>
    </div>
    <div class="row">
      <el-button @click="load">刷新</el-button>
      <el-button v-if="page.workspace.canWrite" type="primary" @click="edit(null)">
        新建策略
      </el-button>
    </div>
  </div>
  <p v-if="references.error.value" class="error-text" role="alert">
    操作读取失败：{{ references.error.value }}
    <el-button text @click="references.load">重试</el-button>
  </p>
  <p v-if="page.mutation.error.value && !page.open.value" class="error-text" role="alert">
    {{ page.mutation.error.value }}
  </p>
  <p v-if="page.focus.message.value" class="notice" role="status">{{ page.focus.message.value }}</p>
  <AsyncState
    :loading="page.items.loading.value"
    :error="page.items.error.value"
    :empty="!page.items.data.value.length"
    empty-text="暂无执行策略。可创建统一的资源约束并绑定操作。"
    @retry="load"
  >
    <section class="panel overflow">
      <el-table
        :data="page.items.data.value"
        row-key="id"
        :expand-row-keys="page.focus.expandedKeys.value"
        :row-class-name="page.focus.rowClassName"
      >
        <el-table-column type="expand">
          <template #default="{ row }"><EntityDetails :item="row" /></template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="160" />
        <el-table-column prop="policy.maxRows" label="行数上限" width="110" />
        <el-table-column prop="policy.timeoutMs" label="超时毫秒" width="110" />
        <el-table-column prop="policy.resultLimit" label="结果上限" width="110" />
        <el-table-column label="绑定操作" min-width="180">
          <template #default="{ row }">
            {{
              row.boundOps
                .map(
                  (id: string) =>
                    references.data.value.operations?.find((item) => item.id === id)?.name || id,
                )
                .join('，') || '未绑定'
            }}
          </template>
        </el-table-column>
        <el-table-column v-if="page.workspace.canWrite" label="操作" width="130">
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
    title="维护执行策略"
    width="640px"
    :close-on-click-modal="false"
    :show-close="!page.mutation.pending.value"
    :close-on-press-escape="!page.mutation.pending.value"
  >
    <el-form label-position="top" @submit.prevent="save">
      <el-form-item label="名称" required><el-input v-model="form.name" /></el-form-item>
      <div class="form-columns">
        <el-form-item label="最大行数">
          <el-input-number v-model="form.maxRows" :min="1" />
        </el-form-item>
        <el-form-item label="超时毫秒">
          <el-input-number v-model="form.timeoutMs" :min="1" />
        </el-form-item>
        <el-form-item label="结果数量上限">
          <el-input-number v-model="form.resultLimit" :min="1" />
        </el-form-item>
      </div>
      <p class="muted">失败处理固定为 FAIL，达到限制时不会悄悄降级。</p>
      <el-form-item label="读取范围（可选 JSON 对象）">
        <el-input v-model="form.readScope" type="textarea" :rows="4" />
      </el-form-item>
      <el-form-item label="绑定操作">
        <el-select v-model="form.boundOps" multiple filterable>
          <el-option
            v-for="item in references.data.value.operations || []"
            :key="item.id"
            :value="item.id"
            :label="item.name"
          />
        </el-select>
      </el-form-item>
      <EditorActions
        :pending="page.mutation.pending.value"
        :error="page.mutation.error.value"
        :stale="page.draft.stale.value"
        :can-write="page.workspace.canWrite && !references.error.value"
        @cancel="page.open.value = false"
      />
    </el-form>
  </el-dialog>
</template>
