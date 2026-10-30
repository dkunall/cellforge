package com.cellforge.cell.work.adapter.web;

import com.cellforge.cell.work.adapter.web.Views.ProjectView;
import com.cellforge.cell.work.adapter.web.Views.TaskView;
import com.cellforge.cell.work.application.ProjectService;
import com.cellforge.cell.work.application.ProjectService.Board;
import com.cellforge.cell.work.domain.Activity;
import com.cellforge.cell.work.domain.TaskStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/projects")
class ProjectController {

    record NewProject(@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9]{1,9}") String key, @NotBlank @Size(max = 120) String name) {
    }

    record BoardView(UUID projectId, String key, long boardVersion, Map<TaskStatus, List<TaskView>> columns) {
    }

    private final ProjectService projects;

    ProjectController(ProjectService projects) {
        this.projects = projects;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ProjectView create(@Valid @RequestBody NewProject body) {
        return ProjectView.of(projects.create(body.key(), body.name()));
    }

    @GetMapping
    List<ProjectView> list() {
        return projects.list().stream().map(ProjectView::of).toList();
    }

    @GetMapping("/{id}")
    ProjectView get(@PathVariable UUID id) {
        return ProjectView.of(projects.get(id));
    }

    @PostMapping("/{id}/archive")
    ProjectView archive(@PathVariable UUID id) {
        return ProjectView.of(projects.archive(id));
    }

    @PutMapping("/{id}/guests/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void openToGuest(@PathVariable UUID id, @PathVariable String userId) {
        projects.openToGuest(id, userId);
    }

    @DeleteMapping("/{id}/guests/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void closeToGuest(@PathVariable UUID id, @PathVariable String userId) {
        projects.closeToGuest(id, userId);
    }

    /**
     * The board is what clients poll, so it carries a weak ETag on the board version and answers 304 when
     * nothing moved. The version is read in the same transaction as the tasks, so the two always agree.
     */
    @GetMapping("/{id}/board")
    ResponseEntity<BoardView> board(@PathVariable UUID id,
                                    @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        Board board = projects.board(id);
        String etag = "W/\"b" + board.project().boardVersion() + "\"";
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        Map<TaskStatus, List<TaskView>> columns = new EnumMap<>(TaskStatus.class);
        for (TaskStatus s : TaskStatus.values()) {
            columns.put(s, board.tasks().stream().filter(t -> t.status() == s)
                .map(TaskView::of).toList());
        }
        return ResponseEntity.ok().eTag(etag)
            .body(new BoardView(id, board.project().key(), board.project().boardVersion(), columns));
    }

    @GetMapping("/{id}/activity")
    List<Activity> activity(@PathVariable UUID id, @RequestParam(defaultValue = "50") int limit) {
        return projects.activity(id, limit);
    }
}
