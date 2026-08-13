# MikroTik Local Manager

Painel web local para observar um RouterOS 7 e executar, quando explicitamente
habilitado, somente o bloqueio/liberação de dispositivos da Fase 4. O backend
mantém as credenciais fora do navegador e usa a estratégia única
`FIREWALL_MAC_RULE`.

> Estado atual: **Fase 4 — bloqueio por regra de firewall MAC.** A validação de
> escrita em RouterOS físico ainda não foi executada.

Physical Phase 4 write validation: NOT RUN

A Fase 5 não foi iniciada.

## Escopo da Fase 4

Para um dispositivo `AA:BB:CC:DD:EE:FF`, a regra gerenciada tem exatamente:

```text
chain=forward action=drop src-mac-address=AA:BB:CC:DD:EE:FF
comment="MTMGR:DEVICE:AA-BB-CC-DD-EE-FF" disabled=false dynamic=false
```

O comentário completo é a única prova de ownership. A criação usa
`place-before` para ficar antes da primeira regra `forward` estática, e uma
releitura verifica shape, unicidade e ordem depois da escrita. Regra foreign,
manual, duplicada ou com drift nunca é adotada, sobrescrita ou removida.

O executor captura um snapshot novo, reconstrói o plano e revalida tudo antes
de cada mutação; o preview do navegador não é autorização. Resultado de
transporte desconhecido é confirmado por releitura, sem retry cego. O warning
`FASTTRACK_EXISTING_CONNECTIONS` informa que conexões já FastTracked podem
continuar até encerrarem ou expirarem.

Não há mutação de DHCP/leases, Simple Queues/queues, FastTrack, NAT, rotas,
bridge, IPv6, address-lists, velocidade, interface ou qualquer outro recurso.
O detalhe operacional está em
[docs/routeros-device-blocking.md](docs/routeros-device-blocking.md).

## Requisitos

- Java 21 LTS;
- Node.js 20+ e npm;
- RouterOS 7 somente para modo real;
- Maven global não é necessário: use `backend/mvnw`.

```text
Browser → Spring Boot local → API RouterOS REST
                       ↘ SQLite local (metadados e auditoria)
```

O frontend nunca recebe senha, header `Authorization`, URL RouterOS ou
snapshot bruto. O backend, por padrão, escuta em `127.0.0.1:8080`.

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

Abra [http://localhost:3000](http://localhost:3000). O mock mantém o fluxo
local de preview e bloqueio sem alcançar um MikroTik físico.

## Executar com RouterOS real

Siga primeiro [docs/routeros-setup.md](docs/routeros-setup.md) e o guia
detalhado de [bloqueio por FIREWALL_MAC_RULE](docs/routeros-device-blocking.md).
O perfil read-only inicial é:

```dotenv
MIKROTIK_HOST=192.168.88.1
MIKROTIK_PORT=443
MIKROTIK_USERNAME=mtmgr-read
MIKROTIK_PASSWORD=<segredo-de-leitura-local>
MIKROTIK_WRITE_USERNAME=mtmgr-write
MIKROTIK_WRITE_PASSWORD=<segredo-de-escrita-local>
MIKROTIK_VERIFY_SSL=true
MIKROTIK_CONNECT_TIMEOUT_MS=3000
MIKROTIK_READ_TIMEOUT_MS=5000
MIKROTIK_MOCK_MODE=false
MIKROTIK_WRITE_ENABLED=false
MIKROTIK_DEVICE_BLOCK_WRITES_ENABLED=false
```

As duas flags de escrita permanecem `false` por padrão. Em equipamento real,
somente uma janela de manutenção aprovada pode mudar **ambas** para `true`,
com credenciais de escrita separadas configuradas. Uma flag sozinha ou a
ausência do par de escrita impede qualquer mutação.

Em Configurações, **Testar conexão** e **Analisar RouterOS** fazem somente
leituras. A UI pode receber `POST` local para gerar um plano, mas isso não é um
`POST` RouterOS. O plano nunca deve ser usado como autorização sem a
revalidação feita pelo executor.

## Variáveis de ambiente

| Variável | Padrão | Uso |
| --- | --- | --- |
| `MIKROTIK_HOST` | `10.0.0.1` | Host ou IP RouterOS, sem URL/credenciais. |
| `MIKROTIK_PORT` | `443` | Porta do serviço `www-ssl`. |
| `MIKROTIK_USERNAME` | vazio | Usuário RouterOS de leitura, somente backend. |
| `MIKROTIK_PASSWORD` | vazio | Segredo do usuário de leitura. |
| `MIKROTIK_WRITE_USERNAME` | vazio | Usuário RouterOS separado para a regra de bloqueio. |
| `MIKROTIK_WRITE_PASSWORD` | vazio | Segredo do usuário de escrita. |
| `MIKROTIK_VERIFY_SSL` | `true` | Valida certificado e hostname/IP. |
| `MIKROTIK_CONNECT_TIMEOUT_MS` | `3000` | Timeout de conexão, positivo. |
| `MIKROTIK_READ_TIMEOUT_MS` | `5000` | Timeout de resposta, positivo. |
| `MIKROTIK_MOCK_MODE` | `true` | Fixtures locais; nenhuma rede RouterOS. |
| `MIKROTIK_WRITE_ENABLED` | `false` | Primeira trava global de escrita real. |
| `MIKROTIK_DEVICE_BLOCK_WRITES_ENABLED` | `false` | Segunda trava, específica da Fase 4. |
| `SERVER_ADDRESS` | `127.0.0.1` | Endereço de escuta do backend. |
| `SERVER_PORT` | `8080` | Porta do backend. |
| `APP_FRONTEND_ORIGIN` | `http://localhost:3000` | Única origem CORS permitida. |
| `APP_DATA_DIR` | `../data` | Diretório SQLite ao iniciar em `backend/`. |

`MIKROTIK_PASSWORD` nunca é fallback para
`MIKROTIK_WRITE_PASSWORD`. O usuário de leitura usa grupo customizado
`read,rest-api`; o de escrita usa grupo customizado `read,write,rest-api`, sem
`policy`, `reboot`, `sensitive`, `sniff`, `ftp`, `ssh`, `telnet` ou `winbox`, e
com `address` limitado ao IP do backend. [RouterOS User oficial da MikroTik](https://manual.mikrotik.com/docs/authentication-authorization-accounting/user/)

## Leituras RouterOS

Uma análise sob demanda captura um snapshot único. Os dados abaixo são lidos
para correlação, preconditions, detecção de FastTrack e proteção contra
conflitos; somente `/rest/ip/firewall/filter` recebe a mutação da Fase 4.

| REST path | Uso |
| --- | --- |
| `/rest/system/resource` | conexão, versão e latência aproximada |
| `/rest/interface` | interfaces e estado observado |
| `/rest/ip/dhcp-server` | correlação de servidor DHCP |
| `/rest/ip/dhcp-server/lease` | MAC, IP, status e lease `bound` |
| `/rest/queue/simple` | inventário observacional, sem alteração |
| `/rest/ip/firewall/filter` | FastTrack, conflitos e regra MAC gerenciada |
| `/rest/ip/firewall/address-list` | somente evidência observacional de conflito existente |

O polling leve lê apenas `system/resource`; diagnóstico e snapshot completo são
sob demanda. Leases são correlacionadas em memória por
`lease.server → DHCP server.name → DHCP server.interface`, sem uma request por
dispositivo.

## Fluxo de segurança

```text
snapshot novo
    ↓
replan + ownership/preconditions
    ↓
place-before + PUT/DELETE allowlisted
    ↓
snapshot de verificação
    ↓
auditoria local e resposta
```

Bloquear é no-op quando já existe uma única regra gerenciada, correta e bem
posicionada. Liberar é no-op quando não existe regra gerenciada nem conflito.
Drift, foreign/manual, duplicidade, `.id` ausente, ordem insegura ou resultado
desconhecido impedem nova mutação automática. O detalhamento de rollback
manual, auditoria e checklist físico está em
[docs/routeros-device-blocking.md](docs/routeros-device-blocking.md).

## Auditoria e dados locais

`BLOCK_DEVICE` e `UNBLOCK_DEVICE` registram no SQLite o tipo `DEVICE`, MAC
normalizado, estado anterior, estado seguinte, sucesso e código de erro. No-op
também é auditado. O histórico não contém senha, Authorization, body JSON,
snapshot bruto ou dump RouterOS.

SQLite continua sendo fonte de verdade apenas para metadados locais: nome e
observações do dispositivo; nome, descrição, CIDR, DHCP server, visibilidade e
papel local `WAN`/`CLIENT` da porta; e auditoria. Salvar esses metadados não
altera o RouterOS.

## Segurança TLS e serviços

Use somente `www-ssl`, restrito ao IP do backend e com certificado confiável
pela JVM do processo. `MIKROTIK_VERIFY_SSL=true` é o padrão; `false` fica
reservado a desenvolvimento local controlado. A documentação oficial descreve
REST sob o serviço seguro e alerta para o risco de credenciais em HTTP.
[REST API oficial da MikroTik](https://manual.mikrotik.com/docs/developer-guides/rest-api/)
e [Services oficial da MikroTik](https://manual.mikrotik.com/docs/system-information-and-utilities/services/).

## Testes de software

Os comandos abaixo são testes locais do projeto, não validação física:

```bash
cd backend
./mvnw test
./mvnw package

cd ../frontend
npm ci
npm test
npm run build
```

Este pedido altera somente documentação; não há alteração de código ou de
testes nesta entrega.

## Documentação relacionada

- [Preparação manual de `www-ssl`, usuários e flags](docs/routeros-setup.md)
- [Arquitetura da Fase 4](docs/architecture.md)
- [Write readiness da Fase 4](docs/routeros-write-readiness.md)
- [Bloqueio, ordem, idempotência, rollback e checklist físico](docs/routeros-device-blocking.md)

## Limites declarados

- somente `FIREWALL_MAC_RULE` em `/ip/firewall/filter`;
- nenhuma alteração de DHCP/leases, queue/Simple Queue, FastTrack, NAT, rota,
  bridge, IPv6, address-list, interface ou velocidade;
- nenhuma adoção ou correção automática de recurso foreign/manual/drifted;
- nenhum retry cego após resultado desconhecido;
- nenhum teste de escrita em RouterOS físico foi executado;
- Fase 5 não foi iniciada.

Physical Phase 4 write validation: NOT RUN

## Fontes oficiais da MikroTik

- [REST API](https://manual.mikrotik.com/docs/developer-guides/rest-api/)
- [Firewall Filter](https://manual.mikrotik.com/docs/cli-reference/ip/firewall/filter/)
- [Packet Flow — FastTrack](https://help.mikrotik.com/docs/spaces/ROS/pages/328227/Packet%2BFlow%2Bin%2BRouterOS/)
- [User e policies](https://manual.mikrotik.com/docs/authentication-authorization-accounting/user/)
- [Services](https://manual.mikrotik.com/docs/system-information-and-utilities/services/)
