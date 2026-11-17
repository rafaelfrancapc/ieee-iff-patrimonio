package br.com.ieeeiffcampos.patrimonio;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("directory")
public record DirectoryProperties(String url, String baseDn, String bindDn, String bindPassword,
        String directorGroup, String adminGroup, String webUrl, String apiUrl,
        String apiUsername, String apiPassword) {
    public boolean managementConfigured() {
        return apiUrl != null && !apiUrl.isBlank() && apiUsername != null && !apiUsername.isBlank()
            && apiPassword != null && !apiPassword.isBlank();
    }
    @Override public String toString() { return "DirectoryProperties[credentials redacted]"; }
}
