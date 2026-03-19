# Data Importer (Python)

当前 data-crawler 仅用于本地文件导入，不再执行网络爬取。

## 支持输入
- `.xlsx`
- `.csv`

## 功能
- 读取本地商品文件并标准化字段。
- 写入 `products` 表。
- 同步写入 `product_vectors` 表（单商品单向量块，`chunk_index=0`）。

## 安装依赖

```bash
pip install -r requirements.txt
```

## 导入 XLSX

```powershell
./import-xlsx-products.ps1 -XlsxPath ../商品.xlsx
```

等价命令：

```bash
python src/main.py --input-xlsx ../商品.xlsx --replace-source XLSX
```

## 导入 CSV

```bash
python src/main.py --input-csv ../商品.csv --replace-source CSV
```

## 仅预览

```bash
python src/main.py --input-xlsx ../商品.xlsx --dry-run
python src/main.py --input-csv ../商品.csv --dry-run
```

## 可选参数
- `--limit`: 限制导入条数，默认 `5000`。
- `--replace-source`: 先清理指定来源再导入，例如 `XLSX` 或 `CSV`。
- `--dry-run`: 只解析并输出统计，不写库。

## 环境变量
- `DB_HOST` 默认 `localhost`
- `DB_PORT` 默认 `5432`
- `DB_NAME` 默认 `ecommerce`
- `DB_USER` 默认 `postgres`
- `DB_PASSWORD` 默认 `123456`

## 知识库向量化

`src/knowledge-vectorizer.py` 用于将后端知识库文件写入 PostgreSQL + pgvector 向量表。

需要环境变量：
- `OPENAI_API_KEY`

数据库连接沿用同一套：
- `DB_HOST`
- `DB_PORT`
- `DB_NAME`
- `DB_USER`
- `DB_PASSWORD`

示例：

```bash
python src/knowledge-vectorizer.py --table knowledge_embeddings --replace-source
```
