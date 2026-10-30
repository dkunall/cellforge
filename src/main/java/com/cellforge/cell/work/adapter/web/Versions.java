package com.cellforge.cell.work.adapter.web;

import com.cellforge.shared.error.InvalidRequestException;
import com.cellforge.shared.error.PreconditionRequiredException;

/**
 * Task ETags are the row version in quotes. Clients echo one back in If-Match to edit; a missing header is
 * refused (428) so nobody overwrites a task they never read.
 */
final class Versions {

    private Versions() {
    }

    static String etag(int version) {
        return "\"" + version + "\"";
    }

    static int required(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new PreconditionRequiredException("if-match-required", "Send If-Match with the task's ETag");
        }
        return parse(ifMatch);
    }

    static Integer optional(String ifMatch) {
        return ifMatch == null || ifMatch.isBlank() ? null : parse(ifMatch);
    }

    private static int parse(String ifMatch) {
        String value = ifMatch.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        value = value.replace("\"", "");
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new InvalidRequestException("invalid-if-match", "If-Match must be a task ETag such as \"3\"");
        }
    }
}
