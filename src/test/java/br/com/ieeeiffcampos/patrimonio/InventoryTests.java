package br.com.ieeeiffcampos.patrimonio;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:inventory-tests;DB_CLOSE_DELAY=-1", "patrimonio.seed-demo=false"})
@AutoConfigureMockMvc
@org.springframework.security.test.context.support.WithMockUser(username="diretor",roles="DIRECTOR")
class InventoryTests {
    @Autowired InventoryService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    NodeRequest request(String parent, String name, InventoryNode.Kind kind) {
        return new NodeRequest(parent, name, "T-" + UUID.randomUUID(), kind, InventoryNode.Status.AVAILABLE,
            1, BigDecimal.ZERO, "Equipe IEEE", "Descrição do patrimônio", InventoryNode.Visibility.PUBLIC);
    }
    NodeRequest withParent(InventoryNode node, String parent) {
        return new NodeRequest(parent, node.name(), node.code(), node.kind(), node.status(), node.quantity(), node.unitValue(), node.responsible(), node.description(), node.visibility());
    }

    @Test void storesNestedItemsAndMovesEntireSubtree() {
        var room = service.save(null, request(null, "Sala", InventoryNode.Kind.SPACE));
        var desk = service.save(null, request(room.id(), "Mesa", InventoryNode.Kind.FURNITURE));
        var box = service.save(null, request(desk.id(), "Caixa", InventoryNode.Kind.CONTAINER));
        var kit = service.save(null, request(box.id(), "Kit", InventoryNode.Kind.ITEM));
        var sensor = service.save(null, request(kit.id(), "Sensor", InventoryNode.Kind.ITEM));
        var destination = service.save(null, request(room.id(), "Armário", InventoryNode.Kind.FURNITURE));
        service.save(box.id(), withParent(box, destination.id()));
        assertThat(service.nodes()).anyMatch(n -> n.id().equals(box.id()) && n.parentId().equals(destination.id()));
        assertThat(service.nodes()).anyMatch(n -> n.id().equals(sensor.id()) && n.parentId().equals(kit.id()));
        assertThat(service.events()).anyMatch(e -> e.get("nodeId").equals(box.id()) && e.get("action").equals("Movimentação") && e.get("destination").equals("Sala / Armário"));
        assertThat(jdbc.queryForObject("SELECT parent_id FROM inventory_node WHERE id=?", String.class, box.id())).isEqualTo(destination.id());
    }

    @Test void refusesCyclesOrOrphansAndPreservesHierarchy() {
        var room = service.save(null, request(null, "Sala protegida", InventoryNode.Kind.SPACE));
        var box = service.save(null, request(room.id(), "Caixa protegida", InventoryNode.Kind.CONTAINER));
        assertThatThrownBy(() -> service.save(room.id(), withParent(room, box.id())))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("si mesmo");
        assertThatThrownBy(() -> service.save(box.id(), withParent(box, box.id())))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.delete(room.id())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("conteúdos");
        assertThatThrownBy(() -> service.save(null, request("missing", "Órfão", InventoryNode.Kind.ITEM)))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.save(null, request(null, "Sem local", InventoryNode.Kind.ITEM)))
            .isInstanceOf(ResponseStatusException.class);
        assertThat(service.nodes()).anyMatch(n -> n.id().equals(room.id()) && n.parentId() == null);
    }

    @Test void rejectsDuplicateCodesIgnoringCase() {
        var room = service.save(null, request(null, "Sala única", InventoryNode.Kind.SPACE));
        var duplicate = new NodeRequest(null, "Outra sala", room.code().toLowerCase(), room.kind(), room.status(), 1, BigDecimal.ZERO, "", "",InventoryNode.Visibility.PUBLIC);
        assertThatThrownBy(() -> service.save(null, duplicate)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("código");
    }

    @Test void recordsDeletionWithoutRemovingAuditTrail() {
        var room = service.save(null, request(null, "Sala temporária", InventoryNode.Kind.SPACE));
        var item = service.save(null, request(room.id(), "Item temporário", InventoryNode.Kind.ITEM));
        service.delete(item.id());
        assertThat(service.nodes()).noneMatch(n -> n.id().equals(item.id()));
        assertThat(service.events()).anyMatch(e -> e.get("nodeId").equals(item.id()) && e.get("action").equals("Exclusão"));
    }

    @Test void rendersDashboardAndProtectsMutationsWithCsrf() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("<h1>Patrimônio</h1>")))
            .andExpect(header().exists("Content-Security-Policy"));
        mvc.perform(get("/assets/patrimonio.js")).andExpect(status().isOk());
        mvc.perform(get("/api/inventory")).andExpect(status().isOk()).andExpect(jsonPath("$.nodes").isArray());
        mvc.perform(post("/api/nodes").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/nodes").with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
    }

    @Test void apiValidatesQuantitiesAndPersistsCreateUpdateDelete() throws Exception {
        var payload = request(null, "Sala pela API", InventoryNode.Kind.SPACE);
        var created = mvc.perform(post("/api/nodes").with(csrf()).contentType("application/json").content(json.writeValueAsString(payload)))
            .andExpect(status().isCreated()).andReturn();
        var id = json.readTree(created.getResponse().getContentAsString()).get("id").asText();
        var invalid = Map.of("name", "Inválido", "code", "INVALID", "kind", "ITEM", "status", "AVAILABLE", "parentId", id,
            "quantity", -1, "unitValue", 0, "responsible", "", "description", "", "visibility", "PUBLIC");
        mvc.perform(post("/api/nodes").with(csrf()).contentType("application/json").content(json.writeValueAsString(invalid)))
            .andExpect(status().isBadRequest());
        var updated = new NodeRequest(null, "Sala renomeada", payload.code(), payload.kind(), payload.status(), 1, BigDecimal.ZERO, "Equipe", "",InventoryNode.Visibility.PUBLIC);
        mvc.perform(put("/api/nodes/" + id).with(csrf()).contentType("application/json").content(json.writeValueAsString(updated)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Sala renomeada"));
        mvc.perform(delete("/api/nodes/" + id).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/nodes/" + id).with(csrf())).andExpect(status().isNotFound());
    }

    @Test void transactionRollsBackNodeWhenAuditWriteFails() {
        var room = service.save(null, request(null, "Sala transacional", InventoryNode.Kind.SPACE));
        jdbc.execute("ALTER TABLE inventory_event ADD CONSTRAINT test_reject_event CHECK (node_name <> 'Falha controlada')");
        try {
            assertThatThrownBy(() -> service.save(null, request(room.id(), "Falha controlada", InventoryNode.Kind.ITEM)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(service.nodes()).noneMatch(n -> n.name().equals("Falha controlada"));
        } finally {
            jdbc.execute("ALTER TABLE inventory_event DROP CONSTRAINT test_reject_event");
        }
    }
}
