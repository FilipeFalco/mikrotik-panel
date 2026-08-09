# MikroTik Local Manager

Painel web local para visualizar interfaces, leases DHCP, Simple Queues e
diagnósticos de um RouterOS 7 sem expor credenciais ao navegador.

> Estado atual: **Fase 2 — integração RouterOS REST somente leitura.** Em modo
> real, o backend emite apenas HTTPS `GET` para RouterOS. Nenhuma escrita
> RouterOS foi implementada.

## Requisitos

- Java 21 LTS;
- Node.js 20+ e npm;
- RouterOS 7 somente para modo real;
- Maven global não é necessário: use `backend/mvnw`.

```text
Browser → Spring Boot local → HTTPS GET /rest → RouterOS
                       ↘ SQLite local (metadados, papel da porta e auditoria)
```

O frontend nunca recebe senha, header `Authorization` ou URL RouterOS. O
backend, por padrão, escuta em `127.0.0.1:8080`.

## Executar em mock mode

Mock mode é o padrão seguro para desenvolvimento e não acessa rede RouterOS.

```bash
cp .env.example .env

cd backend
set -a
. ../.env
set +a
./mvnw spring-boot:run
```

Em outro terminal:

```bash
cd frontend
npm ci
npm run dev
```

Abra [http://localhost:3000](http://localhost:3000). Em mock mode, bloquear,
liberar e mudar limites alteram somente dados em memória para desenvolvimento;
nenhuma chamada é enviada ao MikroTik.

## Executar com RouterOS real em modo somente leitura

1. Siga [docs/routeros-setup.md](docs/routeros-setup.md) para configurar
   `www-ssl`, certificado e usuário dedicado `read,rest-api` sem `write`.
2. Copie `.env.example` para `.env` e mantenha o arquivo fora do Git.
3. Configure o perfil seguro:

```dotenv
MIKROTIK_HOST=192.168.88.1
MIKROTIK_PORT=443
MIKROTIK_USERNAME=mtmgr
MIKROTIK_PASSWORD=<segredo-local>
MIKROTIK_VERIFY_SSL=true
MIKROTIK_CONNECT_TIMEOUT_MS=3000
MIKROTIK_READ_TIMEOUT_MS=5000
MIKROTIK_MOCK_MODE=false
MIKROTIK_WRITE_ENABLED=false
```

4. Inicie backend e frontend como acima. Em Configurações, use **Testar
   conexão**. A UI mostra `RouterOS 7.x · Somente leitura` após sucesso.

O botão pode chamar uma rota `POST` da nossa API local; a comunicação com
RouterOS ainda é somente `GET /rest/system/resource`.

`MIKROTIK_VERIFY_SSL=true` valida a cadeia e o hostname/IP do certificado com
as âncoras de confiança do truststore da JVM que executa o backend. Em uma
instalação Java padrão, isso normalmente é o `cacerts` do JDK/JRE usado pelo
processo — não apenas o repositório de certificados do navegador ou do sistema
operacional. Para um certificado self-signed, faça a CA/certificado ser
confiável por essa JVM; outra opção de implantação é iniciar a JVM com um
truststore explicitamente configurado. Use `false` apenas em desenvolvimento
local controlado: a exceção TLS fica isolada no `RouterOsRestClient` e não
altera SSL global da JVM nem outros clientes HTTP.

## Variáveis de ambiente

| Variável | Padrão | Uso |
| --- | --- | --- |
| `MIKROTIK_HOST` | `10.0.0.1` | Host ou IP RouterOS, sem URL/credenciais. |
| `MIKROTIK_PORT` | `443` | Porta do serviço `www-ssl`. |
| `MIKROTIK_USERNAME` | vazio | Usuário RouterOS dedicado, somente backend. |
| `MIKROTIK_PASSWORD` | vazio | Segredo local; nunca frontend, SQLite ou log. |
| `MIKROTIK_VERIFY_SSL` | `true` | Valida certificado e hostname/IP. |
| `MIKROTIK_CONNECT_TIMEOUT_MS` | `3000` | Timeout de conexão, positivo. |
| `MIKROTIK_READ_TIMEOUT_MS` | `5000` | Timeout de resposta, positivo. |
| `MIKROTIK_MOCK_MODE` | `true` | Usa fixtures locais e nenhuma rede RouterOS. |
| `MIKROTIK_WRITE_ENABLED` | `false` | Kill switch de escrita RouterOS; a Fase 2 não implementa escrita nem se `true`. |
| `SERVER_ADDRESS` | `127.0.0.1` | Endereço de escuta do backend. |
| `SERVER_PORT` | `8080` | Porta do backend. |
| `APP_FRONTEND_ORIGIN` | `http://localhost:3000` | Única origem CORS permitida. |
| `APP_DATA_DIR` | `../data` | Diretório SQLite ao iniciar em `backend/`. |

## O que o modo real lê

| RouterOS REST path | Dados usados |
| --- | --- |
| `/rest/system/resource` | conexão, versão e latência |
| `/rest/interface` | interfaces reais |
| `/rest/ip/dhcp-server` | associação DHCP server → interface |
| `/rest/ip/dhcp-server/lease` | dispositivos DHCP e status |
| `/rest/queue/simple` | Simple Queues observadas/limites de porta com ownership exato |
| `/rest/ip/firewall/filter` | FastTrack no diagnóstico sob demanda |

**RouterOS HTTP methods used: GET only.** Não há `POST`, `PUT`, `PATCH` ou
`DELETE` para o RouterOS. Leases são correlacionadas em memória por
`lease.server → DHCP server.name → DHCP server.interface`, sem uma consulta por
dispositivo. Um lease sem MAC utilizável ou sem DHCP server correspondente é
ignorado de forma segura sem quebrar os demais.

Veja os detalhes de DTOs, mappers, TLS, códigos de erro, direção de taxas e
FastTrack em [docs/routeros-readonly-integration.md](docs/routeros-readonly-integration.md).

## Segurança e comportamento de escrita

Em RouterOS real, a defesa é deliberadamente redundante:

1. Usuário RouterOS sem policy `write`.
2. `MIKROTIK_WRITE_ENABLED=false`.
3. `MikrotikWriteGuard` bloqueia operações RouterOS mutáveis.
4. `RouterOsRestGateway` não tem implementação de mutação na Fase 2.

Assim, botões de bloquear/liberar/alterar velocidade em modo real devolvem erro
seguro antes de qualquer request mutável. Mesmo com
`MIKROTIK_WRITE_ENABLED=true`, não criam queue, não alteram lease e não mudam
firewall.

SQLite continua sendo fonte de verdade apenas para estado local: nome amigável
e observações do dispositivo; nome, descrição, CIDR, DHCP server e visibilidade
da porta; papel `WAN` ou `CLIENT`; e auditoria. `PUT /api/devices/{mac}` e
`PUT /api/ports/{interface}` continuam permitidos para esses metadados locais
em read-only.

## Configuração local de portas e WAN

Interfaces vêm do RouterOS por `GET /rest/interface`. Uma interface descoberta
que ainda não exista no SQLite aparece em **Configurações → Interfaces
descobertas** como não gerenciada. Nessa tela o operador pode salvar nome
amigável, descrição, CIDR, DHCP server, visibilidade no dashboard e o papel
local `WAN` ou `CLIENT`.

```text
RouterOS descobre interface
          ↓
operador configura metadados locais
          ↓
SQLite local
          ↓
dashboard
```

Esse fluxo usa somente `PUT /api/ports/{interface}` na API local. Ele não muda
interface, rota, NAT, DHCP client, firewall ou qualquer outra configuração do
RouterOS. A porta física não é renomeada no equipamento. Em modo real
read-only, esses campos locais continuam editáveis.

O dashboard identifica a Internet pelo papel local `WAN`, não pelo nome físico
da interface. Portanto, a WAN pode ser `ether5` ou outra interface apresentada
pelo fluxo de portas atual; `ether1` é WAN apenas no fixture de mock atual, não
uma regra do produto.

## Diagnóstico e erros

O polling leve de status lê apenas `system/resource`. Diagnósticos separados
verificam REST API, Interfaces, DHCP, Queues e FastTrack; FastTrack não é
consultado a cada cinco segundos. Lista de queues vazia é sucesso, não erro.

| Situação | Resultado seguro |
| --- | --- |
| 401/403 | falha de autenticação/permissão de leitura |
| rede, DNS, timeout ou 5xx | MikroTik desconectado; SQLite e histórico continuam disponíveis |
| TLS | mensagem de validação TLS sem detalhe sensível |
| JSON inesperado | erro controlado, sem body/stack trace RouterOS |

As mensagens e logs não incluem senha ou `Authorization`.

## Limitações da Fase 2

- Sem tráfego instantâneo via `monitor`/POST; contadores acumulados não são
  apresentados como Mbps.
- Sem bloqueio real, `make-static`, alteração de `block-access` ou rate-limit
  de DHCP.
- Sem criação, edição, remoção ou reordenação de Simple Queues.
- Sem alteração de firewall/FastTrack, bridge, IP, DHCP, NAT, rota, VLAN, DNS
  ou interface.
- Sem multi-router, acesso remoto, billing, WebSocket ou SSE.

FastTrack pode contornar Simple Queues e é apenas detectado/avisado. Queues
manuais não são adotadas; somente comentário exatamente
`MTMGR:PORT:<interface>` prova ownership local.

## Testes e build

Backend:

```bash
cd backend
./mvnw test
./mvnw package
```

Frontend:

```bash
cd frontend
npm ci
npm test
npm run build
```

Os testes incluem fake RouterOS, autenticação/indisponibilidade/resposta inválida,
correlação DHCP, direção de queue, FastTrack, bloqueio de métodos mutáveis e
verificação arquitetural de GET-only.

## Documentação RouterOS

- [Preparação manual e usuário read-only](docs/routeros-setup.md)
- [Arquitetura e decisões da integração de leitura](docs/routeros-readonly-integration.md)
- [REST API oficial da MikroTik](https://manual.mikrotik.com/docs/developer-guides/rest-api/)

Physical RouterOS validation: **not performed in this workspace.**
