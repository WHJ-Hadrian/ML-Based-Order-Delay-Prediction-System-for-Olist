# SeekDateSet 数据分析工作台（前端）

基于 Vue 3 + Element Plus 的数据集管理页面，对接 `dataset-service` 的
`/api/v1/datasets`。

## 本地运行

先启动后端（在 `ML-Based-Order-Delay-Prediction-System-for-Olist-java-backend` 目录）：

```powershell
.\mvnw.cmd -pl dataset-service -am -DskipTests package
java -jar dataset-service/target/dataset-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=local
```

再启动前端（在当前目录）：

```powershell
pnpm install
pnpm dev
```

打开 http://localhost:5173 。Vite 会把 `/api` 代理到 `http://localhost:8082`，
需要改目标地址时设置 `VITE_API_TARGET`。

## 页面结构

- 左侧可折叠侧边栏，当前激活数据集库。
- 顶部使用面包屑和紧凑标题，不使用大号 Hero 标题。
- 列表支持 `全部 / 有数据 / 空数据集` 标签页、关键字搜索、格式筛选和分页。
- 新建数据集时立即插入一行，名称依次为 `数据集1`、`数据集2`，并自动聚焦名称输入框。
- 名称可内联编辑，也可通过行尾“改名”操作进入编辑；回车或失焦保存，Esc 取消。
- 每行独立手风琴展开，多行可同时展开，再次点击收起。
- 空数据集展开后显示大号 `+` 上传区；已有数据的数据集在表清单底部提供追加上传。
- 表数量超过 5 张时默认收起，底部渐隐，可展开全部或再次收起。

## 上传规则

- 支持 CSV / TSV / ZIP / XLSX，单文件最大 1 GB（XLSX 最大 100 MB）。
- CSV / TSV 按表头统计列数和数据行数。
- ZIP 只作为容器源文件保存，不把压缩包本身当作数据表；系统解析包内 CSV / TSV / XLSX，
  并将每张表的来源显示为包内文件名。
- XLSX 按 sheet 统计。

## 目录

```text
src/api/         后端接口调用
src/components/  侧边栏、数据集行、行内上传器
src/utils/       状态与格式化工具
src/assets/      全局 Element Plus 主题与页面样式
```

生产构建：

```powershell
pnpm build
```
