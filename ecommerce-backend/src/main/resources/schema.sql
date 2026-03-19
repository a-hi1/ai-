CREATE TABLE IF NOT EXISTS users (
  id UUID PRIMARY KEY,
  email VARCHAR(255) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  role VARCHAR(32) NOT NULL,
  display_name VARCHAR(128),
  phone VARCHAR(32),
  city VARCHAR(128),
  bio VARCHAR(512),
  created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS products (
  id UUID PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  description TEXT,
  price NUMERIC(12, 2) NOT NULL,
  image_url VARCHAR(512),
  tags VARCHAR(512),
  category VARCHAR(128),
  specs VARCHAR(512),
  selling_points TEXT,
  policy VARCHAR(512),
  paid_count INT,
  review_count INT,
  sales_volume INT,
  positive_rate NUMERIC(8, 4),
  total_reviews INT,
  shipping_time VARCHAR(128),
  support_7day_return BOOLEAN,
  support_fast_refund BOOLEAN,
  payment_method VARCHAR(128),
  store_name VARCHAR(255),
  store_url VARCHAR(512),
  top_reviews_json TEXT,
  source_product_id VARCHAR(128),
  data_source VARCHAR(32),
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP
);

ALTER TABLE products ADD COLUMN IF NOT EXISTS data_source VARCHAR(32);
ALTER TABLE products ADD COLUMN IF NOT EXISTS category VARCHAR(128);
ALTER TABLE products ADD COLUMN IF NOT EXISTS specs VARCHAR(512);
ALTER TABLE products ADD COLUMN IF NOT EXISTS selling_points TEXT;
ALTER TABLE products ADD COLUMN IF NOT EXISTS policy VARCHAR(512);
ALTER TABLE products ADD COLUMN IF NOT EXISTS source_product_id VARCHAR(128);
ALTER TABLE products ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP;
ALTER TABLE products ADD COLUMN IF NOT EXISTS paid_count INT;
ALTER TABLE products ADD COLUMN IF NOT EXISTS review_count INT;
ALTER TABLE products ADD COLUMN IF NOT EXISTS sales_volume INT;
ALTER TABLE products ADD COLUMN IF NOT EXISTS positive_rate NUMERIC(8, 4);
ALTER TABLE products ADD COLUMN IF NOT EXISTS total_reviews INT;
ALTER TABLE products ADD COLUMN IF NOT EXISTS shipping_time VARCHAR(128);
ALTER TABLE products ADD COLUMN IF NOT EXISTS support_7day_return BOOLEAN;
ALTER TABLE products ADD COLUMN IF NOT EXISTS support_fast_refund BOOLEAN;
ALTER TABLE products ADD COLUMN IF NOT EXISTS payment_method VARCHAR(128);
ALTER TABLE products ADD COLUMN IF NOT EXISTS store_name VARCHAR(255);
ALTER TABLE products ADD COLUMN IF NOT EXISTS store_url VARCHAR(512);
ALTER TABLE products ADD COLUMN IF NOT EXISTS top_reviews_json TEXT;
UPDATE products SET data_source = 'MANUAL' WHERE data_source IS NULL;
UPDATE products SET updated_at = created_at WHERE updated_at IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_products_source_key
  ON products(data_source, source_product_id)
  WHERE source_product_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS product_vectors (
  id UUID PRIMARY KEY,
  product_id UUID NOT NULL,
  chunk_index INT NOT NULL,
  chunk_text TEXT NOT NULL,
  metadata_json TEXT NOT NULL,
  source VARCHAR(32) NOT NULL,
  updated_at TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_product_vectors_product_chunk
  ON product_vectors(product_id, chunk_index);

CREATE INDEX IF NOT EXISTS idx_product_vectors_source_updated
  ON product_vectors(source, updated_at);

CREATE TABLE IF NOT EXISTS cart_items (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL,
  product_id UUID NOT NULL,
  quantity INT NOT NULL,
  created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS orders (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL,
  status VARCHAR(32) NOT NULL,
  total_amount NUMERIC(12, 2) NOT NULL,
  payment_method VARCHAR(64),
  gateway_trade_no VARCHAR(128),
  paid_at TIMESTAMP,
  created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS order_items (
  id UUID PRIMARY KEY,
  order_id UUID NOT NULL,
  product_id UUID NOT NULL,
  product_name VARCHAR(255) NOT NULL,
  product_description TEXT,
  image_url VARCHAR(512),
  unit_price NUMERIC(12, 2) NOT NULL,
  quantity INT NOT NULL,
  created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS chat_messages (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL,
  session_id VARCHAR(128),
  role VARCHAR(32) NOT NULL,
  content TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL
);

ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS session_id VARCHAR(128);
CREATE INDEX IF NOT EXISTS idx_chat_messages_user_session_created
  ON chat_messages(user_id, session_id, created_at);

CREATE TABLE IF NOT EXISTS product_views (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL,
  product_id UUID NOT NULL,
  source VARCHAR(32) NOT NULL,
  reason VARCHAR(255),
  created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS service_nodes (
  id UUID PRIMARY KEY,
  service_name VARCHAR(128) NOT NULL,
  host VARCHAR(128) NOT NULL,
  port INT NOT NULL,
  status VARCHAR(32) NOT NULL,
  last_heartbeat TIMESTAMP NOT NULL
);
