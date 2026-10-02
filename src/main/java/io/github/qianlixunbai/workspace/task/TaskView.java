package io.github.qianlixunbai.workspace.task;

import io.github.qianlixunbai.workspace.common.ApiError;
import io.github.qianlixunbai.workspace.model.ModelProfile;
import java.time.Instant;
import java.util.UUID;

public record TaskView(UUID taskId, String capability, TaskStatus status,
                       ModelProfile.PublicProfile profile, String promptVersion,
                       Instant createdAt, Instant finishedAt, Object result, ApiError error) {
    @Override public String toString() { return "TaskView[taskId=" + taskId + ",status=" + status + "]"; }
}
