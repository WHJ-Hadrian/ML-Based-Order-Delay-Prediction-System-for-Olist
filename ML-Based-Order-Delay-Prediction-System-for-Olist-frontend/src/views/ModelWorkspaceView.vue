<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuth } from '@/auth/keycloak'
import { getDatasetCatalog, getDatasetVersions, listDatasets } from '@/api/datasets'
import { createModelWorkflow, duplicateModelWorkflow, getModelWorkflow, listModelVersions, listModelWorkflows, trainModel, updateModelWorkflow } from '@/api/models'
import { Aim, CircleCheck, CopyDocument, Cpu, DataAnalysis, DocumentChecked, FullScreen, FolderAdd, Plus, Refresh, Remove, Warning } from '@element-plus/icons-vue'

const auth = useAuth()
const route = useRoute()
const router = useRouter()
const entryContext = {
  datasetId: String(route.query.datasetId || ''),
  versionId: String(route.query.versionId || ''),
  tableId: String(route.query.tableId || ''),
  targetColumn: String(route.query.targetColumn || ''),
  task: String(route.query.task || ''),
}
const datasets = ref([])
const versions = ref([])
const catalog = ref(null)
const models = ref([])
const selectedDatasetId = ref('')
const selectedVersionId = ref('')
const selectedTableId = ref('')
const datasetQuery = ref('')
const columnQuery = ref('')
const task = ref('classification')
const targetColumn = ref('')
const featureColumns = ref([])
const modelName = ref('')
const testSize = ref(0.2)
const randomState = ref(42)
const candidateModels = ref([{ id: 1, algorithm: 'random_forest' }])
const loadingDatasets = ref(false)
const loadingVersions = ref(false)
const loadingCatalog = ref(false)
const loadingModels = ref(false)
const training = ref(false)
const trainingIndex = ref(0)
const listError = ref('')
const catalogError = ref('')
const trainingError = ref('')
const modelsError = ref('')
const trainingResults = ref([])
const trainingFailures = ref([])
const trainingBranchStates = ref({})
const workflows = ref([])
const activeWorkflowId = ref('')
const activeWorkflowName = ref('')
const workflowRevision = ref(0)
const workflowDirty = ref(false)
const workflowBusy = ref(false)
const workflowError = ref('')
const workflowNotice = ref('')
const workflowShell = ref(null)
const focusMode = ref(false)
const nativeFullscreen = ref(false)
const applyingWorkflow = ref(false)

let nextCandidateId = 2
let workflowSaveTimer
let versionsRequest = 0
let catalogRequest = 0
let datasetsRequest = 0

function workflowDocument() {
  return {
    version: 1,
    nodes: [
      { id: 'source', type: 'DATA_SOURCE', data: {
        datasetId: selectedDatasetId.value || null,
        datasetVersionId: selectedVersionId.value || null,
        tableId: selectedTableId.value || null,
      } },
      { id: 'fields', type: 'FEATURE_SELECTION', data: {
        task: task.value,
        targetColumn: targetColumn.value || null,
        featureColumns: [...featureColumns.value],
      } },
      ...candidateModels.value.map((candidate) => ({
        id: `train-${candidate.id}`,
        type: 'TRAIN_MODEL',
        data: { algorithm: candidate.algorithm, modelName: modelName.value, testSize: testSize.value, randomState: randomState.value },
      })),
    ],
    edges: [
      { id: 'source-fields', source: 'source', target: 'fields' },
      ...candidateModels.value.map((candidate) => ({ id: `fields-train-${candidate.id}`, source: 'fields', target: `train-${candidate.id}` })),
    ],
    config: {
      datasetId: selectedDatasetId.value || null,
      datasetVersionId: selectedVersionId.value || null,
      tableId: selectedTableId.value || null,
      task: task.value,
      targetColumn: targetColumn.value || null,
      featureColumns: [...featureColumns.value],
      modelName: modelName.value,
      testSize: testSize.value,
      randomState: randomState.value,
      candidates: candidateModels.value.map(({ id, algorithm }) => ({ id, algorithm })),
    },
  }
}

async function applyWorkflowDocument(document) {
  const config = document?.config || {}
  applyingWorkflow.value = true
  selectedDatasetId.value = config.datasetId || ''
  if (selectedDatasetId.value) await loadVersions(selectedDatasetId.value, config.datasetVersionId || '')
  else {
    versions.value = []
    selectedVersionId.value = ''
    resetCatalog()
  }
  if (selectedVersionId.value) await loadCatalog(selectedVersionId.value)
  selectedTableId.value = catalog.value?.tables?.some((item) => item.tableId === config.tableId)
    ? config.tableId
    : catalog.value?.tables?.[0]?.tableId || ''
  task.value = ['classification', 'regression'].includes(config.task) ? config.task : 'classification'
  targetColumn.value = config.targetColumn || ''
  featureColumns.value = Array.isArray(config.featureColumns) ? [...config.featureColumns] : []
  modelName.value = config.modelName || ''
  testSize.value = Number(config.testSize) || 0.2
  randomState.value = Number.isInteger(config.randomState) ? config.randomState : 42
  const candidates = Array.isArray(config.candidates) ? config.candidates : []
  candidateModels.value = candidates.length
    ? candidates.slice(0, 4).map((candidate, index) => ({
      id: Number.isInteger(candidate.id) ? candidate.id : index + 1,
      algorithm: availableAlgorithms.value.some((item) => item.value === candidate.algorithm)
        ? candidate.algorithm : availableAlgorithms.value[0].value,
    }))
    : [{ id: nextCandidateId++, algorithm: availableAlgorithms.value[0].value }]
  nextCandidateId = Math.max(2, ...candidateModels.value.map((candidate) => candidate.id + 1))
  trainingResults.value = []
  trainingFailures.value = []
  trainingError.value = ''
  await nextTick()
  applyingWorkflow.value = false
}

async function saveWorkflow() {
  clearTimeout(workflowSaveTimer)
  if (!activeWorkflowId.value || !workflowDirty.value) return true
  if (workflowBusy.value) return false
  workflowBusy.value = true
  workflowError.value = ''
  let saveSucceeded = false
  try {
    const document = workflowDocument()
    const name = activeWorkflowName.value
    const saved = await updateModelWorkflow(activeWorkflowId.value, {
      name,
      document,
      expectedRevision: workflowRevision.value,
    })
    workflowRevision.value = saved.revision
    workflowDirty.value = activeWorkflowId.value !== saved.id
      || activeWorkflowName.value !== name
      || JSON.stringify(workflowDocument()) !== JSON.stringify(document)
    saveSucceeded = true
    workflows.value = [saved, ...workflows.value.filter((item) => item.id !== saved.id)]
    workflowNotice.value = '已保存'
    return true
  } catch (error) {
    workflowError.value = error.message || '工作流保存失败'
    return false
  } finally {
    workflowBusy.value = false
    if (saveSucceeded && workflowDirty.value) scheduleWorkflowSave()
  }
}

async function loadWorkflows() {
  workflowBusy.value = true
  workflowError.value = ''
  try {
    workflows.value = await listModelWorkflows() || []
    if (workflows.value.length) await selectWorkflow(workflows.value[0].id, true)
    else await createWorkflow()
  } catch (error) {
    workflowError.value = error.message || '工作流载入失败'
  } finally {
    workflowBusy.value = false
  }
}

async function selectWorkflow(id, skipSave = false) {
  if (!id || id === activeWorkflowId.value) return true
  if (!skipSave && !(await saveWorkflow())) return false
  workflowBusy.value = true
  workflowError.value = ''
  try {
    const loaded = await getModelWorkflow(id)
    activeWorkflowId.value = loaded.id
    activeWorkflowName.value = loaded.name
    workflowRevision.value = loaded.revision
    await applyWorkflowDocument(loaded.document)
    workflowDirty.value = false
    return true
  } catch (error) {
    workflowError.value = error.message || '无法载入所选工作流'
    return false
  } finally {
    workflowBusy.value = false
  }
}

async function createWorkflow() {
  if (!(await saveWorkflow())) return
  workflowBusy.value = true
  workflowError.value = ''
  try {
    const created = await createModelWorkflow({ name: `新建工作流 ${new Date().toLocaleDateString('zh-CN')}`, document: { version: 1, nodes: [], edges: [], config: {} } })
    workflows.value = [created, ...workflows.value]
    activeWorkflowId.value = created.id
    activeWorkflowName.value = created.name
    workflowRevision.value = created.revision
    await applyWorkflowDocument(created.document)
    workflowDirty.value = false
    workflowNotice.value = '工作流已创建'
  } catch (error) {
    workflowError.value = error.message || '创建工作流失败'
  } finally {
    workflowBusy.value = false
  }
}

async function duplicateWorkflow() {
  if (!(await saveWorkflow())) return
  workflowBusy.value = true
  try {
    const copy = await duplicateModelWorkflow(activeWorkflowId.value, `${activeWorkflowName.value} 副本`.slice(0, 255))
    workflows.value = [copy, ...workflows.value]
    await selectWorkflow(copy.id, true)
    workflowNotice.value = '已复制工作流'
  } catch (error) {
    workflowError.value = error.message || '复制工作流失败'
  } finally {
    workflowBusy.value = false
  }
}

async function toggleFocusMode() {
  if (document.fullscreenElement === workflowShell.value) {
    await document.exitFullscreen()
    return
  }
  if (workflowShell.value?.requestFullscreen) {
    try {
      await workflowShell.value.requestFullscreen()
      return
    } catch { /* Fall back to an in-page focus layout. */ }
  }
  focusMode.value = !focusMode.value
}

function syncFullscreen() {
  nativeFullscreen.value = document.fullscreenElement === workflowShell.value
  focusMode.value = nativeFullscreen.value
}

const algorithmCatalog = {
  classification: [
    { value: 'random_forest', label: '随机森林', note: '适合非线性关系与混合特征' },
    { value: 'logistic_regression', label: '逻辑回归', note: '轻量、可解释的分类基线' },
  ],
  regression: [
    { value: 'random_forest', label: '随机森林', note: '适合非线性关系与混合特征' },
    { value: 'ridge_regression', label: '岭回归', note: '稳定的线性回归基线' },
  ],
}

const datasetGroups = computed(() => {
  const groups = new Map()
  for (const dataset of datasets.value) {
    const id = dataset.datasetId || dataset.id
    if (id && !groups.has(id)) groups.set(id, { ...dataset, datasetId: id })
  }
  return [...groups.values()]
})
const visibleDatasets = computed(() => {
  const query = datasetQuery.value.trim().toLocaleLowerCase()
  if (!query) return datasetGroups.value
  return datasetGroups.value.filter((dataset) => datasetLabel(dataset).toLocaleLowerCase().includes(query))
})
const selectedDataset = computed(() => datasetGroups.value.find((item) => item.datasetId === selectedDatasetId.value))
const readyVersions = computed(() => versions.value.filter((item) => item.status === 'READY'))
const selectedVersion = computed(() => readyVersions.value.find((item) => item.datasetVersionId === selectedVersionId.value))
const selectedTable = computed(() => catalog.value?.tables?.find((item) => item.tableId === selectedTableId.value))
const columns = computed(() => selectedTable.value?.columns || [])
const visibleColumns = computed(() => {
  const query = columnQuery.value.trim().toLocaleLowerCase()
  if (!query) return columns.value
  return columns.value.filter((column) => `${column.name} ${column.logicalType || ''}`.toLocaleLowerCase().includes(query))
})
const availableAlgorithms = computed(() => algorithmCatalog[task.value])
const canAddModel = computed(() => candidateModels.value.length < 4
  && availableAlgorithms.value.some((option) => !candidateModels.value.some((candidate) => candidate.algorithm === option.value)))
const canTrain = computed(() => Boolean(
  selectedDataset.value
  && selectedVersion.value
  && selectedTable.value
  && targetColumn.value
  && featureColumns.value.length
  && candidateModels.value.length
  && selectedTable.value.rowCount >= 12
  && selectedTable.value.rowCount <= 100000,
))
const selectedCandidateCount = computed(() => candidateModels.value.length)

function datasetLabel(dataset) {
  return dataset?.name || dataset?.originalFilename || '未命名数据集'
}

function algorithmLabel(value) {
  return availableAlgorithms.value.find((option) => option.value === value)?.label || value
}

function formatNumber(value, digits = 3) {
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: digits }).format(Number(value || 0))
}

function formatTime(value) {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '暂无记录'
}

function metricRows(run) {
  const labels = run.task === 'classification'
    ? { accuracy: '准确率', balanced_accuracy: '平衡准确率', f1_weighted: '加权 F1' }
    : { mae: '平均绝对误差', rmse: '均方根误差', r2: 'R²' }
  return Object.entries(labels)
    .filter(([key]) => key in (run.metrics || {}))
    .map(([key, label]) => ({ key, label, value: run.metrics[key] }))
}

function resetCatalog() {
  catalog.value = null
  selectedTableId.value = ''
  targetColumn.value = ''
  featureColumns.value = []
  columnQuery.value = ''
  trainingResults.value = []
  trainingFailures.value = []
  trainingError.value = ''
}

async function loadDatasets() {
  const requestId = ++datasetsRequest
  loadingDatasets.value = true
  listError.value = ''
  try {
    const result = await listDatasets({ status: 'READY', page: 0, size: 100 })
    if (requestId !== datasetsRequest) return
    datasets.value = result.items || []
    const requestedDatasetId = entryContext.datasetId
    selectedDatasetId.value = datasetGroups.value.some((item) => item.datasetId === requestedDatasetId)
      ? requestedDatasetId
      : datasetGroups.value.some((item) => item.datasetId === selectedDatasetId.value)
        ? selectedDatasetId.value
        : datasetGroups.value[0]?.datasetId || ''
  } catch (error) {
    if (requestId === datasetsRequest) listError.value = error.message || '数据集加载失败'
  } finally {
    if (requestId === datasetsRequest) loadingDatasets.value = false
  }
}

async function loadVersions(datasetId, preferredVersion = '') {
  const requestId = ++versionsRequest
  versions.value = []
  selectedVersionId.value = ''
  resetCatalog()
  if (!datasetId) {
    loadingVersions.value = false
    return
  }
  loadingVersions.value = true
  listError.value = ''
  try {
    const result = await getDatasetVersions(datasetId)
    if (requestId !== versionsRequest) return
    versions.value = result || []
    const preferred = preferredVersion || (selectedDatasetId.value === entryContext.datasetId ? entryContext.versionId : '') || selectedDataset.value?.datasetVersionId
    selectedVersionId.value = readyVersions.value.some((item) => item.datasetVersionId === preferred)
      ? preferred
      : readyVersions.value[0]?.datasetVersionId || ''
  } catch (error) {
    if (requestId === versionsRequest) listError.value = error.message || '数据集版本加载失败'
  } finally {
    if (requestId === versionsRequest) loadingVersions.value = false
  }
}

async function loadCatalog(versionId) {
  const requestId = ++catalogRequest
  resetCatalog()
  if (!versionId) {
    loadingCatalog.value = false
    return
  }
  loadingCatalog.value = true
  catalogError.value = ''
  try {
    const result = await getDatasetCatalog(versionId)
    if (requestId !== catalogRequest) return
    catalog.value = result
    const requestedTableId = selectedDatasetId.value === entryContext.datasetId
      ? entryContext.tableId
      : ''
    const hasRequestedTable = result.tables?.some((table) => table.tableId === requestedTableId)
    const nextTable = result.tables?.find((table) => table.tableId === requestedTableId)
      || result.tables?.[0]
    const requestedTarget = nextTable?.columns?.some((column) => column.name === entryContext.targetColumn)
      ? entryContext.targetColumn
      : ''
    const requestedTask = entryContext.task
    applyingWorkflow.value = true
    selectedTableId.value = hasRequestedTable ? requestedTableId : nextTable?.tableId || ''
    if (['classification', 'regression'].includes(requestedTask)) task.value = requestedTask
    targetColumn.value = requestedTarget
    featureColumns.value = (nextTable?.columns || []).map((column) => column.name).filter((name) => name !== requestedTarget)
    await nextTick()
    applyingWorkflow.value = false
    if (entryContext.datasetId && route.query.datasetId) router.replace({ path: route.path })
  } catch (error) {
    applyingWorkflow.value = false
    if (requestId === catalogRequest) catalogError.value = error.message || '数据表目录加载失败'
  } finally {
    if (requestId === catalogRequest) loadingCatalog.value = false
  }
}

async function loadModels() {
  loadingModels.value = true
  modelsError.value = ''
  try {
    models.value = await listModelVersions() || []
  } catch (error) {
    modelsError.value = error.message || '模型版本加载失败'
  } finally {
    loadingModels.value = false
  }
}

async function initializeWorkspace() {
  await loadDatasets()
  const hasEntryDataset = entryContext.datasetId
    && datasetGroups.value.some((item) => item.datasetId === entryContext.datasetId)
  await Promise.all([
    loadModels(),
    hasEntryDataset ? createWorkflow() : loadWorkflows(),
  ])
  if (hasEntryDataset) {
    applyingWorkflow.value = true
    selectedDatasetId.value = entryContext.datasetId
    await loadVersions(entryContext.datasetId, entryContext.versionId)
    if (selectedVersionId.value) await loadCatalog(selectedVersionId.value)
    applyingWorkflow.value = false
  }
}

function chooseTarget(name) {
  if (targetColumn.value === name) return
  targetColumn.value = name
  featureColumns.value = columns.value.map((column) => column.name).filter((columnName) => columnName !== name)
}

function toggleFeature(name, included) {
  if (name === targetColumn.value) return
  const next = new Set(featureColumns.value)
  if (included) next.add(name)
  else next.delete(name)
  featureColumns.value = [...next]
}

function setTask(value) {
  if (task.value === value) return
  task.value = value
  candidateModels.value = [{ id: nextCandidateId++, algorithm: availableAlgorithms.value[0].value }]
  trainingResults.value = []
  trainingFailures.value = []
}

function setCandidateAlgorithm(candidateId, algorithm) {
  if (candidateModels.value.some((candidate) => candidate.id !== candidateId && candidate.algorithm === algorithm)) return
  candidateModels.value = candidateModels.value.map((candidate) => candidate.id === candidateId ? { ...candidate, algorithm } : candidate)
}

function addCandidate() {
  const option = availableAlgorithms.value.find((candidate) => !candidateModels.value.some((item) => item.algorithm === candidate.value))
  if (!option || candidateModels.value.length >= 4) return
  candidateModels.value = [...candidateModels.value, { id: nextCandidateId++, algorithm: option.value }]
}

function removeCandidate(candidateId) {
  if (candidateModels.value.length <= 1) return
  candidateModels.value = candidateModels.value.filter((candidate) => candidate.id !== candidateId)
}

function validateTraining() {
  if (!selectedDataset.value || !selectedVersion.value || !selectedTable.value) return '请先连接一个 READY 数据集版本和数据表。'
  if (!targetColumn.value) return '在字段节点中指定一个预测目标。'
  if (!featureColumns.value.length) return '至少保留一个输入特征。'
  if (selectedTable.value.rowCount < 12) return '当前数据表少于 12 行，暂不满足训练条件。'
  if (selectedTable.value.rowCount > 100000) return '当前数据表超过 100,000 行，请先选择较小的数据版本。'
  return ''
}

async function submitTraining() {
  trainingError.value = validateTraining()
  if (trainingError.value) return
  if (!activeWorkflowId.value || !(await saveWorkflow())) {
    trainingError.value = '工作流尚未保存，请检查名称或保存错误后再运行。'
    return
  }

  const candidates = candidateModels.value.map((candidate) => ({ ...candidate }))
  const baseName = modelName.value.trim()
  const commonPayload = {
    datasetId: catalog.value.datasetId,
    datasetVersionId: catalog.value.datasetVersionId,
    tableId: selectedTableId.value,
    task: task.value,
    targetColumn: targetColumn.value,
    featureColumns: [...featureColumns.value],
    testSize: testSize.value,
    randomState: randomState.value,
  }

  training.value = true
  trainingIndex.value = 0
  trainingError.value = ''
  trainingResults.value = []
  trainingFailures.value = []
  trainingBranchStates.value = Object.fromEntries(candidates.map((candidate) => [candidate.id, 'running']))
  await Promise.all(candidates.map(async (candidate) => {
    const label = availableAlgorithms.value.find((option) => option.value === candidate.algorithm)?.label || candidate.algorithm
    const name = baseName ? `${baseName} · ${label}`.slice(0, 255) : null
    try {
      const result = await trainModel({ ...commonPayload, name, algorithm: candidate.algorithm })
      trainingResults.value.push({ ...result, candidateId: candidate.id, algorithmLabel: label })
      trainingBranchStates.value[candidate.id] = 'success'
    } catch (error) {
      trainingFailures.value.push({ candidateId: candidate.id, algorithmLabel: label, message: error.message || '训练失败' })
      trainingBranchStates.value[candidate.id] = 'failed'
    } finally {
      trainingIndex.value += 1
    }
  }))
  const branchOrder = new Map(candidates.map((candidate, index) => [candidate.id, index]))
  trainingResults.value.sort((left, right) => branchOrder.get(left.candidateId) - branchOrder.get(right.candidateId))
  trainingFailures.value.sort((left, right) => branchOrder.get(left.candidateId) - branchOrder.get(right.candidateId))
  training.value = false
  if (trainingFailures.value.length) {
    trainingError.value = `${trainingFailures.value.length} 个模型分支未完成；已成功的模型仍已登记，可在下方查看。`
  }
  await loadModels()
}

function openTrial(modelId) {
  router.push({ path: '/model-trials', query: { modelId } })
}

watch(selectedDatasetId, (id) => { if (!applyingWorkflow.value) loadVersions(id) })
watch(selectedVersionId, (id) => { if (!applyingWorkflow.value) loadCatalog(id) })
watch(selectedTableId, () => {
  if (applyingWorkflow.value) return
  targetColumn.value = ''
  featureColumns.value = columns.value.map((column) => column.name)
  trainingResults.value = []
  trainingFailures.value = []
})
watch(() => auth.state.status, (status) => {
  if (status === 'authenticated') {
    initializeWorkspace()
  } else if (status === 'anonymous') {
    datasets.value = []
    versions.value = []
    models.value = []
    workflows.value = []
    activeWorkflowId.value = ''
    workflowRevision.value = 0
    workflowDirty.value = false
    selectedDatasetId.value = ''
    selectedVersionId.value = ''
    resetCatalog()
  }
}, { immediate: true })

onBeforeUnmount(() => {
  datasetsRequest += 1
  versionsRequest += 1
  catalogRequest += 1
  clearTimeout(workflowSaveTimer)
  if (workflowDirty.value && activeWorkflowId.value) saveWorkflow()
  document.removeEventListener('fullscreenchange', syncFullscreen)
  if (document.fullscreenElement === workflowShell.value) document.exitFullscreen()
})

watch(() => JSON.stringify([workflowDocument(), activeWorkflowName.value]), () => {
  if (activeWorkflowId.value && !applyingWorkflow.value) {
    workflowDirty.value = true
    workflowNotice.value = ''
    scheduleWorkflowSave()
  }
})

function scheduleWorkflowSave() {
  clearTimeout(workflowSaveTimer)
  workflowSaveTimer = setTimeout(() => saveWorkflow(), 900)
}

onMounted(() => document.addEventListener('fullscreenchange', syncFullscreen))
</script>

<template>
  <main class="page-content model-page">
    <header class="page-header model-header">
      <div>
        <span class="model-kicker">MODEL WORKSPACE</span>
        <h1>建模工作流</h1>
        <p>从数据字段出发，组合训练分支并比较真实运行结果</p>
      </div>
      <el-button
        circle
        aria-label="刷新数据集与模型版本"
        title="刷新数据集与模型版本"
        :icon="Refresh"
        :loading="loadingDatasets || loadingVersions || loadingCatalog || loadingModels"
        @click="loadDatasets(); loadModels()"
      />
    </header>

    <div v-if="listError" class="model-message is-error" role="alert">
      <el-icon><Warning /></el-icon><span>{{ listError }}</span>
      <el-button text :icon="Refresh" @click="loadDatasets">重试</el-button>
    </div>

    <section ref="workflowShell" class="workflow-shell" :class="{ 'is-focused': focusMode && !nativeFullscreen }" aria-label="可视化建模工作流">
      <div class="workflow-bar">
        <div class="workflow-topline">
          <div class="workflow-title-group">
            <div class="workflow-title"><span class="state-dot" :class="workflowDirty ? 'is-dirty' : 'is-saved'" /><strong>工作流画布</strong><span class="workflow-save-state">{{ workflowBusy ? '同步中' : workflowDirty ? '有未保存修改' : '已保存' }}</span></div>
            <div class="workflow-count">{{ columns.length }} 个字段 <span>·</span> {{ selectedCandidateCount }} 个模型分支</div>
          </div>
          <div class="workflow-controls">
            <el-input v-model="activeWorkflowName" class="workflow-name-input" size="small" maxlength="255" aria-label="工作流名称" placeholder="工作流名称" :disabled="workflowBusy" />
            <el-button circle size="small" aria-label="新建工作流画布" title="新建工作流画布" :icon="FolderAdd" :disabled="workflowBusy" @click="createWorkflow" />
            <el-button circle size="small" aria-label="复制当前画布" title="复制当前画布" :icon="CopyDocument" :disabled="workflowBusy || !activeWorkflowId" @click="duplicateWorkflow" />
            <el-button circle size="small" aria-label="保存工作流" title="保存工作流" :icon="DocumentChecked" :loading="workflowBusy" :disabled="!workflowDirty" @click="saveWorkflow" />
            <el-button circle size="small" :aria-label="focusMode ? '退出画布专注模式' : '专注画布'" :title="focusMode ? '退出画布专注模式' : '专注画布'" :icon="focusMode ? Aim : FullScreen" @click="toggleFocusMode" />
          </div>
        </div>
        <div class="workflow-tabs" role="tablist" aria-label="已保存工作流画布">
          <button
            v-for="workflow in workflows"
            :key="workflow.id"
            type="button"
            role="tab"
            class="workflow-tab"
            :class="{ 'is-active': activeWorkflowId === workflow.id }"
            :aria-selected="activeWorkflowId === workflow.id"
            :title="workflow.name"
            :disabled="workflowBusy"
            @click="selectWorkflow(workflow.id)"
          >
            <span class="tab-dirty-dot" :class="activeWorkflowId === workflow.id && workflowDirty ? 'is-dirty' : 'is-saved'" />
            <span>{{ workflow.name }}</span>
          </button>
        </div>
        <div v-if="workflowError || workflowNotice" class="workflow-feedback" :class="{ 'is-error': workflowError }" role="status">
          <span>{{ workflowError || workflowNotice }}</span>
          <el-button v-if="workflowError" text size="small" :icon="Refresh" @click="loadWorkflows">重试</el-button>
        </div>
      </div>

      <div class="workflow-canvas">
        <div class="workflow-track">
          <section class="flow-node source-node" aria-label="数据源节点">
            <header class="node-heading">
              <span class="node-index">01</span>
              <div><h2>数据源</h2><p>数据集 · 版本 · 数据表</p></div>
              <el-tag v-if="selectedVersion" type="success" effect="plain" size="small">READY</el-tag>
            </header>

            <el-input v-model="datasetQuery" clearable size="small" placeholder="筛选数据集" aria-label="筛选数据集" :disabled="training" />
            <div v-if="loadingDatasets" class="node-empty"><el-skeleton :rows="3" animated /></div>
            <div v-else-if="!datasetGroups.length" class="node-empty">暂无可用数据集</div>
            <div v-else-if="!visibleDatasets.length" class="node-empty">没有匹配的数据集</div>
            <div v-else class="dataset-choices" role="list" aria-label="READY 数据集">
              <button
                v-for="dataset in visibleDatasets"
                :key="dataset.datasetId"
                type="button"
                class="dataset-choice"
                :class="{ 'is-active': selectedDatasetId === dataset.datasetId }"
                :aria-pressed="selectedDatasetId === dataset.datasetId"
                :title="datasetLabel(dataset)"
                :disabled="training"
                @click="selectedDatasetId = dataset.datasetId"
              >
                <span class="dataset-choice-name">{{ datasetLabel(dataset) }}</span>
                <span class="dataset-choice-meta">{{ dataset.fileCount ?? '数据集' }}</span>
              </button>
            </div>

            <div v-if="selectedDataset" class="source-detail">
              <div class="choice-label">版本</div>
              <div v-if="loadingVersions" class="choice-loading">正在读取版本…</div>
              <div v-else class="version-choices">
                <button
                  v-for="version in readyVersions"
                  :key="version.datasetVersionId"
                  type="button"
                  class="version-choice"
                  :class="{ 'is-active': selectedVersionId === version.datasetVersionId }"
                  :aria-pressed="selectedVersionId === version.datasetVersionId"
                  :disabled="training"
                  @click="selectedVersionId = version.datasetVersionId"
                >
                  <strong>v{{ version.versionNo }}</strong><span>{{ formatNumber(version.rowCount, 0) }} 行</span>
                </button>
                <span v-if="!readyVersions.length && !loadingVersions" class="choice-loading">没有 READY 版本</span>
              </div>

              <div class="choice-label table-label">数据表</div>
              <div v-if="loadingCatalog" class="choice-loading">正在读取数据表…</div>
              <div v-else class="table-choices">
                <button
                  v-for="table in catalog?.tables || []"
                  :key="table.tableId"
                  type="button"
                  class="table-choice"
                  :class="{ 'is-active': selectedTableId === table.tableId }"
                  :aria-pressed="selectedTableId === table.tableId"
                  :title="table.tableName"
                  :disabled="training"
                  @click="selectedTableId = table.tableId"
                >
                  <span>{{ table.tableName }}</span><small>{{ formatNumber(table.rowCount, 0) }} 行 · {{ table.columnCount }} 列</small>
                </button>
                <span v-if="selectedVersion && !catalog?.tables?.length && !loadingCatalog" class="choice-loading">该版本没有可训练的数据表</span>
              </div>
            </div>

            <div v-if="catalogError" class="node-inline-error" role="alert">
              <span>{{ catalogError }}</span><el-button text size="small" @click="loadCatalog(selectedVersionId)">重试</el-button>
            </div>
          </section>

          <div class="flow-connector" aria-hidden="true"><span /></div>

          <section class="flow-node fields-node" aria-label="目标与特征节点">
            <header class="node-heading">
              <span class="node-index">02</span>
              <div><h2>目标与特征</h2><p>从字段本身定义预测问题</p></div>
              <el-tag v-if="targetColumn" type="warning" effect="plain" size="small">目标已设</el-tag>
            </header>

            <div v-if="!selectedTable" class="fields-empty">
              <el-icon><DataAnalysis /></el-icon><span>连接数据表后，可直接在字段上设置目标和输入特征。</span>
            </div>
            <template v-else>
              <div class="field-summary">
                <span><strong>{{ columns.length }}</strong> 个字段</span>
                <span><strong>{{ featureColumns.length }}</strong> 个输入</span>
                <span v-if="targetColumn" class="target-summary">目标：{{ targetColumn }}</span>
              </div>
              <div class="task-row">
                <span>预测任务</span>
                <el-radio-group :model-value="task" size="small" :disabled="training" @change="setTask">
                  <el-radio-button value="classification">分类</el-radio-button>
                  <el-radio-button value="regression">回归</el-radio-button>
                </el-radio-group>
              </div>
              <el-input v-model="columnQuery" clearable size="small" placeholder="搜索字段名或类型" aria-label="搜索字段" :disabled="training" />
              <div class="field-list" role="list" aria-label="字段选择">
                <div v-for="column in visibleColumns" :key="column.columnId" class="field-row" :class="{ 'is-target': targetColumn === column.name, 'is-feature': featureColumns.includes(column.name) }">
                  <button
                    type="button"
                    class="field-target-button"
                    :aria-pressed="targetColumn === column.name"
                    :title="`将 ${column.name} 设为预测目标`"
                    :disabled="training"
                    @click="chooseTarget(column.name)"
                  >
                    <span class="field-target-mark">{{ targetColumn === column.name ? 'Y' : '○' }}</span>
                    <span class="field-name" :title="column.name">{{ column.name }}</span>
                    <small>{{ column.logicalType || '字段' }}</small>
                  </button>
                  <el-checkbox
                    :model-value="featureColumns.includes(column.name)"
                    :disabled="training || targetColumn === column.name"
                    :aria-label="`将 ${column.name} 作为输入特征`"
                    @change="toggleFeature(column.name, $event)"
                  >输入</el-checkbox>
                </div>
                <el-empty v-if="!visibleColumns.length" description="没有匹配字段" :image-size="56" />
              </div>
            </template>
          </section>

          <div class="flow-connector branch-connector" aria-hidden="true"><span /></div>

          <section class="flow-node models-node" aria-label="模型训练分支">
            <header class="node-heading">
              <span class="node-index">03</span>
              <div><h2>训练分支</h2><p>同一份数据配置，比较不同模型</p></div>
              <el-button
                circle
                size="small"
                aria-label="添加模型分支"
                title="添加模型分支"
                :icon="Plus"
                :disabled="training || !canAddModel"
                @click="addCandidate"
              />
            </header>

            <div class="model-branch-list">
              <article v-for="(candidate, index) in candidateModels" :key="candidate.id" class="model-branch">
                <div class="branch-title"><span class="branch-index">{{ String.fromCharCode(65 + index) }}</span><strong>模型 {{ index + 1 }}</strong>
                  <el-tag v-if="trainingBranchStates[candidate.id] === 'running'" size="small" effect="plain">运行中</el-tag>
                  <el-tag v-else-if="trainingBranchStates[candidate.id] === 'success'" size="small" type="success" effect="plain">完成</el-tag>
                  <el-tag v-else-if="trainingBranchStates[candidate.id] === 'failed'" size="small" type="danger" effect="plain">失败</el-tag>
                  <el-button v-if="candidateModels.length > 1" text circle size="small" :icon="Remove" :aria-label="`移除模型分支 ${index + 1}`" :disabled="training" @click="removeCandidate(candidate.id)" />
                </div>
                <div class="algorithm-choices">
                  <button
                    v-for="option in availableAlgorithms"
                    :key="option.value"
                    type="button"
                    class="algorithm-choice"
                    :class="{ 'is-active': candidate.algorithm === option.value }"
                    :disabled="training || candidateModels.some((other) => other.id !== candidate.id && other.algorithm === option.value)"
                    :aria-pressed="candidate.algorithm === option.value"
                    @click="setCandidateAlgorithm(candidate.id, option.value)"
                  >
                    <strong>{{ option.label }}</strong><span>{{ option.note }}</span>
                  </button>
                </div>
              </article>
            </div>
            <div v-if="candidateModels.length < 4 && !canAddModel" class="branch-note">当前任务类型没有更多不同的内置算法。</div>

            <div class="training-options">
              <label class="option-field">
                <span>实验名称 <small>可选</small></span>
                <el-input v-model="modelName" maxlength="220" placeholder="自动命名" :disabled="training" />
              </label>
              <label class="option-field">
                <span>留出集 <small>{{ Math.round(testSize * 100) }}%</small></span>
                <el-slider v-model="testSize" :min="0.1" :max="0.4" :step="0.05" show-stops :disabled="training" />
              </label>
              <label class="option-field seed-field">
                <span>随机种子</span>
                <el-input-number v-model="randomState" :min="0" :max="2147483647" controls-position="right" :disabled="training" />
              </label>
            </div>
          </section>
        </div>
      </div>

      <div v-if="trainingError" class="model-message" :class="trainingFailures.length ? 'is-warning' : 'is-error'" role="alert">
        <el-icon><Warning /></el-icon><span>{{ trainingError }}</span>
      </div>
      <div v-if="selectedTable && selectedTable.rowCount < 12" class="model-message is-warning">
        <el-icon><Warning /></el-icon><span>当前数据表少于 12 行，暂不满足训练条件。</span>
      </div>
      <div v-else-if="selectedTable && selectedTable.rowCount > 100000" class="model-message is-warning">
        <el-icon><Warning /></el-icon><span>当前数据表超过 100,000 行，请先选择较小的数据版本。</span>
      </div>

      <footer class="workflow-footer">
        <div class="run-context">
          <template v-if="selectedTable">
            <span :title="datasetLabel(selectedDataset)">{{ datasetLabel(selectedDataset) }}</span>
            <span>v{{ selectedVersion?.versionNo }}</span>
            <span>{{ formatNumber(selectedTable.rowCount, 0) }} 行</span>
            <span>{{ featureColumns.length }} 个输入</span>
          </template>
          <span v-else>等待连接数据源</span>
        </div>
        <div class="run-actions">
          <span v-if="training" class="running-status"><el-icon class="is-spinning"><Refresh /></el-icon>并行训练中 · {{ trainingIndex }} / {{ candidateModels.length }} 个分支已完成</span>
          <span v-else class="form-footnote">每个分支使用相同数据划分并分别登记模型版本</span>
          <el-button type="primary" :icon="Cpu" :loading="training" :disabled="!canTrain || training" @click="submitTraining">
            {{ training ? '运行中' : `运行 ${candidateModels.length} 个分支` }}
          </el-button>
        </div>
      </footer>
    </section>

    <section v-if="trainingResults.length || trainingFailures.length" class="result-panel" aria-label="本次实验结果">
      <div class="result-heading">
        <div><span class="model-kicker">EXPERIMENT OUTPUT</span><h2>本次运行</h2><p>{{ trainingResults.length }} 个模型完成并登记<span v-if="trainingFailures.length"> · {{ trainingFailures.length }} 个分支失败</span></p></div>
        <el-tag :type="trainingFailures.length ? 'warning' : 'success'" effect="plain">{{ trainingFailures.length ? '部分完成' : '已完成' }}</el-tag>
      </div>
      <div class="result-grid">
        <article v-for="run in trainingResults" :key="run.id" class="branch-result">
          <header><span><el-icon><CircleCheck /></el-icon>{{ run.algorithmLabel }}</span><small>{{ run.name }}</small></header>
          <div class="result-meta"><span>{{ run.trainRows.toLocaleString('zh-CN') }} 训练</span><span>{{ run.testRows.toLocaleString('zh-CN') }} 测试</span><span>{{ formatTime(run.createdAt) }}</span></div>
          <div class="result-metrics">
            <div v-for="metric in metricRows(run)" :key="metric.key"><span>{{ metric.label }}</span><strong>{{ formatNumber(metric.value, 4) }}</strong></div>
            <div><span>剔除无效目标</span><strong>{{ formatNumber(run.droppedTargetRows, 0) }}</strong></div>
          </div>
          <div v-if="run.evaluationSamples?.length" class="sample-table">
            <div class="sample-heading"><el-icon><DataAnalysis /></el-icon><h3>留出集样例</h3><span>{{ run.evaluationSamples.length }} 条</span></div>
            <el-table :data="run.evaluationSamples" size="small" table-layout="fixed" max-height="220">
              <el-table-column prop="row" label="原始行" width="76" />
              <el-table-column prop="actual" label="实际值" min-width="110" show-overflow-tooltip />
              <el-table-column prop="predicted" label="预测值" min-width="110" show-overflow-tooltip />
            </el-table>
          </div>
          <footer class="branch-actions">
            <el-button size="small" :icon="Aim" @click="openTrial(run.id)">拿新数据试用</el-button>
          </footer>
        </article>
        <article v-for="failure in trainingFailures" :key="`failed-${failure.candidateId}`" class="branch-result is-failed">
          <header><span><el-icon><Warning /></el-icon>{{ failure.algorithmLabel }}</span><el-tag type="danger" effect="plain" size="small">失败</el-tag></header>
          <p>{{ failure.message }}</p>
        </article>
      </div>
    </section>

    <section class="registry-section" aria-label="模型版本登记">
      <div class="registry-heading">
        <div><h2>模型注册表</h2><span>{{ models.length }} 个版本</span></div>
        <el-button circle aria-label="刷新模型版本" title="刷新模型版本" :icon="Refresh" :loading="loadingModels" @click="loadModels" />
      </div>
      <div v-if="modelsError" class="model-message is-error" role="alert">
        <el-icon><Warning /></el-icon><span>{{ modelsError }}</span>
        <el-button text :icon="Refresh" @click="loadModels">重试</el-button>
      </div>
      <el-table v-else :data="models" row-key="id" table-layout="fixed" v-loading="loadingModels" class="model-table">
        <el-table-column label="模型版本" min-width="210">
          <template #default="{ row }"><div class="model-name-cell" :title="row.name"><strong>{{ row.name }}</strong><span>{{ row.task === 'classification' ? '分类' : '回归' }} · {{ row.algorithm }}</span></div></template>
        </el-table-column>
        <el-table-column label="数据血缘" min-width="180">
          <template #default="{ row }"><div class="lineage-cell"><span>数据集 {{ row.datasetId }}</span><small>版本 {{ row.datasetVersionNo }} · 表 {{ row.tableId }}</small></div></template>
        </el-table-column>
        <el-table-column label="指标" min-width="180">
          <template #default="{ row }"><div class="registry-metrics"><span v-for="(value, key) in row.metrics" :key="key">{{ key }} {{ formatNumber(value, 3) }}</span></div></template>
        </el-table-column>
        <el-table-column prop="targetColumn" label="目标字段" min-width="130" show-overflow-tooltip />
        <el-table-column label="记录时间" width="165">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loadingModels && !modelsError && !models.length" description="暂无模型版本" :image-size="76" />
    </section>
  </main>
</template>

<style scoped>
.model-page { min-width: 0; padding-bottom: 32px; }
.model-header { align-items: center; }
.model-kicker { display: inline-block; margin-bottom: 5px; color: #568374; font-size: 10px; font-weight: 700; line-height: 1.2; }
.model-header h1 { letter-spacing: 0; }
.model-header p { color: #64736d; }
.workflow-shell, .result-panel { min-width: 0; margin-bottom: 12px; overflow: hidden; background: #fff; border: 1px solid var(--line-strong); border-radius: var(--radius-panel); box-shadow: none; }
.workflow-bar { display: grid; min-width: 0; gap: 5px; padding: 6px 10px 0; border-bottom: 1px solid #e1e1e1; }
.workflow-topline, .workflow-title-group, .workflow-title, .workflow-controls { display: flex; min-width: 0; align-items: center; }
.workflow-topline { justify-content: space-between; gap: 12px; }
.workflow-title-group { flex-wrap: wrap; gap: 5px 16px; }
.workflow-title { gap: 8px; color: #718079; font-size: 11px; }
.workflow-title strong { color: #2b4037; font-size: 12px; }
.workflow-save-state { color: #7c8983; font-size: 10px; }
.state-dot, .tab-dirty-dot { width: 7px; height: 7px; flex: 0 0 7px; background: #d59b40; border-radius: 50%; }
.state-dot.is-saved, .tab-dirty-dot.is-saved { background: #4f9a72; }
.state-dot.is-dirty, .tab-dirty-dot.is-dirty { background: #d59b40; }
.workflow-count { display: flex; min-width: 0; align-items: center; gap: 8px; color: #718079; font-size: 11px; }
.workflow-count span { color: #b3beb8; }
.workflow-controls { flex: 0 0 auto; gap: 6px; }
.workflow-name-input { width: min(220px, 25vw); }
.workflow-tabs { display: flex; min-width: 0; gap: 2px; overflow-x: auto; scrollbar-width: thin; }
.workflow-tab { display: flex; width: max-content; min-width: 0; max-width: 220px; height: 32px; flex: 0 0 auto; align-items: center; gap: 7px; padding: 0 9px; overflow: hidden; color: #50545a; background: transparent; border: 0; border-bottom: 2px solid transparent; cursor: pointer; }
.workflow-tab > span:last-child { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.workflow-tab:hover { color: #275d4b; background: #f7faf8; }
.workflow-tab.is-active { color: #0f6cbd; border-bottom-color: #0f6cbd; font-weight: 650; }
.workflow-tab:disabled { cursor: wait; }
.workflow-feedback { display: flex; min-width: 0; min-height: 24px; align-items: center; justify-content: space-between; gap: 8px; padding: 2px 0 6px; color: #68776f; font-size: 10px; }
.workflow-feedback.is-error { color: #9a4139; }
.workflow-count { white-space: nowrap; }
.workflow-shell.is-focused { position: fixed; z-index: 4000; inset: 0; display: flex; width: 100vw; height: 100vh; height: 100dvh; flex-direction: column; margin: 0; overflow: auto; border: 0; border-radius: 0; box-shadow: none; }
.workflow-shell:fullscreen { display: flex; width: 100vw; height: 100vh; height: 100dvh; flex-direction: column; margin: 0; overflow: auto; background: #fff; border: 0; border-radius: 0; box-shadow: none; }
.workflow-shell.is-focused .workflow-canvas, .workflow-shell:fullscreen .workflow-canvas { flex: 1 1 auto; overflow: auto; }
.workflow-shell.is-focused .workflow-footer, .workflow-shell:fullscreen .workflow-footer { flex: 0 0 auto; }
.workflow-canvas { min-width: 0; padding: 10px; container-type: inline-size; background-color: #f3f4f5; background-image: radial-gradient(#d5d9de 0.7px, transparent 0.7px); background-size: 16px 16px; }
.workflow-track { display: grid; grid-template-columns: minmax(230px, 300px) minmax(48px, 1fr) minmax(300px, 360px) minmax(48px, 1fr) minmax(280px, 320px); align-items: start; gap: 0; }
.flow-node { width: 100%; min-width: 0; align-self: start; padding: 10px; background: #fff; border: 1px solid var(--line-strong); border-radius: var(--radius-panel); box-shadow: none; }
.models-node { display: flex; min-width: 0; flex-direction: column; align-items: stretch; }
.models-node > .node-heading,
.models-node > .model-branch-list,
.models-node > .training-options { width: 100%; min-width: 0; flex: 0 0 auto; }
.models-node > .node-heading > div { min-width: 0; flex: 1 1 auto; }
.models-node > .node-heading p { max-width: 100%; white-space: normal; overflow-wrap: anywhere; }
.node-heading { display: flex; min-width: 0; min-height: 38px; align-items: center; gap: 9px; margin-bottom: 13px; }
.node-index { display: grid; width: 26px; height: 26px; flex: 0 0 26px; place-items: center; color: #216c5a; background: #e9f3ee; border: 1px solid #d6e8de; border-radius: 6px; font-size: 10px; font-weight: 700; }
.node-heading > div { min-width: 0; flex: 1; }
.node-heading h2 { margin: 0; overflow: hidden; color: #263b32; font-size: 13px; font-weight: 680; line-height: 1.35; text-overflow: ellipsis; white-space: nowrap; }
.node-heading p { margin: 3px 0 0; color: #829089; font-size: 10px; line-height: 1.3; }
.flow-connector { position: relative; display: flex; width: 100%; align-self: start; align-items: center; justify-content: center; min-height: 1px; margin-top: 47px; }
.flow-connector::before { width: 100%; height: 1px; background: #a9c4b7; content: ''; }
.flow-connector::after { position: absolute; right: 1px; width: 6px; height: 6px; border-top: 1px solid #6b9a83; border-right: 1px solid #6b9a83; content: ''; transform: rotate(45deg); }
.flow-connector span { position: absolute; z-index: 1; width: 7px; height: 7px; background: #fff; border: 1px solid #90b39f; border-radius: 50%; }
.dataset-choices { display: flex; max-height: 178px; flex-direction: column; gap: 4px; margin-top: 8px; overflow-y: auto; }
.dataset-choice { display: flex; min-width: 0; min-height: 32px; align-items: center; justify-content: space-between; gap: 7px; padding: 5px 8px; color: #465950; background: #fff; border: 1px solid #e8eeea; border-radius: 5px; text-align: left; cursor: pointer; }
.dataset-choice:hover { background: #f7faf8; border-color: #b8cec1; }
.dataset-choice.is-active { color: #1a604c; background: #eff7f2; border-color: #8fb7a0; box-shadow: inset 2px 0 #428364; }
.dataset-choice-name { min-width: 0; overflow: hidden; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.dataset-choice-meta { flex: 0 0 auto; color: #8a9791; font-size: 9px; }
.node-empty { padding: 16px 4px; color: #8a9791; font-size: 10px; }
.source-detail { margin-top: 13px; padding-top: 12px; border-top: 1px solid #edf1ee; }
.choice-label { margin-bottom: 6px; color: #78877f; font-size: 10px; font-weight: 650; }
.version-choices { display: flex; flex-wrap: wrap; gap: 5px; }
.version-choice { display: flex; min-height: 29px; align-items: center; gap: 6px; padding: 4px 7px; color: #53655c; background: #f7f9f8; border: 1px solid #e3eae5; border-radius: 5px; cursor: pointer; }
.version-choice strong { color: #344b40; font-size: 10px; }
.version-choice span { color: #84928a; font-size: 9px; }
.version-choice.is-active { background: #eaf3ed; border-color: #91b5a0; }
.table-label { margin-top: 11px; }
.table-choices { display: grid; max-height: 160px; gap: 5px; overflow-y: auto; }
.table-choice { display: flex; min-width: 0; min-height: 34px; align-items: center; justify-content: space-between; gap: 6px; padding: 6px 8px; color: #485b51; background: #fff; border: 1px solid #e4ebe6; border-radius: 5px; text-align: left; cursor: pointer; }
.table-choice.is-active { color: #1b614d; background: #f0f7f3; border-color: #91b5a0; }
.table-choice > span { min-width: 0; overflow: hidden; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.table-choice small { flex: 0 0 auto; color: #87948d; font-size: 9px; }
.choice-loading { display: block; padding: 5px 0; color: #89968f; font-size: 10px; }
.node-inline-error { display: flex; align-items: center; gap: 5px; margin-top: 8px; color: #9b4b40; font-size: 10px; }
.node-inline-error span { min-width: 0; flex: 1; overflow-wrap: anywhere; }
.field-summary { display: flex; min-width: 0; flex-wrap: wrap; gap: 5px 14px; margin-bottom: 10px; color: #75847c; font-size: 10px; }
.field-summary strong { color: #344b40; font-weight: 680; }
.field-summary .target-summary { min-width: 0; max-width: 45%; overflow: hidden; color: #9c7131; text-overflow: ellipsis; white-space: nowrap; }
.task-row { display: flex; min-height: 36px; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 10px; color: #78877f; font-size: 10px; }
.field-list { display: flex; max-height: 390px; flex-direction: column; gap: 4px; margin-top: 8px; overflow: auto; }
.field-row { display: flex; min-width: 0; min-height: 34px; align-items: center; justify-content: space-between; gap: 6px; padding: 3px 6px; background: #fff; border: 1px solid #e8eeea; border-radius: 5px; }
.field-row.is-target { background: #fff9ed; border-color: #e7cd99; }
.field-row.is-feature { border-color: #cbded2; }
.field-target-button { display: flex; min-width: 0; flex: 1; align-items: center; gap: 6px; padding: 0; color: #43574d; background: none; border: 0; text-align: left; cursor: pointer; }
.field-target-button:hover .field-name { color: var(--accent); }
.field-target-mark { display: grid; width: 19px; height: 19px; flex: 0 0 19px; place-items: center; color: #819087; border: 1px solid #d6e0da; border-radius: 4px; font-size: 9px; }
.field-row.is-target .field-target-mark { color: #8b5c13; background: #f9e9c5; border-color: #e6c982; font-weight: 700; }
.field-name { min-width: 0; overflow: hidden; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.field-target-button small { flex: 0 0 auto; max-width: 80px; overflow: hidden; color: #89968f; font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.field-row :deep(.el-checkbox) { flex: 0 0 auto; margin-right: 0; }
.field-row :deep(.el-checkbox__label) { padding-left: 4px; color: #718078; font-size: 9px; }
.fields-empty { display: flex; min-height: 190px; flex-direction: column; align-items: center; justify-content: center; gap: 10px; padding: 20px; color: #84928b; font-size: 11px; line-height: 1.6; text-align: center; }
.fields-empty :deep(.el-icon) { color: #67917a; font-size: 24px; }
.model-branch-list { display: grid; gap: 9px; }
.model-branch { width: 100%; min-width: 0; padding: 9px; background: #fbfcfb; border: 1px solid #e4ebe6; border-radius: 6px; }
.branch-title { display: flex; min-height: 25px; align-items: center; gap: 6px; margin-bottom: 7px; }
.branch-title strong { flex: 1; color: #4a5c53; font-size: 10px; font-weight: 650; }
.branch-index { display: grid; width: 19px; height: 19px; place-items: center; color: #426f5a; background: #e8f1eb; border-radius: 4px; font-size: 9px; font-weight: 700; }
.algorithm-choices { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 5px; }
.algorithm-choice { display: flex; min-width: 0; min-height: 58px; flex-direction: column; align-items: flex-start; justify-content: center; gap: 4px; padding: 7px; color: #4a5b52; background: #fff; border: 1px solid #e3eae5; border-radius: 5px; text-align: left; cursor: pointer; }
.algorithm-choice:hover:not(:disabled) { border-color: #9cbca9; }
.algorithm-choice.is-active { color: #205e49; background: #edf6f0; border-color: #86ad96; box-shadow: inset 0 0 0 1px #c4ddcc; }
.algorithm-choice:disabled { opacity: 0.45; cursor: not-allowed; }
.algorithm-choice strong { font-size: 10px; font-weight: 660; }
.algorithm-choice span { color: #839088; font-size: 9px; line-height: 1.35; }
.branch-note { margin-top: 6px; color: #829089; font-size: 9px; line-height: 1.45; }
.training-options { display: grid; grid-template-columns: minmax(0, 1fr); gap: 9px; margin-top: 13px; padding-top: 12px; border-top: 1px solid #e9efeb; }
.option-field { display: grid; min-width: 0; gap: 5px; color: #66776e; font-size: 10px; font-weight: 600; }
.option-field > span { display: flex; justify-content: space-between; gap: 8px; }
.option-field small { color: #89968f; font-size: 9px; font-weight: 500; }
.option-field :deep(.el-input-number), .option-field :deep(.el-input) { width: 100%; }
.option-field :deep(.el-slider) { padding: 0 7px; }
.model-message { display: flex; min-height: 40px; align-items: center; gap: 9px; margin: 12px 16px; padding: 7px 10px; border: 1px solid; border-radius: 6px; font-size: 11px; }
.model-message > span { min-width: 0; flex: 1; overflow-wrap: anywhere; }
.model-message.is-error { color: #8d3c35; background: #fff4f0; border-color: #f1d2c8; }
.model-message.is-warning { color: #7a6127; background: #fff9e9; border-color: #efe2b9; }
.workflow-footer { display: flex; min-width: 0; min-height: 60px; align-items: center; justify-content: space-between; gap: 14px; padding: 10px 16px; border-top: 1px solid #e6ede8; }
.run-context { display: flex; min-width: 0; flex-wrap: wrap; align-items: center; gap: 5px 12px; color: #708078; font-size: 10px; }
.run-context span { max-width: 240px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.run-actions { display: flex; min-width: 0; align-items: center; gap: 12px; }
.form-footnote, .running-status { min-width: 0; color: #829089; font-size: 10px; }
.running-status { display: inline-flex; align-items: center; gap: 7px; color: var(--accent); }
.is-spinning { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.result-panel { padding: 12px; }
.result-heading { display: flex; min-width: 0; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
.result-heading h2 { margin: 0; color: #263c32; font-size: 16px; line-height: 1.35; }
.result-heading p { margin: 4px 0 0; color: #7d8b84; font-size: 10px; }
.result-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 280px), 1fr)); gap: 10px; }
.branch-result { min-width: 0; padding: 12px; background: #fbfdfb; border: 1px solid #e1eae4; border-radius: 6px; }
.branch-result > header { display: flex; min-width: 0; align-items: center; justify-content: space-between; gap: 10px; }
.branch-result > header > span { display: flex; min-width: 0; align-items: center; gap: 7px; color: #285d45; font-size: 11px; font-weight: 670; }
.branch-result > header > span :deep(.el-icon) { flex: 0 0 auto; color: #31825a; }
.branch-result > header small { min-width: 0; overflow: hidden; color: #77867e; font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.branch-result.is-failed { color: #87443b; background: #fff7f4; border-color: #eed3ca; }
.branch-result.is-failed > header > span { color: #87443b; }
.branch-result.is-failed > p { margin: 8px 0 0; overflow-wrap: anywhere; font-size: 10px; line-height: 1.5; }
.result-meta { display: flex; min-width: 0; flex-wrap: wrap; gap: 6px 12px; margin-top: 9px; color: #839088; font-size: 9px; }
.result-metrics { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); margin-top: 10px; overflow: hidden; border: 1px solid #e4ece6; border-radius: 5px; }
.result-metrics > div { display: flex; min-width: 0; min-height: 50px; flex-direction: column; justify-content: center; gap: 4px; padding: 7px 9px; border-right: 1px solid #e4ece6; border-bottom: 1px solid #e4ece6; }
.result-metrics > div:nth-child(2n) { border-right: 0; }
.result-metrics > div:nth-last-child(-n + 2) { border-bottom: 0; }
.result-metrics span { overflow: hidden; color: #7c8a82; font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.result-metrics strong { overflow: hidden; color: #244536; font-size: 13px; font-weight: 680; text-overflow: ellipsis; white-space: nowrap; }
.sample-table { margin-top: 10px; }
.branch-actions { display: flex; justify-content: flex-end; margin-top: 10px; padding-top: 8px; border-top: 1px solid #e7eeea; }
.sample-heading { display: flex; min-height: 28px; align-items: center; gap: 6px; color: #72827a; }
.sample-heading h3 { margin: 0; color: #40534b; font-size: 10px; font-weight: 650; }
.sample-heading > span { margin-left: auto; font-size: 9px; }
.registry-section { min-width: 0; }
.registry-heading { display: flex; min-height: 48px; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 7px; }
.registry-heading > div { display: flex; align-items: baseline; gap: 9px; }
.registry-heading h2 { margin: 0; color: #20372f; font-size: 15px; font-weight: 650; }
.registry-heading > div > span { color: #829089; font-size: 10px; }
.model-table { width: 100%; overflow: hidden; border: 1px solid #e5ece8; border-radius: 7px; }
.model-name-cell, .lineage-cell { display: flex; min-width: 0; flex-direction: column; gap: 4px; }
.model-name-cell strong, .lineage-cell span { overflow: hidden; color: #30463d; font-size: 11px; font-weight: 620; text-overflow: ellipsis; white-space: nowrap; }
.model-name-cell span, .lineage-cell small { overflow: hidden; color: #84928b; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.registry-metrics { display: flex; min-width: 0; flex-wrap: wrap; gap: 4px 10px; color: #586b63; font-size: 10px; }
.registry-metrics span { max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

@container (max-width: 930px) {
  .workflow-track { grid-template-columns: minmax(0, 1fr); gap: 0; }
  .flow-node { max-width: none; }
  .flow-connector { min-height: 25px; margin-top: 0; }
  .flow-connector::before { width: 1px; height: 100%; }
  .flow-connector::after { right: auto; bottom: 2px; transform: rotate(135deg); }
  .branch-connector span { display: none; }
}

@media (max-width: 1120px) {
  .workflow-canvas { padding: 12px; }
  .flow-node { padding: 12px; }
}

@media (max-width: 900px) {
  .workflow-topline { align-items: flex-start; flex-direction: column; }
  .workflow-controls { width: 100%; justify-content: flex-end; }
  .workflow-name-input { width: min(240px, calc(100vw - 210px)); margin-right: auto; }
  .field-list { max-height: 300px; }
  .workflow-footer { align-items: flex-start; flex-direction: column; }
  .run-actions { width: 100%; justify-content: space-between; }
}

@media (max-width: 600px) {
  .workflow-bar { gap: 5px; padding: 8px 10px 0; }
  .workflow-title-group { align-items: flex-start; flex-direction: column; gap: 3px; }
  .workflow-controls { justify-content: flex-start; }
  .workflow-name-input { width: min(190px, calc(100vw - 210px)); }
  .workflow-tab { min-width: 90px; max-width: 150px; height: 32px; padding: 0 8px; }
  .workflow-canvas { padding: 9px; }
  .flow-node { padding: 11px; }
  .workflow-footer { padding: 11px 12px; }
  .run-context { gap: 5px 9px; }
  .run-actions { align-items: flex-start; flex-direction: column; }
  .run-actions .el-button { width: 100%; }
  .algorithm-choices { grid-template-columns: minmax(0, 1fr); }
  .algorithm-choice { min-height: 48px; }
  .result-panel { padding: 12px; }
  .registry-heading > div { align-items: flex-start; flex-direction: column; gap: 2px; }
}
</style>
