<script setup lang="ts">
/** 关系可手工维护；逆边由服务端原子同步，客户端永不写 inverseOf 或 subsetExceeded。 */
import { onMounted, reactive } from 'vue'
import type { Relation } from '@onto/api-client'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
import { useDraftCollection } from './useDraftCollection'
import { useReferences } from './useReferences'
import EditorActions from './EditorActions.vue'
import EntityDetails from './EntityDetails.vue'
const page = useDraftCollection<Relation>('/relations'),
  references = useReferences(['classes'])
const form = reactive({
  name: '',
  fromClassId: '',
  toClassId: '',
  cardinality: 'ONE_TO_MANY' as Relation['cardinality'],
  inverseName: '',
  transitive: false,
  symmetric: false,
  description: '',
})
const cardinalities = {
  ONE_TO_ONE: '一对一',
  ONE_TO_MANY: '一对多',
  MANY_TO_ONE: '多对一',
  MANY_TO_MANY: '多对多',
}
async function load() {
  await Promise.all([page.load(), references.load()])
}
function edit(item: Relation | null) {
  page.edit(item)
  Object.assign(form, {
    name: item?.name || '',
    fromClassId: item?.fromClassId || '',
    toClassId: item?.toClassId || '',
    cardinality: item?.cardinality || 'ONE_TO_MANY',
    inverseName:
      page.items.data.value.find((entry) => item && entry.id === item.inverseOf)?.name || '',
    transitive: item?.transitive || false,
    symmetric: item?.symmetric || false,
    description: item?.description || '',
  })
}
async function save() {
  if (!form.name.trim() || !form.fromClassId || !form.toClassId) {
    page.mutation.error.value = '请填写名称和两个关系端点。'
    return
  }
  if (form.fromClassId === form.toClassId) {
    page.mutation.error.value = '关系端点须为不同对象。'
    return
  }
  await page.save({ ...form, inverseName: form.inverseName.trim() || undefined })
}
const label = (id: string) =>
  references.data.value.classes?.find((item) => item.id === id)?.name || id
onMounted(load)
</script>
<template>
  <div class="page-heading">
    <div>
      <h1>关系与逆关系</h1>
      <p class="muted">定义对象间的方向与基数，逆边由服务端同步维护。</p>
    </div>
    <div class="row">
      <el-button @click="load">刷新</el-button>
      <el-button v-if="page.workspace.canWrite" type="primary" @click="edit(null)">
        新建关系
      </el-button>
    </div>
  </div>
  <p v-if="references.error.value" class="error-text" role="alert">
    对象选项读取失败：{{ references.error.value }}
    <el-button text @click="references.load">重试</el-button>
  </p>
  <p v-if="page.mutation.error.value && !page.open.value" class="error-text" role="alert">
    {{ page.mutation.error.value }}
  </p>
  <AsyncState
    :loading="page.items.loading.value"
    :error="page.items.error.value"
    :empty="!page.items.data.value.length"
    empty-text="暂无关系。先建立至少两个领域对象。"
    @retry="load"
  >
    <section class="panel overflow">
      <el-table :data="page.items.data.value">
        <el-table-column type="expand">
          <template #default="{ row }"><EntityDetails :item="row" /></template>
        </el-table-column>
        <el-table-column prop="name" label="关系" min-width="130" />
        <el-table-column label="方向" min-width="200">
          <template #default="{ row }">
            {{ label(row.fromClassId) }} → {{ label(row.toClassId) }}
          </template>
        </el-table-column>
        <el-table-column label="基数" width="110">
          <template #default="{ row }">
            {{ cardinalities[row.cardinality as Relation['cardinality']] }}
          </template>
        </el-table-column>
        <el-table-column label="语义" min-width="180">
          <template #default="{ row }">
            {{ row.transitive ? '传递 ' : '' }}{{ row.symmetric ? '对称 ' : ''
            }}{{ row.inverseOf ? '关联逆边' : '单向' }}
            <el-tag v-if="row.subsetExceeded" type="warning" size="small">超出推理子集</el-tag>
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
    :title="page.editing.value ? '编辑关系' : '新建关系'"
    width="640px"
    :close-on-click-modal="false"
    :show-close="!page.mutation.pending.value"
    :close-on-press-escape="!page.mutation.pending.value"
  >
    <el-form label-position="top" @submit.prevent="save">
      <el-form-item label="关系名称" required>
        <el-input v-model="form.name" maxlength="200" />
      </el-form-item>
      <div class="form-columns">
        <el-form-item label="起点对象" required>
          <el-select v-model="form.fromClassId" filterable>
            <el-option
              v-for="item in references.data.value.classes || []"
              :key="item.id"
              :value="item.id"
              :label="item.name"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="终点对象" required>
          <el-select v-model="form.toClassId" filterable>
            <el-option
              v-for="item in references.data.value.classes || []"
              :key="item.id"
              :value="item.id"
              :label="item.name"
            />
          </el-select>
        </el-form-item>
      </div>
      <el-form-item label="关系基数">
        <el-select v-model="form.cardinality">
          <el-option v-for="(label, key) in cardinalities" :key="key" :value="key" :label="label" />
        </el-select>
      </el-form-item>
      <el-form-item label="逆关系名称（留空不新建逆边）">
        <el-input v-model="form.inverseName" />
      </el-form-item>
      <p class="muted">修改端点和基数会同步逆边；删除关系也会删除其关联逆边。</p>
      <div class="row">
        <el-checkbox v-model="form.transitive">传递</el-checkbox>
        <el-checkbox v-model="form.symmetric">对称</el-checkbox>
      </div>
      <p
        v-if="form.inverseName.trim() || form.symmetric || page.editing.value?.subsetExceeded"
        class="readonly-note"
      >
        逆关系或对称设置超出当前推理子集，可继续手工维护。候选真实检查为 SUBSET_EXCEEDED 时，由
        OWNER 留痕豁免。
      </p>
      <el-form-item label="说明">
        <el-input v-model="form.description" type="textarea" :rows="3" />
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
