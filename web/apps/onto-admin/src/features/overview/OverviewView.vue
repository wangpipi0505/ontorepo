<script setup lang="ts">
/** 草稿冲突与正式版本分别读取，冲突为零也不推断五项发布门禁通过。 */
import { computed, watch } from 'vue'
import { useWorkspace } from '../../stores/workspace'
import { useOverviewCards } from './useOverviewCards'
import OverviewMetrics from './OverviewMetrics.vue'
const workspace = useWorkspace()
const overview = useOverviewCards(() => (workspace.space ? [workspace.space] : []))
const state = computed(() =>
  workspace.space ? overview.states.value[overview.key(workspace.space)] : undefined,
)
function mappingIssueTo(issue: { entityId?: string; field?: string }) {
  const params = new URLSearchParams()
  if (issue.entityId) params.set('entityId', issue.entityId)
  if (issue.field) params.set('field', issue.field)
  return `/spaces/${workspace.space?.id}/mappings?${params.toString()}`
}
const mappingKindLabels: Record<string, string> = {
  CLASS_OBJECT: '类与对象映射',
  REQUIRED_PROPERTY: '必填属性',
  RELATION_KEYS: '关系连接键',
  REACHABLE_REFERENCE: '可达引用',
}
const links = [
  ['model', '对象与属性', '管理模型层级与属性分面'],
  ['relations', '关系', '维护方向、基数与逆关系'],
  ['constraints', '约束', '定义范围、依赖与互斥条件'],
  ['rules', '规则与评分', '定义校验规则与评分计算'],
  ['strategies', '执行策略', '配置操作资源和读取范围'],
  ['operations', '操作', '管理查询与可执行操作'],
  ['data-catalog', '数据目录', '查看数据源与映射关系'],
  ['releases', '版本动态', '浏览正式版本、差异与弃用'],
  ['publication', '候选发布', '检查候选并完成发布门禁'],
]
watch(
  () => workspace.space?.id,
  () => overview.ensure(),
)
const readiness = computed(() => {
  const value = state.value?.value
  if (!value) return []
  const mapping = value.mappings.value
  const mappingDone =
    !!mapping && mapping.checks.length >= 4 && mapping.checks.every((check) => check.passed)
  const space = value.space
  return [
    {
      key: 'classes',
      label: '对象',
      detail: `${space.classCount} 个`,
      done: space.classCount > 0,
      to: 'model',
    },
    {
      key: 'properties',
      label: '属性',
      detail: `${space.propertyCount} 个`,
      done: space.propertyCount > 0,
      to: 'model',
    },
    {
      key: 'relations',
      label: '关系',
      detail: value.relations.value === null ? '读取失败' : `${value.relations.value} 条`,
      done: (value.relations.value ?? 0) > 0,
      to: 'relations',
    },
    {
      key: 'rules',
      label: '规则',
      detail: value.rules.value === null ? '读取失败' : `${value.rules.value} 条`,
      done: (value.rules.value ?? 0) > 0,
      to: 'rules',
    },
    {
      key: 'mappings',
      label: '映射四项',
      detail: mapping ? (mappingDone ? '全部通过' : `${mapping.coverage.pending} 项待补`) : '读取失败',
      done: mappingDone,
      to: 'mappings',
    },
    {
      key: 'assistant',
      label: '助手声明',
      detail:
        value.readiness.value === null
          ? '读取失败'
          : `${value.readiness.value.assistantCount} 个操作`,
      done: (value.readiness.value?.assistantCount ?? 0) > 0,
      to: 'assistant',
    },
    {
      key: 'guards',
      label: '守卫绑定',
      detail:
        value.readiness.value === null
          ? '读取失败'
          : `${value.readiness.value.guardCount} 项`,
      done: (value.readiness.value?.guardCount ?? 0) > 0,
      to: 'guards',
    },
    {
      key: 'cases',
      label: '启用用例',
      detail: value.readiness.value === null ? '读取失败' : `${value.readiness.value.caseCount} 条`,
      done: (value.readiness.value?.caseCount ?? 0) > 0,
      to: 'test-cases',
    },
    {
      key: 'candidates',
      label: '候选冻结',
      detail:
        value.readiness.value === null
          ? '读取失败'
          : `${value.readiness.value.candidateCount} 个`,
      done: (value.readiness.value?.candidateCount ?? 0) > 0,
      to: 'candidates',
    },
    {
      key: 'release',
      label: '正式版本',
      detail: value.release.value ? `v${value.release.value.versionNo}` : '尚无发布',
      done: !!value.release.value,
      to: 'publication',
    },
  ]
})
const nextStep = computed(() => readiness.value.find((item) => !item.done))
</script>
<template>
  <div v-if="workspace.space" class="stack">
    <div class="page-heading">
      <div>
        <h1>{{ workspace.space.name }} · 空间概览</h1>
        <p class="muted">
          {{ workspace.space.domain || '暂无领域说明' }} · {{ workspace.space.code }} · 草稿 r{{
            workspace.space.draftRev
          }}
        </p>
      </div>
      <el-button :disabled="state?.loading" @click="overview.retry(workspace.space)">
        刷新概览
      </el-button>
    </div>
    <section class="panel overview-summary">
      <div class="object-summary">
        <span class="muted">当前草稿对象</span>
        <strong>{{ state?.value?.space.classCount ?? workspace.space.classCount }}</strong>
        <span class="muted">
          {{ state?.value?.space.propertyCount ?? workspace.space.propertyCount }} 属性 ·
          {{ state?.value?.space.operationCount ?? workspace.space.operationCount }} 操作
        </span>
      </div>
      <OverviewMetrics :state="state" @retry="overview.retry(workspace.space)" />
    </section>
    <section class="panel stack">
      <div class="row spread">
        <h2>发布就绪度</h2>
        <span class="muted">基于当前草稿与最新映射检查，不替代候选五项门禁</span>
      </div>
      <p v-if="!readiness.length" class="muted">
        {{ state?.loading ? '正在读取就绪度…' : state?.error || '就绪度暂不可用' }}
      </p>
      <ul v-else class="readiness">
        <li v-for="item in readiness" :key="item.key" :class="{ done: item.done }">
          <span class="readiness-mark">{{ item.done ? '✓' : '○' }}</span>
          <b>{{ item.label }}</b>
          <span class="muted">{{ item.detail }}</span>
          <RouterLink :to="`/spaces/${workspace.space.id}/${item.to}`">
            {{ item.done ? '查看' : '去处理' }}
          </RouterLink>
        </li>
      </ul>
      <p v-if="nextStep" class="notice">
        下一步：先处理「{{ nextStep.label }}」（{{ nextStep.detail }}）。
        <RouterLink :to="`/spaces/${workspace.space.id}/${nextStep.to}`">前往处理</RouterLink>
      </p>
      <p v-else-if="readiness.length" class="notice">
        基础就绪度已完成；继续用「候选发布」完成机器门禁、人工复核与提交。
        <RouterLink :to="`/spaces/${workspace.space.id}/publication`">前往发布</RouterLink>
      </p>
    </section>
    <nav class="entry-grid" aria-label="空间设计入口">
      <router-link
        v-for="[path, label, note] in links"
        :key="path"
        :to="`/spaces/${workspace.space.id}/${path}`"
        class="panel entry"
      >
        <strong>{{ label }} →</strong>
        <span class="muted">{{ note }}</span>
      </router-link>
    </nav>
    <section class="panel stack">
      <h2>草稿校验问题</h2>
      <p class="muted">以下为当前草稿冲突检测；正式发布仍需候选五项门禁。</p>
      <p
        v-if="
          state?.value?.conflicts.value &&
          !state.value.conflicts.value.errors.length &&
          !state.value.conflicts.value.warnings.length
        "
      >
        未发现草稿冲突。
      </p>
      <template v-if="state?.value?.conflicts.value">
        <article
          v-for="(issue, i) in [
            ...state.value.conflicts.value.errors,
            ...state.value.conflicts.value.warnings,
          ]"
          :key="i"
          class="issue"
        >
          <strong>{{ issue.kind }}</strong>
          · {{ issue.message }}
          <p class="muted">关联实体：{{ issue.entityIds.join('、') || '空间' }}</p>
        </article>
      </template>
      <p v-else class="muted">
        {{ state?.value?.conflicts.error || state?.error || '正在读取校验问题…' }}
      </p>
    </section>
    <section class="panel stack">
      <h2>映射完整性问题</h2>
      <template v-if="state?.value?.mappings.value">
        <p v-if="!state.value.mappings.value.checks.length">尚未检查映射完整性。</p>
        <article v-for="check in state.value.mappings.value.checks" :key="check.kind" class="issue">
          <strong>{{ mappingKindLabels[check.kind] ?? check.kind }} · {{ check.passed ? '检查完成，未发现问题' : '未通过' }}</strong>
          <p v-for="(issue, i) in check.issues" :key="i">
            {{ issue.message }} · {{ issue.entityId }} {{ issue.field }}
            <RouterLink
              :to="mappingIssueTo(issue)"
            >
              去映射工作台定位 →
            </RouterLink>
          </p>
        </article>
        <router-link :to="`/spaces/${workspace.space.id}/mappings`">
          前往映射工作台处理 →
        </router-link>
      </template>
      <p v-else class="muted">
        {{ state?.value?.mappings.error || state?.error || '正在读取映射完整性…' }}
      </p>
    </section>
  </div>
</template>
<style scoped>
.readiness {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  gap: 8px;
}
.readiness li {
  display: grid;
  grid-template-columns: 24px 110px minmax(0, 1fr) auto;
  gap: 10px;
  align-items: center;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 6px;
}
.readiness li.done {
  background: #f0fdf4;
  border-color: #bbf7d0;
}
.readiness-mark {
  color: var(--muted);
  text-align: center;
}
.readiness li.done .readiness-mark {
  color: #15803d;
}
.overview-summary {
  display: grid;
  grid-template-columns: minmax(180px, 1fr) 2fr;
  gap: 32px;
}
.object-summary {
  display: flex;
  flex-direction: column;
  gap: 14px;
  border-right: 1px solid var(--line);
}
.object-summary strong {
  font-size: 46px;
  color: var(--primary);
}
.entry-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 14px;
}
.entry {
  display: flex;
  flex-direction: column;
  gap: 12px;
  color: var(--text);
}
.entry:hover {
  border-color: var(--primary);
}
.issue {
  border-left: 3px solid var(--primary);
  padding-left: 12px;
  overflow-wrap: anywhere;
}
@media (max-width: 600px) {
  .overview-summary {
    grid-template-columns: 1fr;
    gap: 12px;
  }
  .object-summary {
    border-right: 0;
    border-bottom: 1px solid var(--line);
    padding-bottom: 16px;
  }
  .entry-grid {
    grid-template-columns: 1fr;
  }
}
</style>
