import {
  Collection,
  Grid,
  SetUp,
} from '@element-plus/icons-vue'

export const NAVIGATION_ITEMS = Object.freeze([
  {
    key: 'datasets',
    path: '/datasets',
    title: '数据集库',
    description: '管理原始数据文件，自动识别 CSV、TSV、ZIP 和 Excel 中的数据表。',
    emptyText: '这里还没有数据集',
    icon: Collection,
    showTotal: true,
    component: () => import('@/views/DatasetLibraryView.vue'),
  },
  {
    key: 'fields',
    path: '/fields',
    title: '字段库',
    description: '统一维护数据字段的定义、类型与业务含义。',
    emptyText: '字段库入口已就绪，等待接入字段数据',
    icon: Grid,
    component: () => import('@/views/SectionPlaceholderView.vue'),
  },
  {
    key: 'rules',
    path: '/rules',
    title: '规则库',
    description: '集中管理数据校验、清洗和建模规则。',
    emptyText: '规则库入口已就绪，等待接入规则数据',
    icon: SetUp,
    component: () => import('@/views/SectionPlaceholderView.vue'),
  },
])

export const DEFAULT_NAVIGATION_PATH = NAVIGATION_ITEMS[0].path

export function findNavigationItem(key) {
  return NAVIGATION_ITEMS.find((item) => item.key === key) || NAVIGATION_ITEMS[0]
}

export function findNavigationItemByPath(path) {
  return NAVIGATION_ITEMS.find((item) => item.path === path) || NAVIGATION_ITEMS[0]
}
