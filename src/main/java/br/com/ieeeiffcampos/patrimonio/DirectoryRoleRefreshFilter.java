package br.com.ieeeiffcampos.patrimonio;

import java.io.IOException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.ldap.userdetails.LdapUserDetails;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

public class DirectoryRoleRefreshFilter extends OncePerRequestFilter {
    private final DirectoryRoles directory;
    public DirectoryRoleRefreshFilter(DirectoryRoles directory) { this.directory=directory; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/assets/") || request.getRequestURI().equals("/login");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof LdapUserDetails user) {
            try {
                var roles = directory.currentRoles(user.getUsername());
                var updated = UsernamePasswordAuthenticationToken.authenticated(user,null,roles);
                updated.setDetails(authentication.getDetails());
                SecurityContextHolder.getContext().setAuthentication(updated);
                new HttpSessionSecurityContextRepository().saveContext(SecurityContextHolder.getContext(),request,response);
            } catch (org.springframework.ldap.NameNotFoundException removed) {
                SecurityContextHolder.clearContext();
                if (request.getSession(false)!=null) request.getSession(false).invalidate();
                response.sendError(401); return;
            } catch (org.springframework.ldap.NamingException unavailable) {
                response.setStatus(503); response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"message\":\"Não foi possível verificar as permissões no diretório. Tente novamente.\"}"); return;
            }
        }
        chain.doFilter(request,response);
    }
}
