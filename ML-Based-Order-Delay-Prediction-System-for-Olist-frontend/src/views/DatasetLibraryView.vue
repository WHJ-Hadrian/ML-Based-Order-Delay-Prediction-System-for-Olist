<script setup>
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { FolderAdd, Lock, Refresh, Search } from '@element-plus/icons-vue'
import {
  AuthenticationRequiredError,
  useAuth,
} from '@/auth/keycloak'
import DatasetRow from '@/components/DatasetRow.vue'
import {
  DATASET_FORMATS,
  DATASET_LIMITS,
  DATASET_TAB_KEYS,
  DATASET_TABS,
} from '@/config/dataset'
import { findNavigationItem } from '@/config/navigation'
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
import { useDatasetTotal } from '@/state/workspace'

const route = useRoute()
const router = useRouter()
const auth = useAuth()
const section = computed(() => findNavigationItem(route.meta.navigationKey))
const authReady = computed(() => auth.state.status !== 'checking')
const datasets = ref([])
const loading = ref(false)
const loadError = ref('')
const authRequired = ref(false)
const creating = ref(false)
const page = ref(0)
const size = ref(10)
const totalElements = useDatasetTotal()
const activeTab = ref(DATASET_TAB_KEYS.ALL)
const expandedIds = ref([])
const editingId = ref('')
const uploadingId = ref('')
const uploadController = ref(null)

const filters = reactive({
  keyword: '',
  format: '',
})

const details = reactive({})
const detailLoading = reactive({})
const detailErrors = reactive({})
const uploadProgress = reactive({})

let datasetRequestSequence = 0

const hasDataFilter = computed(() => {
  if (activeTab.value === DATASET_TAB_KEYS.HAS_DATA) {
    return true
  }
  if (activeTab.value === DATASET_TAB_KEYS.EMPTY) {
    return false
  }
  return undefined
})

watch(
  () => auth.state.status,
  (status) => {
    if (status === 'authenticated') {
      authRequired.value = false
      loadDatasets()
      return
    }
    if (status === 'anonymous') {
      authRequired.value = true
      datasets.value = []
      totalElements.value = 0
    }
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  datasetRequestSequence += 1
  uploadController.value?.abort()
})

async function loadDatasets() {
  const requestSequence = ++datasetRequestSequence
  loading.value = true
  loadError.value = ''
  try {
    const result = await listDatasets({
      keyword: filters.keyword,
      format: filters.format,
      hasData: hasDataFilter.value,
      page: page.value,
      size: size.value,
    })
    if (requestSequence !== datasetRequestSequence) {
      return
    }
    datasets.value = result?.items ?? []
    totalElements.value = result?.totalElements ?? 0
    if (typeof result?.page === 'number') {
      page.value = result.page
    }
    if (typeof result?.size === 'number') {
      size.value = result.size
    }
  } catch (error) {
    if (requestSequence !== datasetRequestSequence) {
      return
    }
    if (error instanceof AuthenticationRequiredError) {
      authRequired.value = true
      return
    }
    loadError.value = error?.message || '加载数据集失败'
  } finally {
    if (requestSequence === datasetRequestSequence) {
      loading.value = false
    }
  }
}

function search() {
  page.value = 0
  loadDatasets()
}

function resetFilters() {
  filters.keyword = ''
  filters.format = ''
  activeTab.value = DATASET_TAB_KEYS.ALL
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

function handleSignIn() {
  router.push({ name: 'login' })
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
      activeTab.value !== DATASET_TAB_KEYS.ALL

    if (outsideFirstUnfilteredView) {
      filters.keyword = ''
      filters.format = ''
      activeTab.value = DATASET_TAB_KEYS.ALL
      page.value = 0
      await loadDatasets()
    } else {
      datasets.value = [created, ...datasets.value].slice(0, size.value)
      totalElements.value += 1
    }

    editingId.value = created.id
    ElMessage.success(`已创建「${created.name}」，可以直接修改名称`)
  } catch (error) {
    ElMessage.error(error?.message || '创建数据集失败')
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
  if (file.size > DATASET_LIMITS.maxFileSize) {
    return '单个文件不能超过 1 GB'
  }
  if (/\.xlsx$/i.test(file.name) && file.size > DATASET_LIMITS.maxXlsxSize) {
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
  const controller = new AbortController()
  uploadController.value = controller
  uploadProgress[id] = 0
  try {
    const detail = await addDatasetFile(id, {
      file,
      signal: controller.signal,
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
    if (error?.name === 'AbortError') {
      ElMessage.info('上传已取消')
    } else {
      ElMessage.error(error.message)
    }
  } finally {
    if (uploadController.value === controller) {
      uploadController.value = null
    }
    uploadingId.value = ''
    window.setTimeout(() => {
      uploadProgress[id] = 0
    }, 600)
  }
}

function cancelUpload(id) {
  if (uploadingId.value === id) {
    uploadController.value?.abort()
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
  <div class="page-content">
        <el-breadcrumb separator="/" class="page-breadcrumb">
          <el-breadcrumb-item>工作台</el-breadcrumb-item>
          <el-breadcrumb-item>{{ section.title }}</el-breadcrumb-item>
        </el-breadcrumb>

        <header class="page-header">
          <div>
            <h1>{{ section.title }}</h1>
            <p>{{ section.description }}</p>
          </div>
          <div class="header-actions">
            <el-button
              v-if="auth.state.authenticated"
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

        <el-card v-if="!authReady || authRequired" class="auth-required-card" shadow="never">
          <div class="auth-required-content">
            <span class="auth-required-icon"><el-icon><Lock /></el-icon></span>
            <template v-if="authReady">
              <h2>登录后查看你的数据集</h2>
              <p>登录后可安全访问和管理属于你的数据资产。</p>
              <el-button type="primary" size="large" @click="handleSignIn">
                登录或创建账号
              </el-button>
            </template>
            <template v-else>
              <h2>正在确认账户状态</h2>
              <el-skeleton animated :rows="2" />
            </template>
          </div>
        </el-card>

        <template v-else>
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
              <el-option
                v-for="format in DATASET_FORMATS"
                :key="format.value"
                :label="format.label"
                :value="format.value"
              />
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
              <el-tab-pane
                v-for="tab in DATASET_TABS"
                :key="tab.name"
                :label="tab.label"
                :name="tab.name"
              />
              </el-tabs>
              <span class="list-hint">点击数据集展开，数据表也可以继续展开预览</span>
            </div>
          </template>

          <div v-loading="loading" class="dataset-list">
            <el-alert
              v-if="loadError"
              class="list-load-error"
              type="error"
              :closable="false"
              show-icon
              :title="loadError"
            >
              <template #default>
                <el-button link type="primary" @click="loadDatasets">重试</el-button>
              </template>
            </el-alert>

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
              @cancel-upload="cancelUpload"
              @delete="handleDeleteDataset"
              @rename-table="handleRenameTable"
              @delete-table="handleDeleteTable"
              @retry-detail="loadDetail($event, true)"
            />

            <el-empty
              v-if="!loading && !loadError && datasets.length === 0"
              class="empty-state"
              :image-size="104"
              :description="section.emptyText"
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
  </div>
</template>

<style scoped>
.auth-required-card {
  min-height: 340px;
}

.auth-required-content {
  display: flex;
  min-height: 290px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 28px 18px;
  text-align: center;
}

.auth-required-icon {
  display: grid;
  width: 54px;
  height: 54px;
  margin-bottom: 18px;
  place-items: center;
  color: #176b57;
  font-size: 23px;
  background: rgba(219, 241, 233, 0.9);
  border-radius: 16px;
}

.auth-required-content h2 {
  margin: 0;
  color: #192521;
  font-size: 19px;
  font-weight: 650;
}

.auth-required-content p {
  margin: 9px 0 22px;
  color: #68766f;
  font-size: 13px;
}
</style>
