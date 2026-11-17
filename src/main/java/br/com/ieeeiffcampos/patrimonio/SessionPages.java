package br.com.ieeeiffcampos.patrimonio;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class SessionPages {
    @GetMapping("/login") String login() { return "login"; }
    @GetMapping("/admin/usuarios") String admin() { return "admin"; }

    @ControllerAdvice
    static class IdentityModel {
        private final DirectoryProperties directory;
        private final String hubUrl;
        IdentityModel(DirectoryProperties directory,@Value("${patrimonio.hub-url}") String hubUrl) {
            this.directory=directory; this.hubUrl=hubUrl;
        }
        @ModelAttribute void identity(Model model, Authentication authentication) {
            boolean admin=authentication!=null && authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
            model.addAttribute("username",authentication==null ? "" : authentication.getName());
            model.addAttribute("canEdit",InventoryService.canEdit(authentication)); model.addAttribute("canAdmin",admin);
            model.addAttribute("roleLabel",admin ? "Administrador" : InventoryService.canEdit(authentication) ? "Diretoria" : "Membro");
            model.addAttribute("directoryAdminGroup",directory.adminGroup());
            model.addAttribute("hubUrl",hubUrl); model.addAttribute("directoryWebUrl",directory.webUrl());
        }
    }
}
