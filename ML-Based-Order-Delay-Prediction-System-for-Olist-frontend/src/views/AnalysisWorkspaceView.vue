<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, Check, Close, CopyDocument, DataAnalysis, Delete, FolderOpened, MagicStick, Plus, Refresh, Share } from '@element-plus/icons-vue'
import { ElMessageBox } from 'element-plus'
import * as echarts from 'echarts/core'
import { BarChart, ScatterChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import {
  analyzeDatasetColumn,
  analyzeDatasetRelation,
  copyAnalysisWorkspace,
  createAnalysisWorkspace,
  deleteAnalysisWorkspace,
  getAnalysisWorkspace,
  getDatasetCatalog,
  listAnalysisWorkspaces,
  listDatasets,
  updateAnalysisWorkspace,
} from '@/api/datasets'
import { useAuth } from '@/auth/keycloak'
import WorkflowSourceDialog from '@/components/WorkflowSourceDialog.vue'

echarts.use([BarChart, ScatterChart, GridComponent, TooltipComponent, CanvasRenderer])

const router = useRouter()
const auth = useAuth()
const datasets = ref([])
const datasetNextPage = ref(0)
const datasetHasMore = ref(false)
const loadingMoreDatasets = ref(false)
const catalog = ref(null)
const analysis = ref(null)
const selectedDatasetId = ref('')
const selectedTableId = ref('')
const selectedColumnId = ref('')
const analysisSelection = ref([])
const relationColumnId = ref('')
const relation = ref(null)
const boardCards = ref([])
const addingToBoard = ref(false)
const analysisBatchRequest = ref(0)
const workspaces = ref([])
const activeWorkspaceId = ref('')
const selectedWorkspaceId = ref('')
const workspaceName = ref('新建分析方案')
const workspaceRevision = ref(null)
const workspaceState = ref('new')
const workspaceError = ref('')
const loadingWorkspaces = ref(false)
const copyingWorkspace = ref(false)
const loadingDatasets = ref(false)
const loadingCatalog = ref(false)
const loadingAnalysis = ref(false)
const loadingRelation = ref(false)
const datasetError = ref('')
const catalogError = ref('')
const analysisError = ref('')
const relationError = ref('')
const chartElement = ref(null)
const relationChartElement = ref(null)
const workflowDialogVisible = ref(false)
const workflowReferences = ref([])

let chart = null
let resizeObserver = null
let relationChart = null
let relationResizeObserver = null
let catalogRequest = 0
let analysisRequest = 0
let relationRequest = 0
let workspaceRequest = 0

const selectedTable = computed(() =>
  catalog.value?.tables?.find((table) => table.tableId === selectedTableId.value) || null,
)
const selectedColumn = computed(() =>
  selectedTable.value?.columns?.find((column) => column.columnId === selectedColumnId.value) || null,
)
const selectedTableColumns = computed(() => selectedTable.value?.columns || [])
const relationColumns = computed(() => selectedTableColumns.value.filter((column) => column.columnId !== selectedColumnId.value))
const suggestedRelationColumn = computed(() => {
  const numericPattern = /INT|FLOAT|DECIMAL|NUMBER|DOUBLE|REAL/
  const selectedIsNumeric = numericPattern.test((selectedColumn.value?.logicalType || '').toUpperCase())
  return relationColumns.value.find((column) => numericPattern.test((column.logicalType || '').toUpperCase()))
    || (selectedIsNumeric ? relationColumns.value[0] : null)
    || null
})
const selectedDataset = computed(() =>
  datasets.value.find((dataset) => dataset.id === selectedDatasetId.value) || null,
)
const analysisSuggestion = computed(() => {
  const logicalType = (selectedColumn.value?.logicalType || '').toUpperCase()
  if (/INT|FLOAT|DECIMAL|NUMBER|DOUBLE|REAL/.test(logicalType)) {
    return { label: '数值分布', detail: '查看范围、缺失和区间频数' }
  }
  return { label: '类别分布', detail: '查看高频类别和唯一值估算' }
})
const isHistogram = computed(() => analysis.value?.chartType === 'histogram')
const relationColumn = computed(() =>
  selectedTable.value?.columns?.find((column) => column.columnId === relationColumnId.value) || null,
)
const workspaceStateLabel = computed(() => ({
  new: '新建方案',
  dirty: '有未保存更改',
  saving: '保存中',
  loading: '载入中',
  saved: '已保存',
  warning: '部分卡片不可用',
  error: '保存失败',
}[workspaceState.value] || ''))

function formatNumber(value) {
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(Number(value || 0))
}

function datasetLabel(dataset) {
  return dataset?.name || dataset?.originalFilename || '未命名数据集'
}

function selectAssetColumn(columnId) {
  selectedColumnId.value = columnId
  const firstRelation = relationColumns.value[0]
  if (relationColumnId.value === columnId || !relationColumns.value.some((column) => column.columnId === relationColumnId.value)) {
    relationColumnId.value = firstRelation?.columnId || ''
  }
}

function selectionKey(reference) {
  return cardReferenceId(reference)
}

function isAnalysisSelected(columnId) {
  return analysisSelection.value.some((item) => item.columnId === columnId
    && item.datasetVersionId === selectedDatasetId.value && item.tableId === selectedTableId.value)
}

function removeAnalysisSelection(reference) {
  const key = selectionKey(reference)
  analysisSelection.value = analysisSelection.value.filter((item) => selectionKey(item) !== key)
}

function toggleAnalysisColumn(column, checked) {
  const reference = {
    datasetVersionId: selectedDatasetId.value,
    tableId: selectedTableId.value,
    columnId: column.columnId,
    datasetName: datasetLabel(selectedDataset.value),
    tableName: selectedTable.value?.tableName || '',
    columnName: column.name,
  }
  const key = selectionKey(reference)
  const selected = new Map(analysisSelection.value.map((item) => [selectionKey(item), item]))
  if (checked) {
    if (selected.size >= 8) {
      workspaceError.value = '一次最多选择 8 个字段加入分析画布'
      return
    }
    selected.set(key, reference)
  } else {
    selected.delete(key)
  }
  analysisSelection.value = [...selected.values()]
  workspaceError.value = ''
}

async function addSelectedAnalyses() {
  if (addingToBoard.value || !analysisSelection.value.length) return
  const references = [...analysisSelection.value]
  const requestId = ++analysisBatchRequest.value
  const existing = new Set(boardCards.value.map((card) => card.id))
  const pending = references.filter((item) => !existing.has(selectionKey(item)))
  const availableSlots = 20 - boardCards.value.length
  if (!pending.length) {
    workspaceError.value = '所选字段均已在分析画布中'
    return
  }
  if (pending.length > availableSlots) {
    workspaceError.value = `当前画布仅剩 ${availableSlots} 个位置，请减少选择后重试`
    return
  }

  addingToBoard.value = true
  workspaceError.value = ''
  try {
    const results = await mapWithConcurrency(pending, 3, async (reference) => {
      try {
        const result = await analyzeDatasetColumn(
          reference.datasetVersionId, reference.tableId, reference.columnId,
        )
        return {
          key: selectionKey(reference),
          card: {
            ...reference,
            id: selectionKey(reference),
            columnId: reference.columnId,
            tableId: reference.tableId,
            logicalType: result.logicalType,
            chartType: result.chartType,
            points: result.points || [],
            rowCount: result.rowCount,
            uniqueCountEstimate: result.uniqueCountEstimate,
            missingCount: result.missingCount,
            minimum: result.minimum,
            maximum: result.maximum,
          },
        }
      } catch (error) {
        return { key: selectionKey(reference), error }
      }
    })
    if (requestId !== analysisBatchRequest.value) return
    const cards = results.flatMap((result) => result.card ? [result.card] : [])
    const pendingKeys = new Set(pending.map(selectionKey))
    const failedKeys = new Set(results.filter((result) => result.error).map((result) => result.key))
    const selectedKeys = new Set(analysisSelection.value.map(selectionKey))
    const currentBoardKeys = new Set(boardCards.value.map((card) => card.id))
    const eligibleCards = cards.filter((card) => selectedKeys.has(card.id) && !currentBoardKeys.has(card.id))
    const slotsRemaining = Math.max(0, 20 - boardCards.value.length)
    const cardsToAdd = eligibleCards.slice(0, slotsRemaining)
    const addedKeys = new Set(cardsToAdd.map((card) => card.id))
    boardCards.value = [...boardCards.value, ...cardsToAdd]
    const overflowKeys = new Set(eligibleCards.slice(slotsRemaining).map((card) => card.id))
    const finalBoardKeys = new Set(boardCards.value.map((card) => card.id))
    analysisSelection.value = analysisSelection.value.filter((item) => {
      const key = selectionKey(item)
      if (finalBoardKeys.has(key)) return false
      return !pendingKeys.has(key) || failedKeys.has(key) || overflowKeys.has(key)
    })
    const stillFailedCount = [...failedKeys].filter((key) => selectedKeys.has(key)).length
    const overflowCount = overflowKeys.size
    if (cardsToAdd.length) markWorkspaceDirty()
    if (stillFailedCount || overflowCount) {
      workspaceError.value = `${stillFailedCount ? `${stillFailedCount} 个字段分析失败` : ''}${stillFailedCount && overflowCount ? '；' : ''}${overflowCount ? `${overflowCount} 个结果因画布已满未加入` : ''}。已加入 ${cardsToAdd.length} 张卡片。未完成字段仍保留选择。`
    }
  } catch (error) {
    if (requestId === analysisBatchRequest.value) workspaceError.value = error.message || '批量分析失败，请重试'
  } finally {
    if (requestId === analysisBatchRequest.value) addingToBoard.value = false
  }
}

function useSuggestedRelation() {
  if (!suggestedRelationColumn.value) return
  relationColumnId.value = suggestedRelationColumn.value.columnId
  nextTick(loadRelation)
}

function markWorkspaceDirty() {
  workspaceState.value = 'dirty'
  workspaceError.value = ''
}

async function addCurrentAnalysis() {
  if (!analysis.value || !selectedDataset.value || !selectedTable.value || !selectedColumn.value) return
  const reference = {
    datasetVersionId: analysis.value.datasetVersionId,
    tableId: selectedTableId.value,
    columnId: selectedColumnId.value,
  }
  const exists = boardCards.value.some((card) => card.id === cardReferenceId(reference))
  if (exists) return

  addingToBoard.value = true
  try {
    boardCards.value = [...boardCards.value, {
      id: cardReferenceId(reference),
      ...reference,
      datasetName: datasetLabel(selectedDataset.value),
      tableName: selectedTable.value.tableName,
      columnName: selectedColumn.value.name,
      logicalType: analysis.value.logicalType,
      chartType: analysis.value.chartType,
      points: analysis.value.points || [],
      rowCount: analysis.value.rowCount,
      uniqueCountEstimate: analysis.value.uniqueCountEstimate,
      missingCount: analysis.value.missingCount,
      minimum: analysis.value.minimum,
      maximum: analysis.value.maximum,
    }]
    markWorkspaceDirty()
  } finally {
    addingToBoard.value = false
  }
}

function addCurrentRelation() {
  if (!relation.value || !selectedDataset.value || !selectedTable.value) return
  const reference = {
    datasetVersionId: relation.value.datasetVersionId,
    tableId: relation.value.tableId,
    columnId: relation.value.xColumnId,
    relationColumnId: relation.value.yColumnId,
  }
  if (boardCards.value.some((card) => card.id === cardReferenceId(reference))) return
  if (boardCards.value.length >= 20) {
    workspaceError.value = '一个分析方案最多保存 20 张卡片'
    return
  }
  boardCards.value = [...boardCards.value, {
    id: cardReferenceId(reference),
    ...reference,
    datasetName: datasetLabel(selectedDataset.value),
    tableName: selectedTable.value.tableName,
    columnName: `${relation.value.xColumnName} · ${relation.value.yColumnName}`,
    relationColumnName: relation.value.yColumnName,
    logicalType: relation.value.chartType,
    chartType: relation.value.chartType,
    points: relation.value.points || [],
  }]
  markWorkspaceDirty()
}

function removeBoardCard(cardId) {
  boardCards.value = boardCards.value.filter((card) => card.id !== cardId)
  markWorkspaceDirty()
}

function clearAnalysisBoard() {
  analysisBatchRequest.value += 1
  addingToBoard.value = false
  boardCards.value = []
  markWorkspaceDirty()
}

function savedCardReference(card) {
  const reference = {
    datasetVersionId: card.datasetVersionId,
    tableId: card.tableId,
    columnId: card.columnId,
  }
  if (card.relationColumnId) reference.relationColumnId = card.relationColumnId
  return reference
}

function cardReferenceId(reference) {
  const base = `${reference.datasetVersionId}:${reference.tableId}:${reference.columnId}`
  return reference.relationColumnId ? `${base}:${reference.relationColumnId}` : base
}

async function loadAnalysisCard(reference) {
  try {
    const [dataCatalog, result] = await Promise.all([
      getDatasetCatalog(reference.datasetVersionId),
      reference.relationColumnId
        ? analyzeDatasetRelation(reference.datasetVersionId, reference.tableId, reference.columnId, reference.relationColumnId)
        : analyzeDatasetColumn(reference.datasetVersionId, reference.tableId, reference.columnId),
    ])
    const table = dataCatalog.tables?.find((item) => item.tableId === reference.tableId)
    const column = table?.columns?.find((item) => item.columnId === reference.columnId)
    const relationColumn = reference.relationColumnId
      ? table?.columns?.find((item) => item.columnId === reference.relationColumnId)
      : null
    if (!table || !column || (reference.relationColumnId && !relationColumn)) {
      throw new Error('数据版本中的表或字段已不存在')
    }
    const dataset = datasets.value.find((item) => item.id === reference.datasetVersionId)
    return {
      id: cardReferenceId(reference),
      datasetVersionId: reference.datasetVersionId,
      tableId: reference.tableId,
      columnId: reference.columnId,
      relationColumnId: reference.relationColumnId || '',
      datasetName: dataset ? datasetLabel(dataset) : `数据版本 ${reference.datasetVersionId}`,
      tableName: table.tableName,
      columnName: relationColumn ? `${column.name} · ${relationColumn.name}` : column.name,
      relationColumnName: relationColumn?.name || '',
      logicalType: reference.relationColumnId ? result.chartType : result.logicalType,
      chartType: result.chartType,
      points: result.points || [],
      rowCount: result.rowCount,
      uniqueCountEstimate: result.uniqueCountEstimate,
      missingCount: result.missingCount,
      minimum: result.minimum,
      maximum: result.maximum,
    }
  } catch (error) {
    return {
      id: cardReferenceId(reference),
      ...reference,
      relationColumnId: reference.relationColumnId || '',
      datasetName: `数据版本 ${reference.datasetVersionId}`,
      tableName: '无法读取数据表',
      columnName: '分析卡片不可用',
      points: [],
      error: error.message || '数据版本或字段当前不可用',
    }
  }
}

async function mapWithConcurrency(items, limit, mapper) {
  const results = Array(items.length)
  let nextIndex = 0
  const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (nextIndex < items.length) {
      const index = nextIndex++
      results[index] = await mapper(items[index], index)
    }
  })
  await Promise.all(workers)
  return results
}

async function refreshWorkspaces() {
  loadingWorkspaces.value = true
  try {
    workspaces.value = await listAnalysisWorkspaces()
    if (!activeWorkspaceId.value && !selectedWorkspaceId.value && workspaces.value.length) {
      selectedWorkspaceId.value = workspaces.value[0].id
      await openSelectedWorkspace()
    }
  } catch (error) {
    workspaceError.value = error.message || '分析方案列表加载失败'
  } finally {
    loadingWorkspaces.value = false
  }
}

async function confirmDiscardChanges() {
  if (workspaceState.value !== 'dirty') return true
  try {
    await ElMessageBox.confirm(
      '当前画布有尚未保存的更改，继续操作会放弃这些更改。',
      '放弃未保存更改？',
      { confirmButtonText: '放弃更改', cancelButtonText: '继续编辑', type: 'warning' },
    )
    return true
  } catch {
    return false
  }
}

async function openSelectedWorkspace() {
  const id = selectedWorkspaceId.value
  if (!id || id === activeWorkspaceId.value || !(await confirmDiscardChanges())) return
  analysisBatchRequest.value += 1
  addingToBoard.value = false
  const requestId = ++workspaceRequest
  workspaceState.value = 'loading'
  workspaceError.value = ''
  try {
    const workspace = await getAnalysisWorkspace(id)
    const cards = await mapWithConcurrency(workspace.cards || [], 3, loadAnalysisCard)
    if (requestId !== workspaceRequest) return
    boardCards.value = cards
    activeWorkspaceId.value = workspace.id
    selectedWorkspaceId.value = workspace.id
    workspaceName.value = workspace.name
    workspaceRevision.value = workspace.revision
    const unavailableCount = cards.filter((card) => card.error).length
    workspaceState.value = unavailableCount ? 'warning' : 'saved'
    workspaceError.value = unavailableCount ? `${unavailableCount} 张卡片无法重新读取，已保留其引用。` : ''
  } catch (error) {
    if (requestId !== workspaceRequest) return
    workspaceState.value = 'error'
    workspaceError.value = error.message || '分析方案载入失败'
  }
}

async function startNewWorkspace() {
  if (!(await confirmDiscardChanges())) return
  analysisBatchRequest.value += 1
  addingToBoard.value = false
  workspaceRequest += 1
  activeWorkspaceId.value = ''
  selectedWorkspaceId.value = ''
  workspaceName.value = '新建分析方案'
  workspaceRevision.value = null
  boardCards.value = []
  workspaceState.value = 'new'
  workspaceError.value = ''
}

async function saveWorkspace() {
  const name = workspaceName.value.trim()
  if (!name) {
    workspaceError.value = '请先填写分析方案名称'
    workspaceState.value = 'error'
    return
  }
  if (boardCards.value.length > 20) {
    workspaceError.value = '一个分析方案最多保存 20 张卡片'
    workspaceState.value = 'error'
    return
  }

  workspaceState.value = 'saving'
  workspaceError.value = ''
  const payload = {
    name,
    expectedRevision: workspaceRevision.value,
    cards: boardCards.value.map(savedCardReference),
  }
  try {
    const workspace = activeWorkspaceId.value
      ? await updateAnalysisWorkspace(activeWorkspaceId.value, payload)
      : await createAnalysisWorkspace(payload)
    activeWorkspaceId.value = workspace.id
    selectedWorkspaceId.value = workspace.id
    workspaceName.value = workspace.name
    workspaceRevision.value = workspace.revision
    workspaceState.value = 'saved'
    await refreshWorkspaces()
  } catch (error) {
    workspaceState.value = 'error'
    workspaceError.value = error.message || '分析方案保存失败'
  }
}

function nextWorkspaceCopyName() {
  const baseName = `${workspaceName.value.trim() || '分析方案'} 副本`
  const existingNames = new Set(workspaces.value.map((workspace) => workspace.name.toLocaleLowerCase('zh-CN')))
  let candidate = baseName
  let suffix = 2
  while (existingNames.has(candidate.toLocaleLowerCase('zh-CN'))) {
    candidate = `${baseName} ${suffix}`
    suffix += 1
  }
  return candidate.slice(0, 255)
}

async function copyWorkspace() {
  if (!activeWorkspaceId.value || !['saved', 'warning'].includes(workspaceState.value)) return
  workspaceError.value = ''
  copyingWorkspace.value = true
  try {
    const { value } = await ElMessageBox.prompt('为副本命名，原方案不会被修改。', '复制分析方案', {
      confirmButtonText: '创建副本',
      cancelButtonText: '取消',
      inputValue: nextWorkspaceCopyName(),
      inputMaxlength: 255,
      inputValidator: (value) => Boolean(value?.trim()) || '方案名称不能为空',
    })
    const copy = await copyAnalysisWorkspace(activeWorkspaceId.value, { name: value.trim() })
    workspaces.value = [copy, ...workspaces.value.filter((workspace) => workspace.id !== copy.id)]
    selectedWorkspaceId.value = copy.id
    await openSelectedWorkspace()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    workspaceError.value = error.message || '分析方案复制失败'
  } finally {
    copyingWorkspace.value = false
  }
}

async function removeWorkspace() {
  if (!activeWorkspaceId.value || !(await confirmDiscardChanges())) return
  try {
    await ElMessageBox.confirm(
      `删除“${workspaceName.value}”？此操作不会删除数据集。`,
      '删除分析方案',
      { confirmButtonText: '删除方案', cancelButtonText: '取消', type: 'warning' },
    )
    await deleteAnalysisWorkspace(activeWorkspaceId.value)
    workspaceState.value = 'saved'
    await startNewWorkspace()
    await refreshWorkspaces()
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    workspaceError.value = error.message || '分析方案删除失败'
  }
}

function pointWidth(points, value) {
  const max = Math.max(1, ...points.map((point) => Number(point.value) || 0))
  return `${Math.max(2, ((Number(value) || 0) / max) * 100)}%`
}

async function loadDatasets() {
  loadingDatasets.value = true
  datasetError.value = ''
  try {
    const result = await listDatasets({ status: 'READY', page: 0, size: 100 })
    datasets.value = result.items || []
    datasetNextPage.value = 1
    datasetHasMore.value = datasetNextPage.value < (result.totalPages || 0)
    if (!datasets.value.some((dataset) => dataset.id === selectedDatasetId.value)) {
      selectedDatasetId.value = datasets.value[0]?.id || ''
    }
  } catch (error) {
    datasets.value = []
    datasetError.value = error.message || '数据集加载失败'
  } finally {
    loadingDatasets.value = false
  }
}

async function loadMoreDatasets() {
  if (loadingMoreDatasets.value || !datasetHasMore.value) return
  loadingMoreDatasets.value = true
  datasetError.value = ''
  try {
    const result = await listDatasets({ status: 'READY', page: datasetNextPage.value, size: 100 })
    const existingIds = new Set(datasets.value.map((dataset) => dataset.id))
    datasets.value = [...datasets.value, ...(result.items || []).filter((dataset) => !existingIds.has(dataset.id))]
    datasetNextPage.value += 1
    datasetHasMore.value = datasetNextPage.value < (result.totalPages || 0)
  } catch (error) {
    datasetError.value = error.message || '更多数据集加载失败'
  } finally {
    loadingMoreDatasets.value = false
  }
}

async function refreshAssetBrowser() {
  await loadDatasets()
  if (selectedDatasetId.value) await loadCatalog(selectedDatasetId.value)
}

async function openWorkflowDialog(references) {
  if (!references.length || !(await confirmDiscardChanges())) return
  workflowReferences.value = references
  workflowDialogVisible.value = true
}

function selectedTableReference() {
  if (!selectedTable.value || !catalog.value?.datasetVersionId) return []
  return [{
    datasetVersionId: catalog.value.datasetVersionId,
    tableId: selectedTable.value.tableId,
    datasetName: datasetLabel(selectedDataset.value),
    tableName: selectedTable.value.tableName,
  }]
}

function openCreatedWorkflow(workflowId) {
  router.push({ path: '/workflows', query: { workflow: workflowId } })
}

async function loadCatalog(datasetId) {
  const requestId = ++catalogRequest
  catalog.value = null
  selectedTableId.value = ''
  selectedColumnId.value = ''
  relationColumnId.value = ''
  relation.value = null
  relationError.value = ''
  analysis.value = null
  analysisError.value = ''
  analysisRequest += 1
  relationRequest += 1
  loadingRelation.value = false
  loadingAnalysis.value = false
  catalogError.value = ''
  if (!datasetId) {
    loadingCatalog.value = false
    return
  }

  loadingCatalog.value = true
  try {
    const result = await getDatasetCatalog(datasetId)
    if (requestId !== catalogRequest) return
    catalog.value = result
    selectedTableId.value = result.tables?.[0]?.tableId || ''
  } catch (error) {
    if (requestId === catalogRequest) {
      catalogError.value = error.message || '数据目录加载失败'
    }
  } finally {
    if (requestId === catalogRequest) loadingCatalog.value = false
  }
}

async function loadAnalysis(datasetId, tableId, columnId) {
  const requestId = ++analysisRequest
  analysis.value = null
  analysisError.value = ''
  if (!datasetId || !tableId || !columnId) return

  loadingAnalysis.value = true
  try {
    const result = await analyzeDatasetColumn(datasetId, tableId, columnId)
    if (requestId === analysisRequest) analysis.value = result
  } catch (error) {
    if (requestId === analysisRequest) {
      analysisError.value = error.message || '字段分析失败'
    }
  } finally {
    if (requestId === analysisRequest) loadingAnalysis.value = false
  }
}

async function loadRelation() {
  const requestId = ++relationRequest
  relation.value = null
  relationError.value = ''
  if (!selectedDatasetId.value || !selectedTableId.value || !selectedColumnId.value || !relationColumnId.value) return
  loadingRelation.value = true
  try {
    const result = await analyzeDatasetRelation(
      selectedDatasetId.value, selectedTableId.value, selectedColumnId.value, relationColumnId.value,
    )
    if (requestId === relationRequest) relation.value = result
  } catch (error) {
    if (requestId === relationRequest) relationError.value = error.message || '字段关系分析失败'
  } finally {
    if (requestId === relationRequest) loadingRelation.value = false
  }
}

function renderChart() {
  if (!chartElement.value || !analysis.value) return
  if (!chart) chart = echarts.init(chartElement.value)

  const points = analysis.value.points || []
  chart.setOption({
    animationDuration: 280,
    color: ['#176b57', '#2c8290', '#5a9e9b', '#83b5a8', '#c38b39'],
    grid: { left: 18, right: 24, top: 16, bottom: 18, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      confine: true,
      formatter: (items) => {
        const item = Array.isArray(items) ? items[0] : items
        const label = points[item?.dataIndex]?.label || item?.name || ''
        return `${label}<br/><strong>${formatNumber(item?.value)}</strong> 条记录`
      },
    },
    xAxis: {
      type: 'value',
      minInterval: 1,
      axisLabel: { color: '#64736d' },
      axisLine: { lineStyle: { color: '#dce5e0' } },
      splitLine: { lineStyle: { color: '#e9efec', type: 'dashed' } },
    },
    yAxis: {
      type: 'category',
      inverse: true,
      data: points.map((point) => point.label),
      axisTick: { show: false },
      axisLine: { show: false },
      axisLabel: {
        color: '#40534b',
        width: 170,
        overflow: 'truncate',
        ellipsis: '…',
        hideOverlap: true,
      },
    },
    series: [{
      type: 'bar',
      data: points.map((point) => point.value),
      barMaxWidth: 22,
      showBackground: true,
      backgroundStyle: { color: 'rgba(23, 107, 87, 0.055)', borderRadius: [0, 4, 4, 0] },
      itemStyle: { borderRadius: [0, 4, 4, 0] },
      emphasis: { focus: 'self' },
    }],
  }, true)
}

function renderRelationChart() {
  if (!relationChartElement.value || !relation.value) return
  if (!relationChart) relationChart = echarts.init(relationChartElement.value)
  const points = relation.value.points || []
  const scatter = relation.value.chartType === 'scatter'
  relationChart.setOption({
    animationDuration: 260,
    color: ['#287d79'],
    grid: { left: 48, right: 22, top: 22, bottom: 48, containLabel: true },
    tooltip: {
      trigger: 'item',
      confine: true,
      formatter: (item) => scatter
        ? `${relation.value.xColumnName}: ${formatNumber(item.value[0])}<br/>${relation.value.yColumnName}: ${formatNumber(item.value[1])}`
        : `${item.name}<br/><strong>${formatNumber(item.value)}</strong> · ${formatNumber(points[item.dataIndex]?.rowCount, 0)} 条有效记录`,
    },
    xAxis: scatter
      ? { type: 'value', name: relation.value.xColumnName, nameLocation: 'middle', nameGap: 30, splitLine: { lineStyle: { color: '#e9efec', type: 'dashed' } } }
      : { type: 'category', name: relation.value.groupColumnName, data: points.map((point) => point.label), axisLabel: { width: 120, overflow: 'truncate', hideOverlap: true }, axisTick: { show: false } },
    yAxis: scatter
      ? { type: 'value', name: relation.value.yColumnName, nameLocation: 'middle', nameGap: 40, splitLine: { lineStyle: { color: '#e9efec', type: 'dashed' } } }
      : { type: 'value', name: `平均 ${relation.value.valueColumnName}`, splitLine: { lineStyle: { color: '#e9efec', type: 'dashed' } } },
    series: scatter
      ? [{ type: 'scatter', data: points.map((point) => [point.x, point.y]), symbolSize: 8, emphasis: { focus: 'self' } }]
      : [{ type: 'bar', data: points.map((point) => point.y), barMaxWidth: 28, itemStyle: { borderRadius: [4, 4, 0, 0] } }],
  }, true)
}

function refreshCurrentAnalysis() {
  if (selectedDatasetId.value && selectedTableId.value && selectedColumnId.value) {
    loadAnalysis(selectedDatasetId.value, selectedTableId.value, selectedColumnId.value)
    if (relation.value) loadRelation()
  } else {
    loadDatasets()
  }
}

function openDatasets() {
  router.push('/datasets')
}

watch(
  () => auth.state.status,
  (status) => {
    if (status === 'authenticated') {
      loadDatasets()
      refreshWorkspaces()
    } else if (status === 'anonymous') {
      analysisBatchRequest.value += 1
      addingToBoard.value = false
      datasets.value = []
      catalog.value = null
      analysis.value = null
      relation.value = null
      selectedDatasetId.value = ''
      selectedTableId.value = ''
      selectedColumnId.value = ''
      analysisSelection.value = []
      relationColumnId.value = ''
      boardCards.value = []
      workspaces.value = []
      activeWorkspaceId.value = ''
      selectedWorkspaceId.value = ''
      workspaceRevision.value = null
      workspaceState.value = 'new'
    }
  },
  { immediate: true },
)

watch(selectedDatasetId, (datasetId) => loadCatalog(datasetId))
watch(selectedTableId, (tableId) => {
  selectedColumnId.value = selectedTable.value?.columns?.[0]?.columnId || ''
  relationColumnId.value = selectedTable.value?.columns?.find((column) => column.columnId !== selectedColumnId.value)?.columnId || ''
  relation.value = null
  if (!tableId) analysis.value = null
})
watch(selectedColumnId, (columnId) => {
  if (columnId && columnId === relationColumnId.value) {
    relationColumnId.value = selectedTable.value?.columns?.find((column) => column.columnId !== columnId)?.columnId || ''
  }
})
watch([selectedDatasetId, selectedTableId, selectedColumnId], ([datasetId, tableId, columnId]) => {
  loadAnalysis(datasetId, tableId, columnId)
})
watch([selectedDatasetId, selectedTableId, selectedColumnId, relationColumnId], () => {
  relationRequest += 1
  loadingRelation.value = false
  relation.value = null
  relationError.value = ''
})
watch(analysis, async (value) => {
  if (!value) {
    chart?.clear()
    return
  }
  await nextTick()
  renderChart()
})

watch(relation, async (value) => {
  if (!value) {
    relationChart?.clear()
    return
  }
  await nextTick()
  renderRelationChart()
})

watch(chartElement, (element) => {
  resizeObserver?.disconnect()
  if (!element || typeof ResizeObserver === 'undefined') return
  resizeObserver = new ResizeObserver(() => chart?.resize())
  resizeObserver.observe(element)
})

watch(relationChartElement, (element) => {
  relationResizeObserver?.disconnect()
  if (!element || typeof ResizeObserver === 'undefined') return
  relationResizeObserver = new ResizeObserver(() => relationChart?.resize())
  relationResizeObserver.observe(element)
})

onBeforeUnmount(() => {
  analysisBatchRequest.value += 1
  catalogRequest += 1
  analysisRequest += 1
  relationRequest += 1
  resizeObserver?.disconnect()
  relationResizeObserver?.disconnect()
  chart?.dispose()
  relationChart?.dispose()
  chart = null
  relationChart = null
})
</script>

<template>
  <main class="page-content analysis-page">
    <header class="page-header analysis-header">
      <div>
        <span class="analysis-kicker">DATA WORKSPACE</span>
        <h1>分析工作台</h1>
        <p>结构化数据 · 多方案探索</p>
      </div>
      <el-button
        circle
        aria-label="刷新分析"
        title="刷新分析"
        :icon="Refresh"
        :loading="loadingDatasets || loadingCatalog || loadingAnalysis || loadingRelation"
        @click="refreshCurrentAnalysis"
      />
    </header>

    <section class="analysis-workspace-toolbar" aria-label="分析方案">
      <label class="workspace-picker">
        <span>分析方案</span>
        <el-select
          v-model="selectedWorkspaceId"
          filterable
          :loading="loadingWorkspaces"
          placeholder="打开已保存方案"
          no-data-text="还没有保存的分析方案"
        >
          <el-option v-for="workspace in workspaces" :key="workspace.id" :value="workspace.id" :label="workspace.name">
            <span class="workspace-option" :title="workspace.name">{{ workspace.name }}</span>
          </el-option>
        </el-select>
      </label>
      <label class="workspace-name-field">
        <span>方案名称</span>
        <el-input
          v-model="workspaceName"
          maxlength="255"
          placeholder="给这组分析起个名字"
          @input="markWorkspaceDirty"
        />
      </label>
      <el-button :icon="FolderOpened" :disabled="!selectedWorkspaceId || workspaceState === 'loading'" @click="openSelectedWorkspace">打开</el-button>
      <el-button :icon="Plus" @click="startNewWorkspace">新建</el-button>
      <el-button
        v-if="activeWorkspaceId"
        :icon="CopyDocument"
        :loading="copyingWorkspace"
        :disabled="copyingWorkspace || !['saved', 'warning'].includes(workspaceState)"
        @click="copyWorkspace"
      >复制</el-button>
      <el-button
        type="primary"
        :icon="Check"
        :loading="workspaceState === 'saving'"
        :disabled="workspaceState === 'loading'"
        @click="saveWorkspace"
      >保存</el-button>
      <el-button
        v-if="activeWorkspaceId"
        circle
        aria-label="删除分析方案"
        title="删除分析方案"
        :icon="Delete"
        :disabled="workspaceState === 'saving' || workspaceState === 'loading'"
        @click="removeWorkspace"
      />
      <span class="workspace-state" :class="`is-${workspaceState}`" role="status">{{ workspaceStateLabel }}</span>
    </section>
    <div v-if="workspaceError" class="analysis-message is-error" role="alert">
      <span>{{ workspaceError }}</span>
      <el-button v-if="workspaceState === 'error' && selectedWorkspaceId" text :icon="FolderOpened" @click="openSelectedWorkspace">重新载入</el-button>
      <el-button v-else text :icon="Refresh" @click="refreshWorkspaces">重试</el-button>
    </div>

    <section class="analysis-explorer" aria-label="数据资产浏览器">
      <header class="explorer-heading">
        <div>
          <span class="explorer-kicker">ASSET EXPLORER</span>
          <h2>从数据资产开始</h2>
          <p>跨数据集与数据表收集字段，再组合成一张分析画布。</p>
        </div>
        <div class="explorer-actions">
          <el-button
            :icon="Share"
            :disabled="!selectedDataset || !selectedTable || !catalog?.datasetVersionId"
            @click="openWorkflowDialog(selectedTableReference())"
          >在工作流中继续</el-button>
          <el-button text :icon="Refresh" :loading="loadingDatasets || loadingCatalog" @click="refreshAssetBrowser">刷新资产</el-button>
        </div>
      </header>
      <div class="explorer-grid">
        <section class="asset-column" aria-label="数据集">
          <div class="asset-column-heading"><strong>数据集</strong><span>{{ datasets.length }}</span></div>
          <el-skeleton v-if="loadingDatasets" :rows="5" animated />
          <el-empty v-else-if="!datasets.length" description="暂无已就绪数据集" :image-size="58" />
          <template v-else>
            <button
              v-for="dataset in datasets"
              :key="dataset.id"
              type="button"
              class="asset-item"
              :class="{ 'is-active': dataset.id === selectedDatasetId }"
              :title="datasetLabel(dataset)"
              @click="selectedDatasetId = dataset.id"
            >
              <span class="asset-item-copy"><strong>{{ datasetLabel(dataset) }}</strong><small>{{ dataset.tableCount || 0 }} 张表 · {{ formatNumber(dataset.rowCount) }} 行</small></span>
              <el-icon><ArrowRight /></el-icon>
            </button>
            <el-button v-if="datasetHasMore" class="load-more-assets" text :loading="loadingMoreDatasets" @click="loadMoreDatasets">加载更多数据集</el-button>
          </template>
        </section>
        <section class="asset-column" aria-label="数据表">
          <div class="asset-column-heading"><strong>数据表</strong><span>{{ catalog?.tables?.length || 0 }}</span></div>
          <el-skeleton v-if="loadingCatalog" :rows="5" animated />
          <el-empty v-else-if="!catalog?.tables?.length" description="选择数据集查看表" :image-size="58" />
          <template v-else>
            <button
              v-for="table in catalog?.tables || []"
              :key="table.tableId"
              type="button"
              class="asset-item"
              :class="{ 'is-active': table.tableId === selectedTableId }"
              :title="table.tableName"
              @click="selectedTableId = table.tableId"
            >
              <span class="asset-item-copy"><strong>{{ table.tableName }}</strong><small>{{ formatNumber(table.rowCount) }} 行 · {{ table.columnCount || table.columns?.length || 0 }} 个字段</small></span>
              <el-icon><ArrowRight /></el-icon>
            </button>
          </template>
        </section>
        <section class="asset-column field-column" aria-label="字段">
          <div class="asset-column-heading"><strong>字段</strong><span>{{ selectedTableColumns.length }}</span></div>
          <el-empty v-if="!selectedTableColumns.length" description="选择数据表查看字段" :image-size="58" />
          <template v-else>
            <div
              v-for="column in selectedTableColumns"
              :key="column.columnId"
              class="asset-item"
              :class="{ 'is-active': column.columnId === selectedColumnId }"
              :title="column.name"
            >
              <el-checkbox
                :model-value="isAnalysisSelected(column.columnId)"
                :disabled="boardCards.some((card) => card.id === `${selectedDatasetId}:${selectedTableId}:${column.columnId}`)"
                :aria-label="`选择字段 ${column.name} 加入分析`"
                @change="(checked) => toggleAnalysisColumn(column, checked)"
              />
              <button type="button" class="asset-item-select" :title="`预览 ${column.name}`" @click="selectAssetColumn(column.columnId)">
                <span class="asset-item-copy"><strong>{{ column.name }}</strong><small>{{ column.logicalType || '未分类' }} · {{ column.nullable ? '可为空' : '非空' }}</small></span>
                <el-icon><DataAnalysis /></el-icon>
              </button>
            </div>
          </template>
        </section>
      </div>
      <div v-if="selectedColumn" class="explorer-suggestion">
        <div><span class="suggestion-mark"><el-icon><MagicStick /></el-icon></span><div><strong>推荐：{{ analysisSuggestion.label }}</strong><small>{{ analysisSuggestion.detail }} · {{ selectedColumn.name }}</small></div></div>
        <el-button size="small" type="primary" plain @click="selectAssetColumn(selectedColumn.columnId)">查看字段分析</el-button>
        <el-button v-if="suggestedRelationColumn" size="small" plain @click="useSuggestedRelation">比较 {{ suggestedRelationColumn.name }}</el-button>
      </div>
      <div v-if="analysisSelection.length" class="analysis-selection-bar">
        <div class="selection-summary"><strong>已选 {{ analysisSelection.length }} / 8 个字段</strong><small>可跨数据集与数据表组合 · 画布最多 20 张卡片</small></div>
        <div class="selection-items">
          <el-tag v-for="item in analysisSelection" :key="selectionKey(item)" closable effect="plain" :title="`${item.datasetName} · ${item.tableName} · ${item.columnName}`" @close="removeAnalysisSelection(item)">
            {{ item.columnName }} · {{ item.datasetName }} / {{ item.tableName }}
          </el-tag>
        </div>
        <el-button type="primary" :loading="addingToBoard" :disabled="addingToBoard || boardCards.length >= 20" @click="addSelectedAnalyses">分析并加入画布</el-button>
      </div>
    </section>

    <WorkflowSourceDialog v-model="workflowDialogVisible" :references="workflowReferences" @opened="openCreatedWorkflow" />

    <div v-if="datasetError" class="analysis-message is-error" role="alert">
      <span>{{ datasetError }}</span>
      <el-button text :icon="Refresh" @click="loadDatasets">重试</el-button>
    </div>
    <div v-else-if="catalogError" class="analysis-message is-error" role="alert">
      <span>{{ catalogError }}</span>
      <el-button text :icon="Refresh" @click="loadCatalog(selectedDatasetId)">重试</el-button>
    </div>
    <div v-else-if="analysisError" class="analysis-message is-error" role="alert">
      <span>{{ analysisError }}</span>
      <el-button text :icon="Refresh" @click="refreshCurrentAnalysis">重试</el-button>
    </div>

    <section v-if="datasets.length && selectedTable" class="analysis-metrics" aria-label="数据概况">
      <div class="metric-cell">
        <span>记录数</span>
        <strong :title="formatNumber(analysis?.rowCount ?? selectedTable.rowCount)">{{ formatNumber(analysis?.rowCount ?? selectedTable.rowCount) }}</strong>
        <small>当前数据表</small>
      </div>
      <div class="metric-cell">
        <span>字段数</span>
        <strong :title="formatNumber(selectedTable.columnCount)">{{ formatNumber(selectedTable.columnCount) }}</strong>
        <small>当前数据表</small>
      </div>
      <div class="metric-cell">
        <span>唯一值估算</span>
        <strong :title="analysis ? formatNumber(analysis.uniqueCountEstimate) : ''">{{ loadingAnalysis ? '...' : analysis ? formatNumber(analysis.uniqueCountEstimate) : '—' }}</strong>
        <small>当前字段</small>
      </div>
      <div class="metric-cell">
        <span>缺失记录</span>
        <strong :title="analysis ? formatNumber(analysis.missingCount) : ''">{{ loadingAnalysis ? '...' : analysis ? formatNumber(analysis.missingCount) : '—' }}</strong>
        <small>当前字段</small>
      </div>
    </section>

    <section v-if="datasets.length && selectedTable" class="chart-panel" aria-label="字段分布图">
      <div class="chart-heading">
        <div class="chart-title-group">
          <span class="chart-mark"><el-icon><DataAnalysis /></el-icon></span>
          <div class="chart-title-copy">
            <h2 :title="selectedColumn?.name || '字段分布'">{{ selectedColumn?.name || '字段分布' }}</h2>
            <span :title="selectedTable.tableName">{{ selectedTable.tableName }} · {{ isHistogram ? '数值分布' : '类别频数' }}</span>
          </div>
        </div>
        <el-tag v-if="analysis" effect="plain" type="success" size="small">
          {{ analysis.logicalType || '字段' }}
        </el-tag>
        <el-button
          v-if="analysis"
          size="small"
          plain
          :icon="Plus"
          :loading="addingToBoard"
          :disabled="boardCards.length >= 20 || boardCards.some((card) => card.id === `${analysis.datasetVersionId}:${selectedTableId}:${selectedColumnId}`)"
          @click="addCurrentAnalysis"
        >加入画布</el-button>
      </div>

      <div class="chart-body" :class="{ 'is-loading': loadingAnalysis }">
        <el-skeleton v-if="loadingAnalysis" :rows="7" animated />
        <div v-else-if="analysisError" class="chart-empty">
          <el-icon><DataAnalysis /></el-icon>
          <span>分析暂不可用</span>
        </div>
        <el-empty
          v-else-if="analysis && !analysis.points?.length"
          description="当前字段没有可绘制的数据"
          :image-size="84"
        />
        <div v-else ref="chartElement" class="distribution-chart"></div>
      </div>

      <div v-if="analysis && isHistogram" class="chart-footnote">
        <span>范围 {{ formatNumber(analysis.minimum) }} 至 {{ formatNumber(analysis.maximum) }}</span>
        <span>共 {{ analysis.points.length }} 个区间</span>
      </div>
      <div v-else-if="analysis" class="chart-footnote">
        <span>显示频数最高的 {{ analysis.points.length }} 个类别</span>
        <span>唯一值为近似估算</span>
      </div>
    </section>

    <section v-if="boardCards.length || activeWorkspaceId || workspaceState === 'dirty'" class="analysis-board" aria-label="分析画布">
      <header class="board-heading">
        <div>
          <h2>分析画布</h2>
          <span>{{ boardCards.length }} 个分析 · 可跨数据集比较</span>
        </div>
        <div class="board-heading-actions">
          <el-button :icon="Share" :disabled="!boardCards.length" @click="openWorkflowDialog(boardCards)">整张画布加入工作流</el-button>
          <el-button text :icon="Close" :disabled="!boardCards.length" @click="clearAnalysisBoard">清空画布</el-button>
        </div>
      </header>
      <el-empty v-if="!boardCards.length" description="画布为空，可以从字段分布加入分析" :image-size="64" />
      <div v-else class="board-grid">
        <article v-for="card in boardCards" :key="card.id" class="board-card">
          <header class="board-card-heading">
            <div class="board-card-title" :title="card.columnName">
              <strong>{{ card.columnName }}</strong>
              <span :title="`${card.datasetName} · ${card.tableName}`">{{ card.datasetName }} · {{ card.tableName }}</span>
            </div>
            <div class="board-card-actions">
              <el-button circle text aria-label="将此表加入工作流" title="将此表加入工作流" :icon="Share" @click="openWorkflowDialog([card])" />
              <el-button circle text aria-label="移除分析卡片" title="移除分析卡片" :icon="Close" @click="removeBoardCard(card.id)" />
            </div>
          </header>
          <div class="board-card-stats">
            <template v-if="card.relationColumnId">
              <span>{{ card.chartType === 'scatter' ? `${card.points.length} 个样本点` : `${card.points.length} 个分组` }}</span>
              <span>{{ card.chartType === 'scatter' ? '确定性抽样' : '分组均值' }}</span>
            </template>
            <template v-else>
              <span :title="`记录数 ${formatNumber(card.rowCount)}`">{{ formatNumber(card.rowCount) }} 行</span>
              <span :title="`缺失记录 ${formatNumber(card.missingCount)}`">缺失 {{ formatNumber(card.missingCount) }}</span>
              <span>{{ card.logicalType || '字段' }}</span>
            </template>
          </div>
          <div v-if="!card.relationColumnId && card.points.length" class="board-bars">
            <div v-for="point in card.points.slice(0, 8)" :key="point.label" class="board-bar-row" :title="`${point.label}: ${formatNumber(point.value)}`">
              <span>{{ point.label }}</span>
              <div class="board-bar-track"><i :style="{ width: pointWidth(card.points, point.value) }"></i></div>
              <b>{{ formatNumber(point.value) }}</b>
            </div>
          </div>
          <div v-else-if="card.relationColumnId && card.points.length" class="board-relation-points">
            <div class="board-relation-head"><span>{{ card.chartType === 'scatter' ? card.columnName.split(' · ')[0] : '分组' }}</span><span>{{ card.chartType === 'scatter' ? card.relationColumnName : '数值' }}</span></div>
            <div v-for="(point, index) in card.points.slice(0, 8)" :key="`${point.label || 'sample'}:${index}`" class="board-relation-row">
              <span :title="point.label || formatNumber(point.x)">{{ point.label || formatNumber(point.x) }}</span>
              <strong>{{ formatNumber(point.y) }}</strong>
            </div>
            <small v-if="card.chartType === 'scatter'">显示 {{ Math.min(card.points.length, 8) }} / {{ card.points.length }} 个确定性样本点</small>
            <small v-else>显示前 {{ Math.min(card.points.length, 8) }} 个类别的均值</small>
          </div>
          <el-empty v-else :description="card.error || '没有可绘制的数据'" :image-size="56" />
        </article>
      </div>
    </section>

    <section v-if="datasets.length && selectedTable?.columns?.length > 1" class="relation-panel" aria-label="字段关系分析">
      <div class="relation-heading">
        <div><h2>字段关系</h2><span :title="`${selectedColumn?.name || ''} · ${relationColumn?.name || ''}`">{{ selectedColumn?.name }} · {{ relationColumn?.name }}</span></div>
        <div class="relation-actions">
          <el-select v-model="relationColumnId" size="small" filterable :title="relationColumn?.name || ''" placeholder="选择比较字段">
            <el-option v-for="column in relationColumns" :key="column.columnId" :value="column.columnId" :label="column.name" />
          </el-select>
          <el-button type="primary" plain :loading="loadingRelation" :disabled="!selectedColumnId || !relationColumnId || selectedColumnId === relationColumnId" @click="loadRelation">分析关系</el-button>
        </div>
      </div>
      <div v-if="relationError" class="analysis-message is-error" role="alert"><span>{{ relationError }}</span><el-button text :icon="Refresh" @click="loadRelation">重试</el-button></div>
      <el-skeleton v-else-if="loadingRelation" :rows="5" animated />
      <el-empty v-else-if="relation && !relation.points?.length" description="所选字段没有可用于关系分析的记录" :image-size="76" />
      <div v-else-if="relation" ref="relationChartElement" class="relation-chart"></div>
      <div v-if="relation" class="relation-board-action">
        <el-button size="small" plain :icon="Plus" :disabled="boardCards.length >= 20 || boardCards.some((card) => card.id === `${relation.datasetVersionId}:${relation.tableId}:${relation.xColumnId}:${relation.yColumnId}`)" @click="addCurrentRelation">加入分析画布</el-button>
      </div>
      <p class="relation-note">数值字段显示最多 250 个确定性样本点；类别与数值字段显示前 12 组的数值均值。两个类别字段暂不支持关系分析。</p>
    </section>

    <section v-else-if="!loadingDatasets && !datasetError && !catalogError" class="analysis-empty">
      <div class="empty-icon"><el-icon><DataAnalysis /></el-icon></div>
      <h2>{{ datasets.length ? '当前数据集没有可分析的数据表' : '暂无已就绪的数据集' }}</h2>
      <p>{{ datasets.length ? '选择其他版本或前往数据集库检查数据表。' : '先将一个数据集发布为 READY，再回来查看字段分布。' }}</p>
      <el-button type="primary" @click="openDatasets">打开数据集库</el-button>
    </section>
  </main>
</template>

<style scoped>
.analysis-page {
  padding-bottom: 30px;
}

.analysis-header {
  align-items: center;
}

.analysis-kicker {
  display: inline-block;
  margin-bottom: 5px;
  color: #568374;
  font-size: 10px;
  font-weight: 700;
  line-height: 1.2;
}

.analysis-header h1 {
  letter-spacing: 0;
}

.analysis-header p {
  color: #64736d;
}

.analysis-explorer {
  min-width: 0;
  overflow: hidden;
  background: rgba(250, 253, 251, 0.78);
  border: 1px solid rgba(255, 255, 255, 0.88);
  border-radius: 8px;
  box-shadow: 0 10px 28px rgba(27, 61, 50, 0.055);
}
.explorer-actions { display: flex; flex: 0 0 auto; flex-wrap: wrap; align-items: center; justify-content: flex-end; gap: 6px; }
.board-heading-actions, .board-card-actions { display: flex; min-width: 0; align-items: center; gap: 4px; }
.board-heading-actions { flex-wrap: wrap; justify-content: flex-end; }
.board-heading-actions .el-button + .el-button, .board-card-actions .el-button + .el-button { margin-left: 0; }

.explorer-heading {
  display: flex;
  min-height: 70px;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 13px 16px;
  border-bottom: 1px solid #e4ebe7;
}

.explorer-heading > div { min-width: 0; }
.explorer-kicker { color: #5f897a; font-size: 9px; font-weight: 750; }
.explorer-heading h2 { margin: 3px 0 0; color: #243b32; font-size: 14px; font-weight: 680; }
.explorer-heading p { margin: 4px 0 0; color: #78877f; font-size: 10px; line-height: 1.45; }

.explorer-grid {
  display: grid;
  grid-template-columns: minmax(0, .95fr) minmax(0, .95fr) minmax(0, 1.1fr);
  min-height: 250px;
  max-height: 340px;
}

.asset-column {
  min-width: 0;
  overflow: auto;
  padding: 10px;
  border-right: 1px solid #e4ebe7;
  scrollbar-width: thin;
}

.asset-column:last-child { border-right: 0; }

.asset-column-heading {
  display: flex;
  position: sticky;
  z-index: 1;
  top: -10px;
  align-items: center;
  justify-content: space-between;
  margin: -10px -10px 7px;
  padding: 11px 12px 8px;
  color: #50655c;
  background: rgba(250, 253, 251, 0.96);
  font-size: 10px;
}

.asset-column-heading span { color: #83918a; font-variant-numeric: tabular-nums; }

.asset-item {
  display: flex;
  width: 100%;
  min-width: 0;
  min-height: 50px;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin: 2px 0;
  padding: 8px 10px;
  color: #5c6d65;
  text-align: left;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 6px;
  cursor: pointer;
}

.asset-item:hover { background: #f1f7f4; }
.asset-item.is-active { color: #245d4b; background: #e8f3ee; border-color: #d4e7de; }
.asset-item > .el-icon { flex: 0 0 auto; font-size: 13px; }
.field-column .asset-item { justify-content: flex-start; gap: 8px; padding-inline: 8px; cursor: default; }
.field-column .asset-item > :deep(.el-checkbox) { flex: 0 0 auto; margin-right: 0; }
.asset-item-select { display: flex; min-width: 0; flex: 1 1 auto; align-items: center; justify-content: space-between; gap: 8px; padding: 0; color: inherit; text-align: left; background: transparent; border: 0; cursor: pointer; }
.asset-item-select > .el-icon { flex: 0 0 auto; font-size: 13px; }

.asset-item-copy {
  display: grid;
  min-width: 0;
  gap: 4px;
}

.asset-item-copy strong,
.asset-item-copy small {
  display: block;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.asset-item-copy strong { font-size: 11px; font-weight: 620; }
.asset-item-copy small { color: #86928c; font-size: 9px; }
.load-more-assets { width: 100%; margin: 4px 0 2px; }

.explorer-suggestion {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 7px;
  padding: 10px 12px;
  border-top: 1px solid #e4ebe7;
}

.explorer-suggestion > div {
  display: flex;
  min-width: 0;
  flex: 1 1 auto;
  align-items: center;
  gap: 9px;
}

.explorer-suggestion > div > div {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.explorer-suggestion strong,
.explorer-suggestion small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.explorer-suggestion strong { color: #35594b; font-size: 10px; }
.explorer-suggestion small { color: #819088; font-size: 9px; }
.suggestion-mark { display: grid; flex: 0 0 28px; width: 28px; height: 28px; place-items: center; color: #2f775e; background: #e8f3ee; border-radius: 6px; }
.analysis-selection-bar { display: grid; min-width: 0; grid-template-columns: minmax(150px, auto) minmax(0, 1fr) auto; align-items: center; gap: 10px; padding: 9px 12px; border-top: 1px solid #e4ebe7; }
.selection-summary { display: grid; min-width: 0; gap: 3px; }
.selection-summary strong { color: #35594b; font-size: 10px; }
.selection-summary small { color: #819088; font-size: 9px; }
.selection-items { display: flex; min-width: 0; flex-wrap: wrap; gap: 5px; }
.selection-items :deep(.el-tag) { max-width: 100%; }
.selection-items :deep(.el-tag__content) { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.analysis-message {
  display: flex;
  min-height: 42px;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  margin-top: 12px;
  padding: 8px 12px;
  border-radius: 6px;
  font-size: 12px;
}

.analysis-message > span {
  min-width: 0;
  overflow-wrap: anywhere;
}

.analysis-message.is-error {
  color: #8d3c35;
  background: #fff4f0;
  border: 1px solid #f1d2c8;
}

.analysis-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin-block: 15px;
  overflow: hidden;
  background: rgba(250, 253, 251, 0.84);
  border: 1px solid rgba(255, 255, 255, 0.9);
  border-radius: 8px;
  box-shadow: 0 10px 28px rgba(27, 61, 50, 0.05);
}

.metric-cell {
  display: flex;
  min-width: 0;
  min-height: 94px;
  flex-direction: column;
  justify-content: center;
  gap: 5px;
  padding: 13px 19px;
  border-right: 1px solid #e7eeea;
}

.metric-cell:last-child {
  border-right: 0;
}

.metric-cell > span {
  color: #62736b;
  font-size: 11px;
}

.metric-cell strong {
  overflow: hidden;
  color: #1a3e32;
  font-size: 21px;
  font-weight: 680;
  line-height: 1.2;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric-cell small {
  color: #91a099;
  font-size: 10px;
}

.chart-panel {
  overflow: hidden;
  background: rgba(250, 253, 251, 0.86);
  border: 1px solid rgba(255, 255, 255, 0.9);
  border-radius: 8px;
  box-shadow: 0 14px 34px rgba(27, 61, 50, 0.065);
}

.analysis-board {
  min-width: 0;
  margin-top: 16px;
}

.board-heading {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.board-heading > div:first-child {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.board-heading h2 {
  margin: 0;
  color: #20372f;
  font-size: 14px;
  font-weight: 650;
}

.board-heading span {
  color: #829089;
  font-size: 10px;
}

.board-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 300px), 1fr));
  align-items: start;
  gap: 12px;
}

.board-card {
  min-width: 0;
  overflow: hidden;
  padding: 13px 14px 12px;
  background: rgba(250, 253, 251, 0.86);
  border: 1px solid #e1eae5;
  border-radius: 7px;
}

.board-card-heading {
  display: flex;
  min-width: 0;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.board-card-title {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 4px;
}

.board-card-title strong,
.board-card-title span {
  display: block;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.board-card-title strong {
  color: #20372f;
  font-size: 13px;
  font-weight: 650;
}

.board-card-title span {
  color: #829089;
  font-size: 10px;
}

.board-card-stats {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  gap: 6px 12px;
  margin-top: 11px;
  color: #64736d;
  font-size: 10px;
}

.board-relation-points { display: grid; gap: 5px; padding: 4px 1px 0; }
.board-relation-head, .board-relation-row { display: grid; grid-template-columns: minmax(0, 1.4fr) minmax(0, .8fr); align-items: center; gap: 10px; }
.board-relation-head { padding-bottom: 5px; color: #83918a; font-size: 9px; border-bottom: 1px solid #e8eeeb; }
.board-relation-row > span { min-width: 0; overflow: hidden; color: #566a60; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.board-relation-row > strong { color: #345d4c; font-size: 10px; font-variant-numeric: tabular-nums; text-align: right; }
.board-relation-points > small { margin-top: 2px; color: #8b9891; font-size: 9px; }

.board-card-stats > span {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.board-bars {
  display: grid;
  gap: 8px;
  margin-top: 13px;
}

.board-bar-row {
  display: grid;
  min-width: 0;
  grid-template-columns: minmax(50px, 0.9fr) minmax(30px, 1.4fr) minmax(28px, auto);
  align-items: center;
  gap: 8px;
  color: #586b63;
  font-size: 10px;
}

.board-bar-row > span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.board-bar-track {
  height: 7px;
  overflow: hidden;
  background: #edf2ef;
  border-radius: 4px;
}

.board-bar-track i {
  display: block;
  height: 100%;
  background: #43836d;
  border-radius: inherit;
}

.board-bar-row b {
  overflow: hidden;
  color: #344d42;
  font-size: 10px;
  font-weight: 600;
  text-align: right;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.relation-panel {
  min-width: 0;
  margin-top: 15px;
  padding: 16px 19px 11px;
  background: rgba(250, 253, 251, 0.86);
  border: 1px solid rgba(255, 255, 255, 0.9);
  border-radius: 8px;
  box-shadow: 0 12px 30px rgba(27, 61, 50, 0.05);
}

.relation-heading {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.relation-heading > div:first-child { display: flex; min-width: 0; flex-direction: column; gap: 4px; }
.relation-actions { display: flex; min-width: 0; align-items: center; gap: 7px; }
.relation-actions :deep(.el-select) { width: min(220px, 28vw); }
.relation-heading h2 { margin: 0; color: #20372f; font-size: 14px; font-weight: 650; }
.relation-heading span { max-width: 100%; overflow: hidden; color: #829089; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.relation-chart { width: 100%; height: 330px; }
.relation-board-action { display: flex; justify-content: flex-end; padding: 0 16px 10px; }
.relation-note { margin: 0; color: #829089; font-size: 10px; line-height: 1.5; }

.chart-heading {
  display: flex;
  min-height: 68px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 13px 20px;
  border-bottom: 1px solid #e7eeea;
}

.chart-title-group {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 11px;
}

.chart-mark,
.empty-icon {
  display: grid;
  flex: 0 0 34px;
  width: 34px;
  height: 34px;
  place-items: center;
  color: #176b57;
  background: #e4f1eb;
  border-radius: 7px;
}

.chart-title-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 4px;
}

.chart-title-copy h2 {
  max-width: 540px;
  margin: 0;
  overflow: hidden;
  color: #20372f;
  font-size: 14px;
  font-weight: 650;
  line-height: 1.35;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chart-title-copy > span {
  overflow: hidden;
  color: #829089;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chart-body {
  min-height: 380px;
  padding: 17px 14px 9px;
}

.chart-body.is-loading {
  padding: 32px 24px;
}

.distribution-chart {
  width: 100%;
  height: 352px;
}

.chart-empty {
  display: flex;
  min-height: 345px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: #75857d;
  font-size: 12px;
}

.chart-empty .el-icon {
  color: #93b7aa;
  font-size: 29px;
}

.chart-footnote {
  display: flex;
  min-height: 38px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 20px;
  color: #819089;
  font-size: 10px;
  border-top: 1px solid #e7eeea;
}

.chart-footnote > span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.analysis-empty {
  display: flex;
  min-height: 360px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  margin-top: 15px;
  padding: 28px;
  text-align: center;
  background: rgba(250, 253, 251, 0.78);
  border: 1px solid rgba(255, 255, 255, 0.9);
  border-radius: 8px;
}

.empty-icon {
  width: 48px;
  height: 48px;
  margin-bottom: 10px;
  font-size: 20px;
}

.analysis-empty h2 {
  margin: 0;
  color: #273d35;
  font-size: 16px;
  font-weight: 650;
}

.analysis-empty p {
  max-width: 440px;
  margin: 9px 0 17px;
  color: #73837b;
  font-size: 12px;
  line-height: 1.6;
}

@media (max-width: 900px) {
  .explorer-grid { grid-template-columns: repeat(3, minmax(210px, 1fr)); overflow-x: auto; }
  .asset-column { min-width: 210px; }
}

@media (max-width: 600px) {
  .explorer-heading { align-items: flex-start; padding: 11px 12px; }
  .explorer-actions { justify-content: flex-start; }
  .explorer-heading p { max-width: 230px; }
  .explorer-grid { display: flex; min-height: 220px; max-height: 290px; }
  .asset-column { flex: 0 0 78vw; max-width: 300px; }
  .explorer-suggestion { align-items: stretch; flex-wrap: wrap; }
  .explorer-suggestion > div { flex-basis: 100%; }
  .explorer-suggestion > .el-button { flex: 1 1 auto; margin-left: 0; }
  .analysis-selection-bar { grid-template-columns: minmax(0, 1fr) auto; }
  .selection-summary { grid-column: 1 / -1; }
  .selection-items { grid-column: 1 / -1; }

  .analysis-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .metric-cell {
    min-height: 84px;
    padding: 11px 14px;
  }

  .metric-cell:nth-child(2) {
    border-right: 0;
  }

  .metric-cell:nth-child(-n + 2) {
    border-bottom: 1px solid #e7eeea;
  }

  .metric-cell strong {
    font-size: 19px;
  }

  .chart-heading {
    flex-wrap: wrap;
    min-height: 62px;
    padding-inline: 12px;
  }

  .chart-heading > .el-button {
    margin-left: auto;
  }

  .chart-body {
    min-height: 338px;
    padding-inline: 5px;
  }

  .distribution-chart {
    height: 310px;
  }

  .relation-panel { padding: 13px 11px 10px; }
  .relation-heading { align-items: flex-start; flex-direction: column; }
  .relation-actions { width: 100%; }
  .relation-actions :deep(.el-select) { flex: 1 1 auto; width: auto; }
  .relation-chart { height: 270px; }

  .board-grid { grid-template-columns: minmax(0, 1fr); }
  .board-card { padding-inline: 11px; }

  .chart-footnote {
    align-items: flex-start;
    flex-direction: column;
    justify-content: center;
    gap: 4px;
    padding-block: 7px;
  }
}
</style>
