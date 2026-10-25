<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { FolderAdd, Refresh, Search } from '@element-plus/icons-vue'
import AppSidebar from '@/components/AppSidebar.vue'
import DatasetRow from '@/components/DatasetRow.vue'
import liquidGlassBackdrop from '@/assets/videos/liquid-wave.mp4'
import {
  addDatasetFile,
  createDataset,
  deleteDataset,
  deleteDatasetTable,
  getDataset,
  listDatasets,
  renameDataset,
  renameDatasetTable,
} from '@/api/datasets'

const MAX_FILE_SIZE = 1024 * 1024 * 1024
const MAX_XLSX_SIZE = 100 * 1024 * 1024

const sectionMeta = {
  datasets: {
    title: '数据集库',
    description: '管理原始数据文件，自动识别 CSV、TSV、ZIP 和 Excel 中的数据表。',
  },
  fields: {
    title: '字段库',
    description: '统一维护数据字段的定义、类型与业务含义。',
    emptyText: '字段库入口已就绪，等待接入字段数据',
  },
  rules: {
    title: '规则库',
    description: '集中管理数据校验、清洗和建模规则。',
    emptyText: '规则库入口已就绪，等待接入规则数据',
  },
}

const sidebarCollapsed = ref(false)
const activeSection = ref('datasets')
const datasets = ref([])
const loading = ref(false)
const creating = ref(false)
const page = ref(0)
const size = ref(10)
const totalElements = ref(0)
const activeTab = ref('all')
const expandedIds = ref([])
const editingId = ref('')
const uploadingId = ref('')

const filters = reactive({
  keyword: '',
  format: '',
})

const details = reactive({})
const detailLoading = reactive({})
const detailErrors = reactive({})
const uploadProgress = reactive({})

const currentSection = computed(
  () => sectionMeta[activeSection.value] || sectionMeta.datasets,
)

const hasDataFilter = computed(() => {
  if (activeTab.value === 'hasData') {
    return true
  }
  if (activeTab.value === 'empty') {
    return false
  }
  return undefined
})

onMounted(() => {
  loadDatasets()
})

async function loadDatasets() {
  loading.value = true
  try {
    const result = await listDatasets({
      keyword: filters.keyword,
      format: filters.format,
      hasData: hasDataFilter.value,
      page: page.value,
      size: size.value,
    })
    datasets.value = result?.items ?? []
    totalElements.value = result?.totalElements ?? 0
    if (typeof result?.page === 'number') {
      page.value = result.page
    }
    if (typeof result?.size === 'number') {
      size.value = result.size
    }
  } catch (error) {
    datasets.value = []
    totalElements.value = 0
    ElMessage.error(error.message)
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 0
  loadDatasets()
}

function resetFilters() {
  filters.keyword = ''
  filters.format = ''
  activeTab.value = 'all'
  page.value = 0
  loadDatasets()
}

function handleTabChange() {
  page.value = 0
  loadDatasets()
}

function changePage(nextPage) {
  page.value = nextPage
  loadDatasets()
}

function changeSize(nextSize) {
  size.value = nextSize
  page.value = 0
  loadDatasets()
}

async function handleCreate() {
  if (creating.value) {
    return
  }
  creating.value = true
  try {
    const created = await createDataset()
    const outsideFirstUnfilteredView =
      page.value !== 0 ||
      Boolean(filters.keyword.trim()) ||
      Boolean(filters.format) ||
      activeTab.value !== 'all'

    if (outsideFirstUnfilteredView) {
      filters.keyword = ''
      filters.format = ''
      activeTab.value = 'all'
      page.value = 0
      await loadDatasets()
    } else {
      datasets.value = [created, ...datasets.value].slice(0, size.value)
      totalElements.value += 1
    }

    editingId.value = created.id
    ElMessage.success(`已创建「${created.name}」，可以直接修改名称`)
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    creating.value = false
  }
}

function isExpanded(id) {
  return expandedIds.value.includes(id)
}

async function toggleDataset(id) {
  if (isExpanded(id)) {
    expandedIds.value = expandedIds.value.filter((value) => value !== id)
    return
  }
  expandedIds.value = [...expandedIds.value, id]
  if (!details[id] && !detailLoading[id]) {
    await loadDetail(id)
  }
}

async function loadDetail(id, force = false) {
  if (detailLoading[id] || (details[id] && !force)) {
    return
  }
  detailLoading[id] = true
  detailErrors[id] = ''
  try {
    const detail = await getDataset(id)
    details[id] = detail
    syncRowFromDetail(id, detail)
  } catch (error) {
    detailErrors[id] = error.message
  } finally {
    detailLoading[id] = false
  }
}

function startEdit(id) {
  editingId.value = id
}

function cancelEdit() {
  editingId.value = ''
}

async function saveName({ id, name }) {
  const row = datasets.value.find((item) => item.id === id)
  if (!row || name === row.name) {
    editingId.value = ''
    return
  }

  const previousName = row.name
  editingId.value = ''
  row.name = name
  try {
    const updated = await renameDataset(id, { name, description: row.description })
    syncRowFromDetail(id, updated)
    if (details[id]) {
      details[id] = {
        ...details[id],
        name: updated.name,
        description: updated.description,
        updatedAt: updated.updatedAt,
      }
    }
    ElMessage.success('名称已更新')
  } catch (error) {
    row.name = previousName
    ElMessage.error(error.message)
  }
}

async function handleDeleteDataset(id) {
  const row = datasets.value.find((item) => item.id === id)
  if (!row) {
    return
  }
  try {
    await ElMessageBox.confirm(
      `删除「${row.name}」后，其中的文件和所有数据表都会一并删除，且无法恢复。`,
      '删除数据集',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger',
      },
    )
  } catch {
    return
  }

  try {
    await deleteDataset(id)
    datasets.value = datasets.value.filter((item) => item.id !== id)
    totalElements.value = Math.max(totalElements.value - 1, 0)
    expandedIds.value = expandedIds.value.filter((value) => value !== id)
    delete details[id]
    delete detailErrors[id]
    if (editingId.value === id) {
      editingId.value = ''
    }
    ElMessage.success('数据集已删除')
    if (datasets.value.length === 0 && page.value > 0) {
      page.value -= 1
      await loadDatasets()
    }
  } catch (error) {
    ElMessage.error(error.message)
  }
}

async function handleRenameTable({ datasetId, tableId, name }) {
  if (!name) {
    return
  }
  const current = details[datasetId]
  const table = current?.tables?.find((item) => item.id === tableId)
  if (table && table.name === name) {
    return
  }
  const previousName = table?.name
  if (table) {
    table.name = name
  }

  try {
    const detail = await renameDatasetTable(datasetId, tableId, name)
    const latest = details[datasetId]
    if (latest) {
      latest.tables = detail.tables
      latest.updatedAt = detail.updatedAt
    } else {
      details[datasetId] = detail
    }
    detailErrors[datasetId] = ''
    syncRowFromDetail(datasetId, detail)
    ElMessage.success('数据表名称已更新')
  } catch (error) {
    if (table && previousName !== undefined) {
      table.name = previousName
    }
    ElMessage.error(error.message)
  }
}

async function handleDeleteTable({ datasetId, tableId, name }) {
  try {
    await ElMessageBox.confirm(
      `删除数据表「${name}」？如果它来自压缩包中的独立文件，对应源文件也会一并清理。`,
      '删除数据表',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger',
      },
    )
  } catch {
    return
  }

  try {
    const detail = await deleteDatasetTable(datasetId, tableId)
    const current = details[datasetId]
    if (current) {
      Object.assign(current, detail)
    } else {
      details[datasetId] = detail
    }
    detailErrors[datasetId] = ''
    syncRowFromDetail(datasetId, detail)
    ElMessage.success('数据表已删除')
  } catch (error) {
    ElMessage.error(error.message)
  }
}

function validateFile(file) {
  if (!/\.(csv|tsv|zip|xlsx)$/i.test(file.name)) {
    return '仅支持 CSV、TSV、ZIP、XLSX 文件'
  }
  if (file.size === 0) {
    return '不能上传空文件'
  }
  if (file.size > MAX_FILE_SIZE) {
    return '单个文件不能超过 1 GB'
  }
  if (/\.xlsx$/i.test(file.name) && file.size > MAX_XLSX_SIZE) {
    return 'XLSX 文件不能超过 100 MB'
  }
  return ''
}

async function handleUpload({ id, file }) {
  const validationError = validateFile(file)
  if (validationError) {
    ElMessage.warning(validationError)
    return
  }
  if (uploadingId.value) {
    ElMessage.warning('请等待当前文件上传完成')
    return
  }

  uploadingId.value = id
  uploadProgress[id] = 0
  try {
    const detail = await addDatasetFile(id, {
      file,
      onProgress: (value) => {
        uploadProgress[id] = value
      },
    })
    const current = details[id]
    if (current) {
      Object.assign(current, detail)
    } else {
      details[id] = detail
    }
    detailErrors[id] = ''
    syncRowFromDetail(id, detail)
    if (!isExpanded(id)) {
      expandedIds.value = [...expandedIds.value, id]
    }
    ElMessage.success(`已上传 ${file.name}`)
  } catch (error) {
    ElMessage.error(error.message)
  } finally {
    uploadingId.value = ''
    window.setTimeout(() => {
      uploadProgress[id] = 0
    }, 600)
  }
}

function syncRowFromDetail(id, detail) {
  const row = datasets.value.find((item) => item.id === id)
  if (!row) {
    return
  }
  Object.assign(row, {
    name: detail.name,
    description: detail.description,
    originalFilename: detail.originalFilename,
    format: detail.format,
    sizeBytes: detail.sizeBytes,
    sha256: detail.sha256,
    contentType: detail.contentType,
    status: detail.status,
    tableCount: detail.tableCount,
    rowCount: detail.rowCount,
    fileCount: detail.fileCount,
    createdAt: detail.createdAt,
    updatedAt: detail.updatedAt,
  })
}
</script>

<template>
  <div class="app-frame">
    <div class="app-backdrop" aria-hidden="true">
      <video
        class="app-backdrop-media"
        :src="liquidGlassBackdrop"
        autoplay
        muted
        loop
        playsinline
        draggable="false"
      ></video>
      <span class="app-backdrop-wash"></span>
    </div>

    <AppSidebar
      v-model:collapsed="sidebarCollapsed"
      v-model:active="activeSection"
      :total="totalElements"
    />

    <main class="app-main">
      <div class="page-content">
        <template v-if="activeSection === 'datasets'">
        <el-breadcrumb separator="/" class="page-breadcrumb">
          <el-breadcrumb-item>工作台</el-breadcrumb-item>
          <el-breadcrumb-item>数据集库</el-breadcrumb-item>
        </el-breadcrumb>

        <header class="page-header">
          <div>
            <h1>数据集库</h1>
            <p>管理原始数据文件，自动识别 CSV、TSV、ZIP 和 Excel 中的数据表。</p>
          </div>
          <div class="header-actions">
            <el-button
              type="primary"
              size="large"
              :icon="FolderAdd"
              :loading="creating"
              @click="handleCreate"
            >
              新建数据集
            </el-button>
          </div>
        </header>

        <el-card class="toolbar-card" shadow="never">
          <div class="library-toolbar">
            <el-input
              v-model="filters.keyword"
              class="search-input"
              size="large"
              placeholder="搜索数据集名称或文件名"
              clearable
              :prefix-icon="Search"
              @keyup.enter="search"
              @clear="search"
            />
            <el-select
              v-model="filters.format"
              class="format-select"
              size="large"
              placeholder="全部格式"
              clearable
              @change="search"
            >
              <el-option label="CSV" value="CSV" />
              <el-option label="TSV" value="TSV" />
              <el-option label="ZIP" value="ZIP" />
              <el-option label="XLSX" value="XLSX" />
            </el-select>
            <el-button size="large" :icon="Search" @click="search">搜索</el-button>
            <el-button size="large" :icon="Refresh" @click="resetFilters">重置</el-button>
            <span class="toolbar-spacer"></span>
            <span class="toolbar-count">当前共 <strong>{{ totalElements }}</strong> 个数据集</span>
          </div>
        </el-card>

        <el-card class="list-card" shadow="never">
          <template #header>
            <div class="list-header">
              <el-tabs v-model="activeTab" class="library-tabs" @tab-change="handleTabChange">
                <el-tab-pane label="全部" name="all" />
                <el-tab-pane label="有数据" name="hasData" />
                <el-tab-pane label="空数据集" name="empty" />
              </el-tabs>
              <span class="list-hint">点击数据集展开，数据表也可以继续展开预览</span>
            </div>
          </template>

          <div v-loading="loading" class="dataset-list">
            <DatasetRow
              v-for="item in datasets"
              :key="item.id"
              :item="item"
              :expanded="isExpanded(item.id)"
              :editing="editingId === item.id"
              :detail="details[item.id]"
              :detail-loading="Boolean(detailLoading[item.id])"
              :detail-error="detailErrors[item.id] || ''"
              :uploading="uploadingId === item.id"
              :upload-progress="uploadProgress[item.id] || 0"
              @toggle="toggleDataset"
              @start-edit="startEdit"
              @cancel-edit="cancelEdit"
              @save-name="saveName"
              @upload="handleUpload"
              @delete="handleDeleteDataset"
              @rename-table="handleRenameTable"
              @delete-table="handleDeleteTable"
              @retry-detail="loadDetail($event, true)"
            />

            <el-empty
              v-if="!loading && datasets.length === 0"
              class="empty-state"
              :image-size="104"
              description="这里还没有数据集"
            >
              <el-button type="primary" :icon="FolderAdd" @click="handleCreate">
                新建第一个数据集
              </el-button>
            </el-empty>

            <div v-if="totalElements > 0" class="pagination-row">
              <span class="pagination-hint">每页 {{ size }} 条，支持 CSV / TSV / ZIP / XLSX</span>
              <el-pagination
                :current-page="page + 1"
                :page-size="size"
                :page-sizes="[10, 20, 50]"
                :total="totalElements"
                layout="sizes, prev, pager, next, jumper"
                background
                @current-change="(value) => changePage(value - 1)"
                @size-change="changeSize"
              />
            </div>
          </div>
        </el-card>
        </template>

        <template v-else>
          <el-breadcrumb separator="/" class="page-breadcrumb">
            <el-breadcrumb-item>工作台</el-breadcrumb-item>
            <el-breadcrumb-item>{{ currentSection.title }}</el-breadcrumb-item>
          </el-breadcrumb>

          <header class="page-header">
            <div>
              <h1>{{ currentSection.title }}</h1>
              <p>{{ currentSection.description }}</p>
            </div>
          </header>

          <el-card class="list-card section-placeholder-card" shadow="never">
            <el-empty
              :image-size="112"
              :description="currentSection.emptyText"
            />
          </el-card>
        </template>
      </div>
    </main>
  </div>
</template>
