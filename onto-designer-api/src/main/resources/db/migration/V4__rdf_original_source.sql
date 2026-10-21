-- 图同构的规范TTL用于执行；原文单独冻结，以便人工处理超范围构造时查看原始行号。
ALTER TABLE ont_rdf_document ADD COLUMN original_content text;
ALTER TABLE ont_rdf_document ADD COLUMN original_format varchar(5);
UPDATE ont_rdf_document SET original_content=content,original_format=format WHERE original_content IS NULL;
ALTER TABLE ont_rdf_document ALTER COLUMN original_content SET NOT NULL;
ALTER TABLE ont_rdf_document ALTER COLUMN original_format SET NOT NULL;
