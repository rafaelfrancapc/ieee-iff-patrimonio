# Verificação — 17/11/2026

## Testes automatizados

`./mvnw verify`: **22 testes, nenhuma falha ou erro**. Compilação em Java 21.

- Hierarquia, movimentação de subárvores, prevenção de ciclos e códigos duplicados.
- Exclusão de registros vazios e preservação do histórico.
- Transação do inventário com rollback quando a auditoria falha.
- Login LDAP por bind em diretório UnboundID efêmero, com mapeamento de membro, diretoria e administrador.
- Ausência de dados para anônimos e de edição para membros.
- Sigilo herdado, remoção de registros restritos da resposta, histórico restrito e preservação do sigilo ao mover.
- Revogação de grupos em uma sessão já aberta e bloqueio quando o diretório não pode ser consultado.
- CSRF e senha incorreta.
- Cadastro, edição, papel e exclusão de contas pelo contrato GraphQL oficial, utilizando um servidor HTTP de teste.
- Proteção contra autoexclusão, auto-revogação, exclusão da conta de integração e remoção do último administrador.
- Erros GraphQL não são tratados como sucesso nem geram auditoria de conclusão.

## Navegador

Revisão local com banco em memória, diretório de teste e API administrativa simulada. Nenhuma conta real foi criada ou alterada.

- Login administrativo por LDAP; navegação e classificação sigilosa.
- Cadastro fictício no diretório, atribuição de diretoria e histórico com autor.
- Interface com título funcional “Patrimônio”, métricas discretas e a identidade do Hub.
- Interface e CSV de organização física, sem campos ou colunas monetárias.
- Desktop de 1440 × 1000 e celular de 390 × 844; temas claro e escuro.

Capturas com dados fictícios:

- [Login](preview/login.jpg)
- [Inventário claro](preview/patrimonio-claro.jpg)
- [Inventário escuro](preview/patrimonio-escuro.jpg)
- [Celular](preview/patrimonio-mobile.jpg)
- [Administração](preview/administracao.jpg)
- [Classificação sigilosa](preview/sigilo.jpg)

## Limites

A suíte cobre o protocolo LDAP e o contrato HTTP/GraphQL usado pelo LLDAP. Não foi realizada autenticação contra o LLDAP de produção: os endereços internos, DN e credenciais precisam ser fornecidos pela implantação. O Dockerfile e o Compose foram preparados; a execução Docker não foi validada porque o daemon local não estava disponível.

O banco de demonstração existente foi preservado. Os testes automatizados e a última revisão de navegador usam bases próprias e efêmeras.
