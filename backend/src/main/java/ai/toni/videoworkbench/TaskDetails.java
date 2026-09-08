package ai.toni.videoworkbench;
import java.util.List;
public record TaskDetails(VideoTask task, List<TranscriptSegment> transcript, TaskResult result) { }
