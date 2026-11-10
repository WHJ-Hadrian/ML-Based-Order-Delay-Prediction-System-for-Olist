import {
  HomeFilled,
  Collection,
  DataAnalysis,
  Grid,
  Cpu,
  MagicStick,
  SetUp,
  Share,
  Timer,
} from '@element-plus/icons-vue'

export const NAVIGATION_ITEMS = Object.freeze([
  {
    key: 'workspace',
    path: '/workspace',
    title: '工作区概览',
    description: '查看最近使用的数据、分析方案、工作流与模型。',
    emptyText: '工作区还没有资源',
    icon: HomeFilled,
    component: () => import('@/views/WorkspaceHomeView.vue'),
  },
  {
    key: 'datasets',
    path: '/datasets',
    title: '数据集',
    description: '管理原始数据文件，自动识别 CSV、TSV、ZIP 和 Excel 中的数据表。',
    emptyText: '这里还没有数据集',
    icon: Collection,
    showTotal: true,
    component: () => import('@/views/DatasetLibraryView.vue'),
  },
  {
    key: 'analysis',
    path: '/analysis',
    title: '数据分析',
    description: '探索已就绪数据集中的字段分布与记录概况。',
    emptyText: '选择一个已就绪的数据集开始分析',
    icon: DataAnalysis,
    component: () => import('@/views/AnalysisWorkspaceView.vue'),
  },
  {
    key: 'models',
    path: '/models',
    title: '模型训练',
    description: '使用数据集版本配置分类或回归训练，并查看已登记的模型版本。',
    emptyText: '选择 READY 数据集版本开始训练',
    icon: Cpu,
    component: () => import('@/views/ModelWorkspaceView.vue'),
  },
  {
    key: 'trials',
    path: '/model-trials',
    title: '模型试炼场',
    description: '用独立数据版本运行预测、评估和模型比较。',
    emptyText: '选择已登记模型开始试炼',
    icon: MagicStick,
    component: () => import('@/views/ModelTrialView.vue'),
  },
  {
    key: 'workflows',
    path: '/workflows',
    title: '工作流',
    description: '组合数据、分析、规则与模型节点，保存可复用流程。',
    emptyText: '从空白画布开始编排一个工作流',
    icon: Share,
    component: () => import('@/views/WorkflowWorkspaceView.vue'),
  },
  {
    key: 'workflow-runs',
    path: '/workflow-runs',
    title: '运行中心',
    description: '跨工作流查看后台运行状态、节点日志与冻结结果。',
    emptyText: '还没有工作流运行',
    icon: Timer,
    component: () => import('@/views/WorkflowRunCenterView.vue'),
  },
  {
    key: 'fields',
    path: '/fields',
    title: '字段库',
    description: '统一维护数据字段的定义、类型与业务含义。',
    emptyText: '字段库入口已就绪，等待接入字段数据',
    icon: Grid,
    component: () => import('@/views/FieldLibraryView.vue'),
  },
  {
    key: 'rules',
    path: '/rules',
    title: '规则库',
    description: '集中管理数据校验、清洗和建模规则。',
    emptyText: '规则库入口已就绪，等待接入规则数据',
    icon: SetUp,
    component: () => import('@/views/RuleLibraryView.vue'),
  },
])

// Start in the project overview; data and experiments are project resources.
export const DEFAULT_NAVIGATION_PATH = '/workspace'

export function findNavigationItem(key) {
  return NAVIGATION_ITEMS.find((item) => item.key === key) || NAVIGATION_ITEMS[0]
}

export function findNavigationItemByPath(path) {
  return NAVIGATION_ITEMS.find((item) => item.path === path) || NAVIGATION_ITEMS[0]
}
