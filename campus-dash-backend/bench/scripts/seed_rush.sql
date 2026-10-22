-- 创建一个当前可参与的演示活动。重复执行会刷新窗口，并只清理本活动上一轮产生的数据。
SET @campaign_id = 90001;

INSERT INTO wallet_account(id,owner_id,owner_type,available,frozen,version) VALUES
  (2001,2001,'USER',5000,0,0),(2002,2002,'USER',5000,0,0),(9001,9001,'USER',5000,0,0)
ON DUPLICATE KEY UPDATE id=id;

-- 先保存活动关联关系；直接只删rush_request会遗留Errand、托管单和资金流水，
-- 下一轮会同时污染唯一索引、账户余额和对账结果。
DROP TEMPORARY TABLE IF EXISTS tmp_rush_seed_request;
CREATE TEMPORARY TABLE tmp_rush_seed_request (
  request_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci PRIMARY KEY,
  errand_id BIGINT NULL,
  KEY idx_tmp_errand (errand_id)
);
INSERT INTO tmp_rush_seed_request(request_id,errand_id)
SELECT request_id,errand_id FROM rush_request WHERE campaign_id=@campaign_id;

-- 按待删除流水的净变化反向恢复账户快照。压测用户是1000001~1003000专用账户，
-- 但这里仍按实际流水回滚，不会粗暴覆盖其他活动带来的余额变化。
UPDATE wallet_account a
JOIN (
  SELECT l.account_id,
         SUM(CASE WHEN l.direction='CREDIT' THEN l.amount ELSE -l.amount END) AS net_amount
  FROM wallet_ledger l
  JOIN tmp_rush_seed_request t ON t.errand_id=l.ref_id
  GROUP BY l.account_id
) x ON x.account_id=a.id
SET a.available=a.available-x.net_amount,
    a.version=a.version+1;

DELETE g FROM grab_record g JOIN tmp_rush_seed_request t ON t.errand_id=g.errand_id;
DELETE a FROM runner_capacity_assignment a JOIN tmp_rush_seed_request t ON t.errand_id=a.errand_id;
DELETE s FROM errand_status_log s JOIN tmp_rush_seed_request t ON t.errand_id=s.errand_id;
DELETE l FROM wallet_ledger l JOIN tmp_rush_seed_request t ON t.errand_id=l.ref_id;
DELETE o FROM escrow_order o JOIN tmp_rush_seed_request t ON t.errand_id=o.errand_id;
DELETE e FROM errand e JOIN tmp_rush_seed_request t ON t.errand_id=e.id;
DELETE r FROM rush_request r JOIN tmp_rush_seed_request t ON t.request_id=r.request_id;
DELETE FROM rush_campaign WHERE id=@campaign_id;

INSERT INTO rush_campaign
  (id,campus_id,name,start_at,end_at,quota_total,sold_count,status,version)
VALUES
  (@campaign_id,1,'午餐限时代购',DATE_SUB(NOW(3),INTERVAL 5 MINUTE),
   DATE_ADD(NOW(3),INTERVAL 60 MINUTE),500,0,'ENABLED',0);

-- 3000个压测用户需要有钱包；生产环境由注册/充值流程创建。
INSERT INTO wallet_account(id,owner_id,owner_type,available,frozen,version)
SELECT 1000000+n,1000000+n,'USER',100000,0,0
FROM (
  SELECT ones.n + tens.n*10 + hundreds.n*100 + thousands.n*1000 AS n
  FROM (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) ones
  CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) tens
  CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
        UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) hundreds
  CROSS JOIN (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3) thousands
) seed
WHERE n BETWEEN 1 AND 3000
ON DUPLICATE KEY UPDATE id=id;

DROP TEMPORARY TABLE tmp_rush_seed_request;
