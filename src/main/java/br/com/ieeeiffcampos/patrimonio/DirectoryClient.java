package br.com.ieeeiffcampos.patrimonio;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** Server-only client for the LLDAP GraphQL schema and its documented simple-login endpoint. */
@Service
public class DirectoryClient {
    private final DirectoryProperties config;
    private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    public DirectoryClient(DirectoryProperties config,ObjectMapper json) { this.config=config; this.json=json; }
    public Map<String,Object> query(String query,Map<String,Object> variables) {
        if (!config.managementConfigured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Conexão administrativa com o diretório não configurada.");
        try {
            var login=post("/auth/simple/login",Map.of("username",config.apiUsername(),"password",config.apiPassword()),null);
            String token=String.valueOf(login.get("token"));
            if (token.equals("null") || token.isBlank()) throw new IllegalStateException("Missing directory token");
            var result=post("/api/graphql",Map.of("query",query,"variables",variables),token);
            if (result.containsKey("errors")) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"O diretório recusou a operação. Atualize a lista para conferir o estado atual.");
            return object(result.get("data"));
        } catch (ResponseStatusException error) { throw error; }
        catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Não foi possível acessar a administração do diretório.");
        }
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> object(Object value) { return (Map<String,Object>)value; }
    @SuppressWarnings("unchecked") public static List<Map<String,Object>> list(Object value) { return (List<Map<String,Object>>)value; }
    private Map<String,Object> post(String path,Map<String,Object> body,String token) throws Exception {
        URI base=URI.create(config.apiUrl());
        if (!(base.getScheme().equals("http") || base.getScheme().equals("https")) || base.getHost()==null || base.getUserInfo()!=null
                || base.getQuery()!=null || base.getFragment()!=null) throw new IllegalStateException("Invalid directory endpoint");
        var builder=HttpRequest.newBuilder(URI.create(config.apiUrl().replaceAll("/+$","")+path))
            .timeout(Duration.ofSeconds(10)).header("Content-Type","application/json");
        if (token!=null) builder.header("Authorization","Bearer "+token);
        var response=http.send(builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        if (response.statusCode()!=200) throw new IllegalStateException("Directory request failed");
        return object(json.readValue(response.body(),Map.class));
    }
}
