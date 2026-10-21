package cn.ontorepo.api;

import cn.ontorepo.api.ApiDtos.Envelope;
import cn.ontorepo.common.domain.Problem;
import cn.ontorepo.ingest.application.SuggestionService;
import cn.ontorepo.mapping.application.ManagedFileService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 受管上传与三来源人工确认接口，文件系统路径永不接受客户端指定。 */
@RestController
@RequestMapping("/onto")
public class IngestController {
    private final ManagedFileService files;
    private final SuggestionService suggestions;
    private final Transport t;

    public IngestController(ManagedFileService files, SuggestionService suggestions, Transport t) {
        this.files = files;
        this.suggestions = suggestions;
        this.t = t;
    }

    @RequiresPermission("ingest.read")
    @GetMapping("/data-files")
    public Envelope<List<Map<String, Object>>> files(
            HttpServletRequest r, @RequestParam UUID spaceId) {
        return Envelope.ok(files.list(t.user(r), spaceId));
    }

    @RequiresPermission("ingest.write")
    @PostMapping(value = "/data-files", consumes = "multipart/form-data")
    public Envelope<Map<String, Object>> upload(
            HttpServletRequest r,
            @RequestParam UUID spaceId,
            @RequestParam long expectedDraftRev,
            @RequestPart("file") MultipartFile file) {
        if (file.getSize() > 8L * 1024 * 1024) throw Problem.bad("上传超过8MiB限制");
        try {
            return Envelope.ok(
                    files.upload(
                            t.user(r),
                            spaceId,
                            expectedDraftRev,
                            file.getOriginalFilename(),
                            file.getBytes()));
        } catch (java.io.IOException e) {
            throw Problem.bad("上传内容不可读取");
        }
    }

    @RequiresPermission("ingest.write")
    @PostMapping("/suggestions/extract")
    public Envelope<Map<String, Object>> extract(
            HttpServletRequest r, @RequestBody P3Dtos.ExtractWrite b) {
        return Envelope.ok(suggestions.extract(t.user(r), t.body(b)));
    }

    @RequiresPermission("ingest.read")
    @GetMapping("/suggestions")
    public Envelope<List<Map<String, Object>>> suggestions(
            HttpServletRequest r,
            @RequestParam UUID spaceId,
            @RequestParam(required = false) UUID batchId) {
        return Envelope.ok(suggestions.list(t.user(r), spaceId, batchId));
    }

    @RequiresPermission("ingest.write")
    @PostMapping("/suggestions/decide")
    public Envelope<Map<String, Object>> decide(
            HttpServletRequest r, @RequestBody P3Dtos.DecideWrite b) {
        return Envelope.ok(suggestions.decide(t.user(r), t.body(b)));
    }
}
