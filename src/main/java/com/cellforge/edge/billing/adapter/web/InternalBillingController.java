package com.cellforge.edge.billing.adapter.web;

import com.cellforge.edge.billing.application.Billing;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class InternalBillingController {

    private final Billing billing;

    InternalBillingController(Billing billing) {
        this.billing = billing;
    }

    /** Runs the same sweep the scheduler does; for operators and for tests that move the clock. */
    @PostMapping("/internal/billing/run")
    Billing.RunResult run() {
        return billing.runDue();
    }
}
