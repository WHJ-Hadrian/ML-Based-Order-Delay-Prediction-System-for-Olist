<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Bell, EditPen, Search } from '@element-plus/icons-vue'
import { NAVIGATION_ITEMS } from '@/config/navigation'

const emit = defineEmits(['open-runs'])
const router = useRouter()
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

function openPath(path) {
  if (!path) return
  router.push(path)
  selectedPath.value = ''
}

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
    <el-popover v-model:visible="workspacePopover" placement="bottom-start" :width="260" trigger="click">
      <template #reference>
        <button type="button" class="workspace-switcher" title="重命名工作区">
          <span class="workspace-mark">S</span>
          <span>{{ workspaceName }}</span>
          <el-icon><EditPen /></el-icon>
        </button>
      </template>
      <div class="workspace-rename">
        <span>工作区名称</span>
        <el-input v-model="editingName" maxlength="48" @keyup.enter="saveWorkspaceName" />
        <el-button type="primary" size="small" @click="saveWorkspaceName">保存</el-button>
      </div>
    </el-popover>

    <el-select
      v-model="selectedPath"
      class="command-search"
      filterable
      clearable
      :prefix-icon="Search"
      placeholder="搜索并打开数据、分析、流程或模型"
      @change="openPath"
    >
      <el-option v-for="item in searchableItems" :key="item.key" :label="item.title" :value="item.path">
        <span class="command-option"><el-icon><component :is="item.icon" /></el-icon><span>{{ item.title }}</span><small>{{ item.description }}</small></span>
      </el-option>
    </el-select>

    <button type="button" class="topbar-icon-button" aria-label="打开运行中心" title="运行中心" @click="emit('open-runs')">
      <el-icon><Bell /></el-icon>
    </button>
    <slot />
  </header>
</template>

<style scoped>
.workbench-topbar { display: flex; height: 46px; flex: 0 0 46px; align-items: center; gap: 10px; padding: 0 12px; background: rgba(248, 250, 249, .96); border-bottom: 1px solid #dce3df; }
.workspace-switcher { display: flex; min-width: 190px; max-width: 270px; height: 32px; align-items: center; gap: 8px; padding: 0 9px; overflow: hidden; color: #2d463c; font-size: 11px; font-weight: 620; background: transparent; border: 0; border-radius: 6px; cursor: pointer; }
.workspace-switcher:hover { background: #eaf0ed; }
.workspace-switcher > span:nth-child(2) { min-width: 0; flex: 1; overflow: hidden; text-align: left; text-overflow: ellipsis; white-space: nowrap; }
.workspace-mark { display: grid; width: 22px; height: 22px; flex: 0 0 22px; place-items: center; color: #fff; font-size: 10px; background: #28775f; border-radius: 5px; }
.command-search { width: min(520px, 42vw); margin: 0 auto; }
.command-search :deep(.el-select__wrapper) { min-height: 32px; background: #fff; border-radius: 6px; box-shadow: 0 0 0 1px #d7e0db inset; }
.command-option { display: grid; min-width: 0; grid-template-columns: 18px auto minmax(0, 1fr); align-items: center; gap: 7px; }
.command-option small { overflow: hidden; color: #8a9690; text-overflow: ellipsis; white-space: nowrap; }
.topbar-icon-button { display: grid; width: 32px; height: 32px; flex: 0 0 32px; place-items: center; color: #52665d; background: transparent; border: 0; border-radius: 6px; cursor: pointer; }
.topbar-icon-button:hover { color: #1d5d49; background: #eaf0ed; }
.workspace-rename { display: grid; gap: 9px; color: #53655c; font-size: 11px; }
.workspace-rename .el-button { justify-self: end; }
@media (max-width: 760px) {
  .workbench-topbar { padding-inline: 8px; }
  .workspace-switcher { min-width: 0; max-width: 150px; }
  .command-search { width: auto; min-width: 0; flex: 1; }
  .command-search :deep(.el-select__placeholder) { font-size: 0; }
  .command-search :deep(.el-select__placeholder)::after { font-size: 11px; content: '搜索'; }
}
@media (max-width: 520px) {
  .workspace-switcher { max-width: 112px; gap: 5px; padding-inline: 5px; }
  .workspace-mark { width: 20px; height: 20px; flex-basis: 20px; }
  .topbar-icon-button { width: 28px; flex-basis: 28px; }
  .workbench-topbar { gap: 4px; }
  :slotted(.account-trigger) { width: 30px; justify-content: center; padding: 0; }
  :slotted(.account-trigger .account-name), :slotted(.account-trigger > .el-icon) { display: none; }
}
</style>
