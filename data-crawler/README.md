# Data Crawler (Python)

轻量、合规的商品抓取与向量化前置标准化工具。

## 数据源策略
- 首选: `https://fakestoreapi.com/products`
- 备选补量: `https://dummyjson.com/products`
- 默认抓取 220 条，建议范围 200~300 条。

## 目标字段（8字段优先）
- `title` -> `products.name`
- `category` -> `products.category`
- `price` -> `products.price`
- `specs` -> `products.specs`
- `selling_points` -> `products.selling_points`
- `policy` -> `products.policy`
- `update_time` -> `products.updated_at`
- `source` + `source_product_id` -> `products.data_source` / `products.source_product_id`

## 入库模型（修正后）
- `products`：只放干净商品展示字段，不再塞向量 metadata 文本
- `product_vectors`：单商品单向量块（chunk_index=0），存 `chunk_text + metadata_json`
- `chunk_text` 内强制包含：
	- `product_id=...`
	- `update_time=...`

## 标准化规则
- 清洗 HTML / 空白 / 异常符号
- 卖点摘要长度控制
- 规格正则提取，提取失败回退为标准款
- 每条描述文本都带:
	- `product_id=...`
	- `update_time=...`

## 运行方式
1. 安装依赖

```bash
pip install -r requirements.txt
```

2. 预览抓取结果（不写库）

```bash
python src/main.py --limit 220 --dry-run
```

3. 实际入库（清空旧爬虫数据后重建）

```bash
python src/main.py --limit 220 --replace
```

运行后会同时写入：
- `products` N 行
- `product_vectors` N 行

## XLSX 一键导入
如果你的商品来源是 Excel (`.xlsx`)，可以直接使用脚本：

```powershell
./import-xlsx-products.ps1 -XlsxPath ../商品-2026-03-13_19-19.xlsx
```

可选参数：

- `-Limit 5000`：限制导入上限
- `-DryRun`：仅预览，不写入数据库

示例：

```powershell
./import-xlsx-products.ps1 -XlsxPath ../商品-2026-03-13_19-19.xlsx -DryRun
```

脚本会自动执行：
- 按文件字段映射入库
- 自动商品分类（用于后续归属）
- 同步写入 `product_vectors`
- 覆盖旧 `XLSX` 来源数据

## 环境变量
- `DB_HOST` 默认 `localhost`
- `DB_PORT` 默认 `5432`
- `DB_NAME` 默认 `ecommerce`
- `DB_USER` 默认 `postgres`
- `DB_PASSWORD` 默认 `123456`

## 与 AI 检索链对接
- 后端统一检索网关接口: `GET /api/retrieval/search`
- AI 导购查询走检索网关，不直接访问向量存储。
