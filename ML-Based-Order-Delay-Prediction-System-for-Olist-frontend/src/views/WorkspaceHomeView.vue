<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuth } from '@/auth/keycloak'
import { listDatasets, listAnalysisWorkspaces } from '@/api/datasets'
import { listWorkflows } from '@/api/workflows'
import { listModelVersions } from '@/api/models'

const router = useRouter()
const auth = useAuth()
const loading = ref(false)
const loadError = ref('')
const datasets = ref([])
const datasetTotal = ref(0)
const analyses = ref([])
const workflows = ref([])
const models = ref([])

const resourceRows = computed(() => [
  ...datasets.value.slice(0, 4).map((item) => ({
    id: `dataset-${item.id || item.datasetId}`,
    type: '数据集',
    name: item.name || item.originalFilename || '未命名数据集',
    details: `${item.tableCount ?? '-'} 张表`,
    path: `/datasets?dataset=${encodeURIComponent(item.id || item.datasetId)}`,
  })),
  ...analyses.value.slice(0, 4).map((item) => ({
    id: `analysis-${item.id}`,
    type: '分析方案',
    name: item.name || '未命名分析方案',
    details: formatTime(item.updatedAt),
    path: '/analysis',
  })),
  ...workflows.value.slice(0, 4).map((item) => ({
    id: `workflow-${item.id}`,
    type: '流水线',
    name: item.name || '未命名流水线',
    details: formatTime(item.updatedAt),
    path: `/pipelines?workflow=${encodeURIComponent(item.id)}`,
  })),
  ...models.value.slice(0, 4).map((item) => ({
    id: `model-${item.id}`,
    type: '模型版本',
    name: item.name || '未命名模型',
    details: `${item.task === 'classification' ? '分类' : '回归'} · ${item.algorithm || '-'}`,
    path: `/evaluation?modelId=${encodeURIComponent(item.id)}`,
  })),
])

function formatTime(value) {
  const date = new Date(value)
  if (!value || Number.isNaN(date.getTime())) return '-'
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' }).format(date)
}

async function loadResources() {
  if (!auth.state.authenticated) return
  loading.value = true
  loadError.value = ''
  const requests = await Promise.allSettled([
    listDatasets({ page: 0, size: 100 }),
    listAnalysisWorkspaces(),
    listWorkflows(),
    listModelVersions(),
  ])
  const [datasetResult, analysisResult, workflowResult, modelResult] = requests
  datasets.value = datasetResult.status === 'fulfilled' ? datasetResult.value?.items || [] : []
  datasetTotal.value = datasetResult.status === 'fulfilled' ? datasetResult.value?.totalElements || 0 : 0
  analyses.value = analysisResult.status === 'fulfilled' ? analysisResult.value || [] : []
  workflows.value = workflowResult.status === 'fulfilled' ? workflowResult.value || [] : []
  models.value = modelResult.status === 'fulfilled' ? modelResult.value || [] : []
  const failures = requests.filter((result) => result.status === 'rejected').length
  if (failures === requests.length) loadError.value = '资源读取失败。检查登录或服务配置后重试。'
  else if (failures) loadError.value = `${failures} 类资源读取失败。`
  loading.value = false
}

onMounted(() => {
  if (auth.state.status === 'authenticated') loadResources()
})

watch(() => auth.state.status, (status) => {
  if (status === 'authenticated') loadResources()
  if (status === 'anonymous') {
    datasets.value = []
    analyses.value = []
    workflows.value = []
    models.value = []
  }
})
</script>

<template>
  <main class="overview-page">
    <h1 class="visually-hidden">概览</h1>
    <header v-if="auth.state.authenticated" class="page-header page-actions">
      <el-button :loading="loading" @click="loadResources">刷新</el-button>
    </header>

    <p v-if="!auth.state.authenticated" class="login-required">请登录以查看数据集、分析方案、流水线和模型版本。</p>

    <template v-else>
    <div v-if="loadError" class="error-row" role="alert">
      <span>{{ loadError }}</span>
      <button type="button" @click="loadResources">重试</button>
    </div>

    <section class="resource-counts" aria-label="资源数量">
      <span>数据集 <strong>{{ datasetTotal }}</strong></span>
      <span>分析方案 <strong>{{ analyses.length }}</strong></span>
      <span>流水线 <strong>{{ workflows.length }}</strong></span>
      <span>模型版本 <strong>{{ models.length }}</strong></span>
    </section>

    <nav class="primary-actions" aria-label="常用操作">
      <el-button type="primary" @click="router.push('/datasets')">导入数据</el-button>
      <el-button @click="router.push('/analysis')">开始分析</el-button>
      <el-button @click="router.push('/experiments')">新建实验</el-button>
      <el-button @click="router.push('/pipelines')">新建流水线</el-button>
    </nav>

    <section class="resource-table" aria-labelledby="resource-heading">
      <h2 id="resource-heading">资源</h2>
      <p v-if="loading" class="table-message">正在读取</p>
      <p v-else-if="!resourceRows.length" class="table-message">暂无资源。<button type="button" @click="router.push('/datasets')">导入数据</button></p>
      <table v-else>
        <thead>
          <tr><th>类型</th><th>名称</th><th>版本 / 日期 / 规模</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="row in resourceRows" :key="row.id">
            <td>{{ row.type }}</td>
            <td class="resource-name">{{ row.name }}</td>
            <td>{{ row.details }}</td>
            <td><button type="button" @click="router.push(row.path)">打开</button></td>
          </tr>
        </tbody>
      </table>
    </section>
    </template>
  </main>
</template>

<style scoped>
.overview-page { width: min(100%, 1200px); min-width: 0; margin: 0 auto; }
.page-header { border-bottom: 1px solid var(--line); }
.login-required { margin: 0; padding: 18px 0; color: var(--ink-soft); border-bottom: 1px solid var(--line); font-size: 11px; }
.table-message button { padding: 0; color: var(--accent-hover); background: transparent; border: 0; cursor: pointer; font-size: 11px; }
.error-row { display: flex; min-height: 38px; align-items: center; justify-content: space-between; gap: 12px; padding: 0 10px; color: #7a271a; background: #fff8f6; border-bottom: 1px solid #efd5cf; font-size: 11px; }
.error-row button, .resource-table button { padding: 0; color: var(--accent-hover); background: transparent; border: 0; cursor: pointer; font-size: 11px; }
.resource-counts { display: flex; flex-wrap: wrap; gap: 10px 28px; padding: 12px 0; border-bottom: 1px solid var(--line); color: var(--ink-soft); font-size: 11px; }
.resource-counts strong { margin-left: 5px; color: var(--ink); font-size: 12px; font-variant-numeric: tabular-nums; }
.primary-actions { display: flex; flex-wrap: wrap; gap: 8px; padding: 14px 0 20px; }
.resource-table h2 { margin: 0; padding: 0 0 9px; border-bottom: 1px solid var(--line-strong); font-size: 13px; font-weight: 650; }
.resource-table table { width: 100%; border-collapse: collapse; table-layout: fixed; }
.resource-table th, .resource-table td { height: 38px; padding: 0 10px; border-bottom: 1px solid var(--line-subtle); font-size: 11px; text-align: left; }
.resource-table th { color: var(--ink-faint); background: #fafafa; font-weight: 600; }
.resource-table th:first-child { width: 120px; }
.resource-table th:nth-child(3) { width: 220px; }
.resource-table th:last-child { width: 72px; }
.resource-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.table-message { margin: 0; padding: 18px 10px; color: var(--ink-faint); border-bottom: 1px solid var(--line); font-size: 11px; }
@media (max-width: 680px) {
  .resource-table { overflow-x: auto; }
  .resource-table table { min-width: 660px; }
  .resource-counts { gap: 8px 16px; }
}
</style>
