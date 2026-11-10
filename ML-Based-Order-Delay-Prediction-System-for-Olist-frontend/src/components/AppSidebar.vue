<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NAVIGATION_ITEMS } from '@/config/navigation'

const route = useRoute()
const router = useRouter()
const activeModule = computed(() => NAVIGATION_ITEMS.find((item) => item.key === route.meta.navigationKey)?.group || '')
const moduleItems = computed(() => NAVIGATION_ITEMS.filter((item) => item.group && item.group === activeModule.value))
const activeKey = computed(() => route.meta.hideNavigationActive ? '' : route.meta.navigationKey || '')

function navigate(item) {
  if (item && route.path !== item.path) router.push(item.path)
}
</script>

<template>
  <aside class="app-sidebar" aria-label="当前模块导航">
    <nav class="sidebar-navigation">
      <section v-if="moduleItems.length" class="navigation-group" :aria-label="activeModule">
        <button
          v-for="item in moduleItems"
          :key="item.key"
          type="button"
          class="navigation-item"
          :class="{ 'is-active': activeKey === item.key }"
          :aria-current="activeKey === item.key ? 'page' : undefined"
          :aria-label="item.title"
          :title="item.title"
          @click="navigate(item)"
        >
          <el-icon><component :is="item.icon" /></el-icon>
        </button>
      </section>
    </nav>
  </aside>
</template>

<style scoped>
.app-sidebar { display: flex; width: var(--navigation-rail-width); height: 100%; flex: 0 0 var(--navigation-rail-width); flex-direction: column; overflow: hidden; color: #30363b; background: #f7f7f7; border-right: 1px solid #e3e5e8; }
.sidebar-navigation { min-height: 0; flex: 1 1 auto; overflow: auto; padding: 8px 0 16px; scrollbar-width: thin; }
.navigation-group { margin-top: 0; }
.navigation-item + .navigation-item { margin-top: 2px; }
.navigation-item { display: flex; width: 38px; min-width: 38px; min-height: 36px; align-items: center; justify-content: center; margin-inline: auto; padding: 0; color: #51585e; background: transparent; border: 0; border-radius: 3px; cursor: pointer; }
.navigation-item:hover { color: #24292e; background: #ededed; }
.navigation-item.is-active { color: #285444; background: #e8efec; font-weight: 620; }
.navigation-item .el-icon { width: 16px; flex: 0 0 16px; color: #798188; font-size: 16px; }
.navigation-item.is-active .el-icon { color: var(--accent); }
@media (max-width: 760px) { .app-sidebar { position: fixed; z-index: 20; top: 48px; bottom: 0; left: 0; width: var(--navigation-rail-width); height: calc(100dvh - 48px); border-right: 1px solid #e3e5e8; } }
</style>
