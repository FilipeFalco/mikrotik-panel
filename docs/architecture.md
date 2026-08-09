# Arquitetura — MikroTik Local Manager

## Escopo atual: Fase 2 somente leitura

A aplicação local lê dados de um RouterOS 7 por REST e preserva escrita apenas
no SQLite local. Em modo real, o RouterOS é fonte de verdade para interfaces,
leases, queues observadas e diagnóstico; SQLite é fonte de verdade para
metadados escolhidos pelo operador e auditoria.

```text
Browser (localhost)
        │ /api
        ▼
React + Vite
        ▼
Spring Boot (127.0.0.1)
 ├── SQLite + Flyway
 │   ├── managed_port: nome, descrição, CIDR, DHCP server, visibilidade
 │   ├── managed_device: nome amigável e observações
 │   └── audit_log: auditoria local sem segredos
 └── MikrotikGateway
     ├── MockMikrotikGateway
     └── RouterOsRestGateway
         └── RouterOsRestClient → HTTPS GET /rest → RouterOS
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
```

**RouterOS HTTP methods used: GET only.** Embora o RouterOS REST também suporte
verbos mutáveis e comandos via POST, a Fase 2 não os chama.
[REST API oficial](https://manual.mikrotik.com/docs/developer-guides/rest-api/)

## Dados e correlação

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

`RouterOsSimpleQueueMapper` observa somente queues cujo comentário é exatamente
`MTMGR:PORT:<interface>`. Queue manual não é adotada. `max-limit` RouterOS é
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

FastTrack só é consultado em diagnóstico. Regra habilitada com
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

As mutações **locais** não passam pelo guard: metadados e configuração de portas
continuam graváveis no SQLite.

Basic Auth fica somente no cliente backend. `MikrotikProperties.toString()`
mascara senha; falhas e logs RouterOS não incluem senha, header Authorization,
body remoto nem stack trace na resposta API.

O TLS verifica certificado/hostname por padrão. `verifySsl=false` é uma exceção
isolada no cliente RouterOS para desenvolvimento local com self-signed não
confiável; não instala trust-all global na JVM. Timeouts padrão são 3 s para
conectar e 5 s para resposta.

401/403 viram falha de autenticação/permissão; indisponibilidade, TLS e payload
inesperado são categorias separadas e sanitizadas. Consulte
[routeros-readonly-integration.md](routeros-readonly-integration.md) para a
tabela de erros e configuração.

## Fora do escopo

Não há escrita RouterOS, tráfego instantâneo por monitor/POST, bloqueio real,
alteração de DHCP, criação/alteração/remoção de queues, mudança de firewall ou
FastTrack, nem configuração de bridge, IP, NAT, rota, VLAN ou DNS.

Physical RouterOS validation: **not performed in this workspace.**
