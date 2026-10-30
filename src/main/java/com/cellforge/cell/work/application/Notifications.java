package com.cellforge.cell.work.application;

import com.cellforge.cell.work.domain.Notification;
import java.time.Instant;
import java.util.List;

public interface Notifications {

    void insert(Notification notification);

    List<Notification> unread(String userId, int limit);

    long unreadCount(String userId);

    int markRead(String userId, Instant upTo);
}
