<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuth } from '@/auth/keycloak'
import { listDatasets } from '@/api/datasets'
import { listAnalysisWorkspaces } from '@/api/datasets'
import { listWorkflows } from '@/api/workflows'
import { searchWorkflowRuns } from '@/api/workflows'
import { listModelVersions } from '@/api/models'
import {
  Collection, Cpu, DataAnalysis, MagicStick, Plus, Refresh, Share,
} from '@element-plus/icons-vue'

const router = useRouter()
const auth = useAuth()
const loading = ref(false)
const loadError = ref('')
const datasets = ref([])
const datasetTotal = ref(0)
const analyses = ref([])
const workflows = ref([])
const models = ref([])
const workflowRunSummary = ref({ active: 0, total: 0 })
const workspaceName = ref(readWorkspaceName())

function readWorkspaceName() {
  try { return localStorage.getItem('seekdataset-workspace-name') || '默认分析工作区' }
  catch { return '默认分析工作区' }
}

const resourceGroups = computed(() => [
  {
    key: 'datasets', title: '数据集', icon: Collection, path: '/datasets',
    items: datasets.value.slice(0, 5).map((item) => ({
      id: item.id || item.datasetId,
      title: item.name || item.originalFilename || '未命名数据集',
      detail: `${item.status || '数据集'}${item.tableCount == null ? '' : ` · ${item.tableCount} 张表`}`,
      path: `/datasets?dataset=${encodeURIComponent(item.id || item.datasetId)}`,
    })),
  },
  {
    key: 'analyses', title: '分析方案', icon: DataAnalysis, path: '/analysis',
    items: analyses.value.slice(0, 5).map((item) => ({
      id: item.id,
      title: item.name || '未命名分析方案',
      detail: item.updatedAt ? `更新于 ${formatTime(item.updatedAt)}` : '已保存',
      path: '/analysis',
    })),
  },
  {
    key: 'workflows', title: '工作流', icon: Share, path: '/workflows',
    items: workflows.value.slice(0, 5).map((item) => ({
      id: item.id,
      title: item.name || '未命名工作流',
      detail: item.updatedAt ? `更新于 ${formatTime(item.updatedAt)}` : '工作流画布',
      path: `/workflows?workflow=${encodeURIComponent(item.id)}`,
    })),
  },
  {
    key: 'models', title: '模型版本', icon: Cpu, path: '/models',
    items: models.value.slice(0, 5).map((item) => ({
      id: item.id,
      title: item.name || '未命名模型',
      detail: `${item.task === 'classification' ? '分类' : '回归'} · ${item.algorithm || '模型'}`,
      path: `/model-trials?modelId=${encodeURIComponent(item.id)}`,
    })),
  },
])

const resourceCount = computed(() => datasets.value.length + analyses.value.length + workflows.value.length + models.value.length)

function formatTime(value) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric' }).format(date)
}

async function loadResources() {
  loading.value = true
  loadError.value = ''
  const requests = await Promise.allSettled([
    listDatasets({ page: 0, size: 100 }),
    listAnalysisWorkspaces(),
    listWorkflows(),
    listModelVersions(),
    searchWorkflowRuns({ page: 0, size: 100 }),
    searchWorkflowRuns({ page: 0, size: 1, status: 'QUEUED' }),
    searchWorkflowRuns({ page: 0, size: 1, status: 'RUNNING' }),
    searchWorkflowRuns({ page: 0, size: 1, status: 'CANCELLING' }),
  ])
  const [datasetResult, analysisResult, workflowResult, modelResult, runsResult, queuedResult, runningResult, cancellingResult] = requests
  datasets.value = datasetResult.status === 'fulfilled' ? datasetResult.value?.items || [] : []
  datasetTotal.value = datasetResult.status === 'fulfilled' ? datasetResult.value?.totalElements || 0 : 0
  analyses.value = analysisResult.status === 'fulfilled' ? analysisResult.value || [] : []
  workflows.value = workflowResult.status === 'fulfilled' ? workflowResult.value || [] : []
  models.value = modelResult.status === 'fulfilled' ? modelResult.value || [] : []
  workflowRunSummary.value = {
    active: [queuedResult, runningResult, cancellingResult]
      .reduce((sum, result) => sum + (result.status === 'fulfilled' ? result.value?.totalElements || 0 : 0), 0),
    total: runsResult.status === 'fulfilled' ? runsResult.value?.totalElements || 0 : 0,
  }
  const failures = requests.filter((result) => result.status === 'rejected')
  if (failures.length === requests.length) loadError.value = '资源服务暂不可用，请检查登录状态或后端服务。'
  else if (failures.length) loadError.value = `${failures.length} 类资源暂时无法加载，其他资源仍可使用。`
  loading.value = false
}

function openItem(item) {
  router.push(item.path)
}

onMounted(() => {
  if (auth.state.status === 'authenticated') loadResources()
})

import { watch } from 'vue'
watch(() => auth.state.status, (status) => {
  if (status === 'authenticated') loadResources()
  if (status === 'anonymous') {
    datasets.value = []
    analyses.value = []
    workflows.value = []
    models.value = []
    workflowRunSummary.value = { active: 0, total: 0 }
  }
})
</script>

<template>
  <main class="workspace-home">
    <header class="workspace-heading">
      <div>
        <div class="workspace-breadcrumb"><span>工作区</span><span>/</span><strong>{{ workspaceName }}</strong></div>
        <h1>{{ workspaceName }}</h1>
        <p>{{ resourceCount }} 项可访问资源</p>
      </div>
      <el-button :icon="Refresh" :loading="loading" @click="loadResources">刷新</el-button>
    </header>

    <div v-if="loadError" class="workspace-notice" role="status">
      <span>{{ loadError }}</span><el-button text :icon="Refresh" @click="loadResources">重试</el-button>
    </div>

    <section class="overview-metrics" aria-label="项目概览">
      <div class="overview-metric"><span>数据集</span><strong>{{ datasetTotal }}</strong></div>
      <div class="overview-metric"><span>分析方案</span><strong>{{ analyses.length }}</strong></div>
      <div class="overview-metric"><span>模型版本</span><strong>{{ models.length }}</strong></div>
      <button type="button" class="overview-metric is-action" @click="router.push('/workflow-runs')">
        <span>工作流运行</span><strong>{{ workflowRunSummary.active }} <small>运行中</small></strong><small class="metric-total">共 {{ workflowRunSummary.total }} 次</small>
      </button>
    </section>

    <section class="workspace-actions" aria-label="创建或打开工作内容">
      <button type="button" class="action-item" @click="router.push('/datasets')">
        <el-icon><Plus /></el-icon><span><strong>添加数据</strong><small>上传文件或新建数据集</small></span>
      </button>
      <button type="button" class="action-item" @click="router.push('/analysis')">
        <el-icon><DataAnalysis /></el-icon><span><strong>新建分析</strong><small>探索字段与数据关系</small></span>
      </button>
      <button type="button" class="action-item" @click="router.push('/workflows')">
        <el-icon><Share /></el-icon><span><strong>打开工作流</strong><small>编排可复用的数据流程</small></span>
      </button>
      <button type="button" class="action-item" @click="router.push('/models')">
        <el-icon><Cpu /></el-icon><span><strong>训练模型</strong><small>配置训练实验与算法</small></span>
      </button>
      <button type="button" class="action-item" @click="router.push('/model-trials')">
        <el-icon><MagicStick /></el-icon><span><strong>试用模型</strong><small>用独立数据验证或预测</small></span>
      </button>
    </section>

    <section class="resource-section" aria-label="工作区资源">
      <header class="resource-heading"><h2>资源</h2><span>最近访问</span></header>
      <div class="resource-grid">
        <section v-for="group in resourceGroups" :key="group.key" class="resource-group">
          <header>
            <div><el-icon><component :is="group.icon" /></el-icon><h3>{{ group.title }}</h3></div>
            <button type="button" :aria-label="`查看全部${group.title}`" :title="`查看全部${group.title}`" @click="router.push(group.path)">查看全部</button>
          </header>
          <div v-if="loading" class="resource-loading">正在读取…</div>
          <button v-for="item in group.items" v-else :key="item.id" type="button" class="resource-row" :title="item.title" @click="openItem(item)">
            <span class="resource-row-copy"><strong>{{ item.title }}</strong><small>{{ item.detail }}</small></span>
          </button>
          <div v-if="!loading && !group.items.length" class="resource-empty">暂无{{ group.title }}</div>
        </section>
      </div>
    </section>
  </main>
</template>

<style scoped>
.workspace-home { width: min(100%, 1440px); min-width: 0; margin: 0 auto; padding: 24px 28px 40px; color: #242424; }
.workspace-heading { display: flex; min-width: 0; align-items: center; justify-content: space-between; gap: 16px; padding-bottom: 18px; border-bottom: 1px solid #e5e7e9; }
.workspace-breadcrumb { display: flex; min-width: 0; align-items: center; gap: 8px; color: #737a80; font-size: 11px; }
.workspace-breadcrumb strong { overflow: hidden; color: #495057; font-weight: 550; text-overflow: ellipsis; white-space: nowrap; }
.workspace-heading h1 { margin: 8px 0 3px; color: #24292e; font-size: 22px; font-weight: 650; line-height: 1.25; }
.workspace-heading p { margin: 0; color: #737a80; font-size: 11px; }
.workspace-notice { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-top: 12px; padding: 8px 10px; color: #655525; background: #fbf8ed; border: 1px solid #eee5c9; font-size: 11px; }
.overview-metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); margin-top: 14px; border-top: 1px solid #e5e7e9; border-left: 1px solid #e5e7e9; }
.overview-metric { display: grid; min-width: 0; min-height: 66px; align-content: center; gap: 4px; padding: 8px 12px; color: #747c82; background: #fff; border: 0; border-right: 1px solid #e5e7e9; border-bottom: 1px solid #e5e7e9; text-align: left; }
.overview-metric > span { font-size: 10px; }
.overview-metric > strong { color: #303840; font-size: 18px; font-weight: 650; font-variant-numeric: tabular-nums; }
.overview-metric.is-action { cursor: pointer; }
.overview-metric.is-action:hover { background: #f5f8fb; }
.overview-metric.is-action > strong { display: flex; align-items: baseline; gap: 5px; color: #286da8; font-size: 16px; }
.overview-metric.is-action > strong small, .metric-total { color: #828a90; font-size: 9px; font-weight: 450; }
.metric-total { margin-top: -5px; }
.workspace-actions { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 0; margin: 16px 0 26px; border-top: 1px solid #e5e7e9; border-bottom: 1px solid #e5e7e9; }
.action-item { display: flex; min-width: 0; min-height: 68px; align-items: center; gap: 10px; padding: 10px 12px; color: #394149; text-align: left; background: transparent; border: 0; cursor: pointer; }
.action-item + .action-item { border-left: 1px solid #e5e7e9; }
.action-item:hover { background: #f5f7f8; }
.action-item > .el-icon { display: grid; width: 28px; height: 28px; flex: 0 0 28px; place-items: center; color: #286da8; background: #edf4fa; font-size: 15px; }
.action-item > span { display: grid; min-width: 0; gap: 4px; }
.action-item strong, .action-item small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.action-item strong { color: #303840; font-size: 11px; font-weight: 620; }
.action-item small { color: #7a8288; font-size: 9px; }
.resource-heading { display: flex; align-items: baseline; gap: 10px; margin-bottom: 10px; }
.resource-heading h2 { margin: 0; color: #303840; font-size: 14px; font-weight: 650; }
.resource-heading > span { color: #8a9197; font-size: 10px; }
.resource-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); border-top: 1px solid #e5e7e9; border-left: 1px solid #e5e7e9; }
.resource-group { min-width: 0; min-height: 190px; border-right: 1px solid #e5e7e9; border-bottom: 1px solid #e5e7e9; }
.resource-group > header { display: flex; height: 38px; align-items: center; justify-content: space-between; gap: 8px; padding: 0 10px; background: #f7f8f9; border-bottom: 1px solid #e5e7e9; }
.resource-group > header > div { display: flex; min-width: 0; align-items: center; gap: 7px; }
.resource-group > header .el-icon { color: #68757f; font-size: 13px; }
.resource-group h3 { margin: 0; color: #454d54; font-size: 11px; font-weight: 620; }
.resource-group > header button { padding: 3px 4px; color: #1769aa; font-size: 10px; background: transparent; border: 0; cursor: pointer; }
.resource-group > header button:hover { text-decoration: underline; }
.resource-row { display: flex; width: 100%; min-width: 0; height: 35px; align-items: center; padding: 0 10px; color: #454d54; text-align: left; background: #fff; border: 0; border-bottom: 1px solid #f0f1f2; cursor: pointer; }
.resource-row:hover { background: #f5f8fb; }
.resource-row-copy { display: flex; min-width: 0; width: 100%; align-items: baseline; justify-content: space-between; gap: 10px; }
.resource-row strong, .resource-row small { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.resource-row strong { color: #3c444b; font-size: 10px; font-weight: 550; }
.resource-row small { flex: 0 1 auto; color: #899198; font-size: 9px; }
.resource-empty, .resource-loading { padding: 12px 10px; color: #92999f; font-size: 10px; }
@media (max-width: 900px) { .workspace-actions { grid-template-columns: repeat(3, minmax(0, 1fr)); } .action-item:nth-child(4) { border-left: 0; } }
@media (max-width: 620px) { .workspace-home { padding: 16px 12px 28px; } .overview-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); } .workspace-actions { grid-template-columns: repeat(2, minmax(0, 1fr)); } .action-item:nth-child(odd) { border-left: 0; } .action-item:nth-child(4) { border-left: 1px solid #e5e7e9; } .action-item:last-child { grid-column: span 2; } .resource-grid { grid-template-columns: minmax(0, 1fr); } .resource-group { min-height: 0; } }
</style>
