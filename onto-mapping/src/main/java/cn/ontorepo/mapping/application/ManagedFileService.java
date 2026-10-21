package cn.ontorepo.mapping.application;

import cn.ontorepo.common.domain.Input;
import cn.ontorepo.common.domain.Problem;
import cn.ontorepo.common.infra.Db;
import cn.ontorepo.space.application.AuthService;
import cn.ontorepo.space.application.SpaceService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 受管文件只能通过服务端UUID定位；上传名永不参与目标路径拼接。 */
@Service
public class ManagedFileService {
    private final Db db;
    private final SpaceService spaces;
    private final Path root;
    private static final Map<String, String> MEDIA =
            Map.of(
                    "csv",
                    "text/csv",
                    "json",
                    "application/json",
                    "xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "pdf",
                    "application/pdf",
                    "docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "txt",
                    "text/plain");

    public ManagedFileService(
            Db db, SpaceService spaces, @Value("${ONTO_FILES_ROOT:}") String root) {
        this.db = db;
        this.spaces = spaces;
        this.root = root.isBlank() ? null : Path.of(root).toAbsolutePath().normalize();
    }

    /** 上传和源管理权限必须由当前空间OWNER持有。 */
    public void owner(UUID user, UUID space) {
        if (!"OWNER".equals(spaces.role(user, space)))
            throw new Problem(403, "FORBIDDEN", "此操作需要OWNER权限");
    }

    /** 管理目录仅从服务端环境加载，缺配置时拒绝能力而非回退。 */
    public Path root() {
        if (root == null) throw new Problem(503, "FILES_NOT_CONFIGURED", "管理文件目录尚未配置");
        return root;
    }

    /** 文件目录只公开安全字段，不返回实际目录路径。 */
    public List<Map<String, Object>> list(UUID u, UUID s) {
        spaces.role(u, s);
        return db.list(
                "SELECT id,name,media_type,size,sha256,resource FROM data_file WHERE space_id=?"
                        + " ORDER BY created_at,id",
                s);
    }

    /** 引用文件前复核同空间归属，防止跨空间读取。 */
    public Map<String, Object> owned(UUID s, Object id) {
        var rows = db.list("SELECT * FROM data_file WHERE id=? AND space_id=?", Input.uuid(id), s);
        if (rows.isEmpty()) throw new Problem(400, "CROSS_SPACE_REFERENCE", "文件不属于当前空间");
        return rows.getFirst();
    }

    /** 仅供目录预检；调用方若要解析内容，必须使用 bytes，不能重新打开返回路径。 */
    public Path path(Map<String, Object> f) {
        return frozenPath(root(), f.get("resource").toString(), f.get("sha256").toString());
    }

    /** 校验与解析使用同一批字节，避免校验路径后再次打开文件的替换窗口。 */
    public byte[] bytes(Map<String, Object> f) {
        return verified(root(), f.get("resource").toString(), f.get("sha256").toString()).bytes();
    }

    /** 按冻结目录预检；实例适配器仍须对其实际读取的字节核对同一冻结摘要。 */
    public Path frozenPath(Path approvedRoot, String resource, String sha256) {
        return verified(approvedRoot, resource, sha256).path();
    }

    private record VerifiedFile(Path path, byte[] bytes) {}

    private VerifiedFile verified(Path approvedRoot, String resource, String sha256) {
        try {
            Path base = approvedRoot.toRealPath(), p = base.resolve(resource).normalize();
            if (!p.startsWith(base)
                    || Files.isSymbolicLink(p)
                    || !p.toRealPath().startsWith(base)
                    || !Files.isRegularFile(p)
                    || Files.size(p) > 8L * 1024 * 1024) throw new IOException();
            byte[] bytes;
            try (var input = Files.newInputStream(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(8 * 1024 * 1024 + 1);
            }
            // size 预检和读取之间也可能变化，因此真实字节数必须再次受上传上限约束。
            if (bytes.length > 8 * 1024 * 1024) throw new IOException();
            if (!AuthService.digest(bytes).equals(sha256)) throw new IOException();
            return new VerifiedFile(p, bytes);
        } catch (IOException e) {
            throw new Problem(409, "FILE_INTEGRITY_FAILED", "受管文件缺失或摘要不匹配");
        }
    }

    @Transactional
    /** 在OWNER空间锁内保存UUID文件与PG目录，失败回滚清理文件。 */
    public Map<String, Object> upload(UUID u, UUID s, long rev, String name, byte[] bytes) {
        owner(u, s);
        if (name == null
                || name.isBlank()
                || name.length() > 240
                || name.codePoints().anyMatch(Character::isISOControl)) throw Problem.bad("文件名无效");
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (!MEDIA.containsKey(ext)
                || bytes == null
                || bytes.length == 0
                || bytes.length > 8 * 1024 * 1024) throw Problem.bad("文件类型或大小不允许");
        validate(ext, bytes);
        UUID id = UUID.randomUUID();
        String resource = id + "." + ext;
        final Path[] written = {null};
        try {
            var space =
                    spaces.write(
                            u,
                            s,
                            rev,
                            "DATA_FILE_UPLOAD",
                            () -> {
                                owner(u, s);
                                try {
                                    Files.createDirectories(root());
                                    Path dest = root().resolve(resource);
                                    Files.write(dest, bytes, StandardOpenOption.CREATE_NEW);
                                    written[0] = dest;
                                } catch (IOException e) {
                                    throw new Problem(503, "FILE_WRITE_FAILED", "文件保存失败");
                                }
                                db.update(
                                        "INSERT INTO"
                                            + " data_file(id,space_id,name,media_type,size,sha256,resource)"
                                            + " VALUES(?,?,?,?,?,?,?)",
                                        id,
                                        s,
                                        name,
                                        MEDIA.get(ext),
                                        bytes.length,
                                        AuthService.digest(bytes),
                                        resource);
                            });
            // 事务回滚时清理新文件，避免数据库失败留下可引用的孤儿文件。
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .registerSynchronization(
                            new org.springframework.transaction.support
                                    .TransactionSynchronization() {
                                public void afterCompletion(int status) {
                                    if (status != STATUS_COMMITTED)
                                        try {
                                            Files.deleteIfExists(written[0]);
                                        } catch (IOException ignored) {
                                        }
                                }
                            });
            var file =
                    db.one(
                            "SELECT id,name,media_type,size,sha256,resource FROM data_file WHERE"
                                    + " id=?",
                            id);
            return Map.of("space", space, "file", file);
        } catch (RuntimeException e) {
            if (written[0] != null)
                try {
                    Files.deleteIfExists(written[0]);
                } catch (IOException ignored) {
                }
            throw e;
        }
    }

    /** ZIP总量/条目数有上限；宏、嵌入对象、外部实体在解析之前拒绝。 */
    public static Map<String, byte[]> zip(byte[] bytes) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        int total = 0;
        int entryCount = 0;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (++entryCount > 256
                        || e.getName().contains("..")
                        || e.getName().startsWith("/")
                        || e.getName().contains("\\")) throw new IOException();
                if (e.isDirectory()) {
                    // 目录也可能携带DEFLATED数据；getNextEntry会隐式解压剩余载荷，绕过文件字节预算。
                    // 只读一个实际字节确认空目录，不信任未知/伪造的声明size，也不完整解压目录载荷。
                    if (z.read() != -1) throw new IOException();
                    continue;
                }
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n;
                while ((n = z.read(chunk)) != -1) {
                    total += n;
                    if (total > 32 * 1024 * 1024) throw new IOException();
                    b.write(chunk, 0, n);
                }
                if (out.putIfAbsent(e.getName(), b.toByteArray()) != null) throw new IOException();
                String low = e.getName().toLowerCase(Locale.ROOT);
                if (low.contains("vbaproject")
                        || low.contains("embeddings/")
                        || low.endsWith(".bin")) throw new IOException();
                if (low.endsWith(".xml") || low.endsWith(".rels")) {
                    String xml = text(b.toByteArray());
                    if (xml.contains("<!DOCTYPE")
                            || xml.contains("<!ENTITY")
                            || xml.matches("(?s).*TargetMode\\s*=\\s*['\"]External['\"].*"))
                        throw new IOException();
                }
            }
            return out;
        } catch (IOException e) {
            throw Problem.bad("Office压缩内容无效或超过解析预算");
        }
    }

    /** 文本只接受有效UTF-8并拒绝二进制和可执行签名。 */
    public static String text(byte[] bytes) {
        try {
            String t =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(bytes))
                            .toString();
            if (t.indexOf(0) >= 0 || t.startsWith("MZ") || t.startsWith("#!"))
                throw new CharacterCodingException();
            return t;
        } catch (CharacterCodingException e) {
            throw Problem.bad("文本文件不是有效UTF-8或包含可执行内容");
        }
    }

    private void validate(String ext, byte[] bytes) {
        if (ext.equals("pdf")) {
            if (bytes.length < 8
                    || !new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-"))
                throw Problem.bad("PDF签名与扩展名不符");
            return;
        }
        if (ext.equals("docx") || ext.equals("xlsx")) {
            var zip = zip(bytes);
            if (!zip.containsKey("[Content_Types].xml")
                    || !zip.containsKey(
                            ext.equals("docx") ? "word/document.xml" : "xl/workbook.xml"))
                throw Problem.bad("Office结构与扩展名不符");
            return;
        }
        String text = text(bytes);
        if (text.startsWith("%PDF-") || text.startsWith("PK")) throw Problem.bad("文件签名与扩展名不符");
        if (ext.equals("json"))
            try {
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(text);
            } catch (Exception e) {
                throw Problem.bad("JSON文件解析失败");
            }
    }
}
