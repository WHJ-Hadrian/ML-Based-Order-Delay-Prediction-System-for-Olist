import assert from 'node:assert/strict'
import test from 'node:test'
import { appendWorkflowSources, sourceFromCatalog, uniqueTableReferences } from './workflowSources.js'

const versionId = '550e8400-e29b-41d4-a716-446655440001'
const datasetId = '550e8400-e29b-41d4-a716-446655440002'
const tableId = '550e8400-e29b-41d4-a716-446655440003'
const catalog = {
  datasetId,
  datasetVersionId: versionId,
  versionNo: 4,
  status: 'READY',
  schemaHash: 'schema-4',
  tables: [{ tableId, tableName: 'customers', rowCount: 9 }],
}

test('deduplicates cards that point to the same fixed data table', () => {
  const references = uniqueTableReferences([
    { datasetVersionId: versionId, tableId, columnId: 'a' },
    { datasetVersionId: versionId, tableId, columnId: 'b' },
  ])

  assert.equal(references.length, 1)
  assert.equal(references[0].key, `${versionId}:${tableId}`)
})

test('resolves authoritative catalog fields and rejects stale assets', () => {
  const source = sourceFromCatalog({ datasetVersionId: versionId, tableId }, catalog, 'Customer data')

  assert.deepEqual(source, {
    datasetId,
    datasetName: 'Customer data',
    datasetVersionId: versionId,
    versionNo: 4,
    tableId,
    tableName: 'customers',
    rowCount: 9,
    schemaHash: 'schema-4',
  })
  assert.throws(() => sourceFromCatalog({ datasetVersionId: 'old', tableId }, catalog, 'Customer data'), /不可用/)
  assert.throws(() => sourceFromCatalog({ datasetVersionId: versionId, tableId: 'missing' }, catalog, 'Customer data'), /不可用/)
  assert.throws(() => sourceFromCatalog({ datasetVersionId: versionId, tableId }, { ...catalog, status: 'FAILED' }, 'Customer data'), /不可用/)
})

test('adds new sources without duplicating an existing table reference', () => {
  const source = sourceFromCatalog({ datasetVersionId: versionId, tableId }, catalog, 'Customer data')
  const existing = {
    version: 1,
    nodes: [{ id: 'source-existing', type: 'DATA_SOURCE', position: { x: 50, y: 50 }, data: { config: source } }],
    edges: [],
  }

  const duplicate = appendWorkflowSources(existing, [source], () => 'unused')
  assert.equal(duplicate.addedCount, 0)
  assert.equal(duplicate.document.nodes.length, 1)

  const another = { ...source, tableId: '550e8400-e29b-41d4-a716-446655440004', tableName: 'orders' }
  const appended = appendWorkflowSources(existing, [another], () => 'source-orders')
  assert.equal(appended.addedCount, 1)
  assert.equal(appended.document.nodes[1].id, 'source-orders')
  assert.equal(appended.document.nodes[1].data.config.tableName, 'orders')
  assert.equal(existing.nodes.length, 1)
})

test('enforces workflow node and coordinate limits before saving', () => {
  const fullDocument = {
    version: 1,
    nodes: Array.from({ length: 200 }, (_, index) => ({
      id: `node-${index}`, type: 'FILTER', position: { x: index, y: 0 }, data: { config: {} },
    })),
    edges: [],
  }
  const source = sourceFromCatalog({ datasetVersionId: versionId, tableId }, catalog, 'Customer data')
  assert.throws(() => appendWorkflowSources(fullDocument, [source]), /200 个节点/)
  assert.throws(() => appendWorkflowSources({
    version: 1,
    nodes: [{ id: 'far', type: 'FILTER', position: { x: 999_900, y: 0 }, data: { config: {} } }],
    edges: [],
  }, [source]), /画布右侧空间不足/)
})
