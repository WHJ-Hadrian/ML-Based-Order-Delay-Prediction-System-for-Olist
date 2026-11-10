<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowRight,
  Box,
  Collection,
  Connection,
  Cpu,
  DataAnalysis,
  Files,
  Fold,
  Grid,
  HomeFilled,
  MagicStick,
  Search,
  SetUp,
  Setting,
  Timer,
} from '@element-plus/icons-vue'
import { NAVIGATION_ITEMS, findNavigationItemByPath } from '@/config/navigation'
import { useWorkbench } from '@/state/workbench'

const props = defineProps({
  collapsed: { type: Boolean, default: false },
  total: { type: Number, default: 0 },
})

const emit = defineEmits(['update:collapsed', 'open-settings'])
const route = useRoute()
const router = useRouter()
const { documents } = useWorkbench()
const treeOpen = ref(false)
const activePanel = ref('explorer')
const selectedTool = ref('explorer')
const searchQuery = ref('')
const expandedGroups = reactive({
  data: true,
  modeling: true,
  automation: true,
})

const activities = [
  { key: 'explorer', label: '项目', icon: Files },
  { key: 'search', label: '搜索', icon: Search },
  { key: 'runs', label: '运行', icon: Timer },
]

const treeGroups = [
  {
    key: 'data',
    label: '数据资产',
    items: [
      { key: 'datasets', label: '数据集' },
      { key: 'analysis', label: '数据分析' },
      { key: 'fields', label: '字段定义' },
      { key: 'rules', label: '质量规则' },
    ],
  },
  {
    key: 'modeling',
    label: '建模与实验',
    items: [
      { key: 'models', label: '训练实验' },
      { key: 'trials', label: '模型试用' },
    ],
  },
  {
    key: 'automation',
    label: '流水线',
    items: [
      { key: 'workflows', label: '工作流' },
      { key: 'workflow-runs', label: '运行历史' },
    ],
  },
]

const itemIcons = {
  workspace: HomeFilled,
  datasets: Collection,
  analysis: DataAnalysis,
  models: Cpu,
  trials: MagicStick,
  workflows: Connection,
  'workflow-runs': Timer,
  fields: Grid,
  rules: SetUp,
}

const activeKey = computed(() => route.meta.hideNavigationActive ? '' : route.meta.navigationKey || findNavigationItemByPath(route.path).key)
const searchableItems = computed(() => {
  const query = searchQuery.value.trim().toLocaleLowerCase()
  if (!query) return []
  return NAVIGATION_ITEMS.filter((item) => `${item.title} ${item.description}`.toLocaleLowerCase().includes(query))
})
const recentDocuments = computed(() => [...documents.value].reverse().slice(0, 5))
const selectedActivity = computed(() => selectedTool.value)

function itemFor(key) {
  return NAVIGATION_ITEMS.find((item) => item.key === key)
}

function selectActivity(key) {
  if (key === 'runs') {
    selectedTool.value = 'runs'
    router.push('/workflow-runs')
    treeOpen.value = false
    return
  }

  const isSamePanel = activePanel.value === key
  const isOpenPanel = isSamePanel && !props.collapsed
  activePanel.value = key
  selectedTool.value = key
  if (props.collapsed) emit('update:collapsed', false)

  if (window.matchMedia('(max-width: 760px)').matches) {
    treeOpen.value = !(isSamePanel && treeOpen.value)
  } else if (isOpenPanel) {
    emit('update:collapsed', true)
  }
}

function selectItem(key) {
  activePanel.value = 'explorer'
  selectedTool.value = 'explorer'
  const item = itemFor(key)
  if (item && route.path !== item.path) router.push(item.path)
  treeOpen.value = false
}

function openRecent(path) {
  activePanel.value = 'search'
  selectedTool.value = 'search'
  if (path) router.push(path)
  treeOpen.value = false
}

function toggleGroup(key) {
  expandedGroups[key] = !expandedGroups[key]
}

function toggleExplorer() {
  if (window.matchMedia('(max-width: 760px)').matches) {
    treeOpen.value = !treeOpen.value
    return
  }
  emit('update:collapsed', !props.collapsed)
}

function openSettings() {
  emit('open-settings')
}

watch(() => route.path, () => {
  const key = route.meta.navigationKey || findNavigationItemByPath(route.path).key
  if (key === 'workflow-runs') selectedTool.value = 'runs'
  else if (selectedTool.value === 'runs') selectedTool.value = activePanel.value
  treeOpen.value = false
}, { immediate: true })
</script>

<template>
  <aside class="app-sidebar" :class="{ 'is-collapsed': collapsed, 'is-tree-open': treeOpen }" aria-label="工作区工具">
    <nav class="activity-rail" aria-label="工具窗口">
      <div class="activity-brand" aria-label="SeekDateSet">SD</div>
      <div class="activity-list">
        <button
          v-for="item in activities"
          :key="item.key"
          type="button"
          class="activity-button"
          :class="{ 'is-active': selectedActivity === item.key }"
          :aria-label="`${item.label}${item.key === 'runs' ? '中心' : '视图'}`"
          :aria-pressed="item.key === 'runs' ? activeKey === 'workflow-runs' : activePanel === item.key && !collapsed"
          :title="item.key === 'runs' ? '运行中心' : `${item.label}视图`"
          @click="selectActivity(item.key)"
        >
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </button>
      </div>
      <div class="activity-tools">
        <button type="button" class="activity-button" :aria-label="collapsed ? '展开工具窗口' : '收起工具窗口'" :title="collapsed ? '展开工具窗口' : '收起工具窗口'" @click="toggleExplorer"><el-icon><Fold /></el-icon></button>
        <button type="button" class="activity-button" aria-label="设置" title="设置" @click="openSettings"><el-icon><Setting /></el-icon></button>
      </div>
    </nav>

    <section v-if="activePanel === 'explorer'" class="side-panel explorer-pane" aria-label="工作区资源">
      <header class="panel-heading">
        <div class="panel-heading-copy">
          <span class="panel-kicker">工作区</span>
          <h2>资源管理器</h2>
        </div>
        <button type="button" class="panel-toggle" :aria-label="collapsed ? '展开工具窗口' : '收起工具窗口'" :title="collapsed ? '展开工具窗口' : '收起工具窗口'" @click="toggleExplorer"><el-icon><Fold /></el-icon></button>
      </header>

      <nav class="explorer-tree" aria-label="工作区资源树">
        <button
          type="button"
          class="tree-item workspace-home-item"
          :class="{ 'is-active': activeKey === 'workspace' }"
          :aria-current="activeKey === 'workspace' ? 'page' : undefined"
          title="工作区概览"
          @click="selectItem('workspace')"
        >
          <el-icon><component :is="HomeFilled" /></el-icon>
          <span>概览</span>
        </button>
        <section v-for="group in treeGroups" :key="group.key" class="tree-group">
          <button type="button" class="tree-group-heading" :aria-expanded="expandedGroups[group.key]" @click="toggleGroup(group.key)">
            <el-icon :class="{ 'is-expanded': expandedGroups[group.key] }"><ArrowRight /></el-icon>
            <span>{{ group.label }}</span>
          </button>
          <div v-if="expandedGroups[group.key]" class="tree-group-items">
            <button
              v-for="item in group.items"
              :key="item.key"
              type="button"
              class="tree-item"
              :class="{ 'is-active': activeKey === item.key }"
              :aria-current="activeKey === item.key ? 'page' : undefined"
              :title="item.label"
              @click="selectItem(item.key)"
            >
              <el-icon><component :is="itemIcons[item.key] || Box" /></el-icon>
              <span>{{ item.label }}</span>
              <small v-if="item.key === 'datasets'">{{ total }}</small>
            </button>
          </div>
        </section>
      </nav>

      <div class="explorer-footer"><span class="workspace-health"><i aria-hidden="true"></i>工作区就绪</span></div>
    </section>

    <section v-else-if="activePanel === 'search'" class="side-panel search-pane" aria-label="资源搜索">
      <header class="panel-heading">
        <div class="panel-heading-copy">
          <span class="panel-kicker">快速定位</span>
          <h2>搜索资源</h2>
        </div>
        <button type="button" class="panel-toggle" :aria-label="collapsed ? '展开工具窗口' : '收起工具窗口'" :title="collapsed ? '展开工具窗口' : '收起工具窗口'" @click="toggleExplorer"><el-icon><Fold /></el-icon></button>
      </header>
      <el-input v-model="searchQuery" class="resource-search" clearable :prefix-icon="Search" placeholder="搜索页面或资源" aria-label="搜索页面或资源" />
      <div class="search-results" aria-live="polite">
        <template v-if="searchQuery.trim()">
          <span class="search-section-label">匹配项 · {{ searchableItems.length }}</span>
          <button v-for="item in searchableItems" :key="item.key" type="button" class="search-result" @click="openRecent(item.path)">
            <el-icon><component :is="item.icon" /></el-icon>
            <span><strong>{{ item.title }}</strong><small>{{ item.description }}</small></span>
          </button>
          <p v-if="!searchableItems.length" class="search-empty">没有匹配的资源</p>
        </template>
        <template v-else>
          <span class="search-section-label">最近打开</span>
          <button v-for="item in recentDocuments" :key="item.path" type="button" class="search-result" @click="openRecent(item.path)">
            <el-icon><component :is="item.icon" /></el-icon>
            <span><strong>{{ item.title }}</strong><small>{{ item.description }}</small></span>
          </button>
          <p v-if="!recentDocuments.length" class="search-empty">输入名称查找工作区资源</p>
        </template>
      </div>
    </section>
  </aside>
</template>

<style scoped>
.app-sidebar { position: relative; z-index: 16; display: flex; width: 260px; height: 100dvh; flex: 0 0 260px; overflow: hidden; color: #424242; background: #f7f7f7; border-right: 1px solid #e3e5e8; transition: width .18s ease, flex-basis .18s ease; }
.activity-rail { display: flex; width: 52px; height: 100%; flex: 0 0 52px; flex-direction: column; align-items: center; background: #f3f4f5; border-right: 1px solid #e3e5e8; }
.activity-brand { display: grid; width: 30px; height: 30px; flex: 0 0 30px; place-items: center; margin: 11px 0 16px; color: #0f6cbd; font-size: 10px; font-weight: 750; background: #ebf3fc; border: 1px solid #d8e8f8; border-radius: 4px; }
.activity-list, .activity-tools { display: grid; width: 100%; justify-items: center; gap: 5px; }
.activity-tools { margin-top: auto; padding: 8px 0; border-top: 1px solid #e3e5e8; }
.activity-button { position: relative; display: grid; width: 40px; height: 42px; place-items: center; padding: 3px 0; color: #667078; background: transparent; border: 0; border-radius: 4px; cursor: pointer; }
.activity-button .el-icon { font-size: 18px; }
.activity-button > span { display: none; }
.activity-button:hover { color: #242424; background: #e8eaed; }
.activity-button.is-active { color: #0f6cbd; background: #e7f1fb; }
.activity-button.is-active::before { position: absolute; top: 5px; bottom: 5px; left: -6px; width: 2px; background: #0f6cbd; content: ''; }
.activity-button:focus-visible { outline: 2px solid #0f6cbd; outline-offset: 1px; }
.side-panel { display: flex; min-width: 0; min-height: 0; flex: 1 1 auto; flex-direction: column; background: #f7f7f7; }
.app-sidebar.is-collapsed .side-panel { flex: 0 0 0; overflow: hidden; opacity: 0; pointer-events: none; }
.panel-heading { display: flex; min-width: 0; height: 58px; flex: 0 0 58px; align-items: center; justify-content: space-between; gap: 8px; padding: 0 10px 0 13px; border-bottom: 1px solid #e3e5e8; }
.panel-heading-copy { display: grid; min-width: 0; gap: 3px; }
.panel-kicker { color: #70777e; font-size: 9px; line-height: 1; }
.panel-heading h2 { margin: 0; overflow: hidden; color: #242424; font-size: 13px; font-weight: 650; text-overflow: ellipsis; white-space: nowrap; }
.panel-toggle { display: grid; width: 26px; height: 26px; flex: 0 0 26px; place-items: center; color: #70777e; background: transparent; border: 0; border-radius: 4px; cursor: pointer; }
.panel-toggle:hover { color: #0f6cbd; background: #e8eaed; }
.explorer-tree { min-height: 0; flex: 1 1 auto; overflow: auto; padding: 8px 6px; scrollbar-width: thin; }
.tree-group + .tree-group { margin-top: 3px; }
.tree-group-heading { display: flex; width: 100%; height: 25px; align-items: center; gap: 5px; padding: 0 6px; color: #626a70; font-size: 10px; font-weight: 620; text-align: left; background: transparent; border: 0; border-radius: 3px; cursor: pointer; }
.tree-group-heading:hover { color: #242424; background: #ededed; }
.tree-group-heading .el-icon { color: #7b8288; font-size: 11px; transition: transform .12s ease; }
.tree-group-heading .el-icon.is-expanded { transform: rotate(90deg); }
.tree-group-items { padding-left: 13px; }
.tree-item { display: flex; width: 100%; min-width: 0; height: 29px; align-items: center; gap: 8px; padding: 0 8px; color: #50545a; font-size: 11px; text-align: left; background: transparent; border: 0; border-radius: 3px; cursor: pointer; }
.tree-item:hover { color: #242424; background: #ededed; }
.tree-item.is-active { color: #0f6cbd; font-weight: 620; background: #e7f1fb; }
.tree-item .el-icon { width: 16px; flex: 0 0 16px; color: #7a838a; font-size: 14px; }
.tree-item.is-active .el-icon { color: #0f6cbd; }
.tree-item > span { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tree-item small { min-width: 18px; margin-left: auto; padding: 2px 4px; color: #70777e; font-size: 9px; font-variant-numeric: tabular-nums; text-align: center; background: #eceef0; border-radius: 3px; }
.tree-item.is-active small { color: #0f6cbd; background: #fff; }
.explorer-footer { display: flex; height: 30px; flex: 0 0 30px; align-items: center; padding: 0 12px; border-top: 1px solid #e3e5e8; }
.workspace-health { display: inline-flex; align-items: center; gap: 6px; color: #70777e; font-size: 9px; }
.workspace-health i { width: 6px; height: 6px; border-radius: 50%; background: #107c10; }
.resource-search { margin: 9px 9px 0; width: calc(100% - 18px); }
.resource-search :deep(.el-input__wrapper) { min-height: 31px; border-radius: 4px; }
.search-results { min-width: 0; flex: 1; overflow: auto; padding: 10px 7px; }
.search-section-label { display: block; margin: 0 7px 6px; color: #70777e; font-size: 9px; font-weight: 620; }
.search-result { display: flex; width: 100%; min-width: 0; align-items: flex-start; gap: 8px; padding: 7px; color: #50545a; text-align: left; background: transparent; border: 0; border-radius: 3px; cursor: pointer; }
.search-result:hover { background: #ededed; }
.search-result > .el-icon { flex: 0 0 auto; margin-top: 1px; color: #727b82; font-size: 14px; }
.search-result > span { display: grid; min-width: 0; gap: 2px; }
.search-result strong, .search-result small { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.search-result strong { color: #303438; font-size: 10px; font-weight: 600; }
.search-result small { color: #7a8288; font-size: 9px; }
.search-empty { margin: 8px 7px; color: #70777e; font-size: 10px; line-height: 1.5; }
.app-sidebar.is-collapsed { width: 52px; flex-basis: 52px; }
@media (min-width: 761px) and (max-width: 1100px) { .app-sidebar { width: 232px; flex-basis: 232px; } }
@media (max-width: 760px) {
  .app-sidebar, .app-sidebar.is-collapsed { position: fixed; right: 10px; bottom: calc(10px + env(safe-area-inset-bottom)); left: 10px; width: auto; height: 58px; flex-basis: auto; overflow: visible; background: #fff; border: 1px solid #dfe3e8; border-radius: 6px; box-shadow: 0 4px 16px rgba(27, 38, 48, .12); }
  .activity-rail { width: 100%; flex-basis: 100%; flex-direction: row; justify-content: space-around; padding: 3px 5px; overflow-x: auto; background: #fff; border: 0; border-radius: inherit; }
  .activity-brand, .activity-tools { display: none; }
  .activity-list { display: flex; width: max-content; min-width: 100%; height: 100%; align-items: center; justify-content: space-around; gap: 1px; }
  .activity-button { width: 64px; height: 48px; flex: 0 0 64px; gap: 1px; padding: 2px 0; font-size: 9px; }
  .activity-button .el-icon { font-size: 16px; }
  .activity-button > span { display: block; position: static; padding: 0; color: inherit; font-size: inherit; line-height: 1; background: transparent; }
  .activity-button.is-active::before { top: auto; right: 9px; bottom: -3px; left: 9px; width: auto; height: 2px; }
  .side-panel { position: absolute; right: 0; bottom: calc(100% + 8px); left: 0; height: min(55dvh, 420px); flex: 0 0 auto; overflow: hidden; background: #fff; border: 1px solid #dfe3e8; border-radius: 6px; box-shadow: 0 8px 28px rgba(27, 38, 48, .16); visibility: hidden; pointer-events: none; }
  .app-sidebar.is-tree-open .side-panel { visibility: visible; pointer-events: auto; }
  .panel-heading { height: 48px; flex-basis: 48px; }
  .explorer-footer { height: 26px; flex-basis: 26px; }
}
</style>
