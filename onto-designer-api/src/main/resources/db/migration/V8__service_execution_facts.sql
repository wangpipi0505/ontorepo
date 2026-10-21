-- SERVICE执行与本地入口使用同样的真实主体与摘要事实；LEGACY历史记录保持原样。
ALTER TABLE op_execution DROP CONSTRAINT execution_facts_required;
ALTER TABLE op_execution ADD CONSTRAINT execution_facts_required CHECK(
 caller='LEGACY' OR (caller IN('TRIAL','LOCAL_TEST','SERVICE') AND principal_id IS NOT NULL AND input_digest IS NOT NULL AND output_digest IS NOT NULL)
);
