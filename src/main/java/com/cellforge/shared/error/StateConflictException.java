package com.cellforge.shared.error;

import java.util.Map;

public class StateConflictException extends DomainException {

    public StateConflictException(String code, String message) {
        super(code, message);
    }

    public StateConflictException(String code, String message, Map<String, Object> details) {
        super(code, message, details);
    }
}
