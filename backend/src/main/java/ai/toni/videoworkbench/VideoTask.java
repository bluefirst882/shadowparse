package ai.toni.videoworkbench;
import java.time.Instant;
public record VideoTask(String id, String fileName, String videoPath, long sizeBytes, TaskStatus status, TaskStage stage, int progress, String errorMessage, Instant createdAt, Instant updatedAt) {}

