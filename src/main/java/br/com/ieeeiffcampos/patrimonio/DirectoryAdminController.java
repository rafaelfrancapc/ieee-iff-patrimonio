package br.com.ieeeiffcampos.patrimonio;

import java.security.Principal;
import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
public class DirectoryAdminController {
    private final DirectoryAdminService service;
    public DirectoryAdminController(DirectoryAdminService service) { this.service=service; }
    public record CreateUser(@NotBlank @Pattern(regexp="[a-z0-9][a-z0-9._-]{1,63}") String id,
        @NotBlank @Size(max=120) String displayName,@NotBlank @Email @Size(max=254) String email) {}
    public record UpdateUser(@NotBlank @Size(max=120) String displayName,@NotBlank @Email @Size(max=254) String email) {}
    public record RoleRequest(@NotNull DirectoryAdminService.Role role) {}
    @GetMapping("/directory") public Map<String,Object> overview() { return service.overview(); }
    @PostMapping("/users") @ResponseStatus(HttpStatus.CREATED)
    public void create(@Valid @RequestBody CreateUser user,Principal actor) { service.create(user.id(),user.displayName(),user.email(),actor.getName()); }
    @PutMapping("/users/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void update(@PathVariable String id,@Valid @RequestBody UpdateUser user,Principal actor) { service.update(id,user.displayName(),user.email(),actor.getName()); }
    @PutMapping("/users/{id}/role") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void role(@PathVariable String id,@Valid @RequestBody RoleRequest request,Principal actor) { service.role(id,request.role(),actor.getName()); }
    @DeleteMapping("/users/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id,Principal actor) { service.delete(id,actor.getName()); }
}
