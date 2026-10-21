package cn.ontorepo.trace.application;

import cn.ontorepo.model.infra.*;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.shacl.ShaclValidator;
import org.springframework.stereotype.Component;

import java.util.*;

/** 候选实例通过真实Jena SHACL验证；校验图与RDF导出共享同一映射。 */
@Component
public class ShaclChecker {
    public Map<String, Object> check(Map<String, Object> p) {
        Model data = RdfCodec.model(p), shapes = ShaclSchema.build(p, data, true);
        try {
            var report = ShaclValidator.get().validate(shapes.getGraph(), data.getGraph());
            try {
                return Map.of(
                        "conforms", report.conforms(), "report", RdfCodec.ttl(report.getModel()));
            } finally {
                report.getModel().close();
            }
        } finally {
            data.close();
            shapes.close();
        }
    }
}
