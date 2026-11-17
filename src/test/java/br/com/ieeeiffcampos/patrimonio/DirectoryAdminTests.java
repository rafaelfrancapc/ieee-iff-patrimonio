package br.com.ieeeiffcampos.patrimonio;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.OffsetDateTime;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:admin-tests;DB_CLOSE_DELAY=-1","patrimonio.seed-demo=false","directory.api-username=integration-service","directory.api-password=test-only-api-password"})
@AutoConfigureMockMvc
@WithMockUser(username="admin",roles="ADMIN")
class DirectoryAdminTests {
 static final ObjectMapper JSON=new ObjectMapper();
 static final List<Map<String,Object>> users=new ArrayList<>();
 static final List<Map<String,Object>> groups=new ArrayList<>();
 static boolean reject=false;
 static final HttpServer fake=start();
 @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;
 @DynamicPropertySource static void properties(DynamicPropertyRegistry props){props.add("directory.api-url",()->"http://127.0.0.1:"+fake.getAddress().getPort());}
 static HttpServer start(){
  try{var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
   server.createContext("/auth/simple/login",exchange->{
    var body=JSON.readValue(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8),Map.class);
    boolean valid="integration-service".equals(body.get("username"))&&"test-only-api-password".equals(body.get("password"));
    byte[] response=JSON.writeValueAsBytes(valid?Map.of("token","test-token"):Map.of("error","invalid"));exchange.sendResponseHeaders(valid?200:401,response.length);exchange.getResponseBody().write(response);exchange.close();
   });
   server.createContext("/api/graphql",exchange->{
    try{var body=DirectoryClient.object(JSON.readValue(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8),Map.class));
     var query=String.valueOf(body.get("query"));var variables=DirectoryClient.object(body.get("variables"));
     Map<String,Object> response;
     synchronized(users){response=reject?Map.of("errors",List.of(Map.of("message","Test directory rejection"))):Map.of("data",operation(query,variables));}
     byte[] bytes=JSON.writeValueAsBytes(response);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
    }catch(Exception error){exchange.sendResponseHeaders(500,-1);}finally{exchange.close();}
   });server.start();return server;
  }catch(Exception error){throw new ExceptionInInitializerError(error);}
 }
 static Map<String,Object> operation(String query,Map<String,Object> variables){
  if(query.startsWith("query"))return new LinkedHashMap<>(Map.of("users",users,"groups",groups));
  if(query.contains("createUser(")){var user=new LinkedHashMap<>(DirectoryClient.object(variables.get("user")));user.put("groups",new ArrayList<>());users.add(user);return Map.of("createUser",Map.of("id",user.get("id")));}
  if(query.contains("updateUser(")){var updates=DirectoryClient.object(variables.get("user"));users.stream().filter(u->u.get("id").equals(updates.get("id"))).findFirst().orElseThrow().putAll(updates);return Map.of("updateUser",Map.of("ok",true));}
  if(query.contains("createGroup(")){var group=new LinkedHashMap<String,Object>(Map.of("id",groups.size()+10,"displayName",variables.get("name"),"users",new ArrayList<>()));groups.add(group);return Map.of("createGroup",group);}
  if(query.contains("deleteUser(")){users.removeIf(u->u.get("id").equals(variables.get("id")));return Map.of("deleteUser",Map.of("ok",true));}
  boolean add=query.contains("addUserToGroup(");
  var user=users.stream().filter(u->u.get("id").equals(variables.get("user"))).findFirst().orElseThrow();
  var group=groups.stream().filter(g->g.get("id").equals(variables.get("group"))).findFirst().orElseThrow();
  var memberships=DirectoryClient.list(user.get("groups"));
  if(add)memberships.add(new LinkedHashMap<>(Map.of("id",group.get("id"),"displayName",group.get("displayName"))));else memberships.removeIf(g->g.get("id").equals(group.get("id")));
  return Map.of(add?"addUserToGroup":"removeUserFromGroup",Map.of("ok",true));
 }
 @BeforeEach void fixture(){synchronized(users){reject=false;users.clear();groups.clear();groups.add(new LinkedHashMap<>(Map.of("id",1,"displayName","lldap_admin","users",new ArrayList<>(List.of(Map.of("id","admin"),Map.of("id","other-admin"))))));users.add(new LinkedHashMap<>(Map.of("id","admin","displayName","Administrador","email","admin@example.test","groups",new ArrayList<>(List.of(Map.of("id",1,"displayName","lldap_admin"))))));}jdbc.update("DELETE FROM directory_event");}
 @Test void rendersAdministrativePageAndReturnsNoServiceCredentials() throws Exception {
  mvc.perform(get("/admin/usuarios")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Usuários e papéis")));
  var body=mvc.perform(get("/api/admin/directory")).andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(true)).andReturn().getResponse().getContentAsString();
  assertThat(body).doesNotContain("test-only-api-password","test-token");
 }
 @Test void createsEditsAssignsRolesAndDeletesUserThroughDocumentedGraphqlContract() throws Exception {
  mvc.perform(post("/api/admin/users").with(csrf()).contentType("application/json").content("{\"id\":\"new.member\",\"displayName\":\"Novo membro\",\"email\":\"member@example.test\"}")).andExpect(status().isCreated());
  mvc.perform(put("/api/admin/users/new.member").with(csrf()).contentType("application/json").content("{\"displayName\":\"Nome atualizado\",\"email\":\"updated@example.test\"}")).andExpect(status().isNoContent());
  mvc.perform(put("/api/admin/users/new.member/role").with(csrf()).contentType("application/json").content("{\"role\":\"DIRECTOR\"}")).andExpect(status().isNoContent());
  assertThat(users).anyMatch(u->u.get("displayName").equals("Nome atualizado")&&DirectoryClient.list(u.get("groups")).stream().anyMatch(g->g.get("displayName").equals("ieee_diretoria")));
  mvc.perform(delete("/api/admin/users/new.member").with(csrf())).andExpect(status().isNoContent());
  assertThat(users).noneMatch(u->u.get("id").equals("new.member"));
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM directory_event WHERE actor='admin'",Integer.class)).isEqualTo(4);
 }
 @Test void refusesSelfDemotionSelfDeletionAndServiceAccountDeletion() throws Exception {
  mvc.perform(put("/api/admin/users/admin/role").with(csrf()).contentType("application/json").content("{\"role\":\"MEMBER\"}")).andExpect(status().isConflict());
  mvc.perform(delete("/api/admin/users/admin").with(csrf())).andExpect(status().isConflict());
  mvc.perform(delete("/api/admin/users/integration-service").with(csrf())).andExpect(status().isConflict());
 }
 @Test void refusesRemovingLastAdministrator() throws Exception {
  DirectoryClient.list(groups.getFirst().get("users")).clear();DirectoryClient.list(groups.getFirst().get("users")).add(Map.of("id","admin"));
  mvc.perform(delete("/api/admin/users/admin").with(user("other-admin").roles("ADMIN")).with(csrf())).andExpect(status().isConflict());
 }
 @Test void membershipChangeRequiresAdministrativeRoleAndCsrf() throws Exception {
  mvc.perform(put("/api/admin/users/admin/role").with(user("director").roles("DIRECTOR")).with(csrf()).contentType("application/json").content("{\"role\":\"ADMIN\"}")).andExpect(status().isForbidden());
  mvc.perform(post("/api/admin/users").contentType("application/json").content("{}")).andExpect(status().isForbidden());
 }
 @Test void graphqlErrorsNeverReportSuccessOrWriteLocalAudit() throws Exception {
  reject=true;
  mvc.perform(post("/api/admin/users").with(csrf()).contentType("application/json").content("{\"id\":\"rejected\",\"displayName\":\"Rejected user\",\"email\":\"rejected@example.test\"}")).andExpect(status().isBadGateway());
  assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM directory_event",Integer.class)).isZero();
 }
 @AfterAll static void stop(){fake.stop(0);}
}
