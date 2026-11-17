package br.com.ieeeiffcampos.patrimonio;

import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static br.com.ieeeiffcampos.patrimonio.DirectoryClient.*;

@Service
@PreAuthorize("hasRole('ADMIN')")
public class DirectoryAdminService {
    public enum Role { MEMBER, DIRECTOR, ADMIN }
    private final DirectoryClient client;
    private final DirectoryProperties config;
    private final JdbcTemplate jdbc;
    public DirectoryAdminService(DirectoryClient client,DirectoryProperties config,JdbcTemplate jdbc) {
        this.client=client; this.config=config; this.jdbc=jdbc;
    }
    public Map<String,Object> overview() {
        if (!config.managementConfigured()) return Map.of("configured",false,"users",List.of(),"groups",List.of());
        var data=client.query("query { users { id email displayName groups { id displayName } } groups { id displayName users { id } } }",Map.of());
        data.put("configured",true); data.put("directorGroup",config.directorGroup()); data.put("adminGroup",config.adminGroup());
        data.put("audit",jdbc.queryForList("SELECT actor,target,operation,occurred_at FROM directory_event ORDER BY id DESC LIMIT 100"));
        return data;
    }
    public synchronized void create(String id,String displayName,String email,String actor) {
        client.query("mutation($user:CreateUserInput!) { createUser(user:$user) { id } }",
            Map.of("user",Map.of("id",id,"displayName",displayName,"email",email)));
        audit(actor,id,"Cadastro de usuário");
    }
    public synchronized void update(String id,String displayName,String email,String actor) {
        client.query("mutation($user:UpdateUserInput!) { updateUser(user:$user) { ok } }",
            Map.of("user",Map.of("id",id,"displayName",displayName,"email",email)));
        audit(actor,id,"Atualização de usuário");
    }
    public synchronized void role(String id,Role role,String actor) {
        var data=overview();
        var users=list(data.get("users")); var groups=list(data.get("groups"));
        var user=users.stream().filter(u -> id.equals(u.get("id"))).findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Usuário não encontrado."));
        var admin=groups.stream().filter(g -> config.adminGroup().equals(g.get("displayName"))).findFirst();
        boolean isAdmin=list(user.get("groups")).stream().anyMatch(g -> config.adminGroup().equals(g.get("displayName")));
        if (role!=Role.ADMIN && isAdmin) {
            protect(id,actor);
            if (admin.isPresent() && list(admin.get().get("users")).size()<=1) throw conflict("Mantenha pelo menos um administrador.");
        }
        String target=role==Role.ADMIN ? config.adminGroup() : role==Role.DIRECTOR ? config.directorGroup() : null;
        if (target!=null && groups.stream().noneMatch(g -> target.equals(g.get("displayName")))) {
            var created=object(client.query("mutation($name:String!) { createGroup(name:$name) { id displayName } }",Map.of("name",target)).get("createGroup"));
            groups=new ArrayList<>(groups); groups.add(created);
        }
        // Grant first; if a later directory mutation fails, report the partial external state
        // instead of claiming an atomic rollback across independent LLDAP operations.
        if (target!=null) {
            var group=groups.stream().filter(g -> target.equals(g.get("displayName"))).findFirst().orElseThrow();
            boolean member=list(user.get("groups")).stream().anyMatch(g -> target.equals(g.get("displayName")));
            if (!member) membership("addUserToGroup",id,group.get("id"));
        }
        for (var group:list(user.get("groups"))) {
            String name=String.valueOf(group.get("displayName"));
            if ((name.equals(config.adminGroup()) || name.equals(config.directorGroup())) && !name.equals(target)) membership("removeUserFromGroup",id,group.get("id"));
        }
        audit(actor,id,"Papel: "+role.name());
    }
    public synchronized void delete(String id,String actor) {
        protect(id,actor);
        var data=overview();
        var admin=list(data.get("groups")).stream().filter(g -> config.adminGroup().equals(g.get("displayName"))).findFirst();
        if (admin.isPresent() && list(admin.get().get("users")).stream().anyMatch(u -> id.equals(u.get("id")))
                && list(admin.get().get("users")).size()<=1) throw conflict("Mantenha pelo menos um administrador.");
        client.query("mutation($id:String!) { deleteUser(userId:$id) { ok } }",Map.of("id",id));
        audit(actor,id,"Exclusão de usuário");
    }
    private void protect(String id,String actor) {
        if (id.equalsIgnoreCase(actor)) throw conflict("Você não pode remover seu próprio acesso administrativo.");
        if (id.equalsIgnoreCase(config.apiUsername())) throw conflict("Esta conta mantém a conexão administrativa do sistema.");
    }
    private void membership(String operation,String user,Object group) {
        client.query("mutation($user:String!,$group:Int!) { "+operation+"(userId:$user,groupId:$group) { ok } }",Map.of("user",user,"group",group));
    }
    private void audit(String actor,String target,String operation) {
        jdbc.update("INSERT INTO directory_event (actor,target,operation,occurred_at) VALUES (?,?,?,?)",actor,target,operation,OffsetDateTime.now());
    }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
}
