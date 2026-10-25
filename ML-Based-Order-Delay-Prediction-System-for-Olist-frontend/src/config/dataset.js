export const DATASET_LIMITS = Object.freeze({
  maxFileSize: 1024 * 1024 * 1024,
  maxXlsxSize: 100 * 1024 * 1024,
})

export const DATASET_FORMATS = Object.freeze([
  { label: 'CSV', value: 'CSV' },
  { label: 'TSV', value: 'TSV' },
  { label: 'ZIP', value: 'ZIP' },
  { label: 'XLSX', value: 'XLSX' },
])

export const DATASET_TAB_KEYS = Object.freeze({
  ALL: 'all',
  HAS_DATA: 'hasData',
  EMPTY: 'empty',
})

export const DATASET_TABS = Object.freeze([
  { label: '全部', name: DATASET_TAB_KEYS.ALL },
  { label: '有数据', name: DATASET_TAB_KEYS.HAS_DATA },
  { label: '空数据集', name: DATASET_TAB_KEYS.EMPTY },
])
