<script setup lang="ts">
/** 规则输入是声明式白名单表达式；前端不执行条件，不把结果伪装成规则验证通过。 */
import { onMounted, reactive, ref } from 'vue'
import type { Rule } from '@onto/api-client'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import { useDraftCollection } from './useDraftCollection'
import { useReferences } from './useReferences'
import EditorActions from './EditorActions.vue'
import EntityDetails from './EntityDetails.vue'
import ScoringFields from './ScoringFields.vue'
import DraftConflictsPanel from './DraftConflictsPanel.vue'
import FrozenCandidatePicker from './FrozenCandidatePicker.vue'
import FrozenOperationTrialPanel from './FrozenOperationTrialPanel.vue'
const trialRule = ref<Rule | null>(null),
  trialBusy = ref(false)
const page = useDraftCollection<Rule>('/rules'),
  references = useReferences(['classes', 'properties'])
const scoring = ref<InstanceType<typeof ScoringFields>>()
const form = reactive({
  name: '',
  applicableClassId: '',
  kind: 'VALIDATION' as Rule['kind'],
  condition: '',
  actionType: 'REJECT',
  message: '',
  severity: 'ERROR' as Rule['severity'],
  scoring: null as Record<string, unknown> | null,
})
async function load() {
  await Promise.all([page.load(), references.load()])
}
function edit(item: Rule | null) {
  page.edit(item)
  Object.assign(form, {
    name: item?.name || '',
    applicableClassId: item?.applicableClassId || '',
    kind: item?.kind || 'VALIDATION',
    condition: item?.condition || '',
    actionType: item?.action.type || 'REJECT',
    message: item?.action.message || '',
    severity: item?.severity || 'ERROR',
    scoring: item?.scoring || null,
  })
}
async function save() {
  try {
    if (!form.name.trim() || !form.applicableClassId)
      throw new Error('请填写规则名称并选择适用对象。')
    await page.save({
      name: form.name,
      applicableClassId: form.applicableClassId,
      kind: form.kind,
      condition: form.condition,
      severity: form.severity,
      action: form.kind === 'VALIDATION' ? { type: form.actionType, message: form.message } : {},
      scoring: form.kind === 'SCORING' ? scoring.value?.read() : null,
    })
  } catch (cause) {
    page.mutation.error.value = cause instanceof Error ? cause.message : '评分配置无法读取。'
  }
}
onMounted(load)
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>规则与评分</h1>
      <p class="muted">声明校验和评分规则，在候选中执行与验证。</p>
    </div>
    <div class="row">
      <el-button @click="load">刷新</el-button>
      <el-button v-if="page.workspace.canWrite" type="primary" @click="edit(null)">
        新建规则
      </el-button>
    </div>
  </div>
  <p v-if="references.error.value" class="error-text" role="alert">
    引用读取失败：{{ references.error.value }}
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
    empty-text="暂无规则。建立规则后可绑定到操作，并随候选冻结。"
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
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column prop="kind" label="类型" width="130" />
        <el-table-column prop="condition" label="条件（空条件为真）" min-width="240" />
        <el-table-column prop="severity" label="级别" width="100" />
        <el-table-column
          v-if="page.workspace.canWrite || page.workspace.canWriteFor('candidate.execute')"
          label="操作"
          width="210"
        >
          <template #default="{ row }">
            <el-button v-if="page.workspace.canWrite" link type="primary" @click="edit(row)">
              编辑
            </el-button>
            <el-button
              v-if="page.workspace.canWriteFor('candidate.execute')"
              link
              type="primary"
              @click="trialRule = row"
            >
              冻结试执行
            </el-button>
            <el-button
              link
              type="danger"
              v-if="page.workspace.canWrite"
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
    title="维护规则"
    width="760px"
    :close-on-click-modal="false"
    :show-close="!page.mutation.pending.value"
    :close-on-press-escape="!page.mutation.pending.value"
  >
    <el-form label-position="top" @submit.prevent="save">
      <el-form-item label="规则名称" required>
        <el-input v-model="form.name" maxlength="200" />
      </el-form-item>
      <div class="form-columns">
        <el-form-item label="适用对象" required>
          <el-select v-model="form.applicableClassId" filterable>
            <el-option
              v-for="item in references.data.value.classes || []"
              :key="item.id"
              :value="item.id"
              :label="item.name"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="规则类型">
          <el-select v-model="form.kind">
            <el-option value="VALIDATION" label="校验" />
            <el-option value="SCORING" label="评分" />
          </el-select>
        </el-form-item>
      </div>
      <el-form-item label="条件表达式（留空为 true）">
        <el-input
          v-model="form.condition"
          type="textarea"
          :rows="3"
          placeholder="${属性UUID} >= 0"
        />
      </el-form-item>
      <p class="muted">
        支持属性引用、字面量、比较、逻辑、算术及白名单函数。保存时服务端校验语法、类型和引用。
      </p>
      <el-form-item label="级别">
        <el-select v-model="form.severity">
          <el-option value="ERROR" label="错误 ERROR" />
          <el-option value="WARN" label="警告 WARN" />
        </el-select>
      </el-form-item>
      <template v-if="form.kind === 'VALIDATION'">
        <el-form-item label="命中动作">
          <el-select v-model="form.actionType">
            <el-option value="REJECT" label="拒绝" />
            <el-option value="ACCEPT" label="接受" />
          </el-select>
        </el-form-item>
        <el-form-item label="动作说明"><el-input v-model="form.message" /></el-form-item>
      </template>
      <ScoringFields
        v-else
        ref="scoring"
        :value="form.scoring"
        :properties="references.data.value.properties || []"
      />
      <EditorActions
        :pending="page.mutation.pending.value"
        :error="page.mutation.error.value"
        :stale="page.draft.stale.value"
        :can-write="page.workspace.canWrite && !references.error.value"
        @cancel="page.open.value = false"
      />
    </el-form>
  </el-dialog>
  <el-dialog
    :model-value="!!trialRule"
    :title="`规则试执行 · ${trialRule?.name || ''}`"
    width="860px"
    destroy-on-close
    :close-on-click-modal="false"
    :show-close="!trialBusy"
    :close-on-press-escape="!trialBusy"
    @close="trialRule = null"
  >
    <p class="muted">仅执行冻结候选内绑定此规则的操作，候选中的规则内容可能与当前草稿不同。</p>
    <FrozenCandidatePicker v-if="trialRule" :busy="trialBusy" v-slot="{ candidate }">
      <FrozenOperationTrialPanel
        :candidate="candidate"
        :rule-id="trialRule.id"
        @busy="trialBusy = $event"
      />
    </FrozenCandidatePicker>
  </el-dialog>
  <DraftConflictsPanel />
</template>
