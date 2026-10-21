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
        { path: 'spaces/:spaceId/model', component: () => import('./views/ModelView.vue') },
        {
          path: 'spaces/:spaceId/codebooks',
          component: () => import('./features/p1/CodebooksView.vue'),
        },
        {
          path: 'spaces/:spaceId/relations',
          component: () => import('./features/p1/RelationsView.vue'),
        },
        {
          path: 'spaces/:spaceId/constraints',
          component: () => import('./features/p1/ConstraintsView.vue'),
        },
        {
          path: 'spaces/:spaceId/conflicts',
          component: () => import('./features/p1/ConflictsView.vue'),
        },
        { path: 'spaces/:spaceId/rules', component: () => import('./features/p1/RulesView.vue') },
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
        { path: 'spaces/:spaceId/guards', component: () => import('./features/p2/GuardsView.vue') },
        { path: 'spaces/:spaceId/grants', component: () => import('./features/p2/GrantsView.vue') },
        {
          path: 'spaces/:spaceId/knowledge',
          component: () => import('./features/p2/KnowledgeView.vue'),
        },
        { path: 'spaces/:spaceId/supply', component: () => import('./features/p2/SupplyView.vue') },
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
          component: () => import('./features/p4/ReleasesView.vue'),
        },
        {
          path: 'spaces/:spaceId/consumption',
          component: () => import('./features/p4/ConsumptionView.vue'),
        },
        {
          path: 'spaces/:spaceId/events',
          component: () => import('./features/p4/EventsView.vue'),
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
