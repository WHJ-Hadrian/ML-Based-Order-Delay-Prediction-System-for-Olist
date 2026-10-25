export const STATUS_LABELS = {
  EMPTY: '待上传',
  UPLOADED: '已上传',
  PROCESSING: '处理中',
  READY: '可用',
  FAILED: '失败',
}

export const STATUS_TAG_TYPES = {
  EMPTY: 'info',
  UPLOADED: 'success',
  PROCESSING: 'warning',
  READY: 'primary',
  FAILED: 'danger',
}

export function statusLabel(value) {
  return STATUS_LABELS[value] ?? value ?? '未知'
}

export function statusTagType(value) {
  return STATUS_TAG_TYPES[value] ?? 'info'
}
