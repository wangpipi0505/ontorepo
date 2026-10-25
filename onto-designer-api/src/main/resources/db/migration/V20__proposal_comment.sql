-- R12/UIF-O3：提案元素级评论独立表。
-- 评论是 REVIEW 阶段的协作数据，不混入 publication_check（那是一门禁一行
-- 的权威结论）；候选删除时级联清理，不进入正式发布记录。
CREATE TABLE proposal_comment(
    id bigserial PRIMARY KEY,
    space_id uuid NOT NULL REFERENCES ontology_space(id),
    candidate_id uuid NOT NULL REFERENCES publish_candidate(id),
    actor_id uuid NOT NULL REFERENCES local_user(id),
    -- 元素级评论定位到变更行的 kind+key（如 CLASS/设备）；NULL 表示对整个提案的总体评论
    subject_kind varchar(30),
    subject_key text,
    body text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX proposal_comment_candidate ON proposal_comment(candidate_id, id);
