import { createRouter, createWebHistory } from 'vue-router'
import {
  DEFAULT_NAVIGATION_PATH,
  NAVIGATION_ITEMS,
} from '@/config/navigation'

const routes = [
  {
    path: '/',
    redirect: DEFAULT_NAVIGATION_PATH,
  },
  ...NAVIGATION_ITEMS.map(({ component, ...item }) => ({
    path: item.path,
    name: item.key,
    component,
    meta: { navigationKey: item.key },
  })),
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
