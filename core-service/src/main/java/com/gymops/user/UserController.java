package com.gymops.user;

import com.gymops.common.api.PageRequests;
import com.gymops.common.api.PageResponse;
import com.gymops.user.dto.UserDtos.ChangeRolesRequest;
import com.gymops.user.dto.UserDtos.ChangeStatusRequest;
import com.gymops.user.dto.UserDtos.CreateStaffRequest;
import com.gymops.user.dto.UserDtos.UpdateProfileRequest;
import com.gymops.user.dto.UserDtos.UserResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Users")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private static final String ADMINS = "hasAnyRole('SUPER_ADMIN','GYM_ADMIN')";
    private static final Set<String> SORTABLE = Set.of("email", "fullName", "createdAt");

    private final UserService service;

    @GetMapping("/me")
    public UserResponse me() {
        return service.me();
    }

    @PutMapping("/me")
    public UserResponse updateMe(@Valid @RequestBody UpdateProfileRequest request) {
        return service.updateMyProfile(request);
    }

    @GetMapping
    @PreAuthorize(ADMINS)
    public PageResponse<UserResponse> search(@RequestParam(required = false) RoleName role,
                                             @RequestParam(required = false) UserStatus status,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size,
                                             @RequestParam(required = false) String sort) {
        return service.search(role, status, q, PageRequests.of(page, size, sort, SORTABLE, Sort.by("email")));
    }

    @GetMapping("/{id}")
    @PreAuthorize(ADMINS)
    public UserResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize(ADMINS)
    public ResponseEntity<UserResponse> createStaff(@Valid @RequestBody CreateStaffRequest request) {
        UserResponse created = service.createStaff(request);
        return ResponseEntity.created(URI.create("/api/v1/users/" + created.id())).body(created);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize(ADMINS)
    public UserResponse changeStatus(@PathVariable Long id, @Valid @RequestBody ChangeStatusRequest request) {
        return service.changeStatus(id, request.status());
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize(ADMINS)
    public UserResponse changeRoles(@PathVariable Long id, @Valid @RequestBody ChangeRolesRequest request) {
        return service.changeRoles(id, request.roles());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }
}
