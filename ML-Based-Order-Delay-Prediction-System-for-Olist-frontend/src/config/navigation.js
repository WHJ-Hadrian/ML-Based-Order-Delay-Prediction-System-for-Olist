import {
  Aim,
  Collection,
  Cpu,
  DataAnalysis,
  Grid,
  HomeFilled,
  MagicStick,
  SetUp,
  Timer,
  TrendCharts,
} from '@element-plus/icons-vue'

export const NAVIGATION_ITEMS = Object.freeze([
  {
    key: 'workspace', path: '/workspace', title: '概览', group: '概览', icon: HomeFilled,
    component: () => import('@/views/WorkspaceHomeView.vue'),
  },
  {
    key: 'datasets', path: '/datasets', title: '数据集', group: '数据', icon: Collection,
    component: () => import('@/views/DatasetLibraryView.vue'),
  },
  {
    key: 'quality', path: '/quality', title: '数据处理', group: '数据', icon: SetUp,
    component: () => import('@/views/RuleLibraryView.vue'),
  },
  {
    key: 'fields', path: '/fields', title: '字段定义', group: '数据', icon: Grid,
    component: () => import('@/views/FieldLibraryView.vue'),
  },
  {
    key: 'analysis', path: '/analysis', title: '可视化分析', group: '数据', icon: DataAnalysis,
    component: () => import('@/views/AnalysisWorkspaceView.vue'),
  },
  {
    key: 'delay-analysis', path: '/delay-analysis', title: '延误分析', group: '数据', icon: TrendCharts,
    component: () => import('@/views/DelayAnalysisView.vue'),
  },
  {
    key: 'experiments', path: '/experiments', title: '建模实验', group: '建模', icon: Cpu,
    component: () => import('@/views/ModelWorkspaceView.vue'),
  },
  {
    key: 'evaluation', path: '/evaluation', title: '评估与预测', group: '建模', icon: DataAnalysis,
    component: () => import('@/views/ModelTrialView.vue'),
  },
  {
    key: 'prediction', path: '/prediction', title: '订单预测', group: '建模', icon: Aim,
    component: () => import('@/views/PredictionView.vue'),
  },
  {
    key: 'registry', path: '/registry', title: '模型版本', group: '建模', icon: MagicStick,
    component: () => import('@/views/ModelRegistryView.vue'),
  },
  {
    key: 'pipelines', path: '/pipelines', title: '流水线', group: '自动化', icon: Timer,
    component: () => import('@/views/WorkflowWorkspaceView.vue'),
  },
  {
    key: 'pipeline-runs', path: '/pipeline-runs', title: '运行记录', group: '自动化', icon: Timer,
    component: () => import('@/views/WorkflowRunCenterView.vue'),
  },
])

export const DEFAULT_NAVIGATION_PATH = '/workspace'

export function findNavigationItem(key) {
  return NAVIGATION_ITEMS.find((item) => item.key === key) || NAVIGATION_ITEMS[0]
}

export function findNavigationItemByPath(path) {
  return NAVIGATION_ITEMS.find((item) => item.path === path) || NAVIGATION_ITEMS[0]
}
