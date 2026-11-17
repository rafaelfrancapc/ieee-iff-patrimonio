package br.com.ieeeiffcampos.patrimonio;

import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InventoryService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public InventoryService(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
    }

    public List<InventoryNode> nodes() {
        return jdbc.query("SELECT * FROM inventory_node ORDER BY kind, name, id", (rs, row) ->
            new InventoryNode(rs.getString("id"), rs.getString("parent_id"), rs.getString("name"),
                rs.getString("code"), InventoryNode.Kind.valueOf(rs.getString("kind")),
                InventoryNode.Status.valueOf(rs.getString("status")), rs.getInt("quantity"),
                rs.getBigDecimal("unit_value"), rs.getString("responsible"), rs.getString("description"),
                rs.getObject("updated_at", OffsetDateTime.class), InventoryNode.Visibility.valueOf(rs.getString("visibility"))));
    }

    public List<Map<String, Object>> events() {
        return jdbc.query("SELECT * FROM inventory_event ORDER BY id DESC LIMIT 200", (rs, row) -> {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("actor",rs.getString("actor"));
            event.put("id", rs.getLong("id")); event.put("nodeId", rs.getString("node_id"));
            event.put("name", rs.getString("node_name")); event.put("action", rs.getString("action"));
            event.put("origin", rs.getString("origin")); event.put("destination", rs.getString("destination"));
            event.put("occurredAt", rs.getObject("occurred_at", OffsetDateTime.class));
            return event;
        });
    }

    // Serialize mutations inside this single-instance application: validation and write must
    // see the same hierarchy so two concurrent moves cannot create a cycle.
    @PreAuthorize("hasAnyRole('DIRECTOR','ADMIN')")
    public synchronized InventoryNode save(String id, NodeRequest request) {
        return saveLocked(id, request);
    }

    private InventoryNode saveLocked(String id, NodeRequest request) {
        var all = nodes();
        InventoryNode old = id == null ? null : require(all, id);
        String parent = request.parentId() == null || request.parentId().isBlank() ? null : request.parentId();
        if (parent == null && request.kind() != InventoryNode.Kind.SPACE)
            throw bad("Selecione um local para guardar este patrimônio.");
        if (parent != null) {
            require(all, parent);
            Set<String> visited = new HashSet<>();
            for (String current = parent; current != null; current = require(all, current).parentId()) {
                if (current.equals(id) || !visited.add(current))
                    throw bad("Um item não pode ficar dentro de si mesmo ou de um de seus conteúdos.");
            }
        }
        if (all.stream().anyMatch(n -> n.code().equalsIgnoreCase(request.code().strip()) && !n.id().equals(id)))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Este código patrimonial já está em uso.");
        if (request.kind() == InventoryNode.Kind.SPACE && (request.quantity() != 1 || request.unitValue().signum() != 0))
            throw bad("Espaços devem ter quantidade 1 e valor zero.");
        if (request.name().strip().isEmpty() || request.code().strip().isEmpty()) throw bad("Informe nome e código.");
        // A move must not silently declassify a subtree that inherited secrecy from its old parent.
        InventoryNode.Visibility visibility = old != null && !Objects.equals(old.parentId(),parent)
            && confidential(all,old.id()) ? InventoryNode.Visibility.CONFIDENTIAL : request.visibility();
        String nodeId = id == null ? UUID.randomUUID().toString() : id;
        String origin = old == null ? "" : path(all, old.parentId());
        String destination = path(all, parent);
        OffsetDateTime now = OffsetDateTime.now();
        // TransactionTemplate keeps both the record and its audit event atomic while the
        // public synchronized method holds the hierarchy lock through the commit.
        transaction.executeWithoutResult(status -> {
            if (old == null) {
                jdbc.update("INSERT INTO inventory_node (id,parent_id,name,code,kind,status,quantity,unit_value,responsible,description,updated_at,visibility) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", nodeId, parent,
                    request.name().strip(), request.code().strip(), request.kind().name(), request.status().name(),
                    request.quantity(), request.unitValue(), request.responsible().strip(), request.description().strip(), now, visibility.name());
            } else {
                jdbc.update("UPDATE inventory_node SET parent_id=?, name=?, code=?, kind=?, status=?, quantity=?, unit_value=?, responsible=?, description=?, updated_at=?, visibility=? WHERE id=?",
                    parent, request.name().strip(), request.code().strip(), request.kind().name(), request.status().name(),
                    request.quantity(), request.unitValue(), request.responsible().strip(), request.description().strip(), now, visibility.name(), nodeId);
            }
            String action = old == null ? "Cadastro" : Objects.equals(old.parentId(), parent) ? "Atualização" : "Movimentação";
            jdbc.update("INSERT INTO inventory_event (node_id,node_name,action,origin,destination,occurred_at,actor) VALUES (?,?,?,?,?,?,?)",
                nodeId, request.name().strip(), action, origin, destination, now, actor());
        });
        return require(nodes(), nodeId);
    }

    @PreAuthorize("hasAnyRole('DIRECTOR','ADMIN')")
    public synchronized void delete(String id) {
        var all = nodes();
        var node = require(all, id);
        if (all.stream().anyMatch(n -> id.equals(n.parentId())))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mova ou remova os conteúdos antes de excluir este local.");
        transaction.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM inventory_node WHERE id=?", id);
            jdbc.update("INSERT INTO inventory_event (node_id,node_name,action,origin,destination,occurred_at,actor) VALUES (?,?,?,?,?,?,?)",
                id, node.name(), "Exclusão", path(all, node.parentId()), "", OffsetDateTime.now(), actor());
        });
    }

    @PreAuthorize("isAuthenticated()")
    public List<InventoryNode> visibleNodes(Authentication authentication) {
        var all=nodes();
        if (canEdit(authentication)) return all;
        return all.stream().filter(node -> !confidential(all,node.id())).toList();
    }
    public static boolean canEdit(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_DIRECTOR") || a.getAuthority().equals("ROLE_ADMIN"));
    }
    private boolean confidential(List<InventoryNode> all,String id) {
        while (id!=null) {
            var node=require(all,id);
            if (node.visibility()==InventoryNode.Visibility.CONFIDENTIAL) return true;
            id=node.parentId();
        }
        return false;
    }
    private String actor() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        return auth==null ? "Sistema" : auth.getName();
    }

    private InventoryNode require(List<InventoryNode> all, String id) {
        return all.stream().filter(n -> n.id().equals(id)).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Patrimônio ou local não encontrado."));
    }

    private String path(List<InventoryNode> all, String id) {
        List<String> names = new ArrayList<>();
        while (id != null) {
            var node = require(all, id); names.add(node.name()); id = node.parentId();
        }
        Collections.reverse(names);
        return names.isEmpty() ? "Raiz dos espaços" : String.join(" / ", names);
    }

    private ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
