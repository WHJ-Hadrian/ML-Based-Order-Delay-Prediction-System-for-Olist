export function uniqueTableReferences(references) {
  const tables = new Map()
  for (const reference of references) {
    if (!reference.datasetVersionId || !reference.tableId) throw new Error('缺少固定的数据版本或表引用')
    const key = `${reference.datasetVersionId}:${reference.tableId}`
    if (!tables.has(key)) tables.set(key, { ...reference, key })
  }
  if (!tables.size || tables.size > 20) throw new Error('一次可添加 1 到 20 张数据表')
  return [...tables.values()]
}

export function sourceFromCatalog(reference, catalog, datasetName) {
  if (catalog?.status !== 'READY' || catalog.datasetVersionId !== reference.datasetVersionId) {
    throw new Error('此数据版本已不可用，请重新选择数据资产')
  }
  const table = catalog.tables?.find((item) => item.tableId === reference.tableId)
  if (!table) throw new Error('此数据表已不可用，请重新选择数据资产')
  return {
    datasetId: catalog.datasetId,
    datasetName: String(datasetName || reference.datasetName || catalog.datasetId).slice(0, 255),
    datasetVersionId: catalog.datasetVersionId,
    versionNo: catalog.versionNo,
    tableId: table.tableId,
    tableName: table.tableName,
    rowCount: table.rowCount,
    schemaHash: catalog.schemaHash || '',
  }
}

export function appendWorkflowSources(document, sources, makeId = () => `source-${crypto.randomUUID()}`) {
  const existingNodes = document?.nodes || []
  const known = new Set(existingNodes.filter((node) => node.type === 'DATA_SOURCE')
    .map((node) => `${node.data?.config?.datasetVersionId}:${node.data?.config?.tableId}`))
  const pending = sources.filter((source) => {
    const key = `${source.datasetVersionId}:${source.tableId}`
    if (known.has(key)) return false
    known.add(key)
    return true
  })
  if (existingNodes.length + pending.length > 200) throw new Error('加入后将超过工作流 200 个节点的上限')
  const startX = existingNodes.length
    ? Math.max(...existingNodes.map((node) => Number(node.position?.x) || 0)) + 320
    : 120
  if (pending.length && startX + 580 > 1_000_000) throw new Error('画布右侧空间不足，请先调整节点位置')
  const addedNodes = pending.map((source, index) => ({
    id: makeId(),
    type: 'DATA_SOURCE',
    position: { x: startX + (index % 3) * 290, y: 120 + Math.floor(index / 3) * 190 },
    data: { label: source.tableName, description: '', config: { ...source } },
  }))
  return {
    document: { ...document, version: document?.version || 1, nodes: [...existingNodes, ...addedNodes], edges: document?.edges || [] },
    addedCount: addedNodes.length,
  }
}
