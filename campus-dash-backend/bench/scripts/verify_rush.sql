-- P9 数据不变式校验：所有 violation_count 都应为0。
SELECT 'campaign_oversold' check_name, COUNT(*) violation_count
FROM rush_campaign WHERE sold_count>quota_total
UNION ALL
SELECT 'duplicate_campaign_user', COUNT(*) FROM (
  SELECT campaign_id,user_id FROM rush_request GROUP BY campaign_id,user_id HAVING COUNT(*)>1
) x
UNION ALL
SELECT 'duplicate_source_request', COUNT(*) FROM (
  SELECT source_request_id FROM errand WHERE source_type='RUSH_CAMPAIGN'
  GROUP BY source_request_id HAVING COUNT(*)>1
) x
UNION ALL
SELECT 'success_without_errand', COUNT(*)
FROM rush_request rr LEFT JOIN errand e ON e.id=rr.errand_id
WHERE rr.status='SUCCESS' AND e.id IS NULL
UNION ALL
SELECT 'campaign_sold_mismatch', COUNT(*) FROM (
  SELECT c.id,c.sold_count,COUNT(r.request_id) actual
  FROM rush_campaign c
  LEFT JOIN rush_request r ON r.campaign_id=c.id AND r.status='SUCCESS'
  GROUP BY c.id,c.sold_count
  HAVING c.sold_count<>actual
) x
UNION ALL
SELECT 'success_escrow_mismatch', COUNT(*) FROM (
  SELECT rr.request_id,COUNT(o.id) actual
  FROM rush_request rr
  LEFT JOIN escrow_order o ON o.errand_id=rr.errand_id
  WHERE rr.status='SUCCESS'
  GROUP BY rr.request_id
  HAVING actual<>1
) x
UNION ALL
SELECT 'success_ledger_pair_mismatch', COUNT(*) FROM (
  SELECT rr.request_id,
         COUNT(l.id) entries,
         SUM(CASE WHEN l.direction='DEBIT' THEN l.amount ELSE 0 END) debit_amount,
         SUM(CASE WHEN l.direction='CREDIT' THEN l.amount ELSE 0 END) credit_amount
  FROM rush_request rr
  LEFT JOIN wallet_ledger l ON l.ref_id=rr.errand_id AND l.ref_type='ESCROW'
  WHERE rr.status='SUCCESS'
  GROUP BY rr.request_id
  HAVING entries<>2 OR debit_amount<>credit_amount
) x
UNION ALL
SELECT 'workload_mismatch', COUNT(*) FROM (
  SELECT rw.runner_id,rw.ongoing_count,COUNT(rca.errand_id) actual
  FROM runner_workload rw
  LEFT JOIN runner_capacity_assignment rca
    ON rca.runner_id=rw.runner_id AND rca.status='OCCUPIED'
  GROUP BY rw.runner_id,rw.ongoing_count
  HAVING rw.ongoing_count<>actual
) x;
