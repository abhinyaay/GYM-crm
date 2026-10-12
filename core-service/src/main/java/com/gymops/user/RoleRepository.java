package com.gymops.user;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, Short> {

    Optional<Role> findByName(RoleName name);

    Set<Role> findByNameIn(Collection<RoleName> names);
}
