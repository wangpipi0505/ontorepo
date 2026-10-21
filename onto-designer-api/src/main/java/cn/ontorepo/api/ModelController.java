package cn.ontorepo.api;

import cn.ontorepo.api.ApiDtos.ClassWrite;
import cn.ontorepo.api.ApiDtos.Envelope;
import cn.ontorepo.api.ApiDtos.OntClass;
import cn.ontorepo.api.ApiDtos.OntProperty;
import cn.ontorepo.api.ApiDtos.PropertyWrite;
import cn.ontorepo.api.ApiDtos.Space;
import cn.ontorepo.model.application.ModelService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** 对象和属性接口共享所属空间授权；删除必须携带草稿修订。 */
@RestController
@RequestMapping("/onto")
public class ModelController {
    private final ModelService model;
    private final Transport t;

    public ModelController(ModelService model, Transport t) {
        this.model = model;
        this.t = t;
    }

    /** 读取空间对象树。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "读取空间对象树")
    @RequiresPermission("model.read")
    @GetMapping("/classes")
    public Envelope<List<OntClass>> classes(HttpServletRequest r, @RequestParam UUID spaceId) {
        return Envelope.ok(t.list(model.classes(t.user(r), spaceId), OntClass.class));
    }

    /** 创建对象并校验父级归属。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "创建对象并校验父级归属")
    @RequiresPermission("model.write")
    @PostMapping("/classes")
    public Envelope<Space> createClass(HttpServletRequest r, @RequestBody ClassWrite b) {
        return Envelope.ok(t.as(model.saveClass(t.user(r), null, t.body(b)), Space.class));
    }

    /** 更新对象或移动父级并校验无环。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "更新对象或移动父级并校验无环")
    @RequiresPermission("model.write")
    @PutMapping("/classes/{id}")
    public Envelope<Space> updateClass(
            HttpServletRequest r, @PathVariable UUID id, @RequestBody ClassWrite b) {
        return Envelope.ok(t.as(model.saveClass(t.user(r), id, t.body(b)), Space.class));
    }

    /** 删除未被子类或属性引用的对象。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "删除未被子类或属性引用的对象")
    @RequiresPermission("model.write")
    @DeleteMapping("/classes/{id}")
    public Envelope<Space> deleteClass(
            HttpServletRequest r,
            @PathVariable UUID id,
            @RequestParam UUID spaceId,
            @RequestParam long expectedDraftRev) {
        return Envelope.ok(
                t.as(model.deleteClass(t.user(r), id, spaceId, expectedDraftRev), Space.class));
    }

    /** 读取同空间所属类的属性。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "读取同空间所属类的属性")
    @RequiresPermission("model.read")
    @GetMapping("/properties")
    public Envelope<List<OntProperty>> properties(
            HttpServletRequest r, @RequestParam UUID spaceId, @RequestParam UUID classId) {
        return Envelope.ok(
                t.list(model.properties(t.user(r), spaceId, classId), OntProperty.class));
    }

    /** 读取全空间属性供规则和约束选择，保留按类列表的原必填参数边界。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "读取全空间属性")
    @RequiresPermission("model.read")
    @GetMapping("/spaces/{id}/properties")
    public Envelope<List<OntProperty>> allProperties(HttpServletRequest r, @PathVariable UUID id) {
        return Envelope.ok(t.list(model.allProperties(t.user(r), id), OntProperty.class));
    }

    /** 创建基础属性并校验同类唯一。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "创建基础属性并校验同类唯一")
    @RequiresPermission("model.write")
    @PostMapping("/properties")
    public Envelope<Space> createProperty(HttpServletRequest r, @RequestBody PropertyWrite b) {
        return Envelope.ok(t.as(model.saveProperty(t.user(r), null, t.body(b)), Space.class));
    }

    /** 更新属性类型、必填与说明。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "更新属性类型、必填与说明")
    @RequiresPermission("model.write")
    @PutMapping("/properties/{id}")
    public Envelope<Space> updateProperty(
            HttpServletRequest r, @PathVariable UUID id, @RequestBody PropertyWrite b) {
        return Envelope.ok(t.as(model.saveProperty(t.user(r), id, t.body(b)), Space.class));
    }

    /** 删除基础属性并递增修订。 */
    @io.swagger.v3.oas.annotations.Operation(summary = "删除基础属性并递增修订")
    @RequiresPermission("model.write")
    @DeleteMapping("/properties/{id}")
    public Envelope<Space> deleteProperty(
            HttpServletRequest r,
            @PathVariable UUID id,
            @RequestParam UUID spaceId,
            @RequestParam long expectedDraftRev) {
        return Envelope.ok(
                t.as(model.deleteProperty(t.user(r), id, spaceId, expectedDraftRev), Space.class));
    }
}
