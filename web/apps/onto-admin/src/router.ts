import { createRouter, createWebHistory } from 'vue-router'
import { useSession } from './stores/session'
import { pagePermission } from './composables/pagePermissions'
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('./views/LoginView.vue') },
    {
      path: '/',
      component: () => import('./layouts/WorkbenchLayout.vue'),
      children: [
        { path: '', redirect: '/spaces' },
        { path: 'account', component: () => import('./views/AccountView.vue') },
        { path: 'system/users', component: () => import('./features/system/UsersView.vue') },
        { path: 'system/roles', component: () => import('./features/system/RolesView.vue') },
        { path: 'system/audit', component: () => import('./features/system/IamAuditView.vue') },
        { path: 'spaces', component: () => import('./views/SpacesView.vue') },
        {
          path: 'spaces/:spaceId',
          component: () => import('./features/overview/OverviewView.vue'),
        },
        {
          path: 'spaces/:spaceId/model',
          component: () => import('./views/ModelHubView.vue'),
        },
        {
          path: 'spaces/:spaceId/codebooks',
          redirect: (to) => `/spaces/${String(to.params.spaceId)}/model?tab=codebooks`,
        },
        {
          path: 'spaces/:spaceId/relations',
          redirect: (to) => `/spaces/${String(to.params.spaceId)}/model?tab=relations`,
        },
        {
          path: 'spaces/:spaceId/constraints',
          redirect: (to) => `/spaces/${String(to.params.spaceId)}/model?tab=constraints`,
        },
        {
          path: 'spaces/:spaceId/conflicts',
          redirect: (to) => `/spaces/${String(to.params.spaceId)}/model?tab=conflicts`,
        },
        {
          path: 'spaces/:spaceId/rules',
          redirect: (to) => `/spaces/${String(to.params.spaceId)}/model?tab=rules`,
        },
        {
          path: 'spaces/:spaceId/strategies',
          component: () => import('./features/p1/StrategiesView.vue'),
        },
        {
          path: 'spaces/:spaceId/imports',
          component: () => import('./features/p1/ImportsView.vue'),
        },
        {
          path: 'spaces/:spaceId/test-cases',
          redirect: (to) => `/spaces/${String(to.params.spaceId)}/testing?tab=cases`,
        },
        {
          path: 'spaces/:spaceId/testing',
          component: () => import('./views/TestingView.vue'),
        },
        {
          path: 'spaces/:spaceId/operations',
          component: () => import('./views/OperationsView.vue'),
        },
        {
          path: 'spaces/:spaceId/assistant',
          component: () => import('./features/p2/AssistantView.vue'),
        },
        {
          path: 'spaces/:spaceId/guards',
          redirect: (to) => `/spaces/${String(to.params.spaceId)}/settings?tab=guards`,
        },
        {
          path: 'spaces/:spaceId/grants',
          redirect: (to) => `/spaces/${String(to.params.spaceId)}/settings?tab=members`,
        },
        {
          path: 'spaces/:spaceId/settings',
          component: () => import('./views/SettingsView.vue'),
        },
        {
          path: 'spaces/:spaceId/knowledge',
          component: () => import('./features/p2/KnowledgeView.vue'),
        },
        {
          path: 'spaces/:spaceId/supply',
          redirect: (to) => `/spaces/${String(to.params.spaceId)}/testing?tab=supply`,
        },
        {
          path: 'spaces/:spaceId/datasources',
          component: () => import('./features/p3/SourcesView.vue'),
        },
        {
          path: 'spaces/:spaceId/mappings',
          component: () => import('./features/p3/MappingsView.vue'),
        },
        {
          path: 'spaces/:spaceId/data-query',
          redirect: (to) => `/spaces/${String(to.params.spaceId)}/operations`,
        },
        {
          path: 'spaces/:spaceId/suggestions',
          component: () => import('./features/p3/SuggestionsView.vue'),
        },
        {
          path: 'spaces/:spaceId/data-catalog',
          component: () => import('./features/p3/DataCatalogView.vue'),
        },
        {
          path: 'spaces/:spaceId/candidates',
          redirect: (to) => `/spaces/${String(to.params.spaceId)}/testing?tab=candidates`,
        },
        {
          path: 'spaces/:spaceId/executions',
          component: () => import('./views/ExecutionsView.vue'),
        },
        {
          path: 'spaces/:spaceId/publication',
          component: () => import('./features/p4/PublicationView.vue'),
        },
        {
          path: 'spaces/:spaceId/releases',
          component: () => import('./views/ReleaseHubView.vue'),
        },
        {
          path: 'spaces/:spaceId/consumption',
          component: () => import('./features/p4/ConsumptionView.vue'),
        },
        {
          path: 'spaces/:spaceId/events',
          redirect: (to) => ({
            path: `/spaces/${String(to.params.spaceId)}/releases`,
            query: { ...to.query, tab: 'events' },
          }),
        },
        {
          path: 'spaces/:spaceId/audit',
          component: () => import('./features/p4/AuditView.vue'),
        },
        {
          path: 'spaces/:spaceId/executions/:executionId/trace',
          component: () => import('./features/p4/TraceView.vue'),
        },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/spaces' },
  ],
})
router.beforeEach(async (to) => {
  const session = useSession()
  if (!session.token) return to.path === '/login' ? true : '/login'
  if (to.path === '/login' || to.path === '/account') return true
  try {
    await session.refreshPermissions()
  } catch {
    return session.token ? '/account' : '/login'
  }
  const policy = pagePermission(to.path)
  return policy && session.has(policy.read) ? true : '/account'
})
