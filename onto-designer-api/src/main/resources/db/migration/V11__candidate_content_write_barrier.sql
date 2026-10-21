-- 不可变内容列禁止任何显式UPDATE，包括payload=payload；保留原触发器名及旧候选原字节。
-- 发布状态、失败原因、制品引用与进度仍由应用的空间锁/终态校验推进，不属于内容改写。
DROP TRIGGER candidate_no_update ON publish_candidate;
DROP FUNCTION immutable_candidate_content();
CREATE TRIGGER candidate_no_update
BEFORE UPDATE OF id,space_id,draft_rev,sha256,payload,format_version,capability,created_at,pre_release_id,pre_version_no
ON publish_candidate FOR EACH ROW EXECUTE FUNCTION immutable_candidate();
