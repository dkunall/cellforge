package com.cellforge.cell.work.adapter.web;

import com.cellforge.cell.work.application.InboxService;
import com.cellforge.cell.work.application.InboxService.Digest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/me")
class InboxController {

    record ReadBody(@NotNull Instant upTo) {
    }

    private final InboxService inbox;

    InboxController(InboxService inbox) {
        this.inbox = inbox;
    }

    @GetMapping("/digest")
    Digest digest() {
        return inbox.digest();
    }

    @PostMapping("/notifications/read")
    Map<String, Integer> read(@Valid @RequestBody ReadBody body) {
        return Map.of("marked", inbox.markRead(body.upTo()));
    }
}
