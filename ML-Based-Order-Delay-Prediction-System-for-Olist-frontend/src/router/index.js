import { createRouter, createWebHistory } from 'vue-router'
import {
  DEFAULT_NAVIGATION_PATH,
  NAVIGATION_ITEMS,
} from '@/config/navigation'

const authStudyPage = () => import('@/views/AuthStudyView.vue')
const settingsPage = () => import('@/views/SettingsView.vue')

const routes = [
  {
    path: '/',
    redirect: DEFAULT_NAVIGATION_PATH,
  },
  {
    path: '/login',
    name: 'login',
    component: authStudyPage,
    meta: { fullscreen: true },
  },
  {
    path: '/settings',
    name: 'settings',
    component: settingsPage,
    meta: { hideNavigationActive: true, fullscreen: true },
  },
  ...NAVIGATION_ITEMS.map(({ component, ...item }) => ({
    path: item.path,
    name: item.key,
    component,
    meta: { navigationKey: item.key },
  })),
  { path: '/projects', redirect: (to) => ({ path: '/workspace', query: to.query }) },
  { path: '/sources', redirect: (to) => ({ path: '/datasets', query: to.query }) },
  { path: '/features', redirect: (to) => ({ path: '/experiments', query: to.query }) },
  { path: '/training-runs', redirect: (to) => ({ path: '/experiments', query: to.query }) },
  { path: '/tracking', redirect: (to) => ({ path: '/registry', query: to.query }) },
  { path: '/deployments', redirect: (to) => ({ path: '/registry', query: to.query }) },
  { path: '/governance', redirect: (to) => ({ path: '/settings', query: to.query }) },
  { path: '/models', redirect: (to) => ({ path: '/experiments', query: to.query }) },
  { path: '/model-trials', redirect: (to) => ({ path: '/evaluation', query: to.query }) },
  { path: '/predictions', redirect: (to) => ({ path: '/evaluation', query: to.query }) },
  { path: '/rules', redirect: (to) => ({ path: '/quality', query: to.query }) },
  { path: '/workflows', redirect: (to) => ({ path: '/pipelines', query: to.query }) },
  { path: '/workflow-runs', redirect: (to) => ({ path: '/pipeline-runs', query: to.query }) },
  {
    path: '/:pathMatch(.*)*',
    redirect: DEFAULT_NAVIGATION_PATH,
  },
]

export default createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})
