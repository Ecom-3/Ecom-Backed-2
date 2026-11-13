package com.metaarch.catalog.store;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/locations")
public class LocationController {
  private final StoreService service;
  private final com.metaarch.catalog.config.CatalogAccess access;

  public LocationController(StoreService service, com.metaarch.catalog.config.CatalogAccess access) {
    this.service = service;
    this.access = access;
  }

  @GetMapping
  @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','GLOBAL_ADMIN','SUPER_ADMIN','LOCATION_ADMIN')")
  List<StoreResponse> list(@RequestParam(required = false) Boolean active, Authentication auth) {
    List<StoreResponse> locations = service.listLocations(active);
    if (access.global(auth)) return locations;
    return locations.stream()
        .filter(location -> access.inLocation(auth, location.location())
            || access.inLocation(auth, location.id())
            || access.inLocation(auth, location.name()))
        .toList();
  }

  @PostMapping
  @org.springframework.security.access.prepost.PreAuthorize("@catalogAccess.superAdmin(authentication)")
  ResponseEntity<StoreResponse> create(@Valid @RequestBody StoreRequest request, Authentication auth) {
    requireLocationId(request.id());
    if (!access.superAdmin(auth)) throw new AccessDeniedException("Only Super Admins can manage locations.");
    StoreResponse location = service.create(request);
    return ResponseEntity.created(URI.create("/api/v1/locations/" + location.id())).body(location);
  }

  @PutMapping("/{id}")
  @org.springframework.security.access.prepost.PreAuthorize("@catalogAccess.superAdmin(authentication)")
  StoreResponse update(@PathVariable String id, @Valid @RequestBody StoreRequest request) {
    requireLocationId(id);
    if (!id.equals(request.id())) throw new IllegalArgumentException("Location ID cannot be changed.");
    return service.update(id, request);
  }

  @DeleteMapping("/{id}")
  @org.springframework.security.access.prepost.PreAuthorize("@catalogAccess.superAdmin(authentication)")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@PathVariable String id) {
    requireLocationId(id);
    service.delete(id);
  }

  private void requireLocationId(String id) {
    if (id == null || !id.startsWith("location-")) throw new IllegalArgumentException("Invalid location ID.");
  }
}
