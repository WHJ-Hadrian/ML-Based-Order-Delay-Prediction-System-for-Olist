export const SOFT_TIPS_STORAGE_KEY = 'seekdateset.soft-tips.v1'

export const DEFAULT_SOFT_TIP_SECONDS = 60
export const MIN_SOFT_TIP_SECONDS = 15
export const MAX_SOFT_TIP_SECONDS = 600

export const SOFT_TIP_DELAY_OPTIONS = Object.freeze([
  { value: 30, label: '30 秒' },
  { value: 45, label: '45 秒' },
  { value: 60, label: '1 分钟' },
  { value: 90, label: '1 分 30 秒' },
  { value: 120, label: '2 分钟' },
  { value: 180, label: '3 分钟' },
  { value: 300, label: '5 分钟' },
])

export const SOFT_TIP_PAGES = Object.freeze([
  {
    key: 'datasets',
    title: '数据集库',
    description: '上传、筛选和展开数据集',
    tips: [
      {
        key: 'datasets-drop',
        title: '文件可以直接拖进来',
        text: '把 CSV、TSV、ZIP 或 Excel 文件拖到上传区，系统会自动识别其中的数据表，不需要先解压。',
      },
      {
        key: 'datasets-search',
        title: '搜索和格式可以一起用',
        text: '搜索框支持按数据集名称或文件名查找，再配合右侧格式筛选，能更快定位目标数据集。',
      },
      {
        key: 'datasets-expand',
        title: '数据集可以逐层展开',
        text: '点击数据集展开数据表，再展开数据表就能查看字段、统计信息和预览内容。',
      },
      {
        key: 'datasets-extract',
        title: '字段可以提取到字段库',
        text: '在数据表里点「提取字段」进入字段库，选择目标字段库后就能复用这些字段。',
      },
    ],
  },
  {
    key: 'fields',
    title: '字段库',
    description: '按字段库、数据集、数据表维护字段',
    tips: [
      {
        key: 'fields-hierarchy',
        title: '字段按三层结构组织',
        text: '字段库按「字段库 → 数据集 → 数据表 → 字段」展开，先找数据集，再看具体字段会更清晰。',
      },
      {
        key: 'fields-paste',
        title: '剪贴板可以批量加字段',
        text: '数据表标题右侧的粘贴图标可以直接读取剪贴板，按行批量补字段，比逐条新增更快。',
      },
      {
        key: 'fields-resize',
        title: '表头列宽可以拖动',
        text: '字段名太长时可以拖动表头调整列宽，鼠标悬停字段名还能查看完整内容。',
      },
      {
        key: 'fields-manual',
        title: '手动字段也有地方放',
        text: '不属于具体数据表的字段可以放在「手动添加 / 未指定数据表」分组里，单独维护。',
      },
    ],
  },
  {
    key: 'rules',
    title: '规则库',
    description: '编辑、校验和发布规则',
    tips: [
      {
        key: 'rules-workbook',
        title: '从数据集开始建规则库',
        text: '先选择一个数据集再新建规则库，规则行就能直接引用这个数据集的表和字段。',
      },
      {
        key: 'rules-draft',
        title: '先保存草稿再校验',
        text: '编辑规则行后可以先点「保存草稿」，确认内容完整，再进行校验和发布。',
      },
      {
        key: 'rules-validate',
        title: '发布前先校验',
        text: '点「校验」会检查规则节点；校验通过后再发布，能减少规则运行失败的情况。',
      },
      {
        key: 'rules-run',
        title: '预览运行看结果',
        text: '「预览运行」可以快速查看规则结果，运行详情会持续刷新，直到 Worker 结束。',
      },
    ],
  },
])

function clampIdleSeconds(value) {
  const parsed = Number(value)
  if (!Number.isFinite(parsed)) {
    return DEFAULT_SOFT_TIP_SECONDS
  }
  return Math.min(MAX_SOFT_TIP_SECONDS, Math.max(MIN_SOFT_TIP_SECONDS, Math.round(parsed)))
}

function normalizeDelay(value) {
  if (value === null || value === undefined || value === '') {
    return null
  }
  const parsed = Number(value)
  if (!Number.isFinite(parsed)) {
    return null
  }
  return Math.min(MAX_SOFT_TIP_SECONDS, Math.max(MIN_SOFT_TIP_SECONDS, Math.round(parsed)))
}

export function createDefaultSoftTipSettings() {
  const pages = {}
  for (const page of SOFT_TIP_PAGES) {
    const tips = {}
    for (const tip of page.tips) {
      tips[tip.key] = { enabled: true, delaySeconds: null }
    }
    pages[page.key] = { enabled: true, tips }
  }
  return {
    enabled: true,
    idleSeconds: DEFAULT_SOFT_TIP_SECONDS,
    pages,
  }
}

export function normalizeSoftTipSettings(raw) {
  const fallback = createDefaultSoftTipSettings()
  if (!raw || typeof raw !== 'object') {
    return fallback
  }
  const pages = {}
  for (const page of SOFT_TIP_PAGES) {
    const sourcePage = raw.pages && typeof raw.pages === 'object' ? raw.pages[page.key] : null
    const tips = {}
    for (const tip of page.tips) {
      const sourceTip = sourcePage?.tips && typeof sourcePage.tips === 'object'
        ? sourcePage.tips[tip.key]
        : null
      tips[tip.key] = {
        enabled: sourceTip?.enabled !== false,
        delaySeconds: normalizeDelay(sourceTip?.delaySeconds),
      }
    }
    pages[page.key] = {
      enabled: sourcePage?.enabled !== false,
      tips,
    }
  }
  return {
    enabled: raw.enabled !== false,
    idleSeconds: clampIdleSeconds(raw.idleSeconds),
    pages,
  }
}

export function cloneSoftTipSettings(settings) {
  return normalizeSoftTipSettings(JSON.parse(JSON.stringify(settings || {})))
}

export function getSoftTipPageDefinition(pageKey) {
  return SOFT_TIP_PAGES.find((page) => page.key === pageKey) || null
}

export function getSoftTipDefinition(pageKey, tipKey) {
  const page = getSoftTipPageDefinition(pageKey)
  return page?.tips.find((tip) => tip.key === tipKey) || null
}
