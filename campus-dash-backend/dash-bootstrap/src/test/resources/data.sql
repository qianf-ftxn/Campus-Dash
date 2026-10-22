-- 集成测试的最小固定fixture。常驻数据库可能被压测清理过，UPDATE不会自动创建缺失账户。
INSERT INTO wallet_account (id, owner_id, owner_type, available, frozen, version) VALUES
  (1, -1, 'ESCROW', 0, 0, 0),
  (2, -2, 'COMMISSION', 0, 0, 0),
  (1001, 1001, 'USER', 100000, 0, 0),
  (2001, 2001, 'USER', 5000, 0, 0),
  (2002, 2002, 'USER', 5000, 0, 0),
  (9001, 9001, 'USER', 5000, 0, 0)
ON DUPLICATE KEY UPDATE id = id;
