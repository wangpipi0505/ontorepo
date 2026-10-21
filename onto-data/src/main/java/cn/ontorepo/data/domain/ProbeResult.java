package cn.ontorepo.data.domain;

import java.util.List;

/** 三个阶段独立报告，失败后的阶段必须 NOT_RUN。 */
public record ProbeResult(List<Stage> stages) {
  public enum Status {
    PASSED,
    FAILED,
    NOT_RUN
  }

  public record Stage(String name, Status status, String code) {}

  public ProbeResult {
    stages = List.copyOf(stages);
  }

  public static ProbeResult failed(int stage, String code) {
    var names = List.of("CONNECTIVITY", "AUTHENTICATION", "SCOPE");
    return new ProbeResult(
        java.util.stream.IntStream.range(0, 3)
            .mapToObj(
                i ->
                    new Stage(
                        names.get(i),
                        i < stage ? Status.PASSED : i == stage ? Status.FAILED : Status.NOT_RUN,
                        i == stage ? code : null))
            .toList());
  }

  public static ProbeResult passed() {
    return new ProbeResult(
        List.of(
            new Stage("CONNECTIVITY", Status.PASSED, null),
            new Stage("AUTHENTICATION", Status.PASSED, null),
            new Stage("SCOPE", Status.PASSED, null)));
  }
}
