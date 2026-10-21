<script setup lang="ts">
/** 不可变快照、元数据差异与受影响引用分别展示，便于定位映射失效原因。 */
import type { MetadataSnapshot, MetadataDiff, SourceProbe } from '@onto/api-client'
defineProps<{
  snapshot: MetadataSnapshot | null
  diff: MetadataDiff | null
  probe: SourceProbe | null
}>()
const stages = { CONNECTIVITY: '连通性', AUTHENTICATION: '身份认证', SCOPE: '访问范围' }
const statuses = { PASSED: '通过', FAILED: '失败', NOT_RUN: '未执行' }
</script>
<template>
  <div class="stack">
    <section v-if="probe" class="panel">
      <h2>连接三阶段 · {{ probe.testedAt }}</h2>
      <el-table :data="probe.stages">
        <el-table-column label="阶段">
          <template #default="{ row }">{{ stages[row.stage as keyof typeof stages] }}</template>
        </el-table-column>
        <el-table-column label="结果">
          <template #default="{ row }">
            {{ statuses[row.status as keyof typeof statuses] }}
          </template>
        </el-table-column>
        <el-table-column prop="code" label="代码" />
        <el-table-column prop="message" label="说明" />
      </el-table>
    </section>
    <section v-if="diff" class="panel">
      <h2>元数据变更影响</h2>
      <template
        v-for="(changes, label) in { 新增: diff.added, 移除: diff.removed, 变化: diff.changed }"
        :key="label"
      >
        <h3>{{ label }} · {{ changes.length }}</h3>
        <dl v-for="item in changes" :key="item.path" class="details-grid">
          <dt>{{ item.kind }} {{ item.path }}</dt>
          <dd>
            之前：{{ JSON.stringify(item.before) ?? '无' }}
            <br />
            之后：{{ JSON.stringify(item.after) ?? '无' }}
          </dd>
        </dl>
      </template>
      <h3>受影响映射 · {{ diff.affectedMappings.length }}</h3>
      <p v-for="item in diff.affectedMappings" :key="item.id">
        {{ item.kind }} {{ item.id }}：{{ item.reason }}；操作
        {{ item.operationIds.join('、') || '无' }}
      </p>
      <p class="muted">旧候选保留冻结快照；当前映射需重新校验后再生成候选。</p>
    </section>
    <section v-if="snapshot" class="panel stack">
      <h2>元数据快照</h2>
      <p class="mono">
        {{ snapshot.id }} · {{ snapshot.capturedAt }}
        <br />
        SHA-256：{{ snapshot.sha256 }}
      </p>
      <div v-for="resource in snapshot.resources" :key="resource.name">
        <h3>{{ resource.name }}</h3>
        <p class="muted">主键：{{ resource.primaryKey.join('、') || '未声明' }}</p>
        <el-table :data="resource.fields">
          <el-table-column prop="name" label="字段" />
          <el-table-column prop="type" label="类型" />
          <el-table-column label="可空">
            <template #default="{ row }">{{ row.nullable ? '是' : '否' }}</template>
          </el-table-column>
        </el-table>
        <p class="muted">
          索引：{{
            resource.indexes
              .map((item) => `${item.name} (${item.columns.join(', ')})`)
              .join('；') || '未提供'
          }}；关系：{{
            resource.relations
              .map((item) => `${item.fromColumn} → ${item.targetResource}.${item.targetColumn}`)
              .join('；') || '未提供'
          }}
        </p>
      </div>
    </section>
  </div>
</template>
