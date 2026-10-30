package com.cellforge.cell.work.application;

import com.cellforge.cell.work.domain.Comment;
import java.util.List;
import java.util.UUID;

public interface Comments {

    void insert(Comment comment);

    List<Comment> forTask(UUID taskId);
}
