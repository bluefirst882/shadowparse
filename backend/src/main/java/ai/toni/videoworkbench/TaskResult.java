package ai.toni.videoworkbench;

import java.util.List;

public record TaskResult(String summary, List<String> keyPoints, List<Chapter> chapters) {}
