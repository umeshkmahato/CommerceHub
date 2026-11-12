-- ShopSphere MySQL init — runs once on first container start.
-- Creates the three service-owned databases. Each service connects with
-- its own credentials (least privilege: a service can't touch another DB).

CREATE DATABASE IF NOT EXISTS productdb  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS cartdb     CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS inventorydb CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'product'@'%' IDENTIFIED BY 'product_pw';
CREATE USER IF NOT EXISTS 'cart'@'%'    IDENTIFIED BY 'cart_pw';
CREATE USER IF NOT EXISTS 'inventory'@'%' IDENTIFIED BY 'inventory_pw';

GRANT ALL PRIVILEGES ON productdb.*  TO 'product'@'%';
GRANT ALL PRIVILEGES ON cartdb.*     TO 'cart'@'%';
GRANT ALL PRIVILEGES ON inventorydb.* TO 'inventory'@'%';

FLUSH PRIVILEGES;
