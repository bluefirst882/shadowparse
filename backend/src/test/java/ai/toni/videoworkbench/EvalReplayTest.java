package ai.toni.videoworkbench;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * 摘要结构化输出的回放评测（P1-1）。
 *
 * <p>对同一批「模型原始返回字符串」同时跑三道闸门，产出可进 CI 的准确率与失败用例清单，并给出 P1-2 需要的改造前后对比数据：
 *
 * <ul>
 *   <li>{@code legacy} 旧口径：直接反序列化为 {@link TaskResult} 并只要求 summary / keyPoints / chapters 三个顶层字段非空，
 *       即 P1-2 改造前的行为；
 *   <li>{@code schema} 新口径第一步：复用线上同一份 {@link LlmClient#parseStructured}，空内容 / 非法 JSON / 不符 schema
 *       全部拦下；
 *   <li>{@code full} 新口径完整链路：schema 之上再加 {@link ResultValidator} 的时间轴与引文逐字核验（反幻觉闸门）。
 * </ul>
 *
 * <p>用例的可信边界（如实声明）：{@code positive/real-model-output} 的 summary / keyPoints / chapters 逐字取自 MySQL 中
 * 真实任务的模型返回（仅 JSON 对象键序被数据库规范化），其余正例按同一真实形态手写；负例是在该真实返回上构造的变异
 * （缺字段、类型错、编造引文、时间越界等），因此「旧口径误收数」衡量的是「哪几类结构非法输出旧口径发现不了」， 而不是真实线上输出中被旧口径放过的事故次数。
 *
 * <p>命令：{@code .\mvnw.cmd -f backend/pom.xml test -Dtest=EvalReplayTest}；报告落在 {@code
 * backend/target/eval-report.md}。
 */
class EvalReplayTest {
  /** 报告路径：相对 Maven 模块根目录（Surefire 的工作目录），即 backend/target/eval-report.md。 */
  private static final Path REPORT = Path.of("target", "eval-report.md");

  /**
   * 旧客户端用的是 Spring Boot 配置过的 ObjectMapper（忽略未知字段、允许整数写成小数）， 这里显式对齐，避免旧口径因为 mapper 默认值更严而被误判为「拒绝」。
   */
  private static final ObjectMapper JSON =
      new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  /** 真实转写样本：MySQL 中已完成任务 6493bbed-aa77-4707-abec-41a5e03f292d 的全部 105 个片段。 */
  private static final String TRANSCRIPT_RESOURCE = "eval/summarize-transcript.json";

  /** 真实模型返回：11 个章节的引文均通过过线上引文闸门（内容逐字取自数据库，键序按 schema 顺序排列）。 */
  private static final String REAL_MODEL_OUTPUT =
      """
      {
        "summary": "大四学生暑假从零开始做AI开发，接项目后月收入做到4000+，分享项目拆解与需求确认、用Obsidian维护架构和模块文档、任务拆小、提示词模板、测试先行、多窗口管理以及架构边界设计等经验。",
        "keyPoints": [
          "AI开发最重要的是把项目拆清楚、管明白，先和客户确认需求、功能和交付结果。",
          "需求确认后整理完整项目文档，写清功能范围、使用流程、数据结构、验收标准和暂不做内容。",
          "在Obsidian维护项目架构和模块文档，把任务拆成可独立完成的小步骤，并同步更新状态和测试。",
          "给AI提前准备固定任务模板，只提供当前任务相关架构、模块文档和验收标准，减少上下文污染。",
          "坚持先写测试再写实现，先确认测试失败，再让AI完成功能并跑到测试通过。",
          "不要让一轮对话承载太多事情，多开窗口节省token、减少记忆串现和改坏已完成功能。",
          "多窗口前提是提前设计项目架构、模块边界和任务依赖关系，否则窗口越多越混乱。",
          "AI是执行能力强的开发助手，Obsidian像项目大脑和导航地图，结合后可快速实现复杂项目。"
        ],
        "chapters": [
          {"startMs": 0, "endMs": 7940, "title": "介绍：大四学生暑假从零做AI开发，接项目月收入4000+", "sourceSegmentId": 3948, "sourceEndSegmentId": 3954, "quote": "转大四学生"},
          {"startMs": 7940, "endMs": 27340, "title": "项目拆解最重要：先确认需求，再整理完整项目文档", "sourceSegmentId": 3955, "sourceEndSegmentId": 3974, "quote": "我觉得"},
          {"startMs": 28360, "endMs": 37080, "title": "需求理解不一致容易导致项目失控", "sourceSegmentId": 3975, "sourceEndSegmentId": 3981, "quote": "因为很多项目最后做失败"},
          {"startMs": 37080, "endMs": 55400, "title": "用Obsidian维护项目架构和模块文档", "sourceSegmentId": 3982, "sourceEndSegmentId": 3989, "quote": "所以在需求确认之后"},
          {"startMs": 55400, "endMs": 61700, "title": "把任务拆成独立小步骤，更新状态并运行测试", "sourceSegmentId": 3990, "sourceEndSegmentId": 3995, "quote": "而不是笼统地告诉AI"},
          {"startMs": 61700, "endMs": 80680, "title": "用固定任务模板，只给AI当前任务相关上下文", "sourceSegmentId": 3996, "sourceEndSegmentId": 4010, "quote": "我给AI的提示词"},
          {"startMs": 80680, "endMs": 93080, "title": "坚持测试先行，再写实现，确保验收标准", "sourceSegmentId": 4011, "sourceEndSegmentId": 4021, "quote": "开发过程中"},
          {"startMs": 93080, "endMs": 107140, "title": "不要让一轮对话承载太多，多窗口节省token减少串现", "sourceSegmentId": 4022, "sourceEndSegmentId": 4031, "quote": "还有一个很重要的经验"},
          {"startMs": 107140, "endMs": 114220, "title": "多窗口的前提是提前设计架构、模块边界和依赖", "sourceSegmentId": 4032, "sourceEndSegmentId": 4037, "quote": "当然"},
          {"startMs": 114220, "endMs": 125500, "title": "把项目拆小写清楚，让AI在明确边界内推进", "sourceSegmentId": 4038, "sourceEndSegmentId": 4045, "quote": "这套方法不一定适合所有人"},
          {"startMs": 125500, "endMs": 134040, "title": "总结：AI是执行助手，Obsidian是项目大脑和导航地图", "sourceSegmentId": 4046, "sourceEndSegmentId": 4052, "quote": "总结一下"}
        ]
      }
      """;

  /** 手写正例：两个单片段章节，引文故意补了标点与空白，用于覆盖「格式差异不算幻觉」。 */
  private static final String SMALL_VALID_OUTPUT =
      """
      {
        "summary": "分享用AI接项目的方法：先沟通确认需求，再整理项目文档并在Obsidian维护架构与模块文档，最后测试先行地拆步推进。",
        "keyPoints": [
          "先和客户确认要解决的问题、具体功能与交付结果",
          "把任务拆成可独立完成的小步骤并同步更新状态与测试"
        ],
        "chapters": [
          {"startMs": 0, "endMs": 7940, "title": "开场：大四学生暑假做AI开发接项目", "sourceSegmentId": 3948, "sourceEndSegmentId": 3954, "quote": "转大四学生，暑假在家从零开始做AI开发，接项目之后，月收入做到了4000家"},
          {"startMs": 7940, "endMs": 11500, "title": "项目拆解与需求确认最重要", "sourceSegmentId": 3955, "sourceEndSegmentId": 3958, "quote": "我觉得 AI开发最重要的 是你能不能把一个项目拆清楚 管明白"}
        ]
      }
      """;

  /** 结构化闸门负责的失败原因：其余原因都由业务校验给出。 */
  private static final Set<String> STRUCTURE_REASONS =
      Set.of("empty_content", "invalid_json", "schema_violation");

  private record Case(String name, String output, boolean expectedAccept, String expectedReason) {}

  /** 一道闸门的判定：{@code reason} 仅在拒绝时有值。 */
  private record Gate(boolean accepted, String reason) {
    static Gate accept() {
      return new Gate(true, null);
    }

    static Gate reject(String reason) {
      return new Gate(false, reason);
    }
  }

  private record Outcome(Case testCase, Gate legacy, Gate schema, Gate full) {}

  @Test
  void replaysEvalSetThroughLegacyAndCurrentGates() throws Exception {
    List<TranscriptSegment> transcript = loadTranscript();
    List<Outcome> outcomes = new ArrayList<>();
    for (Case testCase : cases())
      outcomes.add(
          new Outcome(
              testCase,
              legacyGate(testCase.output()),
              schemaGate(testCase.output()),
              fullGate(testCase.output(), transcript)));

    String report = renderReport(outcomes);
    Files.createDirectories(REPORT.getParent());
    Files.writeString(REPORT, report, StandardCharsets.UTF_8);
    System.out.println(report);

    List<String> failures =
        outcomes.stream()
            .filter(outcome -> !matches(outcome))
            .map(EvalReplayTest::describe)
            .toList();
    assertTrue(failures.isEmpty(), "评测集存在与期望不一致的用例：\n" + String.join("\n", failures));
  }

  /** 旧口径：反序列化 + 三个顶层字段非空；任一步失败即视为拒绝（改造前的实现会在后续聚合时抛 NPE）。 */
  private static Gate legacyGate(String output) {
    TaskResult parsed;
    try {
      parsed = JSON.readValue(output, TaskResult.class);
    } catch (Exception ex) {
      return Gate.reject("legacy_parse_failure");
    }
    boolean empty =
        parsed.summary() == null
            || parsed.summary().isBlank()
            || parsed.keyPoints() == null
            || parsed.keyPoints().isEmpty()
            || parsed.chapters() == null
            || parsed.chapters().isEmpty();
    return empty ? Gate.reject("legacy_empty_field") : Gate.accept();
  }

  /** 新口径第一步：与线上完全同一份结构化闸门。 */
  private static Gate schemaGate(String output) {
    LlmClient.StructuredOutput parsed =
        LlmClient.parseStructured(JSON, output, LlmJsonSchema.SUMMARIZE);
    return parsed.ok() ? Gate.accept() : Gate.reject(parsed.reason());
  }

  /** 新口径完整链路：结构化闸门 + 业务校验（时间轴 + 引文逐字核验）。 */
  private static Gate fullGate(String output, List<TranscriptSegment> transcript) {
    LlmClient.StructuredOutput parsed =
        LlmClient.parseStructured(JSON, output, LlmJsonSchema.SUMMARIZE);
    if (!parsed.ok()) return Gate.reject(parsed.reason());
    try {
      new ResultValidator().validate(JSON.treeToValue(parsed.node(), TaskResult.class), transcript);
      return Gate.accept();
    } catch (ResultValidator.Failure failure) {
      return Gate.reject(failure.reason());
    } catch (Exception ex) {
      return Gate.reject("deserialization_failure");
    }
  }

  private static boolean matches(Outcome outcome) {
    return schemaVerdictMatches(outcome.schema(), outcome.testCase())
        && fullVerdictMatches(outcome.full(), outcome.testCase());
  }

  /** 结构化闸门只负责「结构」：结构违例必须被拦下且原因正确；结构本身合法、只是内容不合规的用例必须放行（交给业务校验）， 否则说明闸门口径与分层不符。 */
  private static boolean schemaVerdictMatches(Gate gate, Case testCase) {
    if (testCase.expectedAccept()) return gate.accepted();
    if (STRUCTURE_REASONS.contains(testCase.expectedReason()))
      return !gate.accepted() && gate.reason().equals(testCase.expectedReason());
    return gate.accepted();
  }

  /** 完整闸门即线上真实口径：接受 / 拒绝以及拒绝原因都必须与期望一致。 */
  private static boolean fullVerdictMatches(Gate gate, Case testCase) {
    if (gate.accepted() != testCase.expectedAccept()) return false;
    return gate.accepted() || gate.reason().equals(testCase.expectedReason());
  }

  private static String describe(Outcome outcome) {
    Case testCase = outcome.testCase();
    return "- "
        + testCase.name()
        + "：期望 "
        + (testCase.expectedAccept() ? "accept" : "reject/" + testCase.expectedReason())
        + "，schema 闸门="
        + label(outcome.schema())
        + "，完整闸门="
        + label(outcome.full());
  }

  private static String label(Gate gate) {
    return gate.accepted() ? "accept" : "reject/" + gate.reason();
  }

  private List<Case> cases() {
    return List.of(
        accept("positive/real-model-output", REAL_MODEL_OUTPUT),
        accept(
            "positive/fenced-with-trailing-note",
            "```json\n" + SMALL_VALID_OUTPUT + "```\n\n以上为本次转写的结构化结果，如需调整章节粒度请告知。\n"),
        accept("positive/punctuation-and-whitespace-rewritten-quote", SMALL_VALID_OUTPUT),
        accept(
            "positive/integer-written-as-decimal",
            SMALL_VALID_OUTPUT
                .replace("\"startMs\": 0,", "\"startMs\": 0.0,")
                .replace("\"endMs\": 7940,", "\"endMs\": 7940.0,")),
        accept(
            "positive/extra-unknown-fields",
            """
            {
              "language": "zh",
              "summary": "分享用AI接项目的方法：先沟通确认需求，再整理项目文档并在Obsidian维护架构与模块文档，最后测试先行地拆步推进。",
              "keyPoints": ["先和客户确认要解决的问题、具体功能与交付结果"],
              "chapters": [
                {"startMs": 0, "endMs": 7940, "title": "开场", "sourceSegmentId": 3948, "sourceEndSegmentId": 3954, "quote": "转大四学生", "confidence": 0.92}
              ]
            }
            """),
        reject(
            "negative/missing-chapters",
            """
            {"summary": "摘要", "keyPoints": ["要点"]}
            """,
            "schema_violation"),
        reject(
            "negative/chapters-as-object",
            """
            {"summary": "摘要", "keyPoints": ["要点"], "chapters": {"count": 0}}
            """,
            "schema_violation"),
        reject(
            "negative/title-as-number",
            """
            {"summary": "摘要", "keyPoints": ["要点"], "chapters": [
              {"startMs": 0, "endMs": 7940, "title": 20250927, "sourceSegmentId": 3948, "sourceEndSegmentId": 3954, "quote": "转大四学生"}
            ]}
            """,
            "schema_violation"),
        reject(
            "negative/missing-quote",
            """
            {"summary": "摘要", "keyPoints": ["要点"], "chapters": [
              {"startMs": 0, "endMs": 7940, "title": "开场", "sourceSegmentId": 3948, "sourceEndSegmentId": 3954}
            ]}
            """,
            "schema_violation"),
        reject(
            "negative/fabricated-quote",
            """
            {"summary": "摘要", "keyPoints": ["要点"], "chapters": [
              {"startMs": 0, "endMs": 7940, "title": "开场", "sourceSegmentId": 3948, "sourceEndSegmentId": 3954, "quote": "这段内容在转写里根本没有出现过"}
            ]}
            """,
            "quote_mismatch"),
        reject(
            "negative/quote-borrowed-from-another-segment",
            """
            {"summary": "摘要", "keyPoints": ["要点"], "chapters": [
              {"startMs": 0, "endMs": 7940, "title": "开场", "sourceSegmentId": 3948, "sourceEndSegmentId": 3954, "quote": "我觉得"}
            ]}
            """,
            "quote_mismatch"),
        reject(
            "negative/blank-key-point",
            """
            {"summary": "摘要", "keyPoints": ["把任务拆成可独立完成的小步骤", "   "], "chapters": [
              {"startMs": 0, "endMs": 7940, "title": "开场", "sourceSegmentId": 3948, "sourceEndSegmentId": 3954, "quote": "转大四学生"}
            ]}
            """,
            "empty_key_points"),
        reject(
            "negative/empty-summary",
            """
            {"summary": "", "keyPoints": ["要点"], "chapters": [
              {"startMs": 0, "endMs": 7940, "title": "开场", "sourceSegmentId": 3948, "sourceEndSegmentId": 3954, "quote": "转大四学生"}
            ]}
            """,
            "empty_summary"),
        reject(
            "negative/chapter-starts-before-its-segment",
            """
            {"summary": "摘要", "keyPoints": ["要点"], "chapters": [
              {"startMs": 7000, "endMs": 8300, "title": "开场", "sourceSegmentId": 3955, "sourceEndSegmentId": 3955, "quote": "我觉得"}
            ]}
            """,
            "invalid_chapter_timing"),
        reject(
            "negative/chapter-ends-after-its-segment",
            """
            {"summary": "摘要", "keyPoints": ["要点"], "chapters": [
              {"startMs": 0, "endMs": 2000, "title": "开场", "sourceSegmentId": 3948, "sourceEndSegmentId": 3948, "quote": "转大四学生"}
            ]}
            """,
            "invalid_chapter_timing"),
        reject(
            "negative/overlapping-chapters",
            """
            {"summary": "摘要", "keyPoints": ["要点"], "chapters": [
              {"startMs": 0, "endMs": 900, "title": "开场", "sourceSegmentId": 3948, "sourceEndSegmentId": 3948, "quote": "转大四学生"},
              {"startMs": 500, "endMs": 2500, "title": "自我介绍", "sourceSegmentId": 3949, "sourceEndSegmentId": 3949, "quote": "暑假在家从零开始做AI开发"}
            ]}
            """,
            "invalid_chapter_timing"),
        reject(
            "negative/unknown-source-segment",
            """
            {"summary": "摘要", "keyPoints": ["要点"], "chapters": [
              {"startMs": 0, "endMs": 900, "title": "开场", "sourceSegmentId": 99999, "sourceEndSegmentId": 99999, "quote": "转大四学生"}
            ]}
            """,
            "invalid_chapter_reference"),
        reject(
            "negative/empty-chapter-title",
            """
            {"summary": "摘要", "keyPoints": ["要点"], "chapters": [
              {"startMs": 0, "endMs": 900, "title": "", "sourceSegmentId": 3948, "sourceEndSegmentId": 3948, "quote": "转大四学生"}
            ]}
            """,
            "empty_chapter_title"),
        reject(
            "negative/truncated-json",
            """
            {"summary": "摘要", "keyPoints": ["要点"], "chapters": [{"startMs": 0,
            """,
            "invalid_json"),
        reject("negative/empty-content", "", "empty_content"));
  }

  private static Case accept(String name, String output) {
    return new Case(name, output, true, null);
  }

  private static Case reject(String name, String output, String expectedReason) {
    return new Case(name, output, false, expectedReason);
  }

  private static List<TranscriptSegment> loadTranscript() throws Exception {
    try (InputStream stream = EvalReplayTest.class.getResourceAsStream("/" + TRANSCRIPT_RESOURCE)) {
      if (stream == null) throw new IllegalStateException("缺少评测转写样本 " + TRANSCRIPT_RESOURCE);
      JsonNode fixture = JSON.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
      List<TranscriptSegment> transcript = new ArrayList<>();
      for (JsonNode segment : fixture.path("segments"))
        transcript.add(JSON.treeToValue(segment, TranscriptSegment.class));
      if (transcript.isEmpty()) throw new IllegalStateException("评测转写样本为空");
      return List.copyOf(transcript);
    }
  }

  private static String renderReport(List<Outcome> outcomes) {
    long positives =
        outcomes.stream().filter(outcome -> outcome.testCase().expectedAccept()).count();
    List<Outcome> legacyFalseAccepts =
        outcomes.stream()
            .filter(outcome -> outcome.legacy().accepted() && !outcome.testCase().expectedAccept())
            .toList();
    List<Outcome> legacyFalseRejects =
        outcomes.stream()
            .filter(outcome -> !outcome.legacy().accepted() && outcome.testCase().expectedAccept())
            .toList();

    StringBuilder report = new StringBuilder();
    report.append("# 摘要结构化输出回放评测报告\n\n");
    report.append("> 由 `EvalReplayTest` 生成：对同一批模型原始返回字符串同时跑「旧口径」与「新口径」，即 P1-2 的改造前后对比数据。\n\n");
    report
        .append("- 用例数：")
        .append(outcomes.size())
        .append("（正例 ")
        .append(positives)
        .append("，负例 ")
        .append(outcomes.size() - positives)
        .append("）\n");
    report
        .append("- 转写样本：`backend/src/test/resources/")
        .append(TRANSCRIPT_RESOURCE)
        .append("`（真实任务 6493bbed 的全部 105 个片段）\n");
    report
        .append("- 完整闸门（schema + 业务校验，即线上真实口径）准确率：")
        .append(
            correct(outcomes, outcome -> fullVerdictMatches(outcome.full(), outcome.testCase())))
        .append('\n');
    report
        .append("- 结构化闸门（只判结构，内容违规交业务校验）准确率：")
        .append(
            correct(
                outcomes, outcome -> schemaVerdictMatches(outcome.schema(), outcome.testCase())))
        .append('\n');
    report
        .append("- 旧口径准确率：")
        .append(
            correct(outcomes, outcome -> fullVerdictMatches(outcome.legacy(), outcome.testCase())))
        .append('\n');
    report.append("- **旧口径误收（旧口径通过、新口径拒绝）：**").append(legacyFalseAccepts.size()).append('\n');
    report.append("- 旧口径误杀（旧口径拒绝、期望通过）：").append(legacyFalseRejects.size()).append('\n');

    report.append("\n## 旧口径发现不了的用例\n\n");
    if (legacyFalseAccepts.isEmpty()) {
      report.append("无。\n");
    } else {
      report.append("| 用例 | 新口径判定 |\n| --- | --- |\n");
      for (Outcome outcome : legacyFalseAccepts)
        report
            .append("| ")
            .append(outcome.testCase().name())
            .append(" | ")
            .append(label(outcome.full()))
            .append(" |\n");
    }

    report.append("\n## 用例明细\n\n");
    report.append("| 用例 | 期望 | 旧口径 | schema 闸门 | 完整闸门 |\n| --- | --- | --- | --- | --- |\n");
    for (Outcome outcome : outcomes) {
      Case testCase = outcome.testCase();
      report
          .append("| ")
          .append(testCase.name())
          .append(" | ")
          .append(testCase.expectedAccept() ? "accept" : "reject/" + testCase.expectedReason())
          .append(" | ")
          .append(label(outcome.legacy()))
          .append(" | ")
          .append(label(outcome.schema()))
          .append(" | ")
          .append(label(outcome.full()))
          .append(" |\n");
    }
    return report.toString();
  }

  private static String correct(List<Outcome> outcomes, Predicate<Outcome> matches) {
    long passed = outcomes.stream().filter(matches).count();
    return passed + "/" + outcomes.size();
  }
}
