<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { DataBoard, EditPen, Search, Timer } from '@element-plus/icons-vue'
import { NAVIGATION_ITEMS } from '@/config/navigation'
import { useWorkbench } from '@/state/workbench'

const router = useRouter()
const { state: workbench, toggleBottomPanel } = useWorkbench()
const searchRef = ref(null)
const selectedPath = ref('')
function readWorkspaceName() {
  try {
    return localStorage.getItem('seekdataset-workspace-name') || '默认分析工作区'
  } catch {
    return '默认分析工作区'
  }
}

const workspaceName = ref(readWorkspaceName())
const editingName = ref(workspaceName.value)
const workspacePopover = ref(false)
const searchableItems = computed(() => NAVIGATION_ITEMS)

function handleShortcut(event) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    searchRef.value?.focus?.()
  }
}

function openPath(path) {
  if (!path) return
  router.push(path)
  selectedPath.value = ''
}

onMounted(() => window.addEventListener('keydown', handleShortcut))
onBeforeUnmount(() => window.removeEventListener('keydown', handleShortcut))

function saveWorkspaceName() {
  const name = editingName.value.trim().slice(0, 48)
  if (!name) return
  workspaceName.value = name
  try {
    localStorage.setItem('seekdataset-workspace-name', name)
  } catch {
    // Renaming remains available for this session when storage is unavailable.
  }
  workspacePopover.value = false
}
</script>

<template>
  <header class="workbench-topbar">
    <div class="topbar-workspace">
      <span class="workspace-caption">工作区</span>
      <el-popover v-model:visible="workspacePopover" placement="bottom-start" :width="260" trigger="click">
      <template #reference>
        <button type="button" class="workspace-switcher" title="重命名工作区">
          <span class="workspace-mark"><el-icon><DataBoard /></el-icon></span>
          <span class="workspace-name">{{ workspaceName }}</span>
          <el-icon class="workspace-edit"><EditPen /></el-icon>
        </button>
      </template>
      <div class="workspace-rename">
        <span>工作区名称</span>
        <el-input v-model="editingName" maxlength="48" @keyup.enter="saveWorkspaceName" />
        <el-button type="primary" size="small" @click="saveWorkspaceName">保存</el-button>
      </div>
      </el-popover>
    </div>

    <div class="command-search-wrap">
      <el-select
        ref="searchRef"
        v-model="selectedPath"
        class="command-search"
        filterable
        clearable
        :prefix-icon="Search"
        placeholder="转到页面或打开资源"
        @change="openPath"
      >
        <el-option v-for="item in searchableItems" :key="item.key" :label="item.title" :value="item.path">
          <span class="command-option"><el-icon><component :is="item.icon" /></el-icon><span>{{ item.title }}</span><small>{{ item.description }}</small></span>
        </el-option>
      </el-select>
      <kbd>Ctrl K</kbd>
    </div>

    <button type="button" class="topbar-icon-button" :aria-label="workbench.bottomPanelOpen ? '收起任务面板' : '打开任务面板'" :title="workbench.bottomPanelOpen ? '收起任务面板' : '打开任务面板'" @click="toggleBottomPanel">
      <el-icon><Timer /></el-icon>
      <span>任务</span>
    </button>
    <slot />
  </header>
</template>

<style scoped>
.workbench-topbar { position: relative; display: flex; min-width: 0; height: 48px; flex: 0 0 48px; align-items: center; gap: 12px; padding: 0 12px; background: #fff; border-bottom: 1px solid #e3e5e8; }
.topbar-workspace { display: grid; width: clamp(176px, 20vw, 248px); min-width: 0; flex: 0 1 248px; gap: 2px; }
.workspace-caption { padding-left: 2px; color: #707070; font-size: 10px; font-weight: 600; line-height: 1; }
.workspace-switcher { display: flex; min-width: 0; height: 28px; align-items: center; gap: 8px; padding: 0 2px; overflow: hidden; color: #242424; font-size: 13px; font-weight: 620; background: transparent; border: 0; border-radius: 4px; cursor: pointer; }
.workspace-switcher:hover .workspace-name { color: #0f6cbd; }
.workspace-mark { display: grid; width: 19px; height: 19px; flex: 0 0 19px; place-items: center; color: #0f6cbd; background: #ebf3fc; border-radius: 3px; }
.workspace-mark :deep(.el-icon) { font-size: 12px; }
.workspace-name { min-width: 0; overflow: hidden; text-align: left; text-overflow: ellipsis; white-space: nowrap; }
.workspace-edit { flex: 0 0 auto; color: #98a39d; font-size: 11px; opacity: 0; transition: opacity .15s ease; }
.workspace-switcher:hover .workspace-edit, .workspace-switcher:focus-visible .workspace-edit { opacity: 1; }
.command-search-wrap { position: relative; width: min(480px, 42vw); min-width: 220px; flex: 1 1 480px; margin: 0 auto; }
.command-search { width: 100%; }
.command-search :deep(.el-select__wrapper) { min-height: 32px; padding-right: 56px; background: #f3f4f5; border-radius: 4px; box-shadow: 0 0 0 1px #e3e5e8 inset; transition: background .15s ease, box-shadow .15s ease; }
.command-search :deep(.el-select__wrapper:hover), .command-search :deep(.el-select__wrapper.is-focused) { background: #fff; box-shadow: 0 0 0 1px #0f6cbd inset; }
.command-search-wrap kbd { position: absolute; top: 50%; right: 8px; padding: 3px 5px; color: #87928c; font-family: inherit; font-size: 9px; line-height: 1; background: #fff; border: 1px solid #e1e6e3; border-radius: 4px; transform: translateY(-50%); pointer-events: none; }
.command-option { display: grid; min-width: 0; grid-template-columns: 18px auto minmax(0, 1fr); align-items: center; gap: 7px; }
.command-option small { overflow: hidden; color: #8a9690; text-overflow: ellipsis; white-space: nowrap; }
.topbar-icon-button { display: inline-flex; width: max-content; min-width: 38px; height: 32px; flex: 0 0 auto; align-items: center; justify-content: center; gap: 6px; padding: 0 8px; color: #50545a; font-size: 12px; background: transparent; border: 0; border-radius: 4px; cursor: pointer; }
.topbar-icon-button .el-icon { font-size: 15px; }
.topbar-icon-button:hover { color: #0f6cbd; background: #f0f0f0; }
.workspace-rename { display: grid; gap: 9px; color: #53655c; font-size: 11px; }
.workspace-rename .el-button { justify-self: end; }
@media (max-width: 760px) {
  .workbench-topbar { height: 48px; flex-basis: 48px; gap: 10px; padding-inline: 10px; }
  .topbar-workspace { width: 160px; max-width: 180px; flex: 0 1 180px; }
  .command-search-wrap { width: auto; min-width: 0; flex: 1 1 auto; }
  .command-search-wrap kbd { display: none; }
  .command-search :deep(.el-select__wrapper) { padding-right: 12px; }
  .topbar-icon-button { width: 34px; min-width: 34px; padding: 0; }
  .topbar-icon-button span { display: none; }
}
@media (min-width: 761px) and (max-width: 1024px) {
  .topbar-workspace { width: 148px; flex-basis: 148px; }
  .command-search-wrap { min-width: 140px; }
  :slotted(.account-trigger) { max-width: 132px; }
  :slotted(.account-name) { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
}
@media (max-width: 520px) {
  .workbench-topbar { gap: 6px; padding-inline: 9px; }
  .topbar-workspace { width: 132px; max-width: 132px; flex-basis: 132px; }
  .workspace-caption { display: none; }
  .workspace-switcher { height: 34px; gap: 6px; }
  .workspace-mark { width: 18px; height: 18px; flex-basis: 18px; }
  .topbar-icon-button { width: 30px; min-width: 30px; }
  :slotted(.el-dropdown) { flex: 0 0 auto; }
  :slotted(.account-trigger) { width: 32px; justify-content: center; padding: 0; }
  :slotted(.account-trigger .account-name), :slotted(.account-trigger > .el-icon) { display: none; }
}
</style>
