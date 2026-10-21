package cn.ontorepo.artifact;

/** 可信数据库持有的制品定位和内容证明；摘要必须取自上传字节，不能信任对象元数据。 */
public record ArtifactRef(String key, String sha256, long size) {}
