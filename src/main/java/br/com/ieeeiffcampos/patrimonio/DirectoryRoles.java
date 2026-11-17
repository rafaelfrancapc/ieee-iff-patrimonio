package br.com.ieeeiffcampos.patrimonio;

import java.util.*;
import org.springframework.ldap.core.*;
import org.springframework.ldap.support.LdapNameBuilder;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;
import static org.springframework.ldap.query.LdapQueryBuilder.query;

@Service
public class DirectoryRoles {
    private final LdapTemplate ldap;
    private final DirectoryProperties config;
    public DirectoryRoles(LdapTemplate ldap, DirectoryProperties config) { this.ldap=ldap; this.config=config; }
    public Set<GrantedAuthority> currentRoles(String username) {
        var relativeDn = LdapNameBuilder.newInstance().add("ou","people").add("uid",username).build();
        ldap.lookup(relativeDn);
        var fullDn = LdapNameBuilder.newInstance(config.baseDn()).add("ou","people").add("uid",username).build();
        var groups = ldap.search(query().base("ou=groups").where("member").is(fullDn.toString()),
            (AttributesMapper<String>) attributes -> String.valueOf(attributes.get("cn").get()));
        return WebConfiguration.mapRoles(groups.stream().map(SimpleGrantedAuthority::new).toList(),config);
    }
}
