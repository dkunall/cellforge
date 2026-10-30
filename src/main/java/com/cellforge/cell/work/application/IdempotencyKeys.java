package com.cellforge.cell.work.application;

import java.util.Optional;

public interface IdempotencyKeys {

    record Stored(String requestHash, Integer status, String body) {
    }

    /**
     * Claims the key inside the caller's transaction. Returns empty when this caller now owns it; otherwise
     * the earlier attempt's record. A concurrent first attempt makes this block until it finishes.
     */
    Optional<Stored> claim(String userId, String key, String requestHash);

    void complete(String userId, String key, int status, String body);
}
