package br.com.ieeeiffcampos.patrimonio;

import java.util.Map;
import org.springframework.security.core.Authentication;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class InventoryController {
    private final InventoryService service;
    private final String hubUrl;
    public InventoryController(InventoryService service, @Value("${patrimonio.hub-url}") String hubUrl) {
        this.service = service;
        this.hubUrl = hubUrl;
    }

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("hubUrl", hubUrl);
        return "index";
    }

    @GetMapping("/api/inventory") @ResponseBody
    public Map<String, Object> inventory(Authentication authentication) {
        return Map.of("nodes",service.visibleNodes(authentication),"events",InventoryService.canEdit(authentication) ? service.events() : java.util.List.of());
    }
    @GetMapping("/api/session") @ResponseBody
    public Map<String,Object> session(Authentication authentication) {
        boolean admin=authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        return Map.of("username",authentication.getName(),"canEdit",InventoryService.canEdit(authentication),"canAdmin",admin);
    }

    @PostMapping("/api/nodes") @ResponseBody @ResponseStatus(HttpStatus.CREATED)
    public InventoryNode create(@Valid @RequestBody NodeRequest request) { return service.save(null, request); }

    @PutMapping("/api/nodes/{id}") @ResponseBody
    public InventoryNode update(@PathVariable String id, @Valid @RequestBody NodeRequest request) { return service.save(id, request); }

    @DeleteMapping("/api/nodes/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @ResponseBody
    public void delete(@PathVariable String id) { service.delete(id); }
}
