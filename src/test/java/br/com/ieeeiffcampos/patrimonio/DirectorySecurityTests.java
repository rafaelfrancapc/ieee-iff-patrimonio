package br.com.ieeeiffcampos.patrimonio;

import com.unboundid.ldap.listener.*;
import com.unboundid.ldap.sdk.*;
import java.net.InetAddress;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:directory-security;DB_CLOSE_DELAY=-1","patrimonio.seed-demo=false"})
@AutoConfigureMockMvc
class DirectorySecurityTests {
 static final String BASE="dc=example,dc=test";
 static final String TEST_PASSWORD="test-only-password";
 static final InMemoryDirectoryServer ldap=startDirectory();
 @Autowired MockMvc mvc;
 @Autowired JdbcTemplate jdbc;
 static InMemoryDirectoryServer startDirectory(){
  try{
   var config=new InMemoryDirectoryServerConfig(BASE);config.setSchema(null);
   config.setListenerConfigs(InMemoryListenerConfig.createLDAPConfig("test",InetAddress.getLoopbackAddress(),0,null));
   var server=new InMemoryDirectoryServer(config);server.startListening();
   server.add("dn: "+BASE,"objectClass: domain","dc: example");
   server.add("dn: ou=people,"+BASE,"objectClass: organizationalUnit","ou: people");
   server.add("dn: ou=groups,"+BASE,"objectClass: organizationalUnit","ou: groups");
   for(String user:List.of("member","director","admin","reader"))server.add("dn: uid="+user+",ou=people,"+BASE,"objectClass: inetOrgPerson","uid: "+user,"cn: "+user,"sn: "+user,"userPassword: "+TEST_PASSWORD);
   server.add("dn: cn=ieee_diretoria,ou=groups,"+BASE,"objectClass: groupOfNames","cn: ieee_diretoria","member: uid=director,ou=people,"+BASE);
   server.add("dn: cn=lldap_admin,ou=groups,"+BASE,"objectClass: groupOfNames","cn: lldap_admin","member: uid=admin,ou=people,"+BASE);
   return server;
  }catch(Exception error){throw new ExceptionInInitializerError(error);}
 }
 @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){
  registry.add("directory.url",()->"ldap://127.0.0.1:"+ldap.getListenPort());registry.add("directory.base-dn",()->BASE);
  registry.add("directory.bind-dn",()->"uid=reader,ou=people,"+BASE);registry.add("directory.bind-password",()->TEST_PASSWORD);
 }
 @BeforeEach void fixture() throws Exception {
  ldap.modify("cn=ieee_diretoria,ou=groups,"+BASE,new Modification(ModificationType.REPLACE,"member","uid=director,ou=people,"+BASE));
  jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");jdbc.update("DELETE FROM inventory_node");jdbc.update("DELETE FROM inventory_event");jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
  insert("room",null,"Sala","SPACE","PUBLIC");insert("open","room","Notebook compartilhado","ITEM","PUBLIC");
  insert("secret","room","Armário reservado","CONTAINER","CONFIDENTIAL");insert("inherited","secret","Protótipo reservado","ITEM","PUBLIC");
  jdbc.update("INSERT INTO inventory_event(node_id,node_name,action,origin,destination,occurred_at,actor) VALUES(?,?,?,?,?,?,?)","secret","Armário reservado","Cadastro","","Sala",OffsetDateTime.now(),"director");
 }
 void insert(String id,String parent,String name,String kind,String visibility){
  jdbc.update("INSERT INTO inventory_node(id,parent_id,name,code,kind,status,quantity,unit_value,responsible,description,updated_at,visibility) VALUES(?,?,?,?,?,'AVAILABLE',1,0,'Equipe','',?,?)",id,parent,name,id,kind,OffsetDateTime.now(),visibility);
 }
 String payload(String parent,String visibility){return "{\"parentId\":\""+parent+"\",\"name\":\"Item de teste\",\"code\":\"TEST-NEW\",\"kind\":\"ITEM\",\"status\":\"AVAILABLE\",\"quantity\":1,\"unitValue\":0,\"responsible\":\"Equipe\",\"description\":\"\",\"visibility\":\""+visibility+"\"}";}
 MockHttpSession login(String user) throws Exception {
  var result=mvc.perform(post("/login").with(csrf()).param("username",user).param("password",TEST_PASSWORD)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/")).andReturn();
  var session=(MockHttpSession)result.getRequest().getSession(false);assertThat(session).isNotNull();return session;
 }
 @Test void anonymousGetsNoInventoryOrAdministrativeData() throws Exception {
  mvc.perform(get("/api/inventory")).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/session")).andExpect(status().isUnauthorized());
  mvc.perform(get("/")).andExpect(status().is3xxRedirection());
  mvc.perform(get("/login")).andExpect(status().isOk()).andExpect(content().string(containsString("Acesse com sua conta do Ramo")));
 }
 @Test void actualLdapBindMapsAllThreeRoles() throws Exception {
  for(String user:List.of("member","director","admin")){
   var session=login(user);var context=(SecurityContext)session.getAttribute("SPRING_SECURITY_CONTEXT");
   var authorities=context.getAuthentication().getAuthorities().stream().map(a->a.getAuthority()).toList();
   assertThat(authorities).contains("ROLE_MEMBER");
   if(!user.equals("member"))assertThat(authorities).contains("ROLE_DIRECTOR");
   if(user.equals("admin"))assertThat(authorities).contains("ROLE_ADMIN");
  }
 }
 @Test void memberCannotSeeInheritedSecretsCountsOrHistory() throws Exception {
  mvc.perform(get("/api/inventory").session(login("member"))).andExpect(status().isOk())
   .andExpect(jsonPath("$.nodes",hasSize(2))).andExpect(jsonPath("$.events",hasSize(0)))
   .andExpect(content().string(not(containsString("reservado"))))
   .andExpect(header().string("Cache-Control",containsString("no-store")));
 }
 @Test void memberCannotMutateEvenWithValidCsrfAndKnownSecretIds() throws Exception {
  var session=login("member");
  mvc.perform(post("/api/nodes").session(session).with(csrf()).contentType("application/json").content(payload("room","PUBLIC"))).andExpect(status().isForbidden());
  mvc.perform(put("/api/nodes/secret").session(session).with(csrf()).contentType("application/json").content(payload("room","PUBLIC"))).andExpect(status().isForbidden());
  mvc.perform(delete("/api/nodes/secret").session(session).with(csrf())).andExpect(status().isForbidden());
  mvc.perform(get("/api/admin/directory").session(session)).andExpect(status().isForbidden());
 }
 @Test void directorSeesSecretsAndCanEditButCannotManageAccounts() throws Exception {
  var session=login("director");
  mvc.perform(get("/api/inventory").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.nodes",hasSize(4))).andExpect(jsonPath("$.events[0].actor").value("director"));
  mvc.perform(post("/api/nodes").session(session).with(csrf()).contentType("application/json").content(payload("room","CONFIDENTIAL"))).andExpect(status().isCreated());
  mvc.perform(get("/api/admin/directory").session(session)).andExpect(status().isForbidden());
 }
 @Test void inheritedSecretRemainsRestrictedAfterMovingToPublicRoom() throws Exception {
  mvc.perform(put("/api/nodes/inherited").session(login("director")).with(csrf()).contentType("application/json").content(payload("room","PUBLIC")))
   .andExpect(status().isOk()).andExpect(jsonPath("$.visibility").value("CONFIDENTIAL"));
  mvc.perform(get("/api/inventory").session(login("member"))).andExpect(jsonPath("$.nodes",hasSize(2)));
 }
 @Test void groupRevocationUpdatesAnExistingSessionBeforeAuthorization() throws Exception {
  var session=login("director");
  ldap.modify("cn=ieee_diretoria,ou=groups,"+BASE,new Modification(ModificationType.DELETE,"member","uid=director,ou=people,"+BASE));
  mvc.perform(get("/api/session").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.canEdit").value(false));
  mvc.perform(post("/api/nodes").session(session).with(csrf()).contentType("application/json").content(payload("room","PUBLIC"))).andExpect(status().isForbidden());
  mvc.perform(get("/api/inventory").session(session)).andExpect(jsonPath("$.nodes",hasSize(2)));
 }
 @Test void directoryFailureFailsClosedForExistingSession() throws Exception {
  var session=login("director");
  ldap.modify("uid=reader,ou=people,"+BASE,new Modification(ModificationType.REPLACE,"userPassword","temporary-test-only"));
  try{mvc.perform(get("/api/inventory").session(session)).andExpect(status().isServiceUnavailable()).andExpect(content().string(not(containsString("reservado"))));}
  finally{ldap.modify("uid=reader,ou=people,"+BASE,new Modification(ModificationType.REPLACE,"userPassword",TEST_PASSWORD));}
 }
 @Test void incorrectPasswordAndCsrfCannotAuthenticateOrMutate() throws Exception {
  mvc.perform(post("/login").with(csrf()).param("username","admin").param("password","incorrect-test-only")).andExpect(redirectedUrl("/login?error"));
  mvc.perform(post("/api/nodes").session(login("director")).contentType("application/json").content(payload("room","PUBLIC"))).andExpect(status().isForbidden());
 }
 @AfterAll static void stop(){ldap.shutDown(true);}
}
