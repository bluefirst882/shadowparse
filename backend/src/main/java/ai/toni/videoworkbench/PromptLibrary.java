package ai.toni.videoworkbench;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提示词的唯一来源：正文放在带版本号的资源文件里（{@code prompts/<name>.<version>.system.txt} 与 {@code
 * prompts/<name>.<version>.user.txt}），代码只负责注入 schema 原文、输入文本与重试提示。
 *
 * <p>这样做的理由是 prompt 也是需要「版本」的产物：改了措辞就该换版本号，评测与成本才能按版本对比。
 * 资源文件缺失、占位符没被赋值都在启动时直接失败，避免线上出现「提示词少了一半」的静默降级。
 */
final class PromptLibrary {

  /** 摘要：单块生成 summary / keyPoints / chapters。 */
  static final Prompt SUMMARIZE = load("summarize", "v1");

  /** 跨块合并：只整合纯文本，不产出 chapters。 */
  static final Prompt MERGE = load("merge", "v1");

  /** 字幕翻译。 */
  static final Prompt TRANSLATE = load("translate", "v1");

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

  private PromptLibrary() {}

  /** 一次调用所需的提示词：{@code id} 形如 {@code summarize.v1}，用于日志与成本归因。 */
  static final class Prompt {
    private final String id;
    private final String system;
    private final String userTemplate;

    private Prompt(String id, String system, String userTemplate) {
      this.id = id;
      this.system = system;
      this.userTemplate = userTemplate;
    }

    String id() {
      return id;
    }

    String system() {
      return system;
    }

    /** 用取值替换 {@code {{name}}} 占位符；取值缺失即失败，不做"留个空串跑下去"的兜底。 */
    String user(Map<String, String> values) {
      Matcher matcher = PLACEHOLDER.matcher(userTemplate);
      StringBuilder rendered = new StringBuilder();
      while (matcher.find()) {
        String name = matcher.group(1);
        String value = values.get(name);
        if (value == null) throw new IllegalStateException("提示词 " + id + " 缺少占位符取值 " + name);
        matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
      }
      matcher.appendTail(rendered);
      return rendered.toString();
    }
  }

  private static Prompt load(String name, String version) {
    String base = "/prompts/" + name + "." + version;
    return new Prompt(name + "." + version, read(base + ".system.txt"), read(base + ".user.txt"));
  }

  private static String read(String resource) {
    try (InputStream stream = PromptLibrary.class.getResourceAsStream(resource)) {
      if (stream == null) throw new IllegalStateException("缺少提示词资源 " + resource);
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException("读取提示词资源失败 " + resource, ex);
    }
  }
}
