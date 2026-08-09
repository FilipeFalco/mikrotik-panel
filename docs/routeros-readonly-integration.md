# Integração RouterOS REST somente leitura — Fase 2

## Objetivo e limite de segurança

A Fase 2 conecta o backend local a um RouterOS 7 para **ler** estado real. O
Browser nunca acessa o RouterOS: ele fala com a API local, e somente o backend
faz HTTPS para o equipamento.

```text
Browser → API Spring Boot → HTTPS GET /rest → RouterOS
                         ↘ SQLite local (leitura e escrita local)
```

O perfil esperado para RouterOS real é:

```dotenv
MIKROTIK_MOCK_MODE=false
MIKROTIK_WRITE_ENABLED=false
```

As quatro barreiras independentes são:

1. Usuário RouterOS dedicado com `read,rest-api`, sem `write`.
2. `MIKROTIK_WRITE_ENABLED=false` no backend.
3. `MikrotikWriteGuard` bloqueia as rotas da nossa API que pediriam mutação
   RouterOS.
4. `RouterOsRestGateway` da Fase 2 não implementa escrita: seus métodos de
   velocidade e bloqueio lançam erro antes de qualquer request, inclusive se
   `MIKROTIK_WRITE_ENABLED=true` for configurado por engano.

**RouterOS HTTP methods used: GET only.** O cliente é construído sem uma API
genérica que aceite `HttpMethod`, corpo ou comando arbitrário. `POST`, `PUT`,
`PATCH` e `DELETE` não são emitidos para o RouterOS nesta fase. Isso é mais
restritivo que a própria REST API, que documenta esses verbos para comandos e
CRUD. [REST API oficial](https://manual.mikrotik.com/docs/developer-guides/rest-api/)

SQLite continua editável localmente: nome amigável e observações do dispositivo,
nome/descrição/CIDR/DHCP server/visibilidade da porta e auditoria local. Esses
dados não são enviados ao RouterOS.

## Arquitetura

```text
MikrotikGateway
├── MockMikrotikGateway
└── RouterOsRestGateway
    ├── RouterOsRestClient       HTTPS + Basic Auth + GET-only
    ├── RouterOsMapper           system/resource e interfaces
    ├── RouterOsDhcpMapper       DHCP server + lease → RouterDevice
    ├── RouterOsSimpleQueueMapper / RouterOsRateParser
    └── RouterOsFastTrackDetector
```

`RouterOsRestClient` conhece URL, TLS, autenticação e DTOs JSON. DTOs preservam
valores como `String` e ignoram propriedades desconhecidas. Isso é intencional:
o RouterOS documenta que valores de objetos JSON são codificados como strings,
inclusive booleans e números. Mappers convertem defensivamente para os modelos
internos; `JsonNode`, mapas e JSON cru não saem de `gateway/routeros`.
[Formato JSON REST oficial](https://manual.mikrotik.com/docs/developer-guides/rest-api/)

Em produção a origem é montada internamente como
`https://<MIKROTIK_HOST>:<MIKROTIK_PORT>`. O frontend não recebe nem configura
uma URL RouterOS, usuário, senha ou header `Authorization`.

## Recursos lidos

Cada operação abaixo é uma coleção/objeto lido por `GET`; não há request por
dispositivo. Um refresh de dispositivos faz uma leitura de DHCP servers e uma
de leases, correlacionadas em memória.

| REST path | Uso na Fase 2 | Frequência |
| --- | --- | --- |
| `/rest/system/resource` | conexão, versão e latência aproximada | status/polling leve |
| `/rest/interface` | interfaces reais (`name`, `type`, `running`, `disabled`) | dados de portas/diagnóstico |
| `/rest/ip/dhcp-server` | nome do DHCP server → interface | dados de dispositivos/diagnóstico |
| `/rest/ip/dhcp-server/lease` | leases e campos do dispositivo | dados de dispositivos/diagnóstico |
| `/rest/queue/simple` | observação de queues e limites de porta explicitamente gerenciados | dados de portas/diagnóstico |
| `/rest/ip/firewall/filter` | detecção de FastTrack ativo | somente diagnóstico sob demanda |

A documentação REST informa que `GET` retorna os registros do menu RouterOS e
que o acesso HTTPS REST é exposto sob `/rest` pelo serviço `www-ssl`.
[REST API oficial](https://manual.mikrotik.com/docs/developer-guides/rest-api/)

`/api/system/status` não lê interfaces, DHCP, queues ou firewall: usa somente
`system/resource`. FastTrack não é consultado no polling normal; é lido quando
o diagnóstico é aberto/atualizado.

## Autenticação, TLS e timeouts

O RouterOS REST usa HTTP Basic Auth com as credenciais de usuário do RouterOS.
`MIKROTIK_USERNAME` e `MIKROTIK_PASSWORD` existem apenas no processo backend:
não são devolvidos pela API, persistidos no SQLite ou registrados em logs. O
log operacional, quando necessário, limita-se a método, path, status e duração.
[Autenticação REST oficial](https://manual.mikrotik.com/docs/developer-guides/rest-api/)

| Variável | Padrão | Significado |
| --- | ---: | --- |
| `MIKROTIK_VERIFY_SSL` | `true` | valida cadeia de certificado e hostname/IP com TLS padrão do cliente RouterOS |
| `MIKROTIK_CONNECT_TIMEOUT_MS` | `3000` | limite de conexão de 3 s; deve ser positivo |
| `MIKROTIK_READ_TIMEOUT_MS` | `5000` | limite de resposta de 5 s; deve ser positivo |

Com `MIKROTIK_VERIFY_SSL=true`, use certificado confiável e cujo nome/IP seja
compatível com `MIKROTIK_HOST`. A orientação oficial para certificados
self-signed é importar a CA no repositório confiável do cliente.
[REST API oficial](https://manual.mikrotik.com/docs/developer-guides/rest-api/)

`MIKROTIK_VERIFY_SSL=false` é uma exceção exclusiva para desenvolvimento local
controlado com certificado self-signed ainda não confiável. Ela enfraquece
somente o cliente HTTP RouterOS dedicado; não altera SSL global da JVM,
propriedades globais, nem outros clientes HTTP da aplicação. Não a use em uma
rede não confiável.

## Mapeamento de dados

### Interfaces e tráfego

As interfaces vêm de `/rest/interface`; nenhuma suposição é feita sobre nomes
ou quantidade de portas. A aplicação decide quais interfaces Ethernet aparecem
no painel e quais são localmente gerenciadas. Interfaces descobertas podem
existir sem `ManagedPort` e aparecem em Configurações.

Os contadores cumulativos de bytes não são rotulados como Mbps. A Fase 2 retorna
`TrafficRate.UNAVAILABLE` porque um monitor de tráfego contínuo exigiria um
comando que não faz parte do contrato GET-only.

### DHCP server → lease → porta

O RouterOS documenta `name` e `interface` para DHCP server, e leases com
`server`, `mac-address`, `address`, `host-name`, `status`, `block-access`,
`comment`, `last-seen`, `rate-limit`, `dynamic` e `disabled` quando aplicável.
[DHCP oficial](https://manual.mikrotik.com/docs/network-management/dhcp/)

O correlacionamento é sempre:

```text
lease.server → DHCP server.name → DHCP server.interface → RouterDevice.interfaceName
```

O CIDR configurado localmente é apenas uma conferência posterior; ele não
substitui essa associação quando ela existe. Lease sem MAC utilizável ou sem
server/interface correspondente é ignorado individualmente, com warning
sanitizado e agregado. Ele não derruba a lista dos demais leases e não recebe
identificador ou interface inventados.

| Evidência RouterOS | Estado exibido |
| --- | --- |
| `block-access=true` | `BLOCKED` |
| `block-access` falso e `status=bound` | `ONLINE` |
| outro status, ausente ou não confiável | `UNKNOWN` |

`bound` significa que o cliente aceitou a lease segundo a documentação DHCP;
isso justifica `ONLINE`, sem inferir `OFFLINE` para os demais estados.
`last-seen` é convertido somente quando o intervalo RouterOS é reconhecido;
`never`, valor ausente, inválido ou extremo permanece nulo.

O `rate-limit` de lease é apenas observado. O formato DHCP é
`rx-rate[/tx-rate]` (com sufixos decimais documentados `k` e `M`). A semântica
RouterOS de `rx`/`tx` é perspectiva do roteador: `rx` é upload do cliente e
`tx` é download do cliente. Portanto, a implementação mapeia o primeiro valor
para upload e o segundo para download; um único valor se aplica aos dois. A
direção é uma inferência explícita das duas páginas oficiais que usam a mesma
gramática de rate-limit. [DHCP](https://manual.mikrotik.com/docs/network-management/dhcp/)
e [HotSpot](https://manual.mikrotik.com/docs/authentication-authorization-accounting/hotspot-captive-portal/).

### Simple Queues

`/rest/queue/simple` é somente leitura. A lista pode estar vazia e isso é
resultado válido, não erro de diagnóstico.

Uma queue é atribuída a uma porta **somente** se seu comentário corresponder
exatamente a `MTMGR:PORT:<interface>`, validado pela regra central de ownership.
Queue manual, comentário genérico `MTMGR:` ou marcador de outra interface é
observável para diagnóstico, mas não é adotado, alterado, renomeado ou removido.

Para Simple Queue, a documentação define `max-limit` como
`upload/download`; o modelo local é `SpeedLimit(download, upload)`, por isso o
parser inverte a ordem somente na fronteira RouterOS. Valores malformados ou
overflow não criam limite inventado. [Queues oficiais](https://manual.mikrotik.com/docs/firewall-and-quality-of-service/queues/)

### FastTrack

O diagnóstico procura regras de `/rest/ip/firewall/filter` com
`action=fasttrack-connection` e `disabled=false` (ou ausente, cujo padrão
RouterOS é ativo). Ele preserva apenas cadeia/comentário para contexto e não
move, desabilita, altera ou remove regra alguma.

FastTrack pode desviar Simple Queues; o RouterOS documenta que tráfego
FastTracked ignora queues e outras facilidades L3. O painel informa o risco,
mas qualquer mudança continua sendo decisão manual do administrador.
[Packet Flow oficial](https://manual.mikrotik.com/docs/firewall-and-quality-of-service/packet-flow-in-routeros/)

## Erros e disponibilidade

`connectionStatus()` mede a leitura de `system/resource` e nunca derruba a
aplicação. As mensagens retornadas à UI são sanitizadas; não contêm senha,
header Authorization, URL com credenciais, corpo HTTP ou stack trace.

| Situação | Categoria para a API local | Efeito |
| --- | --- | --- |
| 401/403 | `MIKROTIK_AUTHENTICATION_FAILED` | indica credencial/permissão de leitura inválida |
| timeout, recusa, DNS/host ou 5xx | `MIKROTIK_UNAVAILABLE` | status desconectado; SQLite e histórico seguem disponíveis |
| erro de certificado/TLS | `MIKROTIK_TLS_ERROR` | orientar certificado/host ou ambiente de desenvolvimento controlado |
| JSON/payload inesperado | `MIKROTIK_BAD_RESPONSE` | erro controlado, sem body do equipamento |
| operação RouterOS mutável | `MIKROTIK_WRITES_DISABLED` (tipo interno `WRITE_NOT_IMPLEMENTED`) | zero request mutável RouterOS |

Diagnóstico separa REST API, Interfaces, DHCP, Queues e FastTrack. Por exemplo,
DHCP pode falhar enquanto `system/resource` funciona; uma coleção de queues
vazia é sucesso.

## Modo mock, modo real e limitações

| Configuração | Resultado |
| --- | --- |
| `MIKROTIK_MOCK_MODE=true` | dados em memória; nenhuma rede RouterOS; mutações continuam simuladas para desenvolvimento |
| `MIKROTIK_MOCK_MODE=false`, `MIKROTIK_WRITE_ENABLED=false` | dados reais somente leitura por HTTPS GET; SQLite local permanece editável |
| `MIKROTIK_MOCK_MODE=false`, `MIKROTIK_WRITE_ENABLED=true` | ainda não existe escrita RouterOS na Fase 2; métodos mutáveis falham antes da rede |

Fora do escopo desta fase:

- tráfego instantâneo via `monitor`/POST;
- `block-access`, `make-static` ou qualquer alteração de lease;
- criação, atualização, remoção ou reordenação de queues;
- alteração de firewall/FastTrack, bridge, IP, NAT, rotas, VLAN, DNS ou DHCP;
- billing, multi-router, acesso remoto, WebSocket e SSE.

## Fontes oficiais consultadas

- [REST API — RouterOS Manual](https://manual.mikrotik.com/docs/developer-guides/rest-api/)
- [Services — RouterOS Manual](https://manual.mikrotik.com/docs/system-information-and-utilities/services/)
- [User — RouterOS Manual](https://manual.mikrotik.com/docs/authentication-authorization-accounting/user/)
- [DHCP — RouterOS Manual](https://manual.mikrotik.com/docs/network-management/dhcp/)
- [Queues — RouterOS Manual](https://manual.mikrotik.com/docs/firewall-and-quality-of-service/queues/)
- [Packet Flow in RouterOS — RouterOS Manual](https://manual.mikrotik.com/docs/firewall-and-quality-of-service/packet-flow-in-routeros/)

Physical RouterOS validation: **not performed in this workspace; pending an
operator-provided reachable router and read-only credentials.**
