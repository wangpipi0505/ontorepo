<script setup lang="ts">
/** 默认精确匹配；包含匹配是逐用例的显式选择。冻结后的用例只由候选执行接口读取。 */
import { onMounted, reactive, ref } from 'vue'
import type { TestCase } from '@onto/api-client'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import { useDraftCollection } from './useDraftCollection'
import { useReferences } from './useReferences'
import { parseJson, parseObject } from './inputs'
import EditorActions from './EditorActions.vue'
import EntityDetails from './EntityDetails.vue'
import FrozenCandidatePicker from './FrozenCandidatePicker.vue'
import CandidateTestsPanel from './CandidateTestsPanel.vue'
const batchOpen = ref(false),
  batchBusy = ref(false)
const page = useDraftCollection<TestCase>('/test-cases'),
  references = useReferences(['operations'])
const form = reactive({
  name: '',
  question: '',
  operationId: '',
  input: '{}',
  expected: '{}',
  matchMode: 'EXACT' as TestCase['matchMode'],
  enabled: true,
  mode: 'DIRECT' as NonNullable<TestCase['mode']>,
})
async function load() {
  await Promise.all([page.load(), references.load()])
}
function edit(item: TestCase | null) {
  page.edit(item)
  Object.assign(form, {
    name: item?.name || '',
    question: item?.question || '',
    operationId: item?.operationId || '',
    // 只给新用例默认对象；已存 false、0、null 都是合法业务输入，不能当成缺省。
    input: JSON.stringify(item ? item.input : {}, null, 2),
    expected: JSON.stringify(item?.expected || {}, null, 2),
    matchMode: item?.matchMode || 'EXACT',
    enabled: item?.enabled ?? true,
    mode: item?.mode || 'DIRECT',
  })
}
async function save() {
  try {
    if (!form.name.trim() || !form.question.trim() || !form.operationId)
      throw new Error('请填写名称、问题并选择预期命中操作。')
    await page.save({
      ...form,
      input: parseJson(form.input, '用例输入'),
      expected: parseObject(form.expected, '预期结果'),
    })
  } catch (cause) {
    page.mutation.error.value = cause instanceof Error ? cause.message : '用例无法读取。'
  }
}
onMounted(load)
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>用例管理</h1>
      <p class="muted">定义问题、命中操作及确定性结果断言，随候选冻结后执行。</p>
    </div>
    <div class="row">
      <el-button @click="load">刷新</el-button>
      <el-button v-if="page.workspace.canWriteFor('test.execute')" @click="batchOpen = true">
        批量运行冻结用例
      </el-button>
      <el-button v-if="page.workspace.canWrite" type="primary" @click="edit(null)">
        新建用例
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
  <AsyncState
    :loading="page.items.loading.value"
    :error="page.items.error.value"
    :empty="!page.items.data.value.length"
    empty-text="暂无测试用例。添加问题与结果断言，然后冻结候选运行。"
    @retry="load"
  >
    <section class="panel overflow">
      <el-table :data="page.items.data.value">
        <el-table-column type="expand">
          <template #default="{ row }"><EntityDetails :item="row" /></template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column prop="question" label="问题" min-width="220" />
        <el-table-column prop="matchMode" label="匹配" width="110" />
        <el-table-column label="执行模式" width="110">
          <template #default="{ row }">{{ row.mode || 'DIRECT' }}</template>
        </el-table-column>
        <el-table-column label="启用" width="80">
          <template #default="{ row }">{{ row.enabled ? '是' : '否' }}</template>
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
    v-model="batchOpen"
    title="批量运行冻结用例"
    width="860px"
    destroy-on-close
    :close-on-click-modal="false"
    :show-close="!batchBusy"
    :close-on-press-escape="!batchBusy"
  >
    <FrozenCandidatePicker :busy="batchBusy" v-slot="{ candidate }">
      <CandidateTestsPanel
        :candidate="candidate"
        :enabled="
          !['FREEZING', 'COMMITTED', 'CANCELLED', 'VOID', 'READY_TO_COMMIT'].includes(
            candidate.status,
          )
        "
        @busy="batchBusy = $event"
      />
    </FrozenCandidatePicker>
  </el-dialog>
  <el-dialog
    destroy-on-close
    v-model="page.open.value"
    title="维护测试用例"
    width="760px"
    :close-on-click-modal="false"
    :show-close="!page.mutation.pending.value"
    :close-on-press-escape="!page.mutation.pending.value"
  >
    <el-form label-position="top" @submit.prevent="save">
      <el-form-item label="名称" required><el-input v-model="form.name" /></el-form-item>
      <el-form-item label="问题" required>
        <el-input v-model="form.question" type="textarea" :rows="2" />
      </el-form-item>
      <el-form-item label="预期命中操作" required>
        <el-select v-model="form.operationId" filterable>
          <el-option
            v-for="item in references.data.value.operations || []"
            :key="item.id"
            :value="item.id"
            :label="item.name"
          />
        </el-select>
      </el-form-item>
      <div class="form-columns">
        <el-form-item label="用例输入（JSON）">
          <el-input v-model="form.input" type="textarea" :rows="7" />
        </el-form-item>
        <el-form-item label="预期结果（JSON 对象）">
          <el-input v-model="form.expected" type="textarea" :rows="7" />
        </el-form-item>
      </div>
      <el-form-item label="结果匹配模式">
        <el-select v-model="form.matchMode">
          <el-option value="EXACT" label="精确匹配（默认）" />
          <el-option value="CONTAINS" label="包含匹配（显式放宽）" />
        </el-select>
      </el-form-item>
      <el-form-item label="执行模式">
        <el-select v-model="form.mode">
          <el-option value="DIRECT" label="DIRECT · 直接执行预期操作" />
          <el-option value="NL" label="NL · 使用冻结知识确定性匹配操作" />
        </el-select>
      </el-form-item>
      <p v-if="form.mode === 'NL'" class="muted">
        服务端根据冻结词典、操作说明和参数示例匹配；存在歧义或实际命中与期望操作不同均判失败。
      </p>
      <el-checkbox v-model="form.enabled">启用此用例</el-checkbox>
      <p class="muted">只有候选中已冻结且启用的用例会执行，当前草稿变更不改变旧候选。</p>
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
