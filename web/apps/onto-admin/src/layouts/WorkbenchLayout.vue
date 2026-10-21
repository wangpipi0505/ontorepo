<script setup lang="ts">
/** 共享工作台导航与空间授权上下文；切空间先撤销旧上下文，序号隔离迟到结果及错误。 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSession } from '../stores/session'
import { useWorkspace } from '../stores/workspace'
import { errorMessage } from '../composables/useResource'
import { pagePermission, spacePagePermissions } from '../composables/pagePermissions'
import { roleLabels } from '../composables/labels'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
const route = useRoute(),
  router = useRouter(),
  session = useSession(),
  workspace = useWorkspace()
const loading = ref(false),
  error = ref(''),
  signingOut = ref(false)
const spaceId = computed(() =>
  typeof route.params.spaceId === 'string' ? route.params.spaceId : '',
)
watch(
  () => route.path,
  (path) => {
    workspace.writePermission = pagePermission(path)?.write || ''
  },
  { immediate: true, flush: 'sync' },
)
const systemTabs = [
  { path: '/system/users', label: '用户管理', permission: 'system.user.read' },
  { path: '/system/roles', label: '角色与权限', permission: 'system.role.read' },
  { path: '/system/audit', label: '系统管理审计', permission: 'system.audit.read' },
]
let sequence = 0
async function loadSpace() {
  const ticket = ++sequence
  error.value = ''
  loading.value = !!spaceId.value
  if (!spaceId.value) {
    workspace.clear()
    return
  }
  try {
    await workspace.load(spaceId.value)
  } catch (cause) {
    if (ticket === sequence) error.value = errorMessage(cause)
  } finally {
    if (ticket === sequence) loading.value = false
  }
}
watch(spaceId, loadSpace, { immediate: true })
const tabs = [
  { path: 'model', label: '对象与属性' },
  { path: 'codebooks', label: '码表管理' },
  { path: 'relations', label: '关系与逆关系' },
  { path: 'constraints', label: '约束管理' },
  { path: 'rules', label: '规则与评分' },
  { path: 'conflicts', label: '冲突检查' },
  { path: 'strategies', label: '执行策略' },
  { path: 'operations', label: '模型操作' },
  { path: 'assistant', label: '助手声明' },
  { path: 'knowledge', label: '知识管理' },
  { path: 'guards', label: '空间守卫' },
  { path: 'grants', label: '主体授权', ownerOnly: true },
  { path: 'datasources', label: '数据源管理' },
  { path: 'mappings', label: '三层映射' },
  { path: 'suggestions', label: '三来源建模抽取' },
  { path: 'data-catalog', label: '数据目录' },
  { path: 'imports', label: 'TTL / OWL 导入导出' },
  { path: 'test-cases', label: '用例管理' },
  { path: 'candidates', label: '候选与试执行' },
  { path: 'supply', label: '候选供给' },
  { path: 'executions', label: '执行记录' },
  { path: 'publication', label: '候选发布' },
  { path: 'releases', label: '正式版本' },
  { path: 'consumption', label: '服务消费' },
  { path: 'events', label: '事件与收件箱' },
  { path: 'audit', label: '审计记录' },
]
const groups = [
  {
    label: '概念建模',
    paths: [
      'model',
      'codebooks',
      'relations',
      'constraints',
      'rules',
      'conflicts',
      'strategies',
      'operations',
      'assistant',
    ],
  },
  {
    label: '知识与数据',
    paths: [
      'knowledge',
      'datasources',
      'mappings',
      'suggestions',
      'data-catalog',
      'imports',
    ],
  },
  { label: '操作与测试', paths: ['test-cases', 'candidates', 'supply', 'executions'] },
  { label: '发布与服务', paths: ['publication', 'releases', 'consumption', 'events'] },
  { label: '溯源审计', paths: ['audit'] },
  { label: '空间设置', paths: ['guards', 'grants'] },
]
const visibleGroups = computed(() =>
  groups
    .map((group) => ({
      label: group.label,
      tabs: tabs.filter(
        (tab) =>
          group.paths.includes(tab.path) &&
          session.has(spacePagePermissions[tab.path].read) &&
          (!tab.ownerOnly || workspace.space?.role === 'OWNER'),
      ),
    }))
    .filter((group) => group.tabs.length > 0),
)
const openGroups = ref<string[]>([])
function activeGroup() {
  return groups.find((group) => group.paths.some((path) => route.path.endsWith(`/${path}`)))
    ?.label
}
watch(
  () => route.path,
  () => {
    const label = activeGroup()
    if (label && !openGroups.value.includes(label))
      openGroups.value = [...openGroups.value, label]
  },
  { immediate: true },
)
function toggleGroup(label: string, event: Event) {
  const open = (event.target as HTMLDetailsElement).open
  openGroups.value = open
    ? [...new Set([...openGroups.value, label])]
    : openGroups.value.filter((item) => item !== label)
}
async function logout() {
  if (signingOut.value) return
  signingOut.value = true
  try {
    await session.logout()
  } catch {
    /* 即使撤销请求失败，本地令牌也已在 finally 清理。 */
  } finally {
    signingOut.value = false
    await router.replace('/login')
  }
}
</script>
<template>
  <div class="workbench">
    <aside class="navigation">
      <router-link to="/spaces" class="brand">
        <span class="brand-symbol">本</span>
        <span>
          领域本体
          <small>建模工作台</small>
        </span>
      </router-link>
      <div class="nav-label">工作空间</div>
      <router-link v-if="session.has('space.read')" to="/spaces" class="nav-item">
        空间管理
      </router-link>
      <router-link to="/account" class="nav-item">当前账号</router-link>
      <router-link
        v-for="tab in systemTabs.filter((item) => session.has(item.permission))"
        :key="tab.path"
        :to="tab.path"
        class="nav-item"
      >
        {{ tab.label }}
      </router-link>
      <template v-if="spaceId">
        <div class="nav-label">{{ workspace.space?.name || '当前空间' }}</div>
        <router-link
          :to="`/spaces/${spaceId}`"
          class="nav-item overview-nav"
          :class="{ 'overview-current': route.path === `/spaces/${spaceId}` }"
        >
          空间概览
        </router-link>
        <details
          v-for="group in visibleGroups"
          :key="group.label"
          class="nav-group"
          :open="openGroups.includes(group.label)"
          @toggle="toggleGroup(group.label, $event)"
        >
          <summary>{{ group.label }}</summary>
          <router-link
            v-for="tab in group.tabs"
            :key="tab.path"
            :to="`/spaces/${spaceId}/${tab.path}`"
            class="nav-item"
          >
            {{ tab.label }}
          </router-link>
        </details>
      </template>
      <div class="nav-foot">
        本体全流程工作台
        <br />
        草稿 · 冻结候选 · 正式版本
      </div>
    </aside>
    <div class="work-area">
      <header class="topbar">
        <span class="muted">
          {{ workspace.space?.name || '工作空间' }}
          <template v-if="workspace.space">
            /
            <span class="mono">r{{ workspace.space.draftRev }}</span>
            <span class="pill">{{ roleLabels[workspace.space.role] }}</span>
          </template>
        </span>
        <div class="row">
          <span class="muted">{{ session.user?.displayName }}</span>
          <el-button text :loading="signingOut" @click="logout">退出登录</el-button>
        </div>
      </header>
      <main class="main-content">
        <div v-if="workspace.stale" class="readonly-note" role="alert">
          草稿已被其他编辑更新，当前空间已暂停写入。请关闭编辑框，并使用页面的刷新按钮载入最新内容后重新编辑。
        </div>
        <AsyncState :loading="loading" :error="error" @retry="loadSpace">
          <template v-if="workspace.space?.status === 'ARCHIVED'">
            <div class="readonly-note">此空间已归档，可浏览已有模型、候选与执行记录。</div>
          </template>
          <template v-else-if="workspace.space?.role === 'VIEWER'">
            <div class="readonly-note">你拥有查看权限，编辑与试执行需要空间编辑者授权。</div>
          </template>
          <router-view v-if="!spaceId || workspace.space" :key="spaceId" />
        </AsyncState>
      </main>
    </div>
  </div>
</template>
<style scoped>
.workbench {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr);
}
.navigation {
  background: var(--nav);
  color: #bed0e5;
  padding: 28px 16px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.brand {
  display: flex;
  gap: 12px;
  align-items: center;
  color: white;
  font-size: 20px;
  font-weight: 600;
  margin: 0 8px 34px;
}
.brand small {
  display: block;
  font-size: 11px;
  color: #98b2d0;
  letter-spacing: 2px;
  margin-top: 5px;
}
.brand-symbol {
  display: grid;
  place-items: center;
  border: 1px solid #6887b4;
  border-radius: 6px;
  width: 36px;
  height: 40px;
  font-size: 22px;
}
.nav-label {
  font-size: 11px;
  color: #8ea8c6;
  padding: 20px 14px 8px;
  overflow-wrap: anywhere;
}
.nav-item {
  padding: 12px 14px;
  border-radius: 6px;
  color: #c4d2e4;
  font-size: 14px;
}
.nav-item:hover {
  background: #203c60;
}
.nav-item.router-link-active {
  background: #284f80;
  color: #fff;
  box-shadow: inset 3px 0 #7ca7ff;
}
.overview-nav.router-link-active:not(.overview-current) {
  background: transparent;
  color: #c4d2e4;
  box-shadow: none;
}
.nav-foot {
  margin-top: auto;
  padding: 40px 14px 0;
  font-size: 11px;
  line-height: 1.8;
  color: #8ea8c6;
}
.nav-group {
  width: 100%;
  border-top: 1px solid #34506f;
  margin-top: 12px;
  padding-top: 8px;
}
.nav-group summary {
  cursor: pointer;
  padding: 12px 14px;
  color: #dce8f5;
  font-size: 14px;
}
.nav-group .nav-item {
  display: block;
  margin-top: 4px;
}
.work-area {
  min-width: 0;
}
.topbar {
  height: 66px;
  background: #fff;
  border-bottom: 1px solid var(--line);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 32px;
  gap: 12px;
}
.topbar .pill {
  margin-left: 8px;
}
.main-content {
  padding: 30px 32px;
  max-width: 1600px;
  margin: auto;
}
@media (max-width: 900px) {
  .workbench {
    grid-template-columns: 176px minmax(0, 1fr);
  }
  .main-content {
    padding: 24px 18px;
  }
  .topbar {
    padding: 0 18px;
  }
}
@media (max-width: 650px) {
  .workbench {
    display: block;
  }
  .navigation {
    padding: 14px;
    display: flex;
    flex-direction: row;
    flex-wrap: wrap;
    align-items: center;
    gap: 4px;
  }
  .brand {
    font-size: 16px;
    margin: 0 16px 0 0;
  }
  .brand-symbol {
    width: 28px;
    height: 32px;
    font-size: 18px;
  }
  .brand small,
  .nav-label,
  .nav-foot {
    display: none;
  }
  .nav-item {
    font-size: 12px;
    padding: 9px;
  }
  .nav-group {
    flex-basis: 100%;
    margin-top: 8px;
  }
  .nav-group summary {
    font-size: 12px;
    padding: 8px;
  }
  .nav-group .nav-item {
    display: inline-block;
    margin: 0 4px 4px 0;
  }
  .topbar {
    height: auto;
    min-height: 58px;
    flex-wrap: wrap;
    padding: 10px 16px;
  }
  .main-content {
    padding: 22px 12px;
  }
}
</style>
