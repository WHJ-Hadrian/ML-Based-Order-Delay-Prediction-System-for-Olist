<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { MiniMap } from '@vue-flow/minimap'
import { Handle, Position, VueFlow, useVueFlow } from '@vue-flow/core'
import {
  CircleCheck, CircleClose, CopyDocument, Delete, DocumentChecked, Expand, FullScreen, Loading, Plus, Refresh, VideoPlay,
  SetUp, Sort, DataAnalysis, Connection, Cpu, Histogram, Files, Filter, Operation, FolderAdd,
} from '@element-plus/icons-vue'
import { ElMessageBox } from 'element-plus'
import { cancelWorkflowRun, createWorkflow, deleteWorkflow, duplicateWorkflow, getWorkflow, listWorkflowRuns, listWorkflows, retryWorkflowRun, startWorkflowRun, updateWorkflow } from '@/api/workflows'
import { getDatasetVersionCatalog, getDatasetVersions, listDatasets } from '@/api/datasets'
import { listPublishedRuleVersions } from '@/api/ruleLibrary'

const nodeCatalog = [
  { type: 'DATA_SOURCE', label: '数据源', description: '从数据集版本或表引入数据', icon: Files, color: '#398a68' },
  { type: 'ANALYSIS', label: '分析', description: '将分析方案作为工作流步骤', icon: DataAnalysis, color: '#3479a5' },
  { type: 'RULE', label: '规则', description: '应用已发布的规则版本', icon: SetUp, color: '#b87536' },
  { type: 'FIELD_MAPPING', label: '字段映射', description: '将来源字段绑定到语义字段', icon: Connection, color: '#7c68a4' },
  { type: 'TRANSFORM', label: '转换', description: '配置白名单数据转换操作', icon: Operation, color: '#568278' },
  { type: 'FILTER', label: '筛选', description: '按可视化条件筛选记录', icon: Filter, color: '#bf8050' },
  { type: 'JOIN', label: '连接', description: '按字段关系合并数据流', icon: Connection, color: '#5277a0' },
  { type: 'AGGREGATE', label: '聚合', description: '按维度汇总字段指标', icon: Histogram, color: '#927343' },
  { type: 'TRAIN_MODEL', label: '训练', description: '配置目标、特征与模型任务', icon: Cpu, color: '#397f7b' },
  { type: 'EVALUATE', label: '评估', description: '评估模型或数据质量结果', icon: Sort, color: '#9b6f3a' },
  { type: 'PREDICT', label: '预测', description: '对独立数据运行模型推理', icon: DataAnalysis, color: '#3a7790' },
  { type: 'OUTPUT', label: '输出', description: '定义数据或模型产物输出', icon: Expand, color: '#658b56' },
]

const workflows = ref([])
const runs = ref([])
const selectedRunId = ref('')
const activeId = ref('')
const name = ref('')
const revision = ref(0)
const nodes = ref([])
const edges = ref([])
const selectedNodeId = ref('')
const selectedEdgeId = ref('')
const dirty = ref(false)
const busy = ref(false)
const runBusy = ref(false)
const loadingRuns = ref(false)
const loading = ref(true)
const error = ref('')
const message = ref('')
const canvasShell = ref(null)
const nativeFullscreen = ref(false)
const sourceDatasets = ref([])
const sourceVersions = ref([])
const sourceTables = ref([])
const sourceLoading = ref(false)
const sourceError = ref('')
const filterColumns = ref([])
const filterLoading = ref(false)
const filterError = ref('')
const publishedRules = ref([])
const ruleLoading = ref(false)
const ruleError = ref('')
const selectedNode = computed(() => nodes.value.find((node) => node.id === selectedNodeId.value) || null)
const selectedEdge = computed(() => edges.value.find((edge) => edge.id === selectedEdgeId.value) || null)
const selectedRun = computed(() => runs.value.find((run) => run.id === selectedRunId.value) || null)
const { addEdges, fitView } = useVueFlow()
let saveTimer
let applyingDocument = false
let sourceLoadSequence = 0
let ruleLoadSequence = 0
let filterLoadSequence = 0
let runPollTimer

function runIsActive(run) { return ['QUEUED', 'RUNNING', 'CANCELLING'].includes(run?.status) }

function scheduleRunPoll() {
  clearTimeout(runPollTimer)
  if (activeId.value && runs.value.some(runIsActive)) {
    runPollTimer = setTimeout(refreshRuns, 1800)
  }
}

async function refreshRuns() {
  const workflowId = activeId.value
  if (!workflowId) return
  loadingRuns.value = true
  try {
    const latest = await listWorkflowRuns(workflowId)
    if (activeId.value !== workflowId) return
    runs.value = latest || []
    if (!selectedRunId.value || !runs.value.some((run) => run.id === selectedRunId.value)) {
      selectedRunId.value = runs.value[0]?.id || ''
    } else if (runs.value.some((run) => run.id === selectedRunId.value)) {
      const current = runs.value.find((run) => run.id === selectedRunId.value)
      if (current) runs.value = runs.value.map((run) => run.id === current.id ? current : run)
    }
  } catch (cause) {
    error.value = cause.message || '运行记录载入失败'
  } finally {
    if (activeId.value === workflowId) loadingRuns.value = false
    scheduleRunPoll()
  }
}

async function startRun() {
  if (!activeId.value || runBusy.value || !(await saveWorkflow())) return
  runBusy.value = true
  error.value = ''
  try {
    const run = await startWorkflowRun(activeId.value, revision.value)
    runs.value = [run, ...runs.value.filter((item) => item.id !== run.id)]
    selectedRunId.value = run.id
    message.value = '运行已提交，关闭页面后任务仍会继续'
    scheduleRunPoll()
  } catch (cause) {
    error.value = cause.message || '无法启动工作流运行'
  } finally {
    runBusy.value = false
  }
}

async function cancelRun() {
  if (!selectedRun.value || !runIsActive(selectedRun.value)) return
  runBusy.value = true
  try {
    const updated = await cancelWorkflowRun(selectedRun.value.id)
    runs.value = runs.value.map((run) => run.id === updated.id ? updated : run)
    scheduleRunPoll()
  } catch (cause) {
    error.value = cause.message || '取消运行失败'
  } finally {
    runBusy.value = false
  }
}

async function retryRun() {
  if (!selectedRun.value || runBusy.value) return
  runBusy.value = true
  try {
    const retry = await retryWorkflowRun(selectedRun.value.id)
    runs.value = [retry, ...runs.value]
    selectedRunId.value = retry.id
    scheduleRunPoll()
  } catch (cause) {
    error.value = cause.message || '重试运行失败'
  } finally {
    runBusy.value = false
  }
}

function runStatusLabel(status) {
  return ({ QUEUED: '排队中', RUNNING: '运行中', CANCELLING: '正在取消', SUCCEEDED: '已完成', FAILED: '失败', CANCELED: '已取消' })[status] || status
}

function nodeStatusLabel(status) {
  return ({ QUEUED: '排队中', RUNNING: '运行中', SUCCEEDED: '完成', FAILED: '失败' })[status] || status
}

function formatRunTime(value) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '等待 Worker'
}

function nodeType(type) { return nodeCatalog.find((item) => item.type === type) || nodeCatalog[0] }

function workflowDocument() {
  return {
    version: 1,
    nodes: nodes.value.map((node) => ({
      id: node.id,
      type: node.data?.kind || node.type,
      position: { x: Math.round(node.position.x), y: Math.round(node.position.y) },
      data: {
        label: node.data?.label || nodeType(node.type).label,
        description: node.data?.description || '',
        config: node.data?.config || {},
      },
    })),
    edges: edges.value.map((edge) => ({
      id: edge.id,
      source: edge.source,
      target: edge.target,
      sourceHandle: edge.sourceHandle || 'output',
      targetHandle: edge.targetHandle || 'input',
    })),
  }
}

function applyDocument(document) {
  applyingDocument = true
  nodes.value = (document?.nodes || []).map((node) => ({
    ...node,
    type: 'default',
    data: {
      label: node.data?.label || nodeType(node.type).label,
      description: node.data?.description || '',
      config: node.data?.config || {},
      kind: node.type,
    },
    position: { x: Number(node.position?.x) || 0, y: Number(node.position?.y) || 0 },
  }))
  edges.value = (document?.edges || []).map((edge) => ({ ...edge, sourceHandle: edge.sourceHandle || 'output', targetHandle: edge.targetHandle || 'input' }))
  selectedNodeId.value = ''
  selectedEdgeId.value = ''
  dirty.value = false
  requestAnimationFrame(() => {
    fitView({ padding: 0.2, duration: 180 })
    applyingDocument = false
  })
}

async function loadList() {
  loading.value = true
  error.value = ''
  try {
    workflows.value = await listWorkflows() || []
    if (workflows.value.length) await selectWorkflow(workflows.value[0].id, true)
  } catch (cause) {
    error.value = cause.message || '工作流载入失败'
  } finally {
    loading.value = false
  }
}

async function selectWorkflow(id, skipSave = false) {
  if (!id || id === activeId.value) return true
  if (!skipSave && !(await saveWorkflow())) return false
  busy.value = true
  error.value = ''
  try {
    const loaded = await getWorkflow(id)
    activeId.value = loaded.id
    name.value = loaded.name
    revision.value = loaded.revision
    applyDocument(loaded.document)
    runs.value = []
    selectedRunId.value = ''
    await refreshRuns()
    message.value = ''
    return true
  } catch (cause) {
    error.value = cause.message || '無法载入工作流'
    return false
  } finally {
    busy.value = false
  }
}

async function createNewWorkflow() {
  if (busy.value || !(await saveWorkflow())) return
  busy.value = true
  error.value = ''
  try {
    const created = await createWorkflow({ name: `新建工作流 ${new Date().toLocaleDateString('zh-CN')}`, document: { version: 1, nodes: [], edges: [] } })
    workflows.value = [created, ...workflows.value]
    activeId.value = created.id
    name.value = created.name
    revision.value = created.revision
    applyDocument(created.document)
    runs.value = []
    selectedRunId.value = ''
    message.value = '工作流已创建'
  } catch (cause) {
    error.value = cause.message || '创建工作流失败'
  } finally {
    busy.value = false
  }
}

async function saveWorkflow() {
  clearTimeout(saveTimer)
  if (!activeId.value || !dirty.value) return true
  if (busy.value) return false
  busy.value = true
  error.value = ''
  try {
    const saved = await updateWorkflow(activeId.value, { name: name.value, document: workflowDocument(), expectedRevision: revision.value })
    revision.value = saved.revision
    dirty.value = false
    workflows.value = [saved, ...workflows.value.filter((item) => item.id !== saved.id)]
    message.value = '已保存'
    return true
  } catch (cause) {
    error.value = cause.message || '工作流保存失败'
    return false
  } finally {
    busy.value = false
  }
}

function scheduleSave() {
  clearTimeout(saveTimer)
  saveTimer = setTimeout(() => saveWorkflow(), 850)
}

function addNode(item) {
  if (!activeId.value) return
  const nextIndex = nodes.value.length
  const nodeId = `node-${crypto.randomUUID()}`
  nodes.value = [...nodes.value, {
    id: nodeId,
    type: 'default',
    position: { x: 80 + (nextIndex % 3) * 290, y: 70 + Math.floor(nextIndex / 3) * 190 },
    data: { kind: item.type, label: item.label, description: '', config: {} },
  }]
  selectedNodeId.value = nodeId
  selectedEdgeId.value = ''
}

function connectNodes(connection) {
  if (!connection.source || !connection.target || connection.source === connection.target) return
  addEdges([{ ...connection, id: `edge-${crypto.randomUUID()}`, sourceHandle: 'output', targetHandle: 'input' }])
}

function handleNodeClick({ node }) {
  selectedNodeId.value = node.id
  selectedEdgeId.value = ''
}

async function loadSourceDatasets(nodeId) {
  const sequence = ++sourceLoadSequence
  sourceLoading.value = true
  sourceError.value = ''
  sourceDatasets.value = []
  sourceVersions.value = []
  sourceTables.value = []
  try {
    const page = await listDatasets({ page: 0, size: 100, hasData: true })
    if (sequence !== sourceLoadSequence || selectedNodeId.value !== nodeId) return
    sourceDatasets.value = page?.items || []
    const config = nodes.value.find((node) => node.id === nodeId)?.data?.config || {}
    if (config.datasetId) {
      if (!sourceDatasets.value.some((dataset) => dataset.id === config.datasetId)) {
        sourceDatasets.value.unshift({ id: config.datasetId, name: config.datasetName || config.datasetId })
      }
      await loadSourceVersions(nodeId, config.datasetId, config.datasetVersionId, config.tableId, sequence)
    }
  } catch (cause) {
    if (sequence === sourceLoadSequence) sourceError.value = cause.message || '数据集载入失败'
  } finally {
    if (sequence === sourceLoadSequence) sourceLoading.value = false
  }
}

async function loadSourceVersions(nodeId, datasetId, selectedVersionId = '', selectedTableId = '', sequence = ++sourceLoadSequence) {
  const target = nodes.value.find((node) => node.id === nodeId)
  if (!target) return
  const datasetName = target.data.config?.datasetName
    || sourceDatasets.value.find((dataset) => dataset.id === datasetId)?.name
    || datasetId
  target.data.config = { datasetId, datasetName }
  sourceVersions.value = []
  sourceTables.value = []
  sourceError.value = ''
  try {
    const versions = await getDatasetVersions(datasetId)
    if (sequence !== sourceLoadSequence || selectedNodeId.value !== nodeId) return
    sourceVersions.value = (versions || []).filter((version) => version.status === 'READY')
    const version = sourceVersions.value.find((item) => item.datasetVersionId === selectedVersionId)
    if (version) await loadSourceTables(nodeId, version, selectedTableId, sequence)
  } catch (cause) {
    if (sequence === sourceLoadSequence) sourceError.value = cause.message || '数据集版本载入失败'
  }
}

async function loadSourceTables(nodeId, version, selectedTableId = '', sequence = ++sourceLoadSequence) {
  const target = nodes.value.find((node) => node.id === nodeId)
  if (!target) return
  const datasetName = target.data.config?.datasetName
    || sourceDatasets.value.find((dataset) => dataset.id === version.datasetId)?.name
    || version.datasetId
  target.data.config = {
    datasetId: version.datasetId,
    datasetName,
    datasetVersionId: version.datasetVersionId,
    versionNo: version.versionNo,
    schemaHash: version.schemaHash || '',
  }
  sourceTables.value = []
  sourceError.value = ''
  try {
    const catalog = await getDatasetVersionCatalog(version.datasetVersionId)
    if (sequence !== sourceLoadSequence || selectedNodeId.value !== nodeId) return
    sourceTables.value = catalog?.tables || []
    const table = sourceTables.value.find((item) => item.tableId === selectedTableId)
    if (table) setSourceTable(target, version, table, catalog.schemaHash)
  } catch (cause) {
    if (sequence === sourceLoadSequence) sourceError.value = cause.message || '資料表結構載入失敗'
  }
}

function setSourceTable(node, version, table, schemaHash = version.schemaHash || '') {
  const datasetName = node.data.config?.datasetName
    || sourceDatasets.value.find((dataset) => dataset.id === version.datasetId)?.name
    || version.datasetId
  node.data.config = {
    datasetId: version.datasetId,
    datasetName,
    datasetVersionId: version.datasetVersionId,
    versionNo: version.versionNo,
    tableId: table.tableId,
    tableName: table.tableName,
    rowCount: table.rowCount,
    schemaHash,
  }
}

async function changeSourceDataset(datasetId) {
  const nodeId = selectedNodeId.value
  const sequence = ++sourceLoadSequence
  sourceVersions.value = []
  sourceTables.value = []
  const target = nodes.value.find((node) => node.id === nodeId)
  if (!target) return
  const datasetName = sourceDatasets.value.find((dataset) => dataset.id === datasetId)?.name
  target.data.config = datasetId ? { datasetId, datasetName } : {}
  if (!datasetId) return
  sourceLoading.value = true
  try {
    await loadSourceVersions(nodeId, datasetId, '', '', sequence)
  } finally {
    if (sequence === sourceLoadSequence) sourceLoading.value = false
  }
}

async function loadPublishedRules(nodeId) {
  const sequence = ++ruleLoadSequence
  ruleLoading.value = true
  ruleError.value = ''
  try {
    const rules = await listPublishedRuleVersions({ limit: 100 })
    if (sequence !== ruleLoadSequence || selectedNodeId.value !== nodeId) return
    publishedRules.value = rules || []
  } catch (cause) {
    if (sequence === ruleLoadSequence) ruleError.value = cause.message || '规则版本载入失败'
  } finally {
    if (sequence === ruleLoadSequence) ruleLoading.value = false
  }
}

function changeRuleVersion(versionId) {
  const node = selectedNode.value
  if (!node) return
  const version = publishedRules.value.find((item) => item.id === versionId)
  if (!version) {
    node.data.config = {}
    return
  }
  node.data.config = {
    ruleVersionId: version.id,
    workbookId: version.workbookId,
    workbookName: version.workbookName,
    versionNumber: version.versionNumber,
    sourceDatasetVersionId: version.sourceDatasetVersionId,
    contentHash: version.contentHash,
  }
}

async function changeSourceVersion(versionId) {
  const nodeId = selectedNodeId.value
  const sequence = ++sourceLoadSequence
  sourceTables.value = []
  const target = nodes.value.find((node) => node.id === nodeId)
  const version = sourceVersions.value.find((item) => item.datasetVersionId === versionId)
  if (!target) return
  if (!version) {
    target.data.config = { datasetId: target.data.config.datasetId }
    return
  }
  sourceLoading.value = true
  try {
    await loadSourceTables(nodeId, version, '', sequence)
  } finally {
    if (sequence === sourceLoadSequence) sourceLoading.value = false
  }
}

function changeSourceTable(tableId) {
  const node = selectedNode.value
  if (!node) return
  const table = sourceTables.value.find((item) => item.tableId === tableId)
  const version = sourceVersions.value.find((item) => item.datasetVersionId === node.data.config?.datasetVersionId)
  if (table && version) setSourceTable(node, version, table)
  else if (version) {
    const versionConfig = { ...node.data.config }
    delete versionConfig.tableId
    delete versionConfig.tableName
    delete versionConfig.rowCount
    node.data.config = versionConfig
  }
}

async function loadFilterColumns(nodeId) {
  const sequence = ++filterLoadSequence
  filterColumns.value = []
  filterError.value = ''
  const incoming = edges.value.find((edge) => edge.target === nodeId)
  const source = nodes.value.find((node) => node.id === incoming?.source && node.data?.kind === 'DATA_SOURCE')
  const versionId = source?.data?.config?.datasetVersionId
  if (!versionId) return
  filterLoading.value = true
  try {
    const catalog = await getDatasetVersionCatalog(versionId)
    if (sequence !== filterLoadSequence || selectedNodeId.value !== nodeId) return
    filterColumns.value = catalog?.tables
      ?.find((table) => table.tableId === source.data.config.tableId)?.columns || []
    const config = nodes.value.find((node) => node.id === nodeId)?.data?.config || {}
    if (config.columnName && !filterColumns.value.some((column) => column.name === config.columnName)) {
      filterError.value = '已配置字段不在上游数据表中，请重新选择'
    }
  } catch (cause) {
    if (sequence === filterLoadSequence) filterError.value = cause.message || '上游字段载入失败'
  } finally {
    if (sequence === filterLoadSequence) filterLoading.value = false
  }
}

function changeFilterColumn(columnName) {
  if (!selectedNode.value) return
  selectedNode.value.data.config = { ...selectedNode.value.data.config, columnName }
}

function changeFilterOperator(operator) {
  if (!selectedNode.value) return
  const config = { ...selectedNode.value.data.config, operator }
  if (operator === 'IS_EMPTY' || operator === 'IS_NOT_EMPTY') delete config.value
  else if (typeof config.value !== 'string') config.value = ''
  selectedNode.value.data.config = config
}

function handleEdgeClick({ edge }) {
  selectedEdgeId.value = edge.id
  selectedNodeId.value = ''
}

function removeSelectedNode() {
  if (!selectedNodeId.value) return
  const id = selectedNodeId.value
  nodes.value = nodes.value.filter((node) => node.id !== id)
  edges.value = edges.value.filter((edge) => edge.source !== id && edge.target !== id)
  selectedNodeId.value = ''
}

function removeSelectedEdge() {
  if (!selectedEdgeId.value) return
  edges.value = edges.value.filter((edge) => edge.id !== selectedEdgeId.value)
  selectedEdgeId.value = ''
}

async function duplicateActive() {
  if (!activeId.value || busy.value || !(await saveWorkflow())) return
  busy.value = true
  try {
    const copy = await duplicateWorkflow(activeId.value, `${name.value} 副本`.slice(0, 255))
    workflows.value = [copy, ...workflows.value]
    await selectWorkflow(copy.id, true)
    message.value = '已复制画布'
  } catch (cause) {
    error.value = cause.message || '复制工作流失败'
  } finally {
    busy.value = false
  }
}

async function removeActive() {
  if (!activeId.value || busy.value) return
  try {
    await ElMessageBox.confirm(`删除“${name.value}”后无法恢复。`, '删除工作流', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
    busy.value = true
    await deleteWorkflow(activeId.value)
    workflows.value = workflows.value.filter((item) => item.id !== activeId.value)
    activeId.value = ''
    runs.value = []
    selectedRunId.value = ''
    name.value = ''
    revision.value = 0
    applyDocument({ nodes: [], edges: [] })
    if (workflows.value.length) await selectWorkflow(workflows.value[0].id, true)
    message.value = '工作流已删除'
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') error.value = cause.message || '删除工作流失败'
  } finally {
    busy.value = false
  }
}

async function toggleFullscreen() {
  if (!canvasShell.value) return
  if (document.fullscreenElement === canvasShell.value) await document.exitFullscreen()
  else if (canvasShell.value.requestFullscreen) await canvasShell.value.requestFullscreen()
  else canvasShell.value.classList.toggle('is-focused')
}

function syncFullscreen() { nativeFullscreen.value = document.fullscreenElement === canvasShell.value }

watch(() => JSON.stringify([workflowDocument(), name.value]), () => {
  if (!activeId.value || applyingDocument) return
  dirty.value = true
  message.value = ''
  scheduleSave()
})

watch(selectedNodeId, (nodeId) => {
  const node = nodes.value.find((item) => item.id === nodeId)
  if (node?.data.kind === 'DATA_SOURCE') loadSourceDatasets(nodeId)
  else {
    sourceLoadSequence += 1
    sourceLoading.value = false
  }
  if (node?.data.kind === 'RULE') loadPublishedRules(nodeId)
  else {
    ruleLoadSequence += 1
    ruleLoading.value = false
  }
  if (node?.data.kind === 'FILTER') loadFilterColumns(nodeId)
  else {
    filterLoadSequence += 1
    filterLoading.value = false
  }
})

watch(() => edges.value.map((edge) => `${edge.source}:${edge.target}`).join('|'), () => {
  if (selectedNode.value?.data.kind === 'FILTER') loadFilterColumns(selectedNode.value.id)
})

onMounted(() => {
  document.addEventListener('fullscreenchange', syncFullscreen)
  loadList()
})

onBeforeUnmount(() => {
  clearTimeout(saveTimer)
  clearTimeout(runPollTimer)
  document.removeEventListener('fullscreenchange', syncFullscreen)
  if (dirty.value && activeId.value) saveWorkflow()
})
</script>

<template>
  <main class="workflow-page">
    <header class="page-heading">
      <div>
        <h1>工作流</h1>
        <p>组合数据、分析、规则与模型节点，保存可复用的流程定义。</p>
      </div>
      <el-button type="primary" :icon="FolderAdd" :disabled="busy" @click="createNewWorkflow">新建画布</el-button>
    </header>

    <section ref="canvasShell" class="workflow-workspace" :class="{ 'is-fullscreen': nativeFullscreen }">
      <header class="workflow-toolbar">
        <div class="workflow-title-row">
          <div class="workflow-name-wrap">
            <el-input v-if="activeId" v-model="name" maxlength="255" aria-label="工作流名称" :disabled="busy" />
            <strong v-else>{{ loading ? '正在载入工作流' : '选择或新建工作流' }}</strong>
            <span class="save-state" :class="{ 'is-dirty': dirty, 'is-error': error }">{{ error || (busy ? '同步中' : dirty ? '有未保存修改' : message || (activeId ? '已保存' : '定义草稿')) }}</span>
          </div>
          <div class="toolbar-actions">
            <el-button type="primary" :icon="VideoPlay" :loading="runBusy" :disabled="!activeId || busy || dirty || !nodes.length" @click="startRun">运行</el-button>
            <el-button circle aria-label="复制画布" title="复制画布" :icon="CopyDocument" :disabled="!activeId || busy" @click="duplicateActive" />
            <el-button circle aria-label="保存工作流" title="保存工作流" :icon="DocumentChecked" :loading="busy" :disabled="!dirty" @click="saveWorkflow" />
            <el-button circle aria-label="聚焦画布" title="聚焦画布" :icon="nativeFullscreen ? Expand : FullScreen" :disabled="!activeId" @click="toggleFullscreen" />
            <el-button circle aria-label="删除工作流" title="删除工作流" :icon="Delete" :disabled="!activeId || busy" @click="removeActive" />
          </div>
        </div>
        <nav v-if="workflows.length" class="workflow-tabs" aria-label="工作流画布">
          <button v-for="workflow in workflows" :key="workflow.id" type="button" class="workflow-tab" :class="{ 'is-active': activeId === workflow.id }" :title="workflow.name" :disabled="busy" @click="selectWorkflow(workflow.id)">
            <span class="tab-dot" :class="{ 'is-dirty': activeId === workflow.id && dirty }" />
            <span>{{ workflow.name }}</span>
          </button>
        </nav>
      </header>

      <div v-if="error && !activeId" class="workflow-alert" role="alert"><span>{{ error }}</span><el-button text :icon="Refresh" @click="loadList">重试</el-button></div>
      <section v-if="!loading && !activeId" class="workflow-empty">
        <el-icon><FolderAdd /></el-icon>
        <h2>{{ error ? '暂时无法载入' : '从一个空白画布开始' }}</h2>
        <el-button type="primary" :icon="Plus" :disabled="busy" @click="createNewWorkflow">新建工作流</el-button>
      </section>

      <div v-else-if="activeId" class="workflow-editor">
        <aside class="node-library" aria-label="节点目录">
          <div class="rail-heading"><strong>节点</strong><span>{{ nodeCatalog.length }}</span></div>
          <button v-for="item in nodeCatalog" :key="item.type" class="node-add" type="button" :disabled="busy" :title="item.description" @click="addNode(item)">
            <span class="node-icon" :style="{ '--node-accent': item.color }"><el-icon><component :is="item.icon" /></el-icon></span>
            <span class="node-add-copy"><strong>{{ item.label }}</strong><small>{{ item.description }}</small></span>
            <el-icon class="add-symbol"><Plus /></el-icon>
          </button>
        </aside>

        <section class="canvas-column" aria-label="工作流画布">
          <div class="canvas-meta"><span>{{ nodes.length }} 个节点</span><span>{{ edges.length }} 条连接</span><span>修订 {{ revision }}</span></div>
          <div class="canvas-stage">
            <VueFlow v-model:nodes="nodes" v-model:edges="edges" class="flow-canvas" :min-zoom="0.2" :max-zoom="1.8" :fit-view-on-init="true" :nodes-connectable="true" :nodes-draggable="true" :elements-selectable="true" :delete-key-code="null" @connect="connectNodes" @node-click="handleNodeClick" @edge-click="handleEdgeClick">
              <template #node-default="{ id, data, selected }">
                <div class="flow-node" :class="[{ 'is-selected': selected }, `node-${data.kind.toLowerCase()}`]">
                  <Handle v-if="data.kind !== 'DATA_SOURCE'" id="input" type="target" :position="Position.Left" />
                  <span class="flow-node-icon" :style="{ '--node-accent': nodeType(data.kind).color }"><el-icon><component :is="nodeType(data.kind).icon" /></el-icon></span>
                  <span class="flow-node-copy"><small>{{ nodeType(data.kind).label }}</small><strong>{{ data.label }}</strong><em :title="data.kind === 'DATA_SOURCE' ? `${data.config?.datasetName || ''} · ${data.config?.tableName || ''} · v${data.config?.versionNo || ''}` : data.description || nodeType(data.kind).description">{{ data.kind === 'DATA_SOURCE' && data.config?.tableName ? `${data.config.datasetName || ''} · ${data.config.tableName} · v${data.config.versionNo}` : data.description || nodeType(data.kind).description }}</em></span>
                  <Handle v-if="data.kind !== 'OUTPUT'" id="output" type="source" :position="Position.Right" />
                  <span class="node-index">{{ id.slice(-4) }}</span>
                </div>
              </template>
              <Background pattern-color="#d5dfd9" :gap="18" />
              <MiniMap pannable zoomable />
              <Controls />
              <div v-if="nodes.length === 0" class="canvas-empty">从左侧添加节点，拖动节点并连接端口</div>
            </VueFlow>
          </div>
        </section>

        <aside class="node-inspector" aria-label="节点属性">
          <template v-if="selectedNode">
            <div class="rail-heading"><strong>节点属性</strong><span>{{ nodeType(selectedNode.data.kind).label }}</span></div>
            <label class="field-label">显示名称<el-input v-model="selectedNode.data.label" maxlength="100" /></label>
            <label class="field-label">说明<el-input v-model="selectedNode.data.description" type="textarea" :rows="3" maxlength="240" show-word-limit /></label>
            <template v-if="selectedNode.data.kind === 'DATA_SOURCE'">
              <div class="node-contract source-contract"><strong>数据资产</strong><small>引用固定的数据集版本，画布会保存所选表的 schema 标识。</small></div>
              <el-skeleton v-if="sourceLoading && !sourceDatasets.length" :rows="2" animated />
              <template v-else>
                <label class="field-label">数据集
                  <el-select :model-value="selectedNode.data.config?.datasetId || ''" filterable clearable placeholder="选择数据集" :loading="sourceLoading" @change="changeSourceDataset">
                    <el-option v-for="dataset in sourceDatasets" :key="dataset.id" :value="dataset.id" :label="dataset.name" />
                  </el-select>
                </label>
                <label class="field-label">数据版本
                  <el-select :model-value="selectedNode.data.config?.datasetVersionId || ''" :disabled="!selectedNode.data.config?.datasetId" filterable clearable placeholder="选择已就绪版本" @change="changeSourceVersion">
                    <el-option v-for="version in sourceVersions" :key="version.datasetVersionId" :value="version.datasetVersionId" :label="`v${version.versionNo} · ${version.rowCount.toLocaleString('zh-CN')} 行 · ${version.tableCount} 表`" />
                  </el-select>
                </label>
                <p v-if="selectedNode.data.config?.datasetId && !sourceLoading && !sourceVersions.length" class="source-empty">该数据集尚无已就绪版本</p>
                <label class="field-label">输入表
                  <el-select :model-value="selectedNode.data.config?.tableId || ''" :disabled="!selectedNode.data.config?.datasetVersionId" filterable clearable placeholder="选择版本中的表" @change="changeSourceTable">
                    <el-option v-for="table in sourceTables" :key="table.tableId" :value="table.tableId" :label="table.tableName" />
                  </el-select>
                </label>
                <p v-if="selectedNode.data.config?.datasetVersionId && !sourceLoading && !sourceTables.length" class="source-empty">此版本没有可用的数据表</p>
                <p v-if="selectedNode.data.config?.tableId" class="source-schema">{{ selectedNode.data.config.rowCount?.toLocaleString('zh-CN') }} 行 · {{ sourceTables.find((table) => table.tableId === selectedNode.data.config.tableId)?.columnCount }} 个字段</p>
              </template>
              <p v-if="sourceError" class="source-error" role="alert">{{ sourceError }}</p>
              <p v-else-if="!sourceLoading && !sourceDatasets.length" class="source-empty">没有可选择的数据集</p>
            </template>
            <template v-if="selectedNode.data.kind === 'RULE'">
              <div class="node-contract rule-contract"><strong>已发布规则版本</strong><small>节点固定引用发布版本；运行到其他数据集前仍需完成字段绑定与兼容性校验。</small></div>
              <label class="field-label">规则版本
                <el-select :model-value="selectedNode.data.config?.ruleVersionId || ''" filterable clearable placeholder="选择已发布版本" :loading="ruleLoading" @change="changeRuleVersion">
                  <el-option v-for="rule in publishedRules" :key="rule.id" :value="rule.id" :label="`${rule.workbookName} · v${rule.versionNumber}`" />
                </el-select>
              </label>
              <p v-if="ruleError" class="source-error" role="alert">{{ ruleError }}</p>
              <p v-else-if="!ruleLoading && !publishedRules.length" class="source-empty">暂无可引用的已发布规则版本</p>
              <p v-else-if="selectedNode.data.config?.ruleVersionId" class="source-schema">来源版本 {{ selectedNode.data.config.sourceDatasetVersionId }} · 内容 {{ selectedNode.data.config.contentHash?.slice(0, 12) }}…</p>
            </template>
            <template v-if="selectedNode.data.kind === 'FILTER'">
              <div class="node-contract filter-contract"><strong>样本筛选预览</strong><small>只在上游数据源的有界输入样本上筛选；结果用于检查条件，不代表全量数据物化。</small></div>
              <p v-if="!selectedNode.data.config?.columnName && !filterLoading && !filterError" class="source-empty">先将一个已配置的数据源连接到此节点</p>
              <label class="field-label">字段
                <el-select :model-value="selectedNode.data.config?.columnName || ''" filterable clearable placeholder="选择上游字段" :loading="filterLoading" :disabled="!filterColumns.length" @change="changeFilterColumn">
                  <el-option v-for="column in filterColumns" :key="column.columnId || column.name" :value="column.name" :label="`${column.name} · ${column.logicalType || '未知类型'}`" />
                </el-select>
              </label>
              <label class="field-label">条件
                <el-select :model-value="selectedNode.data.config?.operator || ''" placeholder="选择筛选条件" @change="changeFilterOperator">
                  <el-option label="等于" value="EQUALS" />
                  <el-option label="不等于" value="NOT_EQUALS" />
                  <el-option label="包含文本" value="CONTAINS" />
                  <el-option label="为空" value="IS_EMPTY" />
                  <el-option label="不为空" value="IS_NOT_EMPTY" />
                </el-select>
              </label>
              <label v-if="selectedNode.data.config?.operator && !selectedNode.data.config.operator.startsWith('IS_')" class="field-label">匹配值
                <el-input :model-value="selectedNode.data.config?.value || ''" maxlength="512" placeholder="输入要匹配的值" @update:model-value="(value) => { selectedNode.data.config.value = value }" />
              </label>
              <p v-if="filterLoading" class="source-empty">正在读取上游字段</p>
              <p v-if="filterError" class="source-error" role="alert">{{ filterError }}</p>
            </template>
            <div class="node-contract"><strong>节点类型</strong><span>{{ selectedNode.data.kind }}</span><small>{{ nodeType(selectedNode.data.kind).description }}</small></div>
            <el-button class="remove-node" plain type="danger" :icon="Delete" @click="removeSelectedNode">删除节点</el-button>
          </template>
          <template v-else-if="selectedEdge">
            <div class="rail-heading"><strong>连接属性</strong><span>数据流</span></div>
            <p class="connection-copy">{{ nodes.find((node) => node.id === selectedEdge.source)?.data.label }}<el-icon><Connection /></el-icon>{{ nodes.find((node) => node.id === selectedEdge.target)?.data.label }}</p>
            <el-button class="remove-node" plain type="danger" :icon="Delete" @click="removeSelectedEdge">删除连接</el-button>
          </template>
          <div v-else class="inspector-empty"><el-icon><Operation /></el-icon><span>选择节点或连接查看属性</span></div>
        </aside>
      </div>

      <section v-if="activeId" class="run-monitor" aria-label="工作流运行记录">
        <header class="run-monitor-heading">
          <div><strong>运行记录</strong><span>{{ runs.length }} 次</span></div>
          <div class="run-monitor-actions">
            <el-button circle aria-label="刷新运行记录" title="刷新运行记录" :icon="Refresh" :loading="loadingRuns" @click="refreshRuns" />
            <el-button v-if="selectedRun && runIsActive(selectedRun)" plain type="danger" :icon="CircleClose" :loading="runBusy" @click="cancelRun">取消运行</el-button>
            <el-button v-if="selectedRun && ['FAILED', 'CANCELED'].includes(selectedRun.status)" plain :icon="Refresh" :loading="runBusy" @click="retryRun">重试</el-button>
          </div>
        </header>
        <div v-if="runs.length" class="run-monitor-body">
          <nav class="run-list" aria-label="运行列表">
            <button v-for="run in runs" :key="run.id" type="button" class="run-item" :class="{ 'is-active': selectedRunId === run.id }" :title="`${run.id} · ${runStatusLabel(run.status)}`" @click="selectedRunId = run.id">
              <span class="run-status-icon" :class="`status-${run.status.toLowerCase()}`"><el-icon><Loading v-if="runIsActive(run)" /><CircleCheck v-else-if="run.status === 'SUCCEEDED'" /><CircleClose v-else /></el-icon></span>
              <span class="run-item-copy"><strong>{{ runStatusLabel(run.status) }} · 修订 {{ run.workflowRevision }}</strong><small>{{ formatRunTime(run.createdAt) }}</small></span>
              <span class="run-progress">{{ run.progress }}%</span>
            </button>
          </nav>
          <div v-if="selectedRun" class="run-detail">
            <div class="run-detail-summary"><strong>{{ runStatusLabel(selectedRun.status) }}</strong><span>创建于 {{ formatRunTime(selectedRun.createdAt) }}</span><span>开始于 {{ formatRunTime(selectedRun.startedAt) }}</span><span>修订 {{ selectedRun.workflowRevision }}</span></div>
            <el-progress :percentage="selectedRun.progress" :status="selectedRun.status === 'FAILED' ? 'exception' : selectedRun.status === 'SUCCEEDED' ? 'success' : undefined" :stroke-width="6" :show-text="false" />
            <p v-if="selectedRun.errorMessage" class="run-error" role="alert">{{ selectedRun.errorMessage }}</p>
            <div v-if="selectedRun.nodes?.length" class="run-node-results">
              <article v-for="node in selectedRun.nodes" :key="node.nodeId" class="run-node-result">
                <header><strong :title="node.nodeId">{{ nodes.find((item) => item.id === node.nodeId)?.data.label || node.nodeId }}</strong><span>{{ nodeStatusLabel(node.status) }}</span></header>
                <p v-if="node.errorMessage" class="run-error">{{ node.errorMessage }}</p>
                <template v-if="node.result">
                  <template v-if="node.result.nodeType === 'FILTER'">
                    <div class="run-result-stats"><span>来源 {{ node.result.tableName }}</span><span>条件 {{ node.result.columnName }} · {{ ({ EQUALS: '等于', NOT_EQUALS: '不等于', CONTAINS: '包含文本', IS_EMPTY: '为空', IS_NOT_EMPTY: '不为空' })[node.result.operator] || node.result.operator }}</span><span>样本匹配 {{ node.result.sampleMatchedRows }} / {{ node.result.sampleInputRows }} 行</span><span>全表 {{ Number(node.result.inputRowCount || 0).toLocaleString('zh-CN') }} 行</span></div>
                    <p class="run-preview-note">此结果仅为输入样本预览{{ node.result.sampleTruncated ? '，样本已截断' : '' }}</p>
                  </template>
                  <div v-else class="run-result-stats"><span>{{ node.result.tableName }}</span><span>{{ Number(node.result.rowCount || 0).toLocaleString('zh-CN') }} 行</span><span>{{ node.result.columnCount }} 个字段</span><span>Schema {{ String(node.result.schemaHash || '').slice(0, 12) }}</span></div>
                  <div class="run-columns"><span v-for="column in node.result.columns || []" :key="column.columnId" :title="`${column.name} · ${column.type}`">{{ column.name }}<small>{{ column.type }}</small></span></div>
                  <div v-if="node.result.sampleColumns?.length" class="run-sample-wrap">
                    <div class="run-sample-heading"><strong>输入样本</strong><span>前 {{ node.result.sampleLimit }} 行 · 共 {{ Number(node.result.sampleTotalRows || 0).toLocaleString('zh-CN') }} 行</span><span v-if="node.result.sampleTruncated" class="run-sample-truncated">样本已截断</span></div>
                    <div class="run-sample-scroll" tabindex="0" :aria-label="`${node.result.tableName} 输入样本`">
                      <table class="run-sample-table">
                        <thead><tr><th v-for="(column, index) in node.result.sampleColumns" :key="`${index}-${column}`" :title="column">{{ column }}</th></tr></thead>
                        <tbody>
                          <tr v-for="(row, rowIndex) in node.result.sampleRows || []" :key="rowIndex">
                            <td v-for="(column, columnIndex) in node.result.sampleColumns" :key="`${rowIndex}-${columnIndex}`" :title="row[columnIndex] ?? ''">{{ row[columnIndex] ?? '空' }}</td>
                          </tr>
                        </tbody>
                      </table>
                    </div>
                  </div>
                </template>
              </article>
            </div>
            <p v-else class="run-empty">{{ selectedRun.status === 'QUEUED' ? '等待 Temporal Worker 接收运行' : '暂无节点结果' }}</p>
          </div>
        </div>
        <div v-else class="run-empty">此工作流还没有运行记录</div>
      </section>
    </section>
  </main>
</template>

<style>
@import '@vue-flow/core/dist/style.css';
@import '@vue-flow/core/dist/theme-default.css';
@import '@vue-flow/minimap/dist/style.css';
@import '@vue-flow/controls/dist/style.css';

.workflow-page { min-width: 0; display: grid; gap: 14px; color: #263a31; }
.workflow-page, .workflow-page * { box-sizing: border-box; }
.workflow-page .page-heading { display: flex; min-width: 0; align-items: center; justify-content: space-between; gap: 14px; }
.workflow-page .page-heading h1 { margin: 0; font-size: 20px; line-height: 1.3; }
.workflow-page .page-heading p { margin: 5px 0 0; color: #718078; font-size: 12px; }
.workflow-workspace { display: flex; min-width: 0; min-height: 620px; flex-direction: column; overflow: hidden; background: #fff; border: 1px solid #dfe8e3; border-radius: 8px; }
.workflow-toolbar { display: grid; gap: 0; padding: 10px 14px 0; border-bottom: 1px solid #e4ebe7; }
.workflow-title-row { display: flex; min-width: 0; align-items: center; justify-content: space-between; gap: 12px; padding-bottom: 9px; }
.workflow-name-wrap { display: flex; min-width: 0; flex: 1; align-items: center; gap: 12px; }
.workflow-name-wrap .el-input { width: min(340px, 48vw); }
.workflow-name-wrap strong { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.save-state { color: #748179; font-size: 11px; white-space: nowrap; }
.save-state.is-dirty { color: #aa732f; }
.save-state.is-error { max-width: 42vw; overflow: hidden; color: #aa443c; text-overflow: ellipsis; }
.toolbar-actions { display: flex; flex: 0 0 auto; gap: 5px; }
.workflow-tabs { display: flex; min-width: 0; gap: 2px; overflow-x: auto; scrollbar-width: thin; }
.workflow-tab { display: flex; min-width: 96px; max-width: 220px; height: 34px; flex: 0 0 auto; align-items: center; gap: 7px; padding: 0 10px; overflow: hidden; color: #65756c; background: transparent; border: 0; border-bottom: 2px solid transparent; cursor: pointer; }
.workflow-tab > span:last-child { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.workflow-tab.is-active { color: #246047; border-bottom-color: #3c8a65; font-weight: 650; }
.workflow-tab:disabled { cursor: wait; }
.tab-dot { width: 6px; height: 6px; flex: 0 0 auto; border-radius: 50%; background: #a8b5ae; }
.tab-dot.is-dirty { background: #d3963d; }
.workflow-editor { display: grid; min-width: 0; min-height: 0; flex: 1; grid-template-columns: 220px minmax(0, 1fr) 230px; }
.node-library, .node-inspector { min-width: 0; padding: 12px; background: #fff; }
.node-library { overflow: auto; border-right: 1px solid #e5ece8; }
.node-inspector { border-left: 1px solid #e5ece8; }
.rail-heading { display: flex; min-width: 0; align-items: center; justify-content: space-between; gap: 8px; margin: 2px 0 10px; color: #32463c; font-size: 12px; }
.rail-heading span { color: #8a9690; font-size: 10px; font-weight: 500; }
.node-add { display: flex; width: 100%; min-width: 0; align-items: center; gap: 8px; padding: 7px 6px; color: #34483e; text-align: left; background: #fff; border: 0; border-radius: 5px; cursor: pointer; }
.node-add:hover { background: #f3f7f4; }
.node-add:disabled { cursor: not-allowed; opacity: .55; }
.node-icon, .flow-node-icon { display: grid; width: 30px; height: 30px; flex: 0 0 auto; place-items: center; color: var(--node-accent); background: color-mix(in srgb, var(--node-accent) 11%, white); border: 1px solid color-mix(in srgb, var(--node-accent) 25%, white); border-radius: 6px; }
.node-add-copy { display: grid; min-width: 0; flex: 1; gap: 2px; }
.node-add-copy strong { font-size: 11px; }
.node-add-copy small { overflow: hidden; color: #849188; font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.add-symbol { color: #94a39a; }
.canvas-column { display: flex; min-width: 0; min-height: 0; flex-direction: column; background: #f6f9f7; }
.canvas-meta { display: flex; min-height: 29px; align-items: center; gap: 14px; padding: 0 12px; color: #79867f; font-size: 10px; background: #fff; border-bottom: 1px solid #e5ece8; }
.canvas-stage { position: relative; min-width: 0; min-height: 0; flex: 1; }
.flow-canvas { width: 100%; height: 100%; min-height: 540px; background-color: #f6f9f7; }
.flow-node { position: relative; display: flex; width: 224px; min-height: 82px; align-items: flex-start; gap: 9px; padding: 12px; color: #2e4338; background: #fff; border: 1px solid #d8e3dc; border-left: 3px solid var(--node-accent, #598d72); border-radius: 7px; box-shadow: 0 3px 12px rgba(40, 65, 52, .08); }
.flow-node.is-selected { border-color: #4f9371; box-shadow: 0 0 0 2px rgba(64, 139, 99, .15), 0 5px 14px rgba(40, 65, 52, .1); }
.flow-node-icon { width: 28px; height: 28px; }
.flow-node-copy { display: grid; min-width: 0; gap: 3px; }
.flow-node-copy small { color: var(--node-accent); font-size: 9px; }
.flow-node-copy strong { max-width: 154px; overflow: hidden; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.flow-node-copy em { max-width: 154px; overflow: hidden; color: #7e8b83; font-size: 9px; font-style: normal; text-overflow: ellipsis; white-space: nowrap; }
.node-index { position: absolute; right: 7px; bottom: 5px; color: #a3aea8; font: 8px ui-monospace, monospace; }
.flow-node .vue-flow__handle { width: 9px; height: 9px; background: #fff; border: 2px solid var(--node-accent); }
.canvas-empty { position: absolute; inset: 0; display: grid; place-items: center; color: #829087; font-size: 12px; pointer-events: none; }
.inspector-empty { display: grid; min-height: 180px; align-content: center; justify-items: center; gap: 9px; color: #87938c; font-size: 10px; text-align: center; }
.inspector-empty .el-icon { font-size: 22px; }
.field-label { display: grid; gap: 6px; margin: 14px 0; color: #66756c; font-size: 10px; }
.source-contract { margin-top: 10px; }
.rule-contract { margin-top: 10px; }
.filter-contract { margin-top: 10px; }
.source-schema, .source-empty, .source-error { margin: -5px 0 12px; color: #77867d; font-size: 10px; line-height: 1.5; overflow-wrap: anywhere; }
.source-error { color: #a1463e; }
.node-contract { display: grid; gap: 5px; margin: 14px 0; padding: 9px; background: #f5f8f6; border-radius: 5px; }
.node-contract strong { color: #66756c; font-size: 10px; }
.node-contract span { color: #375f4b; font: 10px ui-monospace, monospace; }
.node-contract small { color: #829087; font-size: 9px; line-height: 1.5; }
.remove-node { width: 100%; margin-top: 8px; }
.connection-copy { display: flex; align-items: center; justify-content: space-between; gap: 8px; color: #596960; font-size: 10px; overflow-wrap: anywhere; }
.workflow-empty { display: grid; min-height: 480px; align-content: center; justify-items: center; gap: 14px; color: #87948d; }
.workflow-empty > .el-icon { font-size: 30px; color: #6c9b7d; }
.workflow-empty h2 { margin: 0; color: #364a3f; font-size: 16px; }
.workflow-alert { display: flex; justify-content: space-between; gap: 10px; padding: 10px 14px; color: #a1463e; font-size: 11px; }
.workflow-workspace.is-fullscreen { width: 100vw; height: 100vh; height: 100dvh; min-height: 0; border: 0; border-radius: 0; }
.workflow-workspace.is-fullscreen .workflow-editor { flex-basis: 0; }
.workflow-workspace.is-fullscreen .canvas-stage, .workflow-workspace.is-fullscreen .canvas-column { min-height: 0; }
.run-monitor { display: grid; min-width: 0; border-top: 1px solid #e2eae5; background: #fff; }
.run-monitor-heading { display: flex; min-width: 0; min-height: 46px; align-items: center; justify-content: space-between; gap: 12px; padding: 6px 14px; }
.run-monitor-heading > div:first-child, .run-monitor-actions { display: flex; min-width: 0; align-items: center; gap: 10px; }
.run-monitor-heading strong { color: #34483e; font-size: 12px; }
.run-monitor-heading span { color: #839088; font-size: 10px; }
.run-monitor-actions { flex: 0 0 auto; }
.run-monitor-body { display: grid; min-width: 0; grid-template-columns: minmax(190px, 260px) minmax(0, 1fr); border-top: 1px solid #edf1ee; }
.run-list { display: grid; align-content: start; max-height: 280px; overflow: auto; border-right: 1px solid #e7ece9; }
.run-item { display: flex; min-width: 0; align-items: center; gap: 9px; padding: 10px 12px; color: #43564b; text-align: left; background: #fff; border: 0; border-bottom: 1px solid #eef2ef; cursor: pointer; }
.run-item:hover, .run-item.is-active { background: #f4f8f5; }
.run-status-icon { display: grid; width: 25px; height: 25px; flex: 0 0 auto; place-items: center; color: #77857d; background: #f0f3f1; border-radius: 50%; }
.run-status-icon.status-running, .run-status-icon.status-queued, .run-status-icon.status-cancelling { color: #347c5a; background: #eaf4ee; }
.run-status-icon.status-succeeded { color: #2b8656; background: #e6f3eb; }
.run-status-icon.status-failed, .run-status-icon.status-canceled { color: #a94b43; background: #fbefed; }
.run-item-copy { display: grid; min-width: 0; flex: 1; gap: 3px; }
.run-item-copy strong, .run-item-copy small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.run-item-copy strong { font-size: 10px; }
.run-item-copy small { color: #8a9690; font-size: 9px; }
.run-progress { flex: 0 0 auto; color: #748179; font-size: 10px; font-variant-numeric: tabular-nums; }
.run-detail { display: grid; min-width: 0; align-content: start; gap: 12px; padding: 14px; }
.run-detail-summary { display: flex; min-width: 0; flex-wrap: wrap; align-items: center; gap: 8px 16px; color: #78857e; font-size: 10px; }
.run-detail-summary strong { color: #34483e; font-size: 12px; }
.run-node-results { display: grid; min-width: 0; gap: 8px; }
.run-node-result { min-width: 0; padding: 10px; background: #f8faf8; border: 1px solid #e6ece8; border-radius: 6px; }
.run-node-result > header { display: flex; min-width: 0; justify-content: space-between; gap: 8px; }
.run-node-result > header strong { min-width: 0; overflow: hidden; color: #3b5045; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.run-node-result > header span { flex: 0 0 auto; color: #738078; font-size: 10px; }
.run-result-stats { display: flex; min-width: 0; flex-wrap: wrap; gap: 6px 14px; margin-top: 8px; color: #718078; font-size: 10px; }
.run-columns { display: flex; min-width: 0; flex-wrap: wrap; gap: 5px; margin-top: 8px; }
.run-columns > span { display: flex; max-width: 100%; min-width: 0; align-items: center; gap: 7px; padding: 4px 7px; overflow: hidden; color: #4b6255; background: #fff; border: 1px solid #e1e9e4; border-radius: 4px; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.run-columns small { flex: 0 0 auto; color: #94a098; font-size: 9px; }
.run-sample-wrap { display: grid; min-width: 0; gap: 7px; margin-top: 12px; }
.run-sample-heading { display: flex; min-width: 0; flex-wrap: wrap; align-items: baseline; gap: 4px 10px; }
.run-sample-heading strong { color: #53645a; font-size: 10px; }
.run-sample-heading span { color: #88948d; font-size: 9px; }
.run-sample-heading .run-sample-truncated { color: #9b6f3a; }
.run-preview-note { margin: 0; color: #8a744f; font-size: 9px; }
.run-sample-scroll { min-width: 0; max-width: 100%; max-height: 220px; overflow: auto; border: 1px solid #e3eae5; border-radius: 4px; }
.run-sample-table { width: max-content; min-width: 100%; border-collapse: collapse; color: #53645a; font-size: 10px; }
.run-sample-table th, .run-sample-table td { max-width: 240px; padding: 6px 9px; overflow: hidden; border-bottom: 1px solid #edf1ee; text-align: left; text-overflow: ellipsis; white-space: nowrap; }
.run-sample-table th { position: sticky; top: 0; z-index: 1; color: #4d6255; background: #f4f8f5; font-weight: 600; }
.run-sample-table td { max-width: 280px; }
.run-sample-table tr:last-child td { border-bottom: 0; }
.run-error { margin: 8px 0 0; color: #a3473e; font-size: 11px; line-height: 1.5; overflow-wrap: anywhere; }
.run-empty { margin: 0; padding: 18px; color: #8b9790; text-align: center; font-size: 11px; }
@media (max-width: 1000px) {
  .workflow-editor { grid-template-columns: 190px minmax(0, 1fr); }
  .node-inspector { grid-column: 1 / -1; border-top: 1px solid #e5ece8; border-left: 0; }
  .node-library { grid-row: span 2; }
  .flow-canvas { min-height: 480px; }
}
@media (max-width: 640px) {
  .workflow-page .page-heading { align-items: flex-start; }
  .workflow-page .page-heading p { max-width: 240px; line-height: 1.5; }
  .workflow-page .page-heading > .el-button { flex: 0 0 auto; }
  .workflow-workspace { min-height: 720px; }
  .workflow-title-row { align-items: flex-start; flex-direction: column; }
  .workflow-name-wrap { width: 100%; flex-wrap: wrap; gap: 4px 10px; }
  .workflow-name-wrap .el-input { width: min(100%, 320px); }
  .toolbar-actions { width: 100%; justify-content: flex-end; }
  .workflow-editor { display: flex; flex-direction: column; }
  .node-library { max-height: 148px; flex: 0 0 auto; border-right: 0; border-bottom: 1px solid #e5ece8; }
  .node-library .rail-heading { margin-bottom: 4px; }
  .node-add { width: 142px; flex: 0 0 auto; }
  .node-library { display: flex; flex-wrap: wrap; gap: 2px; overflow: auto; }
  .node-library .rail-heading { width: 100%; }
  .node-add-copy small { display: none; }
  .canvas-column { min-height: 380px; flex: 1; }
  .flow-canvas { min-height: 380px; }
  .node-inspector { min-height: 180px; flex: 0 0 auto; }
  .run-monitor-body { grid-template-columns: minmax(0, 1fr); }
  .run-list { max-height: 180px; border-right: 0; border-bottom: 1px solid #e7ece9; }
}
</style>
