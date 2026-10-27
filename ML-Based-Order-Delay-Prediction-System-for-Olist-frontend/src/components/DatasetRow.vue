<script setup>
import { computed, h, nextTick, reactive, ref, watch } from 'vue'
import {
  ArrowRight,
  CircleCheck,
  Clock,
  Collection,
  Delete,
  Document,
  EditPen,
  Files,
  Grid,
  Search,
} from '@element-plus/icons-vue'
import { ElAutoResizer, ElTableV2 } from 'element-plus'
import DatasetUploader from '@/components/DatasetUploader.vue'
import TableRowSearch from '@/components/TableRowSearch.vue'
import { listDatasetTableRows, previewDatasetTable } from '@/api/datasets'
import { statusLabel, statusTagType } from '@/utils/dataset'
import { formatDateTime, formatNumber } from '@/utils/format'

const props = defineProps({
  item: { type: Object, required: true },
  expanded: { type: Boolean, default: false },
  editing: { type: Boolean, default: false },
  detail: { type: Object, default: null },
  detailLoading: { type: Boolean, default: false },
  detailError: { type: String, default: '' },
  uploading: { type: Boolean, default: false },
  uploadProgress: { type: Number, default: 0 },
  readying: { type: Boolean, default: false },
})

const emit = defineEmits([
  'toggle',
  'start-edit',
  'cancel-edit',
  'save-name',
  'upload',
  'cancel-upload',
  'ready',
  'delete',
  'rename-table',
  'delete-table',
  'retry-detail',
  'extract-fields',
])

const TABLE_PREVIEW_LIMIT = 5
const TABLE_PREVIEW_ROWS = 5
const TABLE_ROW_PAGE_SIZE = 200
const TABLE_ROW_PREFETCH = 30
const nameInput = ref(null)
const tableNameInput = ref(null)
const draftName = ref(props.item.name)
const tableKeyword = ref('')
const editingTableKey = ref('')
const tableNameDraft = ref('')
const showAllTables = ref(false)
const expandedTableIds = ref([])
const tablePreviews = reactive({})
const tablePreviewLoading = reactive({})
const tablePreviewErrors = reactive({})
const expandedAllTables = reactive({})
const tableAllRows = reactive({})
const tableSearchActive = reactive({})
let cancelOnBlur = false
let cancelTableOnBlur = false

const tables = computed(() => props.detail?.tables ?? [])
const filteredTables = computed(() => {
  const keyword = tableKeyword.value.trim().toLocaleLowerCase()
  if (!keyword) return tables.value
  return tables.value.filter((table) => [table.name, table.fileName]
    .some((value) => String(value || '').toLocaleLowerCase().includes(keyword)))
})
const hasManyTables = computed(() => filteredTables.value.length > TABLE_PREVIEW_LIMIT)
const isClamped = computed(
  () =>
    hasManyTables.value &&
    !tableKeyword.value.trim() &&
    !showAllTables.value &&
    expandedTableIds.value.length === 0,
)
const shortId = computed(() => (props.item.datasetId || props.item.id).slice(0, 8))
const format = computed(() => props.item.format || props.detail?.format || '')

watch(
  () => props.item.name,
  (value) => {
    if (!props.editing) {
      draftName.value = value
    }
  },
)

watch(
  () => props.editing,
  async (editing) => {
    if (!editing) {
      return
    }
    draftName.value = props.item.name
    await nextTick()
    nameInput.value?.focus()
    nameInput.value?.select?.()
  },
  { immediate: true },
)

watch(() => props.item.id, resetTableState)

watch(
  () => props.expanded,
  (expanded) => {
    if (!expanded) {
      resetTableState()
    }
  },
)

function resetTableState() {
  tableKeyword.value = ''
  showAllTables.value = false
  expandedTableIds.value = []
  editingTableKey.value = ''
  tableNameDraft.value = ''
  for (const key of Object.keys(tablePreviews)) {
    delete tablePreviews[key]
  }
  for (const key of Object.keys(tablePreviewLoading)) {
    delete tablePreviewLoading[key]
  }
  for (const key of Object.keys(tablePreviewErrors)) {
    delete tablePreviewErrors[key]
  }
  for (const key of Object.keys(expandedAllTables)) {
    delete expandedAllTables[key]
  }
  for (const key of Object.keys(tableAllRows)) {
    delete tableAllRows[key]
  }
  for (const key of Object.keys(tableSearchActive)) {
    delete tableSearchActive[key]
  }
}

function tableKey(table) {
  return table.id || `${table.fileName}-${table.name}`
}

function isTableExpanded(table) {
  return expandedTableIds.value.includes(tableKey(table))
}

async function toggleTable(table) {
  const key = tableKey(table)
  if (expandedTableIds.value.includes(key)) {
    expandedTableIds.value = expandedTableIds.value.filter((value) => value !== key)
    return
  }
  expandedTableIds.value = [...expandedTableIds.value, key]
  if (!tablePreviews[key] && !tablePreviewLoading[key]) {
    await loadTablePreview(table)
  }
}

async function loadTablePreview(table, { force = false } = {}) {
  const key = tableKey(table)
  if (tablePreviewLoading[key]) {
    return
  }
  if (!force && tablePreviews[key]) {
    return
  }
  tablePreviewLoading[key] = true
  tablePreviewErrors[key] = ''
  try {
    tablePreviews[key] = await previewDatasetTable(props.item.id, table.id, {
      limit: TABLE_PREVIEW_ROWS,
      mode: 'preview',
    })
  } catch (error) {
    tablePreviewErrors[key] = error.message
  } finally {
    tablePreviewLoading[key] = false
  }
}

function allRowsKey(table) {
  return tableKey(table)
}

function buildAllColumns(columns) {
  const renderPreviewText = (value, className) => {
    const text = String(value ?? '')
    return h('span', { class: className, title: text }, text)
  }

  return [
    {
      key: 'index',
      dataKey: '__index',
      title: '#',
      width: 76,
      align: 'right',
      headerCellRenderer: ({ column }) => renderPreviewText(column.title, 'preview-virtual-heading'),
      cellRenderer: ({ cellData }) => renderPreviewText(cellData, 'preview-virtual-value'),
    },
    ...columns.map((column, index) => ({
      key: `column-${index}`,
      dataKey: `c${index}`,
      title: column || `列 ${index + 1}`,
      width: 160,
      flexGrow: 1,
      headerCellRenderer: ({ column: columnConfig }) =>
        renderPreviewText(columnConfig.title, 'preview-virtual-heading'),
      cellRenderer: ({ cellData }) => renderPreviewText(cellData, 'preview-virtual-value'),
    })),
  ]
}

function rowRecord(row, index) {
  const record = { id: index, __index: index + 1 }
  row.forEach((cell, columnIndex) => {
    record[`c${columnIndex}`] = cell
  })
  return record
}

async function loadAllRows(table) {
  const key = allRowsKey(table)
  const state = tableAllRows[key]
  if (!state || state.loading || state.done) {
    return
  }
  state.loading = true
  state.failed = false
  tablePreviewErrors[key] = ''
  try {
    const page = await listDatasetTableRows(props.item.id, table.id, {
      offset: state.nextOffset,
      limit: TABLE_ROW_PAGE_SIZE,
    })
    const start = state.nextOffset
    const records = (page.rows ?? []).map((row, index) => rowRecord(row, start + index))
    state.data.push(...records)
    state.nextOffset += records.length
    state.totalRows = page.totalRows ?? state.totalRows
    state.done = records.length === 0 || state.nextOffset >= state.totalRows
  } catch (error) {
    state.failed = true
    tablePreviewErrors[key] = error.message
  } finally {
    state.loading = false
  }
}

function ensureAllRowsState(table) {
  const key = allRowsKey(table)
  if (tableAllRows[key]) {
    return tableAllRows[key]
  }
  const preview = tablePreviews[key]
  tableAllRows[key] = {
    data: [],
    columns: preview ? buildAllColumns(preview.columns) : [],
    nextOffset: 0,
    totalRows: preview?.totalRows ?? 0,
    loading: false,
    done: false,
    failed: false,
  }
  return tableAllRows[key]
}

async function toggleAllRows(table) {
  const key = allRowsKey(table)
  if (expandedAllTables[key]) {
    expandedAllTables[key] = false
    return
  }
  const state = ensureAllRowsState(table)
  expandedAllTables[key] = true
  if (state.data.length === 0 && !state.done) {
    await loadAllRows(table)
  }
}

function retryTablePreview(table) {
  if (expandedAllTables[allRowsKey(table)]) {
    return loadAllRows(table)
  }
  return loadTablePreview(table, { force: true })
}

function handleRowsRendered(table, params) {
  const state = tableAllRows[allRowsKey(table)]
  if (!state || state.loading || state.done || state.failed) {
    return
  }
  if (params.rowVisibleEnd >= state.data.length - TABLE_ROW_PREFETCH) {
    loadAllRows(table)
  }
}

function isAllRowsInitialLoading(table) {
  const key = allRowsKey(table)
  const state = tableAllRows[key]
  return (
    expandedAllTables[key] &&
    (!state || (state.data.length === 0 && !state.done && !state.failed))
  )
}

function hiddenRowCount(preview) {
  return Math.max(preview.totalRows - preview.rows.length - preview.tailRows.length, 0)
}

function tailRowNumber(preview, index) {
  return preview.totalRows - preview.tailRows.length + index + 1
}

function toggle() {
  emit('toggle', props.item.id)
}

function startEdit() {
  emit('start-edit', props.item.id)
}

function cancelName() {
  cancelOnBlur = true
  emit('cancel-edit')
  nextTick(() => {
    cancelOnBlur = false
  })
}

function commitName() {
  if (cancelOnBlur) {
    return
  }
  const name = draftName.value.trim()
  if (!name || name === props.item.name) {
    emit('cancel-edit')
    return
  }
  emit('save-name', { id: props.item.id, name })
}

function handleNameEnter(event) {
  if (event.isComposing) {
    return
  }
  event.target.blur()
}

function handleNameEscape(event, cancel) {
  if (event.isComposing) {
    return
  }
  cancel()
}

function requestDelete() {
  emit('delete', props.item.id)
}

function setTableNameInput(el) {
  tableNameInput.value = el
}

function isTableEditing(table) {
  return editingTableKey.value === tableKey(table)
}

async function startTableEdit(table) {
  editingTableKey.value = tableKey(table)
  tableNameDraft.value = table.name
  await nextTick()
  tableNameInput.value?.focus()
  tableNameInput.value?.select?.()
}

function cancelTableNameEdit() {
  cancelTableOnBlur = true
  editingTableKey.value = ''
  tableNameDraft.value = ''
  nextTick(() => {
    cancelTableOnBlur = false
  })
}

function commitTableName(table) {
  if (cancelTableOnBlur) {
    return
  }
  const name = tableNameDraft.value.trim()
  editingTableKey.value = ''
  tableNameDraft.value = ''
  if (!name || name === table.name) {
    return
  }
  emit('rename-table', {
    datasetId: props.item.id,
    tableId: table.id,
    name,
  })
}

function handleTableLineClick(table) {
  if (isTableEditing(table)) {
    tableNameInput.value?.focus()
    tableNameInput.value?.select?.()
    return
  }
  toggleTable(table)
}

function handleTableLineMouseDown(event, table) {
  if (isTableEditing(table)) {
    event.preventDefault()
  }
}

function requestDeleteTable(table) {
  emit('delete-table', {
    datasetId: props.item.id,
    tableId: table.id,
    name: table.name,
  })
}

function handleTableKeydown(event, table) {
  if (isTableEditing(table)) {
    return
  }
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    toggleTable(table)
  }
}

function handlePreviewGapKeydown(event, table) {
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    toggleAllRows(table)
  }
}

function selectFile(file) {
  emit('upload', { id: props.item.id, file })
}

</script>

<template>
  <article class="dataset-item" :class="{ 'is-expanded': expanded }">
    <div
      class="dataset-summary"
      @click="toggle"
    >
      <span class="row-chevron" :class="{ 'is-open': expanded }">
        <el-icon><ArrowRight /></el-icon>
      </span>

      <div class="dataset-name-block" @click.stop>
        <el-input
          v-if="editing"
          ref="nameInput"
          v-model="draftName"
          class="name-input"
          size="large"
          maxlength="255"
          @click.stop
          @keyup.enter="handleNameEnter"
          @keyup.esc="handleNameEscape($event, cancelName)"
          @blur="commitName"
        />
        <button v-else type="button" class="name-link" :title="item.name" @click="toggle">
          {{ item.name }}
        </button>
        <span class="dataset-subline">
          <span class="dataset-id">{{ shortId }}…</span>
          <span v-if="item.description" class="dataset-description" :title="item.description">
            {{ item.description }}
          </span>
        </span>
      </div>

      <div class="summary-metrics">
        <span class="metric-chip">
          <el-icon><Files /></el-icon>
          {{ item.fileCount }} 个文件
        </span>
        <span class="metric-chip">
          <el-icon><Grid /></el-icon>
          {{ item.tableCount }} 张表
        </span>
        <span class="metric-chip is-muted">{{ formatNumber(item.rowCount) }} 行</span>
      </div>

      <el-tag v-if="format" class="format-tag" type="info" effect="plain" size="small">
        {{ format }}
      </el-tag>
      <el-tag class="status-tag" :type="statusTagType(item.status)" size="small" effect="light">
        {{ statusLabel(item.status) }}
      </el-tag>

      <div class="row-actions" @click.stop>
        <el-button link type="primary" :icon="Collection" @click="emit('extract-fields', item)">
          提取字段
        </el-button>
        <el-button
          v-if="item.status === 'UPLOADED' || item.status === 'PROCESSING'"
          link
          type="success"
          :icon="CircleCheck"
          :loading="readying"
          @click="emit('ready', item.id)"
        >
          发布
        </el-button>
        <el-button link type="primary" :icon="EditPen" @click="startEdit">改名</el-button>
        <el-button link type="primary" :icon="Document" @click="toggle">
          {{ expanded ? '收起' : '详情' }}
        </el-button>
        <el-button
          link
          type="danger"
          :icon="Delete"
          class="danger-action"
          @click="requestDelete"
        >
          删除
        </el-button>
      </div>
    </div>

    <transition name="accordion">
      <section v-if="expanded" class="dataset-expanded" @click.stop>
        <div v-loading="detailLoading" class="expanded-inner">
          <el-alert
            v-if="detailError"
            class="detail-error"
            type="error"
            :closable="false"
            show-icon
            :title="detailError"
          >
            <template #default>
              <el-button link type="primary" @click="emit('retry-detail', item.id)">重新加载</el-button>
            </template>
          </el-alert>

          <div v-else-if="!detail && detailLoading" class="detail-skeleton">
            <el-skeleton :rows="4" animated />
          </div>

          <template v-else-if="detail">
            <div class="detail-meta">
              <span>
                <el-icon><Clock /></el-icon>
                创建于 {{ formatDateTime(detail.createdAt) }}
              </span>
              <span>更新于 {{ formatDateTime(detail.updatedAt) }}</span>
              <span v-if="detail.files?.length">{{ detail.files.length }} 个源文件</span>
              <el-tag
                v-for="file in detail.files"
                :key="file.id"
                :title="file.originalFilename"
                type="info"
                effect="plain"
                size="small"
                class="file-chip"
              >
                {{ file.originalFilename }}
              </el-tag>
            </div>

            <DatasetUploader
              v-if="tables.length === 0"
              :uploading="uploading"
              :progress="uploadProgress"
              @select="selectFile"
              @cancel="emit('cancel-upload', item.id)"
            />

            <template v-else>
              <div class="tables-heading">
                <div>
                  <strong>数据表</strong>
                  <span>
                    {{ tableKeyword.trim() ? `匹配 ${filteredTables.length} / ${tables.length} 张` : `共 ${tables.length} 张` }}，
                    来自 {{ detail.files?.length ?? 0 }} 个文件
                  </span>
                </div>
                <div class="tables-heading-actions">
                  <el-input
                    v-model="tableKeyword"
                    class="table-metadata-search"
                    clearable
                    :prefix-icon="Search"
                    placeholder="搜索表名或源文件"
                    aria-label="搜索数据表名或源文件"
                  />
                  <span class="tables-total">点击任意表可展开预览</span>
                </div>
              </div>

              <div class="table-window" :class="{ 'is-clamped': isClamped }">
                <div class="table-list">
                  <div
                    v-for="table in filteredTables"
                    :key="tableKey(table)"
                    class="table-block"
                    :class="{ 'is-open': isTableExpanded(table) }"
                  >
                    <div
                      class="table-line"
                      role="button"
                      tabindex="0"
                      :aria-expanded="isTableExpanded(table)"
                      @click="handleTableLineClick(table)"
                      @mousedown="handleTableLineMouseDown($event, table)"
                      @keydown="handleTableKeydown($event, table)"
                    >
                      <span class="table-icon"><el-icon><Grid /></el-icon></span>
                      <div class="table-copy">
                        <div class="table-name-row">
                          <strong
                            :title="table.name"
                            :class="{ 'is-editing-hidden': isTableEditing(table) }"
                          >
                            {{ table.name }}
                          </strong>
                          <el-input
                            v-if="isTableEditing(table)"
                            :ref="setTableNameInput"
                            v-model="tableNameDraft"
                            class="table-name-input"
                            maxlength="255"
                            @click.stop
                            @mousedown.stop
                            @keydown.stop
                            @keyup.enter.prevent="handleNameEnter"
                            @keyup.esc.prevent="handleNameEscape($event, cancelTableNameEdit)"
                            @blur="commitTableName(table)"
                          />
                        </div>
                        <small :title="table.fileName">{{ table.fileName }}</small>
                      </div>
                      <el-tag type="info" effect="plain" size="small">{{ table.format }}</el-tag>
                      <span class="table-stat">{{ formatNumber(table.rowCount) }} 行</span>
                      <span class="table-stat">{{ table.columnCount }} 列</span>
                      <span class="table-actions" @click.stop @keydown.stop>
                        <el-tooltip content="重命名数据表" placement="top">
                          <el-button
                            link
                            type="primary"
                            size="small"
                            :icon="EditPen"
                            aria-label="重命名数据表"
                            @click="startTableEdit(table)"
                          />
                        </el-tooltip>
                        <el-tooltip content="删除数据表" placement="top">
                          <el-button
                            link
                            type="danger"
                            size="small"
                            :icon="Delete"
                            aria-label="删除数据表"
                            @click="requestDeleteTable(table)"
                          />
                        </el-tooltip>
                      </span>
                      <span class="table-chevron" :class="{ 'is-open': isTableExpanded(table) }">
                        <el-icon><ArrowRight /></el-icon>
                      </span>
                    </div>

                    <transition name="table-preview">
                      <div v-if="isTableExpanded(table)" class="table-preview">
                        <div
                          v-if="!tablePreviews[tableKey(table)] && tablePreviewLoading[tableKey(table)]"
                          class="table-preview-loading"
                        >
                          <el-skeleton :rows="3" animated />
                        </div>
                        <template v-else-if="tablePreviews[tableKey(table)]">
                          <TableRowSearch
                            :dataset-id="item.id"
                            :table-id="table.id"
                            :columns="tablePreviews[tableKey(table)].columns"
                            @active-change="tableSearchActive[tableKey(table)] = $event"
                          />
                          <div v-if="!tableSearchActive[tableKey(table)]">
                          <el-alert
                            v-if="
                              tablePreviewErrors[tableKey(table)] &&
                              !expandedAllTables[allRowsKey(table)]
                            "
                            class="table-preview-error"
                            type="error"
                            :closable="false"
                            show-icon
                            :title="tablePreviewErrors[tableKey(table)]"
                          >
                            <template #default>
                              <el-button link type="primary" @click="retryTablePreview(table)">
                                重新加载
                              </el-button>
                            </template>
                          </el-alert>
                          <div class="table-preview-head">
                            <span v-if="expandedAllTables[allRowsKey(table)]">
                              <template v-if="isAllRowsInitialLoading(table)">正在加载行…</template>
                              <template v-else>
                                已加载
                                {{ formatNumber(tableAllRows[allRowsKey(table)]?.data.length ?? 0) }}
                                / {{ formatNumber(tablePreviews[tableKey(table)].totalRows) }} 行
                              </template>
                            </span>
                            <span v-else>
                              前 {{ tablePreviews[tableKey(table)].rows.length }} 行 ·
                              后 {{ tablePreviews[tableKey(table)].tailRows.length }} 行
                            </span>
                            <span class="preview-head-actions">
                              <span>
                                共 {{ formatNumber(tablePreviews[tableKey(table)].totalRows) }} 行 ·
                                {{ tablePreviews[tableKey(table)].columns.length }} 列
                              </span>
                              <el-button
                                v-if="expandedAllTables[allRowsKey(table)]"
                                link
                                type="primary"
                                size="small"
                                @click="toggleAllRows(table)"
                              >
                                收起
                              </el-button>
                            </span>
                          </div>

                          <div
                            v-if="expandedAllTables[allRowsKey(table)]"
                            class="table-preview-virtual"
                          >
                            <el-auto-resizer>
                              <template #default="{ height, width }">
                                <el-table-v2
                                  :columns="tableAllRows[allRowsKey(table)]?.columns ?? []"
                                  :data="tableAllRows[allRowsKey(table)]?.data ?? []"
                                  :width="width"
                                  :height="height"
                                  :row-height="30"
                                  :header-height="30"
                                  row-key="id"
                                  :on-rows-rendered="(params) => handleRowsRendered(table, params)"
                                >
                                  <template #empty>
                                    <el-skeleton
                                      v-if="isAllRowsInitialLoading(table)"
                                      class="table-skeleton"
                                      animated
                                    >
                                      <template #template>
                                        <div
                                          v-for="rowIndex in 12"
                                          :key="`skeleton-${rowIndex}`"
                                          class="table-skeleton-row"
                                        >
                                          <span class="table-skeleton-cell is-index">
                                            <el-skeleton-item variant="text" />
                                          </span>
                                          <span
                                            v-for="(column, columnIndex) in tablePreviews[tableKey(table)].columns"
                                            :key="columnIndex"
                                            class="table-skeleton-cell"
                                          >
                                            <el-skeleton-item variant="text" />
                                          </span>
                                        </div>
                                      </template>
                                    </el-skeleton>
                                  </template>
                                </el-table-v2>
                              </template>
                            </el-auto-resizer>
                          </div>
                          <div
                            v-if="expandedAllTables[allRowsKey(table)]"
                            class="preview-all-status"
                          >
                            <template v-if="tableAllRows[allRowsKey(table)]?.loading">
                              {{
                                isAllRowsInitialLoading(table)
                                  ? '正在加载数据…'
                                  : '正在加载更多行…'
                              }}
                            </template>
                            <template v-else-if="tableAllRows[allRowsKey(table)]?.failed">
                              加载失败
                              <el-button
                                link
                                type="primary"
                                size="small"
                                @click="retryTablePreview(table)"
                              >
                                重试
                              </el-button>
                            </template>
                            <template v-else-if="tableAllRows[allRowsKey(table)]?.done">
                              已加载全部
                              {{ formatNumber(tableAllRows[allRowsKey(table)]?.data.length ?? 0) }} 行
                            </template>
                            <template v-else>
                              已加载
                              {{ formatNumber(tableAllRows[allRowsKey(table)]?.data.length ?? 0) }}
                              行，滚动到底部继续加载
                            </template>
                          </div>

                          <div v-else class="table-preview-scroll">
                            <table class="table-preview-grid">
                              <thead>
                                <tr>
                                  <th class="preview-index">#</th>
                                  <th
                                    v-for="(column, columnIndex) in tablePreviews[tableKey(table)].columns"
                                    :key="columnIndex"
                                  >
                                    <span
                                      class="table-preview-value is-heading"
                                      :title="column || `列 ${columnIndex + 1}`"
                                    >
                                      {{ column || `列 ${columnIndex + 1}` }}
                                    </span>
                                  </th>
                                </tr>
                              </thead>
                              <tbody>
                                <tr
                                  v-for="(row, rowIndex) in tablePreviews[tableKey(table)].rows"
                                  :key="`head-${rowIndex}`"
                                >
                                  <td class="preview-index">{{ rowIndex + 1 }}</td>
                                  <td
                                    v-for="(column, columnIndex) in tablePreviews[tableKey(table)].columns"
                                    :key="columnIndex"
                                  >
                                    <span
                                      class="table-preview-value"
                                      :title="String(row[columnIndex] ?? '')"
                                    >
                                      {{ row[columnIndex] ?? '' }}
                                    </span>
                                  </td>
                                </tr>
                                <tr
                                  v-if="tablePreviews[tableKey(table)].tailRows.length"
                                  class="preview-gap-row"
                                >
                                  <td :colspan="tablePreviews[tableKey(table)].columns.length + 1">
                                    <div
                                      class="preview-gap-action"
                                      role="button"
                                      tabindex="0"
                                      @click.stop="toggleAllRows(table)"
                                      @keydown.stop="handlePreviewGapKeydown($event, table)"
                                    >
                                      <span class="preview-gap-label">展开全部</span>
                                      <span class="preview-gap-hint">
                                        中间省略
                                        {{ formatNumber(hiddenRowCount(tablePreviews[tableKey(table)])) }}
                                        行
                                      </span>
                                    </div>
                                  </td>
                                </tr>
                                <tr
                                  v-for="(row, rowIndex) in tablePreviews[tableKey(table)].tailRows"
                                  :key="`tail-${rowIndex}`"
                                >
                                  <td class="preview-index">
                                    {{ formatNumber(tailRowNumber(tablePreviews[tableKey(table)], rowIndex)) }}
                                  </td>
                                  <td
                                    v-for="(column, columnIndex) in tablePreviews[tableKey(table)].columns"
                                    :key="columnIndex"
                                  >
                                    <span
                                      class="table-preview-value"
                                      :title="String(row[columnIndex] ?? '')"
                                    >
                                      {{ row[columnIndex] ?? '' }}
                                    </span>
                                  </td>
                                </tr>
                              </tbody>
                            </table>
                            <div
                              v-if="tablePreviews[tableKey(table)].rows.length === 0"
                              class="preview-empty"
                            >
                              这张表只有表头，没有数据行
                            </div>
                          </div>
                          </div>
                        </template>
                      </div>
                    </transition>
                  </div>
                </div>
                <div v-if="tableKeyword && filteredTables.length === 0" class="table-filter-empty">
                  没有找到匹配的数据表
                </div>
                <div v-if="isClamped" class="table-fade"></div>
                <div
                  v-if="isClamped || showAllTables"
                  class="table-more"
                  :class="{ 'is-overlay': isClamped }"
                >
                  <el-button link type="primary" @click="showAllTables = !showAllTables">
                    {{ showAllTables ? '收起' : `展开全部 ${tables.length} 张表` }}
                  </el-button>
                </div>
              </div>

              <DatasetUploader
                compact
                :uploading="uploading"
                :progress="uploadProgress"
                @select="selectFile"
                @cancel="emit('cancel-upload', item.id)"
              />
            </template>
          </template>
        </div>
      </section>
    </transition>
  </article>
</template>
