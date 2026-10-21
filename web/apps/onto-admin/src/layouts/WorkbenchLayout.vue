<script setup lang="ts">
/** 共享工作台导航与空间授权上下文；切空间先撤销旧上下文，序号隔离迟到结果及错误。 */
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSession } from '../stores/session'
import { useWorkspace } from '../stores/workspace'
import { errorMessage } from '../composables/useResource'
import { pagePermission } from '../composables/pagePermissions'
import { roleLabels } from '../composables/labels'
import RequirementTags from '../components/RequirementTags.vue'
import Breadcrumbs from '../components/Breadcrumbs.vue'
import GlobalSearch from '../components/GlobalSearch.vue'
import AsyncState from '@onto/shared-ui/AsyncState.vue'
const route = useRoute(),
  router = useRouter(),
  session = useSession(),
  workspace = useWorkspace()
const loading = ref(false),
  error = ref(''),
  signingOut = ref(false)
const spaceId = computed(() => {
  if (typeof route.params.spaceId === 'string') return route.params.spaceId
  return typeof route.query.spaceId === 'string' ? route.query.spaceId : ''
})
const userInitial = computed(() => session.user?.displayName?.slice(0, 1) || '我')
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
function accountCommand(command: string) {
  if (command === 'logout') void logout()
  else void router.push('/account')
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
      <div class="nav-label">工作模块</div>
      <router-link
        v-if="session.has('space.read')"
        to="/spaces"
        class="nav-item"
        active-class=""
        :class="{ active: route.path.startsWith('/spaces') }"
      >
        本体建模
      </router-link>
            <router-link
        to="/data"
        class="nav-item"
        active-class=""
        :class="{ active: route.path.startsWith('/data') }"
      >
        知识与数据
      </router-link>
      <router-link
        to="/delivery"
        class="nav-item"
        active-class=""
        :class="{ active: route.path.startsWith('/delivery') }"
      >
        测试与服务封装
      </router-link>
      <router-link
        to="/trace"
        class="nav-item"
        active-class=""
        :class="{ active: route.path.startsWith('/trace') }"
      >
        数据溯源审计
      </router-link>
      <p v-if="!spaceId" class="nav-hint">模块内可筛选“全部本体”或某套本体。</p><div class="nav-label">系统管理</div>
      <router-link
        v-for="tab in systemTabs.filter((item) => session.has(item.permission))"
        :key="tab.path"
        :to="tab.path"
        class="nav-item"
        active-class=""
      >
        {{ tab.label }}
      </router-link>
      <div class="nav-foot">
        本体全流程工作台
        <br />
        草稿 · 冻结候选 · 正式版本
      </div>
    </aside>
    <div class="work-area">
      <header class="topbar">
        <router-link to="/spaces" class="space-context">
          <span class="space-name">{{ workspace.space?.name || '选择本体空间' }}</span>
          <span v-if="workspace.space" class="space-meta">
            r{{ workspace.space.draftRev }} · {{ roleLabels[workspace.space.role] }}
          </span>
          <RequirementTags v-if="route.path.startsWith('/spaces/')" />
          <span v-if="workspace.space" class="space-switch">切换</span>
        </router-link>
        <GlobalSearch v-if="spaceId" />
        <el-dropdown trigger="click" @command="accountCommand">
          <button class="user-chip" type="button" :title="session.user?.displayName">
            <span class="avatar">{{ userInitial }}</span>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="account">账号信息</el-dropdown-item>
              <el-dropdown-item command="logout" divided :disabled="signingOut">
                退出登录
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </header>
      <Breadcrumbs v-if="spaceId" />
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
  height: 100vh;
  overflow: hidden;
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr);
}
.navigation {
  height: 100vh;
  overflow-y: auto;
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
.nav-item.router-link-active,
.nav-item.active {
  background: #284f80;
  color: #fff;
  box-shadow: inset 3px 0 #7ca7ff;
}
.overview-nav.router-link-active:not(.overview-current) {
  background: transparent;
  color: #c4d2e4;
  box-shadow: none;
}
.nav-hint {
  padding: 4px 14px 16px;
  color: #8ea8c6;
  font-size: 12px;
  line-height: 1.6;
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
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: #eef2f7;
}
.topbar {
  flex: 0 0 60px;
  height: 60px;
  background: #fff;
  border-bottom: 1px solid var(--line);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 14px;
  gap: 12px;
}
.topbar .pill {
  margin-left: 8px;
}
.space-context {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  color: var(--text);
}
.space-name {
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.space-meta {
  color: var(--muted);
  font-size: 12px;
}
.space-switch {
  font-size: 12px;
  color: var(--primary);
  border: 1px solid var(--line);
  border-radius: 999px;
  padding: 2px 8px;
}
.user-chip {
  border: 0;
  background: transparent;
  padding: 0;
}
.avatar {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: var(--primary);
  color: #fff;
  font-weight: 600;
}
.main-content {
  flex: 1;
  width: 100%;
  min-height: 0;
  overflow: auto;
  padding: 14px;
  background: #eef2f7;
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
  .nav-hint {
    display: none;
  }
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
