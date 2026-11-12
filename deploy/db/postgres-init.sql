-- ShopSphere PostgreSQL init — runs once on first container start.
-- Three service-owned databases, one role per service (least privilege).

CREATE DATABASE userdb;
CREATE DATABASE orderdb;
CREATE DATABASE paymentdb;

CREATE USER user_svc     WITH PASSWORD 'user_pw';
CREATE USER order_svc    WITH PASSWORD 'order_pw';
CREATE USER payment_svc  WITH PASSWORD 'payment_pw';

GRANT ALL PRIVILEGES ON DATABASE userdb    TO user_svc;
GRANT ALL PRIVILEGES ON DATABASE orderdb   TO order_svc;
GRANT ALL PRIVILEGES ON DATABASE paymentdb TO payment_svc;
