<script setup>
import { computed, ref, watch } from 'vue'
import { ArrowLeft, ArrowRight, Search } from '@element-plus/icons-vue'
import { searchDatasetTableRows } from '@/api/datasets'

const PAGE_SIZE = 50

const props = defineProps({
  datasetId: { type: String, required: true },
  tableId: { type: String, required: true },
  columns: { type: Array, default: () => [] },
})

const emit = defineEmits(['active-change'])
const keyword = ref('')
const columnIndex = ref(-1)
const offset = ref(0)
const results = ref(null)
const loading = ref(false)
const error = ref('')
let requestSequence = 0

const active = computed(() => Boolean(results.value))
const canGoBack = computed(() => offset.value > 0)
const canGoForward = computed(() => Boolean(results.value)
  && offset.value + results.value.rows.length < results.value.totalMatches)
const rowColumns = computed(() => results.value?.columns || props.columns)

watch(() => props.tableId, () => clearSearch())

function clearSearch() {
  requestSequence += 1
  keyword.value = ''
  columnIndex.value = -1
  offset.value = 0
  results.value = null
  error.value = ''
  loading.value = false
  emit('active-change', false)
}

async function runSearch(nextOffset = 0) {
  const searchTerm = keyword.value.trim()
  if (!searchTerm) return

  const sequence = ++requestSequence
  loading.value = true
  error.value = ''
  try {
    const response = await searchDatasetTableRows(props.datasetId, props.tableId, {
      keyword: searchTerm,
      column: columnIndex.value,
      offset: nextOffset,
      limit: PAGE_SIZE,
    })
    if (sequence !== requestSequence) return
    results.value = response
    offset.value = nextOffset
    emit('active-change', true)
  } catch (requestError) {
    if (sequence !== requestSequence) return
    error.value = requestError.message || '搜索失败，请重试。'
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

function submitSearch() {
  offset.value = 0
  if (!keyword.value.trim()) {
    clearSearch()
    return
  }
  void runSearch(0)
}

function changeColumn(value) {
  columnIndex.value = value
  if (active.value) submitSearch()
}

function previousPage() {
  if (canGoBack.value) void runSearch(Math.max(0, offset.value - PAGE_SIZE))
}

function nextPage() {
  if (canGoForward.value) void runSearch(offset.value + PAGE_SIZE)
}
</script>

<template>
  <section class="table-row-search" aria-label="搜索表格数据">
    <div class="table-row-search-controls">
      <el-input
        v-model="keyword"
        class="table-row-search-input"
        clearable
        maxlength="128"
        placeholder="搜索表格数据"
        @keyup.enter="submitSearch"
        @clear="clearSearch"
      />
      <el-select v-model="columnIndex" class="table-row-search-column" @change="changeColumn">
        <el-option label="所有列" :value="-1" />
        <el-option
          v-for="(column, index) in columns"
          :key="`${index}-${column}`"
          :label="column || `列 ${index + 1}`"
          :value="index"
        >
          <span class="table-row-search-column-label" :title="column || `列 ${index + 1}`">
            {{ column || `列 ${index + 1}` }}
          </span>
        </el-option>
      </el-select>
      <el-button
        type="primary"
        :icon="Search"
        :loading="loading"
        :disabled="!keyword.trim()"
        @click="submitSearch"
      >搜索</el-button>
    </div>

    <p v-if="error" class="table-row-search-error" role="alert">{{ error }}</p>

    <template v-if="results">
      <div class="table-row-search-summary">
        <span>
          <template v-if="results.truncated">
            已扫描 {{ results.scannedRows.toLocaleString() }} 行，命中 {{ results.totalMatches.toLocaleString() }} 行（部分结果）
          </template>
          <template v-else>
            共命中 {{ results.totalMatches.toLocaleString() }} 行
          </template>
        </span>
        <span v-if="results.rows.length">
          {{ (offset + 1).toLocaleString() }}–{{ (offset + results.rows.length).toLocaleString() }}
        </span>
      </div>
      <div v-if="results.rows.length" class="table-row-search-results">
        <table class="table-preview-grid">
          <thead>
            <tr>
              <th class="preview-index">#</th>
              <th v-for="(column, index) in rowColumns" :key="`${index}-${column}`">
                <span
                  class="table-preview-value is-heading"
                  :title="column || `列 ${index + 1}`"
                >
                  {{ column || `列 ${index + 1}` }}
                </span>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in results.rows" :key="row.rowNumber">
              <td class="preview-index">{{ row.rowNumber.toLocaleString() }}</td>
              <td v-for="(column, index) in rowColumns" :key="`${index}-${column}`">
                <span
                  class="table-preview-value"
                  :title="String(row.values[index] ?? '')"
                >
                  {{ row.values[index] ?? '' }}
                </span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="table-row-search-empty">没有找到匹配的数据行</div>
      <div v-if="results.totalMatches > PAGE_SIZE" class="table-row-search-pagination">
        <el-button
          text
          :icon="ArrowLeft"
          aria-label="上一页搜索结果"
          :disabled="!canGoBack || loading"
          @click="previousPage"
        />
        <span>{{ Math.floor(offset / PAGE_SIZE) + 1 }}</span>
        <el-button
          text
          :icon="ArrowRight"
          aria-label="下一页搜索结果"
          :disabled="!canGoForward || loading"
          @click="nextPage"
        />
      </div>
    </template>
  </section>
</template>
