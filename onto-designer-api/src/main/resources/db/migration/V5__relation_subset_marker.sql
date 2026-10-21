-- 用户确认允许手工逆/对称；只读标记进入候选，真实profile默认阻断并仅OWNER可豁免。
ALTER TABLE ont_relation ADD COLUMN subset_exceeded boolean GENERATED ALWAYS AS ("symmetric" OR inverse_of IS NOT NULL) STORED;
