<script setup lang="ts">
/** 四类约束使用固定目标类型；表达式字段独立展示，避免让用户拼写 targetType。 */
import { computed, onMounted, reactive, watch } from 'vue'
import type { Constraint } from '@onto/api-client'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import { useDraftCollection } from './useDraftCollection'
import { useReferences } from './useReferences'
import EditorActions from './EditorActions.vue'
import EntityDetails from './EntityDetails.vue'
const page = useDraftCollection<Constraint>('/constraints'),
  references = useReferences(['classes', 'properties', 'relations'])
const form = reactive({
  name: '',
  kind: 'RANGE' as Constraint['kind'],
  targetRef: '',
  minimum: 0,
  maximum: 1,
  refs: [] as string[],
})
const kinds = {
  RANGE: '数值范围',
  CARDINALITY: '关系基数',
  MUTEX: '对象互斥',
  DEPENDENCY: '属性依赖',
}
const targetType = computed(() =>
  form.kind === 'MUTEX' ? 'CLASS' : form.kind === 'CARDINALITY' ? 'RELATION' : 'PROPERTY',
)
const options = computed(
  () =>
    references.data.value[
      targetType.value === 'CLASS'
        ? 'classes'
        : targetType.value === 'RELATION'
          ? 'relations'
          : 'properties'
    ] || [],
)
watch(
  () => form.kind,
  () => {
    form.targetRef = ''
    form.refs = []
  },
)
async function load() {
  await Promise.all([page.load(), references.load()])
}
async function edit(item: Constraint | null) {
  page.edit(item)
  form.kind = item?.kind || 'RANGE'
  // kind watcher先清理旧目标；下一个微任务再恢复编辑值。
  await Promise.resolve()
  Object.assign(form, {
    name: item?.name || '',
    targetRef: item?.targetRef || '',
    minimum: item?.expr.min ?? 0,
    maximum: item?.expr.max ?? 1,
    refs: (item?.expr.classIds || item?.expr.requires || []) as string[],
  })
}
async function save() {
  if (!form.name.trim() || !form.targetRef) {
    page.mutation.error.value = '请填写名称并选择约束目标。'
    return
  }
  const numeric = form.kind === 'RANGE' || form.kind === 'CARDINALITY'
  if (
    numeric &&
    (form.minimum > form.maximum ||
      (form.kind === 'CARDINALITY' &&
        (!Number.isInteger(form.minimum) || !Number.isInteger(form.maximum) || form.minimum < 0)))
  ) {
    page.mutation.error.value = '请检查范围；基数必须为非负整数。'
    return
  }
  if (!numeric && (!form.refs.length || form.refs.includes(form.targetRef))) {
    page.mutation.error.value = '请选择非空关联集合，且不能引用目标自身。'
    return
  }
  const expr = numeric
    ? { min: form.minimum, max: form.maximum }
    : form.kind === 'MUTEX'
      ? { classIds: form.refs }
      : { requires: form.refs }
  await page.save({
    name: form.name,
    kind: form.kind,
    targetType: targetType.value,
    targetRef: form.targetRef,
    expr,
  })
}
onMounted(load)
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>约束管理</h1>
      <p class="muted">维护范围、基数、互斥和依赖，服务端校验同空间引用及可满足性。</p>
    </div>
    <div class="row">
      <el-button @click="load">刷新</el-button>
      <el-button v-if="page.workspace.canWrite" type="primary" @click="edit(null)">
        新建约束
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
    empty-text="暂无约束。可在属性分面之外添加领域限制。"
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
        <el-table-column prop="kind" label="类型" width="140" />
        <el-table-column prop="targetType" label="目标类型" width="120" />
        <el-table-column label="表达式" min-width="240">
          <template #default="{ row }">
            <code>{{ JSON.stringify(row.expr) }}</code>
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
    title="维护约束"
    width="640px"
    :close-on-click-modal="false"
    :show-close="!page.mutation.pending.value"
    :close-on-press-escape="!page.mutation.pending.value"
  >
    <el-form label-position="top" @submit.prevent="save">
      <el-form-item label="名称" required><el-input v-model="form.name" /></el-form-item>
      <el-form-item label="约束类型">
        <el-select v-model="form.kind">
          <el-option v-for="(label, key) in kinds" :key="key" :value="key" :label="label" />
        </el-select>
      </el-form-item>
      <el-form-item :label="`目标 ${targetType}`" required>
        <el-select v-model="form.targetRef" filterable>
          <el-option v-for="item in options" :key="item.id" :value="item.id" :label="item.name" />
        </el-select>
      </el-form-item>
      <div v-if="form.kind === 'RANGE' || form.kind === 'CARDINALITY'" class="form-columns">
        <el-form-item label="最小值"><el-input-number v-model="form.minimum" /></el-form-item>
        <el-form-item label="最大值"><el-input-number v-model="form.maximum" /></el-form-item>
      </div>
      <el-form-item
        v-else
        :label="form.kind === 'MUTEX' ? '互斥对象集合' : '同类依赖属性集合'"
        required
      >
        <el-select v-model="form.refs" multiple filterable>
          <el-option
            v-for="item in references.data.value[
              form.kind === 'MUTEX' ? 'classes' : 'properties'
            ] || []"
            :key="item.id"
            :value="item.id"
            :label="item.name"
            :disabled="item.id === form.targetRef"
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
