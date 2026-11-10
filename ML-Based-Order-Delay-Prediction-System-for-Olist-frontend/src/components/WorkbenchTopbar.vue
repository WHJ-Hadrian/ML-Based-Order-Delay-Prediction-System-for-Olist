<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, ArrowRight, Clock, Plus, Search } from '@element-plus/icons-vue'
import { NAVIGATION_ITEMS } from '@/config/navigation'
import { useAuth } from '@/auth/keycloak'
import brandMark from '@/assets/img/chahua.png'

const route = useRoute()
const router = useRouter()
const auth = useAuth()
const modules = [
  { key: '数据', label: '数据' },
  { key: '建模', label: '建模' },
  { key: '自动化', label: '自动化' },
  { key: '概览', label: '概览' },
]
const activeModule = computed(() => NAVIGATION_ITEMS.find((item) => item.key === route.meta.navigationKey)?.group || '')
const quickPages = NAVIGATION_ITEMS.map(({ key, path, title }) => ({ key, path, title }))
const contextCommand = computed(() => ({
  datasets: { key: 'dataset:create', label: '新建数据集' },
  quality: { key: 'rules:create', label: '新建规则库' },
  fields: { key: 'fields:create', label: '新建字段库' },
  analysis: { key: 'analysis:create', label: '新建分析方案' },
  experiments: { key: 'experiment:create', label: '新建实验' },
  pipelines: { key: 'pipeline:create', label: '新建流水线' },
})[route.meta.navigationKey] || null)

function openModule(module) {
  const firstPage = NAVIGATION_ITEMS.find((item) => item.group === module.key)
  if (firstPage) router.push(firstPage.path)
}

function openPage(path) {
  if (path) router.push(path)
}

function executeContextCommand() {
  if (contextCommand.value) {
    window.dispatchEvent(new CustomEvent('workbench:command', { detail: contextCommand.value.key }))
  }
}
</script>

<template>
  <header class="workbench-topbar">
    <button type="button" class="brand-mark" aria-label="返回概览" title="返回概览" @click="router.push('/workspace')">
      <img :src="brandMark" alt="" />
    </button>
    <nav class="global-navigation" aria-label="业务模块">
      <button
        v-for="module in modules"
        :key="module.key"
        type="button"
        class="global-navigation-item"
        :class="{ 'is-active': activeModule === module.key }"
        :aria-current="activeModule === module.key ? 'page' : undefined"
        @click="openModule(module)"
      >{{ module.label }}</button>
    </nav>
    <div class="toolbar-tools" aria-label="全局工具">
      <el-button-group class="history-tools">
        <el-button text aria-label="后退" title="后退" :icon="ArrowLeft" @click="router.back()" />
        <el-button text aria-label="前进" title="前进" :icon="ArrowRight" @click="router.forward()" />
      </el-button-group>
      <el-select class="quick-open" filterable clearable :prefix-icon="Search" placeholder="快速打开页面" @change="openPage">
        <el-option v-for="page in quickPages" :key="page.key" :label="page.title" :value="page.path" />
      </el-select>
      <el-button v-if="contextCommand && auth.state.authenticated" type="primary" :icon="Plus" :aria-label="contextCommand.label" :title="contextCommand.label" @click="executeContextCommand">{{ contextCommand.label }}</el-button>
      <el-button text :icon="Clock" aria-label="运行记录" title="运行记录" @click="router.push('/pipeline-runs')">运行记录</el-button>
    </div>
    <div class="global-actions"><slot /></div>
  </header>
</template>

<style scoped>
.workbench-topbar { position: relative; display: flex; min-width: 0; height: 48px; flex: 0 0 48px; align-items: center; padding: 0 16px 0 0; background: #fff; border-bottom: 1px solid #e3e5e8; }
.brand-mark { display: grid; width: var(--navigation-rail-width); height: 48px; flex: 0 0 var(--navigation-rail-width); place-items: center; padding: 0; background: transparent; border: 0; cursor: pointer; }
.brand-mark img { display: block; width: 42px; height: 32px; object-fit: contain; }
.global-navigation { display: flex; min-width: 0; height: 100%; align-items: stretch; }
.global-navigation-item { position: relative; width: var(--navigation-rail-width); min-width: var(--navigation-rail-width); padding: 0; color: #59625d; font-size: 12px; font-weight: 550; background: transparent; border: 0; cursor: pointer; }
.global-navigation-item:hover, .global-navigation-item.is-active { color: #285444; }
.global-navigation-item.is-active { font-weight: 650; }
.global-navigation-item.is-active::after { position: absolute; right: 0; bottom: 0; left: 0; height: 2px; background: #356b58; content: ''; }
.global-actions { display: flex; min-width: 0; align-items: center; margin-left: auto; }
.toolbar-tools { display: flex; min-width: 0; align-items: center; gap: 6px; margin-left: 18px; }
.history-tools { display: inline-flex; flex: 0 0 auto; }
.quick-open { width: 190px; }
.quick-open :deep(.el-select__wrapper) { min-height: 30px; }
.topbar-icon-button { display: inline-flex; width: max-content; min-width: 38px; height: 32px; flex: 0 0 auto; align-items: center; justify-content: center; gap: 6px; padding: 0 8px; color: #50545a; font-size: 12px; background: transparent; border: 0; border-radius: 4px; cursor: pointer; }
.topbar-icon-button .el-icon { font-size: 15px; }
.topbar-icon-button:hover { color: var(--accent); background: #f0f0f0; }
@media (max-width: 760px) {
  .workbench-topbar { height: 48px; flex-basis: 48px; padding-right: 12px; }
  .global-navigation-item { font-size: 11px; }
  .toolbar-tools { margin-left: 10px; }
  .toolbar-tools { gap: 2px; }
  .quick-open { width: 150px; }
  .toolbar-tools :deep(.el-button) { padding-inline: 7px; }
  .topbar-icon-button { width: 34px; min-width: 34px; padding: 0; }
  .topbar-icon-button span { display: none; }
}
@media (min-width: 761px) and (max-width: 1024px) {
  :slotted(.account-trigger) { max-width: 132px; }
  :slotted(.account-name) { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
}
@media (max-width: 520px) {
  .workbench-topbar { padding-right: 8px; }
  .brand-mark img { width: 36px; height: 28px; }
  .toolbar-tools { margin-left: 6px; }
  .history-tools { display: none; }
  .quick-open { display: none; }
  .toolbar-tools > :deep(.el-button:last-child) { display: none; }
  .toolbar-tools > :deep(.el-button) { font-size: 0; }
  .toolbar-tools > :deep(.el-button .el-icon) { margin: 0; font-size: 14px; }
  .toolbar-tools :deep(.el-button--primary) { width: 31px; padding-inline: 0; }
  .toolbar-tools :deep(.el-button--primary span) { display: none; }
  :slotted(.el-dropdown) { flex: 0 0 auto; }
  :slotted(.account-trigger) { width: 32px; justify-content: center; padding: 0; }
  :slotted(.account-trigger .account-name), :slotted(.account-trigger > .el-icon) { display: none; }
}
</style>
