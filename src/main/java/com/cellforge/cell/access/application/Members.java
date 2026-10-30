package com.cellforge.cell.access.application;

import com.cellforge.cell.access.domain.Member;
import com.cellforge.cell.access.domain.Role;
import java.util.List;
import java.util.Optional;

public interface Members {

    Optional<Member> find(String userId);

    List<Member> all();

    void insert(Member member);

    boolean updateRole(String userId, Role role);

    boolean delete(String userId);

    long seats();

    long countByRole(Role role);

    /** Serialises seat-changing transactions of the current tenant until commit. */
    void lockSeats();
}
