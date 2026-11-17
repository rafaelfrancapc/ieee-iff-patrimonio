# Patrimônio IEEE IFF Campos Centro

Inventário físico em Java 21, Spring Boot 4.1.2, Thymeleaf, JDBC e H2, com autenticação no LLDAP. Repositório complementar ao [Hub](https://github.com/rafaelfrancapc/ieee-iff-hub). A [documentação geral](https://github.com/rafaelfrancapc/ieee-iff-docs) descreve a implantação conjunta.

## Executar

Use JDK 21 ou superior. Configure as variáveis abaixo antes de entrar no sistema. Não existe conta local de acesso ou senha administrativa padrão.

```bash
./mvnw verify
java -jar target/patrimonio-0.0.1-SNAPSHOT.jar
```

Abra http://localhost:8081. O endereço inicial solicita a conta do Ramo no LLDAP. Sem configuração/conexão válida, o inventário permanece protegido.

Com Docker, preencha uma cópia de `.env.example` e use `docker compose up --build -d`. Dentro do contêiner, `localhost` aponta para o próprio inventário: use o hostname do LLDAP na rede privada ou o endereço correto do servidor. O Compose fornece `host.docker.internal` quando o serviço está acessível no host Linux. Não use `localhost` no contêiner para alcançar outro serviço.

O Compose publica em `127.0.0.1:8081` e mantém o H2 em volume. `docker compose down` preserva os dados; `down -v` os remove.

## Acesso

| Papel | Consulta | Edição | Usuários e papéis |
| --- | --- | --- | --- |
| Membro | Patrimônio liberado aos membros | Não | Não |
| Diretoria | Todo o patrimônio, incluindo sigiloso | Sim | Não |
| Administrador | Todo o patrimônio | Sim | Sim |

Toda conta autenticada no diretório recebe o papel de membro. O grupo `ieee_diretoria` concede diretoria; `lldap_admin` concede administração e diretoria. Os nomes são configuráveis. O papel administrador mapeado para `lldap_admin` dá acesso administrativo ao diretório compartilhado, inclusive às contas usadas por outros serviços.

As regras são verificadas no servidor. Grupos são consultados novamente nas requisições autenticadas, inclusive em sessões já abertas. Falha ao consultar permissões bloqueia o acesso. Exclusão de uma conta encerra seu acesso quando a sessão fizer a próxima consulta.

Cada registro pode ser liberado aos membros ou sigiloso. O sigilo de um local se aplica a todos os seus descendentes. Os registros restritos são retirados da resposta da API antes de calcular quantidades, busca, árvore e CSV. Membros não recebem o histórico, pois nomes e caminhos antigos também podem revelar informações restritas. Ao mover um item que herdou sigilo, o servidor mantém sua classificação como sigiloso; uma posterior liberação exige edição explícita.

## Diretório e administração

A página **Usuários e papéis** permite criar, editar e excluir contas, além de atribuir os três papéis. As operações usam a API GraphQL do LLDAP no servidor. A aplicação não retorna tokens ou credenciais administrativas ao navegador. Uma conta recém-criada começa como membro; sua senha é definida no portal do LLDAP, que continua responsável pela política de senhas e recuperação.

A aplicação impede autoexclusão, remoção do próprio acesso administrativo, exclusão da conta de integração e remoção do último administrador identificado. Outros grupos do diretório são preservados ao trocar os papéis do inventário. Operações administrativas concluídas são registradas com ator, conta, operação e horário.

As alterações no diretório não participam da transação H2. Se uma operação externa falhar parcialmente, a interface informa a falha e atualiza a lista; confira o estado no LLDAP antes de repetir.

## Configuração

| Variável | Uso |
| --- | --- |
| `LLDAP_LDAP_URL` | URL LDAP/LDAPS do servidor; padrão local `ldap://localhost:3890` |
| `LLDAP_BASE_DN` | Base do diretório; padrão `dc=ieeeiffcampos,dc=com,dc=br` |
| `LLDAP_BIND_DN` / `LLDAP_BIND_PASSWORD` | Conta de leitura para pesquisar usuários e grupos |
| `LLDAP_DIRECTOR_GROUP` | Grupo da diretoria; padrão `ieee_diretoria` |
| `LLDAP_ADMIN_GROUP` | Grupo administrativo; padrão `lldap_admin` |
| `LLDAP_WEB_URL` | Portal de contas, usado nos links de gerenciamento de senha |
| `LLDAP_API_URL` | Origem HTTP(S) interna do LLDAP, normalmente na porta 17170 |
| `LLDAP_API_USERNAME` / `LLDAP_API_PASSWORD` | Conta de serviço com acesso administrativo à API do LLDAP |
| `HUB_URL` | Link de retorno ao Hub; padrão `http://localhost:8080` |
| `SESSION_COOKIE_SECURE` | Use `true` na implantação com HTTPS |
| `PORT` / `BIND_ADDRESS` | Padrões `8081` e `127.0.0.1` |
| `SEED_DEMO` | `true` cria 24 exemplos somente na primeira inicialização; use `false` em produção |
| `PATRIMONIO_DB_URL` | Padrão `jdbc:h2:file:./data/patrimonio;DB_CLOSE_ON_EXIT=FALSE` |
| `PATRIMONIO_DB_USER` / `PATRIMONIO_DB_PASSWORD` | Credenciais da base H2 |

A conta de leitura deve pertencer ao grupo `lldap_strict_readonly`. A conta da API administrativa exige permissões de administração no LLDAP; use uma conta de serviço dedicada e mantenha suas credenciais somente no ambiente do servidor. A primeira conta administradora e o grupo da diretoria devem ser preparados pelo administrador do LLDAP. O grupo da diretoria também pode ser criado pela aplicação quando um administrador atribui esse papel pela primeira vez.

O Spring não lê `.env` automaticamente na execução direta. Forneça variáveis ao processo ou um arquivo de configuração externo fora do repositório. O Compose lê as variáveis declaradas em seu arquivo. Nunca faça commit de `.env`, senhas, tokens, banco, backups ou dados reais do inventário.

Use LDAPS ou uma rede privada isolada para LDAP. Proteja a API administrativa com HTTPS ou rede interna. Publique o inventário atrás de HTTPS e configure `SESSION_COOKIE_SECURE=true`. O login LDAP compartilha a conta com outros serviços; não implementa SSO entre origens.

## Organização física

```text
Sala do Ramo
├── Mesa de trabalho
│   ├── Notebook
│   └── Gaveta
│       └── Kit de papelaria
└── Armário
    └── Caixa de eletrônica
        └── Estojo de sensores
            └── Sensor
```

Qualquer item pode conter outros itens. Somente espaços podem ficar na raiz. O mapa usa blocos aninhados e uma árvore lateral, com navegação por local. A descrição registra a posição física; os blocos não são uma planta em escala.

O inventário inclui cadastro, edição, movimentação de subárvores, proteção contra ciclos, códigos únicos, situação, quantidades, responsáveis, exclusão de locais vazios e histórico com autor. A exportação CSV respeita os filtros e as permissões. A interface mantém o azul IEEE, a marca original e os temas claro/escuro do Hub.

A base de demonstração contém 24 registros fictícios. O marcador de inicialização impede recriar exemplos após esvaziar a base. `SEED_DEMO=false` não apaga dados existentes.

## Persistência e testes

O H2 fica em `data/patrimonio.mv.db`, relativo ao diretório de execução. Execute sempre a partir da mesma pasta. Uma alteração no inventário e seu evento são gravados na mesma transação. A migração adiciona classificação e autor às bases anteriores, sem apagar registros. Registros antigos começam liberados aos membros; revise sua classificação antes de disponibilizar uma base existente.

Para backup, pare a aplicação e copie `data/` ou o volume Docker. Restaure com o serviço parado e preserve as permissões. Use uma única instância para cada arquivo H2.

```bash
./mvnw verify
```

Os testes incluem LDAP por bind com servidor UnboundID local, mapeamento e revogação de grupos, sigilo herdado, filtragem da API, autorização, CSRF, administração via contrato GraphQL, proteção de contas, hierarquia, auditoria e transações. As contas e senhas em testes são fixtures fictícias sem uso em produção.

Veja [verificação e capturas](docs/VERIFICACAO.md). A suíte local não utiliza nem altera o LLDAP de produção.

Referências: [LDAP no Spring Security](https://docs.spring.io/spring-security/reference/servlet/authentication/passwords/ldap.html), [LLDAP](https://github.com/lldap/lldap), [schema GraphQL oficial](https://github.com/lldap/lldap/blob/main/schema.graphql), [cliente oficial de bootstrap](https://github.com/lldap/lldap/blob/main/scripts/bootstrap.sh).
