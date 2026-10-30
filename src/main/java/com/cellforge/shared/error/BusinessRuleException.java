package com.cellforge.shared.error;

import java.util.Map;

public class BusinessRuleException extends DomainException {

    public BusinessRuleException(String code, String message) {
        super(code, message);
    }

    public BusinessRuleException(String code, String message, Map<String, Object> details) {
        super(code, message, details);
    }
}
