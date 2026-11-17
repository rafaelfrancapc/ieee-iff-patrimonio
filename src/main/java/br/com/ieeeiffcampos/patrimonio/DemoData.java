package br.com.ieeeiffcampos.patrimonio;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DemoData implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    public DemoData(JdbcTemplate jdbc, @Value("${patrimonio.seed-demo}") boolean enabled) {
        this.jdbc = jdbc; this.enabled = enabled;
    }
    @Override @Transactional
    public void run(ApplicationArguments args) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM app_metadata WHERE meta_key='initialized'", Integer.class) > 0) return;
        if (enabled && jdbc.queryForObject("SELECT COUNT(*) FROM inventory_node", Integer.class) == 0) {
            add("sala", null, "Sala do Ramo", "ESP-001", "SPACE", "AVAILABLE", 1, "0", "Gestão do Ramo", "Espaço de trabalho e convivência do IEEE IFF Campos Centro. Bloco A · sala 204.");
            add("lab", null, "Laboratório de projetos", "ESP-002", "SPACE", "AVAILABLE", 1, "0", "Equipe de projetos", "Bancadas e equipamentos para desenvolvimento dos projetos. Bloco B · sala 102.");
            add("deposito", null, "Depósito de materiais", "ESP-003", "SPACE", "AVAILABLE", 1, "0", "Gestão do Ramo", "Materiais de apoio e organização de eventos.");
            add("mesa", "sala", "Mesa de trabalho", "MOV-001", "FURNITURE", "IN_USE", 1, "650", "Gestão do Ramo", "À esquerda da entrada, junto à janela.");
            add("armario", "sala", "Armário de equipamentos", "MOV-002", "FURNITURE", "AVAILABLE", 1, "980", "Equipe de projetos", "Canto direito da sala. Chave com a gestão do Ramo.");
            add("bancada", "sala", "Bancada de prototipagem", "MOV-003", "FURNITURE", "IN_USE", 1, "1200", "Equipe RAS", "Parede ao fundo, próxima às tomadas.");
            add("estante", "sala", "Estante de apoio", "MOV-004", "FURNITURE", "AVAILABLE", 1, "430", "Gestão do Ramo", "Ao lado da porta de entrada.");
            add("notebook", "mesa", "Notebook Dell", "EQP-001", "ITEM", "IN_USE", 1, "3800", "Gestão do Ramo", "Notebook de uso compartilhado, com carregador.");
            add("monitor", "mesa", "Monitor 24 polegadas", "EQP-002", "ITEM", "AVAILABLE", 1, "890", "Gestão do Ramo", "Monitor de apoio para reuniões e atividades.");
            add("gaveta", "mesa", "Gaveta de materiais", "REC-001", "CONTAINER", "AVAILABLE", 1, "0", "Gestão do Ramo", "Primeira gaveta da mesa.");
            add("papelaria", "gaveta", "Kit de papelaria", "MAT-001", "ITEM", "AVAILABLE", 3, "25", "Gestão do Ramo", "Canetas, marcadores e bloco de anotações.");
            add("cabos", "gaveta", "Cabos USB-C", "MAT-002", "ITEM", "AVAILABLE", 4, "35", "Gestão do Ramo", "Cabos para carregamento e transferência de dados.");
            add("caixa", "armario", "Caixa de eletrônica", "REC-002", "CONTAINER", "AVAILABLE", 1, "45", "Equipe RAS", "Caixa organizadora transparente na prateleira superior.");
            add("arduino", "caixa", "Arduino Uno", "EQP-003", "ITEM", "AVAILABLE", 5, "85", "Equipe RAS", "Placas para oficinas de introdução à eletrônica.");
            add("sensores", "caixa", "Estojo de sensores", "REC-003", "CONTAINER", "AVAILABLE", 1, "20", "Equipe RAS", "Estojo azul dentro da caixa de eletrônica.");
            add("ultrassom", "sensores", "Sensor ultrassônico", "MAT-003", "ITEM", "AVAILABLE", 6, "12", "Equipe RAS", "Módulos HC-SR04.");
            add("multimetro", "armario", "Multímetro digital", "EQP-004", "ITEM", "MAINTENANCE", 1, "180", "Equipe RAS", "Aguardando substituição das pontas de prova.");
            add("projetor", "armario", "Projetor Epson", "EQP-005", "ITEM", "AVAILABLE", 1, "2900", "Gestão do Ramo", "Guardado na prateleira central com controle remoto.");
            add("solda", "bancada", "Estação de solda", "EQP-006", "ITEM", "IN_USE", 1, "450", "Equipe RAS", "Bancada do fundo. Usar com suporte e ventilação.");
            add("ferramentas", "bancada", "Maleta de ferramentas", "REC-004", "CONTAINER", "AVAILABLE", 1, "150", "Equipe RAS", "Maleta de apoio às atividades de prototipagem.");
            add("alicate", "ferramentas", "Alicate de corte", "FER-001", "ITEM", "AVAILABLE", 2, "35", "Equipe RAS", "Alicates de corte para eletrônica.");
            add("livros", "estante", "Livros técnicos", "MAT-004", "ITEM", "AVAILABLE", 8, "60", "Gestão do Ramo", "Acervo de consulta do Ramo.");
            add("impressora", "lab", "Impressora 3D", "EQP-007", "ITEM", "MAINTENANCE", 1, "2200", "Equipe de projetos", "Em manutenção: calibração da mesa.");
            add("banners", "deposito", "Banners institucionais", "MAT-005", "ITEM", "AVAILABLE", 3, "120", "Gestão do Ramo", "Banners de apoio a eventos do IEEE.");
            jdbc.update("INSERT INTO inventory_event (node_id,node_name,action,origin,destination,occurred_at) VALUES (?,?,?,?,?,?)",
                "sala", "Base de demonstração", "Carga inicial", "", "24 registros de exemplo", OffsetDateTime.now());
        }
        jdbc.update("INSERT INTO app_metadata VALUES ('initialized','true')");
    }
    private void add(String id, String parent, String name, String code, String kind, String status,
                     int quantity, String value, String responsible, String description) {
        jdbc.update("INSERT INTO inventory_node (id,parent_id,name,code,kind,status,quantity,unit_value,responsible,description,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)", id, parent, name, code,
            kind, status, quantity, new BigDecimal(value), responsible, description, OffsetDateTime.now());
    }
}
