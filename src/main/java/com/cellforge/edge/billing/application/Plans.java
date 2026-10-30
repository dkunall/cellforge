package com.cellforge.edge.billing.application;

import com.cellforge.edge.billing.domain.Plan;
import java.util.Optional;

public interface Plans {

    Optional<Plan> find(String code);
}
