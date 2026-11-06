CREATE DATABASE IF NOT EXISTS eventfulfill_test CHARACTER SET utf8mb4;
GRANT ALL PRIVILEGES ON eventfulfill_test.* TO 'eventfulfill'@'%';
CREATE DATABASE IF NOT EXISTS eventfulfill_mq_it CHARACTER SET utf8mb4;
GRANT ALL PRIVILEGES ON eventfulfill_mq_it.* TO 'eventfulfill'@'%';
