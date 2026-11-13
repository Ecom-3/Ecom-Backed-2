package com.metaarch.orgaccess.identity;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {
  private final IdentityService identities;
  public UserController(IdentityService identities) { this.identities = identities; }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public UserSummary create(@Valid @RequestBody UserCreateRequest request, Authentication authentication) {
    UserAccount user = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_LOCATION_ADMIN"))
        ? identities.createManagedStoreManager(request, authentication.getName())
        : identities.create(request, authentication.getName());
    return new UserSummary(user.getId(), user.getUsername(), user.getEmail());
  }

  @GetMapping
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public java.util.List<IdentityService.StaffSummary> list(Authentication authentication) {
    return identities.listStaff(authentication.getName());
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public UserSummary update(@PathVariable Long id, @Valid @RequestBody UserUpdateRequest request,
      Authentication authentication) {
    UserAccount user = authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_LOCATION_ADMIN"))
        ? identities.updateManagedStoreManager(id, request, authentication.getName())
        : identities.updateStaff(id, request, authentication.getName());
    return new UserSummary(user.getId(), user.getUsername(), user.getEmail());
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public void delete(@PathVariable Long id, Authentication authentication) {
    identities.deleteStaff(id, authentication.getName());
  }

  @PostMapping("/{id}/password-reset")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public void resetStoreManagerPassword(@PathVariable Long id,
      @Valid @RequestBody StaffPasswordResetRequest request, Authentication authentication) {
    boolean locationAdmin = authentication.getAuthorities().stream()
        .anyMatch(authority -> authority.getAuthority().equals("ROLE_LOCATION_ADMIN"));
    if (locationAdmin) identities.resetLocationManagedStaffPassword(id, request.newPassword(), authentication.getName());
    else identities.resetStoreManagerPassword(id, request.newPassword(), authentication.getName());
  }

  @PutMapping("/me/password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void changeMyPassword(@Valid @RequestBody PasswordChangeRequest request, Authentication authentication) {
    identities.changePassword(authentication.getName(), request.currentPassword(), request.newPassword());
  }

  public record UserSummary(Long id, String username, String email) {}
}
