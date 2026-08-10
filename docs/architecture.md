# Arquitetura — MikroTik Local Manager

## Escopo atual: Fase 3 — preparação de escrita segura

A aplicação local lê dados de um RouterOS 7 por REST e preserva escrita apenas
no SQLite local. A Fase 3 acrescenta snapshot, ownership, reconciliation,
write readiness e dry-run, todos observacionais. Em modo real, o RouterOS é
fonte de verdade para interfaces, leases, queues, firewall e address lists
observados; SQLite é fonte de verdade para metadados escolhidos pelo operador
e auditoria.

```text
Browser (localhost)
        │ /api
        ▼
React + Vite
        ▼
Spring Boot (127.0.0.1)
 ├── SQLite + Flyway
 │   ├── managed_port: nome, descrição, CIDR, DHCP server, visibilidade e papel WAN/CLIENT
 │   ├── managed_device: nome amigável e observações
 │   └── audit_log: auditoria local sem segredos
 └── MikrotikGateway
     ├── MockMikrotikGateway
     └── RouterOsRestGateway
         └── RouterOsRestClient → HTTPS GET /rest → RouterOS

Spring services
 ├── RouterSnapshotService (uma observação batched e imutável)
 ├── ReconciliationService (comparação, sem correção)
 ├── WriteReadinessService (diagnóstico futuro)
 └── OperationPlanningService (dry-run, sem executor)
```

O frontend não conhece JSON RouterOS, TLS, Basic Auth, paths REST ou credenciais.
O backend monta a origem HTTPS a partir de host/porta; ele nunca aceita URL
RouterOS arbitrária do browser.

## Seleção de gateway

| Configuração | Gateway | Comportamento |
| --- | --- | --- |
| `MIKROTIK_MOCK_MODE=true` | `MockMikrotikGateway` | fixtures em memória; nenhuma conexão de rede |
| `MIKROTIK_MOCK_MODE=false` | `RouterOsRestGateway` | leitura real HTTPS REST |

O antigo gateway indisponível da Fase 1 não participa mais da configuração. Uma
falha de rede do gateway real é representada por status desconectado ou erro
sanitizado, sem derrubar serviços SQLite locais.

## Fronteira RouterOS

`MikrotikGateway` expõe modelos de domínio (`RouterInterface`, `RouterDevice`,
`SpeedLimit`, `TrafficRate` e `GatewayConnectionStatus`), não `JsonNode`,
`Map<String, Object>` ou DTOs de transporte.

```text
RouterOsRestClient
    ↓ DTOs RouterOS com Strings
RouterOsMapper / RouterOsDhcpMapper / RouterOsSimpleQueueMapper
    ↓ modelos de domínio
services → API → frontend
```

DTOs ficam em `gateway/routeros/dto` e ignoram propriedades novas/desconhecidas.
Isso protege o restante da aplicação das particularidades REST: RouterOS
documenta que valores de objetos JSON são strings, inclusive números e
booleans. [REST API oficial](https://manual.mikrotik.com/docs/developer-guides/rest-api/)

O cliente REST é read-only por construção: ele oferece coleções `get…`, não um
método genérico que receba verbo HTTP, body ou comando. Os únicos paths são:

```text
GET /rest/system/resource
GET /rest/interface
GET /rest/ip/dhcp-server
GET /rest/ip/dhcp-server/lease
GET /rest/queue/simple
GET /rest/ip/firewall/filter
GET /rest/ip/firewall/address-list
```

**RouterOS HTTP methods used: GET only.** Embora o RouterOS REST também suporte
verbos mutáveis e comandos via POST, a Fase 3 não os chama.
[REST API oficial](https://manual.mikrotik.com/docs/developer-guides/rest-api/)

## Snapshot, ownership e planos

Uma ação de reconciliation, write readiness ou dry-run captura uma única
janela observável dos recursos necessários. Interfaces, DHCP servers, DHCP
leases, Simple Queues, firewall filters e address lists são buscados no máximo
uma vez por snapshot e processados em memória; não existe uma request RouterOS
por dispositivo. O endpoint combinado `GET /api/write-analysis`, usado pela UI,
captura uma vez, deriva reconciliation e readiness do mesmo `RouterSnapshot` e
assim não duplica as seis coleções. O status de conexão pode acrescentar uma
leitura única de `system/resource`. O polling normal continua leve e não chama
essa análise.

```text
RouterOS
   │ GET
   ▼
RouterSnapshot
   ├── Ownership analyzer
   ├── Reconciliation
   ├── Write readiness
   └── Operation planner
               │
               ▼
            Dry-run
               │
               X  nenhum executor e nenhuma escrita RouterOS
```

O snapshot contém instante de captura e fingerprint diagnóstico. Ambos tornam
a natureza efêmera de um plano explícita, mas não são autorização nem lock.
Qualquer execução de uma fase futura terá de reler e revalidar o estado, pois
RouterOS pode mudar entre o check e o uso (TOCTOU).

Ownership é definido por comentário completo esperado, nunca por prefixo, nome,
MAC, IP, target ou .id. `MTMGR:DEVICE:…` (MAC canônico em maiúsculas e com
hífens, por exemplo `AA-BB-CC-DD-EE-01`) e `MTMGR:PORT:…` são contratos
centralizados em `ManagedResourceIdentifier`; recurso manual parecido é
`FOREIGN` ou `UNKNOWN`, não é adotado. O nome convencionado de uma queue serve
apenas para detectar colisão.

A reconciliation distingue `IN_SYNC`, `DRIFTED`, `MISSING`, `CONFLICT`,
`AMBIGUOUS_OWNERSHIP` e `NOT_APPLICABLE`. `DRIFTED` pressupõe ownership
confirmado e configuração divergente; `CONFLICT` representa recurso
foreign/não comprovado que compete pelo nome ou target, inclusive quando o
target CIDR é igual, subnet, supernet ou sobreposto ao CIDR local, e bloqueia
uma futura mutação. Encontrar dois comentários esperados iguais é ambiguidade
bloqueante, e nunca seleciona silenciosamente um deles. Overlap é somente
evidência de conflito; ownership continua dependendo do comentário exato.

Readiness valida portas a partir do estado local: somente CLIENT habilitada é
candidata às futuras operações de banda. WAN e CLIENT desabilitada podem ser
`NOT_APPLICABLE` sem bloquear; CLIENT habilitada com CIDR ausente ou inválido
produz `MANAGED_PORTS_VALID` bloqueante. A resposta combinada expõe o fingerprint
do snapshot para permitir verificar que as duas visões pertencem à mesma leitura.

O planner recebe uma intenção tipada, mas recompõe ownership, estado e
preconditions a partir do snapshot e SQLite atuais. Suporta dry-run de
`BLOCK_DEVICE`, `UNBLOCK_DEVICE`, `SET_PORT_SPEED` e `SET_DEVICE_SPEED`.
Cada plano tem avisos/conflitos, `generatedAt` e fingerprint; é sempre
`executable=false` na Fase 3. Um no-op pode ser `changeRequired=false` e não
é tratado como erro. A criação do plano gera a auditoria funcional resumida
`OPERATION_PLAN_CREATED`, sem payload, credenciais, JSON RouterOS ou dumps;
reconciliation permanece efêmera para não poluir o histórico.

## Dados e correlação

### Configuração local de portas

O RouterOS descobre interfaces por `GET /rest/interface`; ele é a fonte de
verdade apenas para a existência e o estado observado delas. Uma interface
Ethernet nova sem registro em `managed_port` é não gerenciada e continua visível em
**Configurações → Interfaces descobertas**. O operador pode então persistir
nome amigável, descrição, CIDR, DHCP server, visibilidade e papel local no
SQLite por `PUT /api/ports/{interface}`.

```text
RouterOS descobre interface → configuração local → SQLite → dashboard
```

`WAN` e `CLIENT` são papéis locais, não características inferidas do nome ou
tipo físico da interface. O dashboard escolhe a Internet pelo papel `WAN` e
lista portas de cliente habilitadas pelo papel `CLIENT`; não há regra de que
`ether1` seja Internet. A atribuição de papel não altera rota, NAT, DHCP
client, interface list, firewall nem qualquer outro estado RouterOS. O fixture
de mock pode declarar `ether1` como WAN, mas isso não é lógica de runtime.

Como essa persistência ocorre somente no SQLite, ela permanece disponível em
modo real read-only. A API local pode usar `PUT`; a restrição GET-only se aplica
ao transporte entre backend e RouterOS.

`RouterOsMapper` preserva `name`, `type`, `running` e `disabled` de interfaces.
Tráfego instantâneo permanece indisponível: byte counters acumulados não são
convertidos sem uma amostragem explícita e testada.

`RouterOsDhcpMapper` constrói uma vez o mapa DHCP server → interface e processa
as leases em memória:

```text
lease.server → DHCP server.name → DHCP server.interface → RouterDevice.interfaceName
```

Não há query por lease. MAC é normalizado pela regra central da aplicação;
lease sem MAC válido ou sem associação server/interface é ignorado de forma
isolada e gera warning sanitizado. `block-access=true` vira `BLOCKED`,
`status=bound` vira `ONLINE` quando não bloqueado, e demais estados são
`UNKNOWN`. O CIDR local funciona apenas como conferência posterior.
[DHCP oficial](https://manual.mikrotik.com/docs/network-management/dhcp/)

`RouterOsSimpleQueueMapper` observa queues e a reconciliation somente confirma
ownership quando o comentário é exatamente `MTMGR:PORT:<interface>`. Queue
manual não é adotada; target CIDR sobreposto à rede local gera conflito
`FOREIGN` bloqueante, sem alterar ownership. `max-limit` RouterOS é
`upload/download`, enquanto o domínio usa `download/upload`; a inversão ocorre
uma única vez no parser da fronteira e tem teste de direção.
[Queues oficiais](https://manual.mikrotik.com/docs/firewall-and-quality-of-service/queues/)

## Status, polling e diagnóstico

`connectionStatus()` mede uma leitura de `/rest/system/resource` e devolve
versão/latência aproximada. É o caminho leve para polling de status; ele não lê
DHCP, interfaces, queues ou firewall.

O frontend faz polling de `/api/ports` enquanto conectado e deriva a lista
global de dispositivos das portas retornadas, evitando uma chamada simultânea
desnecessária a `/api/devices`. A API `GET /api/devices` continua disponível.

`MikrotikDiagnosticsGateway` separa checagens por recurso sob demanda:

| Check | Leitura |
| --- | --- |
| REST API | `system/resource` |
| Interfaces | `interface` |
| DHCP | DHCP servers + leases correlacionadas |
| Queues | `queue/simple` |
| FastTrack | `ip/firewall/filter` |
| Address lists | `ip/firewall/address-list`, inventário do snapshot e correlação estreita de filtro manual ativo em `chain=forward` com `drop`/`reject` que referencia a entrada exata; `chain=input` não é bloqueio de cliente e nunca há decisão de estratégia ou remoção |

FastTrack só é consultado em diagnóstico, readiness ou plano sob demanda. Regra habilitada com
`action=fasttrack-connection` é informada, nunca alterada. Isso é relevante
porque FastTrack pode ignorar Simple Queues e outras facilidades L3.
[Packet Flow oficial](https://manual.mikrotik.com/docs/firewall-and-quality-of-service/packet-flow-in-routeros/)

Lista vazia de queues é um sucesso. Cada check pode falhar independentemente;
por exemplo, REST/system resource pode funcionar enquanto DHCP falha.

## Segurança

Em modo real, a segurança contra escrita acidental é redundante:

1. Usuário RouterOS dedicado com `read,rest-api`, sem `write`.
2. `MIKROTIK_WRITE_ENABLED=false` por padrão.
3. `MikrotikWriteGuard` recusa APIs de mutação RouterOS quando escrita está
   desabilitada.
4. `RouterOsRestGateway` sempre lança `WRITE_NOT_IMPLEMENTED` antes de rede
   para `setPortSpeed`, `setDeviceSpeed`, `blockDevice` e `unblockDevice`, mesmo
   se `writeEnabled=true`.
5. Planner, reconciliation e readiness dependem somente de snapshots; não têm
   mecanismo de execução, auto-reparo ou adoção de recurso.

As mutações **locais** não passam pelo guard: metadados e configuração de portas
continuam graváveis no SQLite.

Basic Auth fica somente no cliente backend. `MikrotikProperties.toString()`
mascara senha; falhas e logs RouterOS não incluem senha, header Authorization,
body remoto nem stack trace na resposta API.

Com `verifySsl=true`, o TLS verifica certificado e hostname/IP usando o
truststore da JVM que executa o backend — em instalações padrão, normalmente o
`cacerts` do JDK/JRE desse processo. A CA/certificado do RouterOS deve ser
confiável por esse truststore, ou a JVM deve ser iniciada com um truststore
explicitamente configurado. `verifySsl=false` é uma exceção isolada no
`RouterOsRestClient` para desenvolvimento local com self-signed não confiável;
não instala trust-all global na JVM. Timeouts padrão são 3 s para conectar e 5
s para resposta.

401/403 viram falha de autenticação/permissão; indisponibilidade, TLS e payload
inesperado são categorias separadas e sanitizadas. Consulte
[routeros-readonly-integration.md](routeros-readonly-integration.md) para a
tabela de erros e configuração.

## Fora do escopo

Não há escrita RouterOS, tráfego instantâneo por monitor/POST, bloqueio real,
alteração de DHCP, criação/alteração/remoção de queues ou address lists, mudança
de firewall ou FastTrack, nem configuração de bridge, IP, NAT, rota, VLAN ou
DNS. Não há executor, confirmação de execução, auto-reparo ou adoção de
recursos.

A estratégia de bloqueio permanece `UNDECIDED`: a Fase 4 deverá escolher entre
DHCP `block-access` e firewall/address-list de acordo com a topologia real e a
documentação RouterOS. A Fase 3 não escolhe nem implementa essa estratégia.

Physical RouterOS validation: **not performed in this workspace.**
