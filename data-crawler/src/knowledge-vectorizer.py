import argparse
import hashlib
import json
import math
import os
import re
from pathlib import Path
from typing import Any, Dict, List, Tuple

import psycopg2
from psycopg2.extras import execute_values

try:
    from dotenv import load_dotenv
except Exception:
    def load_dotenv(*_args, **_kwargs):
        return False

try:
    from openai import OpenAI
except Exception:
    OpenAI = None

load_dotenv()

OPENAI_API_KEY = os.getenv("OPENAI_API_KEY")
DEFAULT_HASH_ONLY = os.getenv("KB_VECTOR_HASH_ONLY", "false").strip().lower() in {"1", "true", "yes", "on"}
openai_client = OpenAI(api_key=OPENAI_API_KEY) if (OpenAI is not None and OPENAI_API_KEY and not DEFAULT_HASH_ONLY) else None

PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_KB_PATH = PROJECT_ROOT / "ecommerce-backend" / "src" / "main" / "resources" / "ai" / "advisor-knowledge-base.json"


def get_db_config() -> dict:
    return {
        "host": os.getenv("DB_HOST", "localhost"),
        "port": int(os.getenv("DB_PORT", "5432")),
        "dbname": os.getenv("DB_NAME", "ecommerce"),
        "user": os.getenv("DB_USER", "postgres"),
        "password": os.getenv("DB_PASSWORD", "123456"),
    }


def hash_embedding(text: str, dim: int = 1536) -> List[float]:
    tokens = [token for token in re.split(r"[\s,，。！？!?:：/\\-]+", text.lower()) if token]
    if not tokens:
        tokens = [text]

    vector = [0.0] * dim
    for token in tokens:
        digest = hashlib.sha256(token.encode("utf-8")).digest()
        for offset in range(0, 24, 4):
            chunk = int.from_bytes(digest[offset:offset + 4], byteorder="big", signed=False)
            idx = chunk % dim
            sign = -1.0 if ((chunk >> 1) & 1) else 1.0
            weight = 1.0 + (len(token) % 7) * 0.07
            vector[idx] += sign * weight

    norm = math.sqrt(sum(item * item for item in vector))
    if norm <= 0.0:
        return vector
    return [item / norm for item in vector]


def get_embedding(text: str, hash_only: bool = False) -> List[float]:
    if hash_only or openai_client is None:
        return hash_embedding(text)

    embedding_model = os.getenv("OPENAI_EMBEDDING_MODEL", "text-embedding-3-small")
    try:
        response = openai_client.embeddings.create(
            model=embedding_model,
            input=text,
        )
        return response.data[0].embedding
    except Exception:
        return hash_embedding(text)


def stable_id(prefix: str, raw: str) -> str:
    digest = hashlib.sha256(raw.encode("utf-8")).hexdigest()[:16]
    return f"{prefix}_{digest}"


def normalize_content(value: Any) -> str:
    if isinstance(value, str):
        return value.strip()
    if isinstance(value, (dict, list)):
        return json.dumps(value, ensure_ascii=False)
    return str(value)


def load_knowledge_documents(kb_path: Path) -> List[Tuple[str, str, Dict[str, Any]]]:
    with kb_path.open("r", encoding="utf-8") as f:
        kb = json.load(f)

    documents: List[Tuple[str, str, Dict[str, Any]]] = []

    question_flows = kb.get("questionFlows", [])
    if isinstance(question_flows, dict):
        # Backward compatibility for older dict schema.
        question_flows = [
            {
                "intent": category,
                **(flow if isinstance(flow, dict) else {}),
            }
            for category, flow in question_flows.items()
        ]

    prompt_fields = {
        "category": "categoryQuestion",
        "budget": "budgetQuestion",
        "usage": "sceneQuestion",
        "function": "functionQuestion",
        "appearance": "appearanceQuestion",
        "preference": "preferenceQuestion",
    }

    for flow in question_flows:
        if not isinstance(flow, dict):
            continue

        category = normalize_content(flow.get("intent") or flow.get("category") or "通用导购")
        aliases = flow.get("aliases", [])
        required = flow.get("requiredDimensions", [])

        for dim, field_name in prompt_fields.items():
            prompt = normalize_content(flow.get(field_name, ""))
            if not prompt:
                continue
            doc_id = stable_id("flow", f"{category}:{dim}:{prompt}")
            content = (
                f"品类: {category}\n"
                f"别名: {normalize_content(aliases)}\n"
                f"维度: {dim}\n"
                f"提问: {prompt}\n"
                f"必需维度: {normalize_content(required)}"
            )
            meta = {
                "source": "advisor-knowledge-base.json",
                "type": "question_flow",
                "category": category,
                "dimension": dim,
            }
            documents.append((doc_id, content, meta))

    entries = kb.get("entries", [])
    for i, entry in enumerate(entries):
        title = entry.get("title", "") if isinstance(entry, dict) else ""
        summary = entry.get("summary", "") if isinstance(entry, dict) else ""
        content = entry.get("content", "") if isinstance(entry, dict) else ""
        keywords = []
        if isinstance(entry, dict):
            keywords = entry.get("keywords", []) or entry.get("tags", [])
        category = entry.get("category", "") if isinstance(entry, dict) else ""

        entry_text = summary or content

        raw = f"{title}|{entry_text}|{normalize_content(keywords)}|{category}|{i}"
        doc_id = stable_id("entry", raw)
        full_content = (
            f"标题: {normalize_content(title)}\n"
            f"分类: {normalize_content(category)}\n"
            f"内容: {normalize_content(entry_text)}\n"
            f"关键词: {normalize_content(keywords)}"
        )
        meta = {
            "source": "advisor-knowledge-base.json",
            "type": "knowledge_entry",
            "index": i,
            "title": normalize_content(title),
            "category": normalize_content(category),
        }
        documents.append((doc_id, full_content, meta))

    return documents


def ensure_schema(conn, table_name: str) -> None:
    has_vector_extension = True
    with conn.cursor() as cur:
        try:
            cur.execute("CREATE EXTENSION IF NOT EXISTS vector")
        except Exception:
            has_vector_extension = False
            conn.rollback()

    with conn.cursor() as cur:
        if has_vector_extension:
            cur.execute(
                f"""
                CREATE TABLE IF NOT EXISTS {table_name} (
                  id VARCHAR(80) PRIMARY KEY,
                  source VARCHAR(128) NOT NULL,
                  doc_type VARCHAR(64) NOT NULL,
                  category VARCHAR(128),
                  title VARCHAR(255),
                  content TEXT NOT NULL,
                  metadata_json JSONB NOT NULL,
                  embedding VECTOR(1536) NOT NULL,
                  embedding_json JSONB,
                  updated_at TIMESTAMP NOT NULL
                )
                """
            )
        else:
            cur.execute(
                f"""
                CREATE TABLE IF NOT EXISTS {table_name} (
                  id VARCHAR(80) PRIMARY KEY,
                  source VARCHAR(128) NOT NULL,
                  doc_type VARCHAR(64) NOT NULL,
                  category VARCHAR(128),
                  title VARCHAR(255),
                  content TEXT NOT NULL,
                  metadata_json JSONB NOT NULL,
                  embedding_json JSONB NOT NULL,
                  updated_at TIMESTAMP NOT NULL
                )
                """
            )
        cur.execute(
            f"CREATE INDEX IF NOT EXISTS idx_{table_name}_source ON {table_name}(source)"
        )
        cur.execute(
            f"CREATE INDEX IF NOT EXISTS idx_{table_name}_category ON {table_name}(category)"
        )
    conn.commit()
    return has_vector_extension


def replace_documents(conn, table_name: str, source_name: str) -> None:
    with conn.cursor() as cur:
        cur.execute(
            f"DELETE FROM {table_name} WHERE source = %s",
            (source_name,),
        )
    conn.commit()


def upsert_documents(conn,
                     table_name: str,
                     documents: List[Tuple[str, str, Dict[str, Any]]],
                     use_vector_extension: bool,
                     hash_only: bool) -> Tuple[int, int]:
    success = 0
    failure = 0
    rows = []

    for doc_id, content, metadata in documents:
        try:
            embedding = get_embedding(content, hash_only=hash_only)
            vector_literal = "[" + ",".join(f"{item:.8f}" for item in embedding) + "]"
            embedding_json = json.dumps(embedding, ensure_ascii=False)
            if use_vector_extension:
                rows.append(
                    (
                        doc_id,
                        metadata.get("source", "advisor-knowledge-base.json"),
                        metadata.get("type", "knowledge_entry"),
                        metadata.get("category", ""),
                        metadata.get("title", ""),
                        content,
                        json.dumps(metadata, ensure_ascii=False),
                        vector_literal,
                        embedding_json,
                    )
                )
            else:
                rows.append(
                    (
                        doc_id,
                        metadata.get("source", "advisor-knowledge-base.json"),
                        metadata.get("type", "knowledge_entry"),
                        metadata.get("category", ""),
                        metadata.get("title", ""),
                        content,
                        json.dumps(metadata, ensure_ascii=False),
                        embedding_json,
                    )
                )
            success += 1
        except Exception as ex:
            failure += 1
            print(f"Failed: {doc_id}, error={ex}")

    if rows:
        with conn.cursor() as cur:
            if use_vector_extension:
                execute_values(
                    cur,
                    f"""
                    INSERT INTO {table_name} (
                        id, source, doc_type, category, title, content, metadata_json, embedding, embedding_json, updated_at
                    ) VALUES %s
                    ON CONFLICT (id) DO UPDATE SET
                        source = EXCLUDED.source,
                        doc_type = EXCLUDED.doc_type,
                        category = EXCLUDED.category,
                        title = EXCLUDED.title,
                        content = EXCLUDED.content,
                        metadata_json = EXCLUDED.metadata_json,
                        embedding = EXCLUDED.embedding,
                        embedding_json = EXCLUDED.embedding_json,
                        updated_at = EXCLUDED.updated_at
                    """,
                    rows,
                    template="(%s, %s, %s, %s, %s, %s, %s::jsonb, %s::vector, %s::jsonb, NOW())",
                )
            else:
                execute_values(
                    cur,
                    f"""
                    INSERT INTO {table_name} (
                        id, source, doc_type, category, title, content, metadata_json, embedding_json, updated_at
                    ) VALUES %s
                    ON CONFLICT (id) DO UPDATE SET
                        source = EXCLUDED.source,
                        doc_type = EXCLUDED.doc_type,
                        category = EXCLUDED.category,
                        title = EXCLUDED.title,
                        content = EXCLUDED.content,
                        metadata_json = EXCLUDED.metadata_json,
                        embedding_json = EXCLUDED.embedding_json,
                        updated_at = EXCLUDED.updated_at
                    """,
                    rows,
                    template="(%s, %s, %s, %s, %s, %s, %s::jsonb, %s::jsonb, NOW())",
                )
        conn.commit()

    return success, failure


def count_source_documents(conn, table_name: str, source_name: str) -> int:
    with conn.cursor() as cur:
        cur.execute(
            f"SELECT COUNT(*) FROM {table_name} WHERE source = %s",
            (source_name,),
        )
        row = cur.fetchone()
    return int(row[0]) if row else 0


def main() -> None:
    parser = argparse.ArgumentParser(description="Vectorize advisor knowledge base into PostgreSQL pgvector")
    parser.add_argument(
        "--kb-path",
        default=str(DEFAULT_KB_PATH),
        help="Path to advisor-knowledge-base.json",
    )
    parser.add_argument(
        "--table",
        default="knowledge_embeddings",
        help="PostgreSQL table name",
    )
    parser.add_argument(
        "--replace-source",
        action="store_true",
        help="Delete existing rows from the same source before writing",
    )
    parser.add_argument(
        "--hash-only",
        action="store_true",
        help="Force local hash embedding only (no external embedding provider)",
    )
    args = parser.parse_args()

    kb_path = Path(args.kb_path).resolve()
    if not kb_path.exists():
        raise FileNotFoundError(f"Knowledge base not found: {kb_path}")

    documents = load_knowledge_documents(kb_path)
    print(f"Loaded {len(documents)} documents from {kb_path}")
    hash_only = args.hash_only or DEFAULT_HASH_ONLY

    conn = psycopg2.connect(**get_db_config())
    try:
        use_vector_extension = ensure_schema(conn, args.table)
        if args.replace_source:
            replace_documents(conn, args.table, "advisor-knowledge-base.json")
        success, failure = upsert_documents(conn, args.table, documents, use_vector_extension, hash_only)
        total = count_source_documents(conn, args.table, "advisor-knowledge-base.json")
    finally:
        conn.close()

    print("--- Vectorization Summary ---")
    print(f"Table: {args.table}")
    print(f"Success: {success}")
    print(f"Failure: {failure}")
    print(f"Source rows in DB: {total}")
    print(f"Storage mode: {'pgvector' if use_vector_extension else 'json-embedding-fallback'}")
    if hash_only:
        print("Embedding mode: local-hash-only")
    elif openai_client is None:
        print("Embedding mode: local-hash-fallback")
    else:
        print("Embedding mode: remote-with-local-fallback")


if __name__ == "__main__":
    main()
