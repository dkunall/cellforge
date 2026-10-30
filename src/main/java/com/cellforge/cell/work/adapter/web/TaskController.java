package com.cellforge.cell.work.adapter.web;

import com.cellforge.cell.work.adapter.web.Views.TaskView;
import com.cellforge.cell.work.application.TaskService;
import com.cellforge.cell.work.application.TaskService.Created;
import com.cellforge.cell.work.application.TaskService.NewTask;
import com.cellforge.cell.work.application.TaskService.TaskChanges;
import com.cellforge.cell.work.domain.Comment;
import com.cellforge.cell.work.domain.Task;
import com.cellforge.cell.work.domain.TaskStatus;
import com.cellforge.shared.error.InvalidRequestException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
class TaskController {

    record NewTaskBody(@NotBlank @Size(max = 200) String title, @Size(max = 20000) String description,
                       TaskStatus status, String assigneeId) {
    }

    record MoveBody(@NotNull TaskStatus status, UUID afterId) {
    }

    record CommentBody(@NotBlank @Size(max = 10000) String body) {
    }

    private static final Set<String> PATCHABLE = Set.of("title", "description", "assigneeId");

    private final TaskService tasks;
    private final ObjectMapper json;

    TaskController(TaskService tasks, ObjectMapper json) {
        this.tasks = tasks;
        this.json = json;
    }

    @PostMapping("/projects/{projectId}/tasks")
    ResponseEntity<TaskView> create(@PathVariable UUID projectId, @Valid @RequestBody NewTaskBody body,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        if (key != null && (key.isBlank() || key.length() > 200)) {
            throw new InvalidRequestException("invalid-idempotency-key", "Idempotency-Key must be 1-200 characters");
        }
        Created created = tasks.create(projectId, new NewTask(body.title(), body.description(), body.status(),
            body.assigneeId()), key, key == null ? null : fingerprint(projectId, body));
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED)
            .eTag(Versions.etag(created.task().version()));
        if (created.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(TaskView.of(created.task()));
    }

    @GetMapping("/tasks/{id}")
    ResponseEntity<TaskView> get(@PathVariable UUID id) {
        Task task = tasks.get(id);
        return ResponseEntity.ok().eTag(Versions.etag(task.version())).body(TaskView.of(task));
    }

    /**
     * Merge-patch style: absent fields stay, {@code "assigneeId": null} unassigns.
     */
    @PatchMapping("/tasks/{id}")
    ResponseEntity<TaskView> update(@PathVariable UUID id, @RequestBody JsonNode body,
                                    @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        int expected = Versions.required(ifMatch);
        if (!body.isObject()) {
            throw new InvalidRequestException("validation-failed", "Body must be a JSON object");
        }
        body.fieldNames().forEachRemaining(f -> {
            if (!PATCHABLE.contains(f)) {
                throw new InvalidRequestException("validation-failed", "Cannot patch " + f + "; use /move for status");
            }
        });
        String title = text(body, "title", 200);
        if (title != null && title.isBlank()) {
            throw new InvalidRequestException("validation-failed", "title must not be blank");
        }
        TaskChanges changes = new TaskChanges(title, text(body, "description", 20000), body.has("assigneeId"),
            text(body, "assigneeId", 200));
        Task task = tasks.update(id, expected, changes);
        return ResponseEntity.ok().eTag(Versions.etag(task.version())).body(TaskView.of(task));
    }

    @PostMapping("/tasks/{id}/move")
    ResponseEntity<TaskView> move(@PathVariable UUID id, @Valid @RequestBody MoveBody body,
                                  @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        Task task = tasks.move(id, body.status(), body.afterId(), Versions.optional(ifMatch));
        return ResponseEntity.ok().eTag(Versions.etag(task.version())).body(TaskView.of(task));
    }

    @DeleteMapping("/tasks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID id) {
        tasks.delete(id);
    }

    @PostMapping("/tasks/{id}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    Comment comment(@PathVariable UUID id, @Valid @RequestBody CommentBody body) {
        return tasks.comment(id, body.body());
    }

    @GetMapping("/tasks/{id}/comments")
    List<Comment> comments(@PathVariable UUID id) {
        return tasks.comments(id);
    }

    private static String text(JsonNode body, String field, int max) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual() || node.asText().length() > max) {
            throw new InvalidRequestException("validation-failed", field + " must be a string of at most " + max + " characters");
        }
        return node.asText();
    }

    private String fingerprint(UUID projectId, NewTaskBody body) {
        try {
            byte[] bytes = (projectId + "|" + json.writeValueAsString(body)).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
