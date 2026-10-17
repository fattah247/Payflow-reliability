-- A provider report for one day checks which payouts were sent that day: find them by creation time, not by scanning
-- every batch ever made. (Merchant-scoped reports already use idx_settlement_batch_merchant.)
create index idx_settlement_batch_created on settlement_batches (created_at);
