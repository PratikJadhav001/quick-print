CREATE DATABASE IF NOT EXISTS print
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE print;

CREATE TABLE IF NOT EXISTS shop_orders (
  token INT NOT NULL AUTO_INCREMENT,
  student_name VARCHAR(100) NOT NULL,
  phone VARCHAR(30) NOT NULL,
  items_json JSON NOT NULL,
  total DECIMAL(10,2) NOT NULL,
  notes VARCHAR(280) NULL,
  status ENUM('PENDING','PRINTING','READY','COLLECTED') NOT NULL DEFAULT 'PENDING',
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (token),
  INDEX idx_shop_orders_status_created (status, created_at)
);

CREATE TABLE IF NOT EXISTS shop_prices (
  price_key VARCHAR(30) NOT NULL,
  amount DECIMAL(10,2) NOT NULL,
  PRIMARY KEY (price_key)
);

INSERT INTO shop_prices (price_key, amount) VALUES
  ('bw', 1.00),
  ('color', 8.00),
  ('bwDouble', 1.50),
  ('colorDouble', 12.00)
ON DUPLICATE KEY UPDATE price_key = VALUES(price_key);
