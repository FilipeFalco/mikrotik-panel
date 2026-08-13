# Arquitetura — MikroTik Local Manager

## Escopo atual: Fase 4

A Fase 4 acrescenta execução real somente para bloquear e liberar dispositivos
com a estratégia `FIREWALL_MAC_RULE`. O restante da aplicação continua usando
leituras RouterOS e escrita local no SQLite. Não existe executor genérico de
RouterOS.

Physical Phase 4 write validation: NOT RUN

A Fase 5 não foi iniciada.

## Fronteiras de confiança

```text
Browser (localhost)
        │ intenção local: MAC e ação
        ▼
Spring Boot (127.0.0.1)
 ├── SQLite + Flyway
 │   ├── metadados locais
 │   └── audit_log sem segredos
 ├── RouterOsRestGateway
 │   └── HTTPS GET com credenciais de leitura
 └── DeviceBlockExecutionService
     ├── snapshot + replan + preconditions
     └── RouterOsWriteClient
         └── HTTPS PUT/DELETE allowlisted com credenciais de escrita
                              │
                              ▼
                         RouterOS 7
```

O frontend não conhece JSON RouterOS, TLS, Basic Auth, paths remotos, `.id`,
ownership ou credenciais. Ele envia apenas a intenção do operador à API local.
O backend não aceita URL RouterOS arbitrária nem body RouterOS vindo do
navegador.

Há dois pares de credenciais no backend: um para leitura e um para a escrita
de bloqueio. O par de leitura nunca é usado como fallback para o par de
escrita. Os segredos ficam somente no processo backend/configuração local e
nunca chegam ao frontend, SQLite, auditoria ou log.

## Gateways e allow-list de transporte

| Modo | Leitura | Mutação de bloqueio |
| --- | --- | --- |
| `MIKROTIK_MOCK_MODE=true` | fixture em memória | mock local, sem rede |
| `MIKROTIK_MOCK_MODE=false` | `RouterOsRestGateway` por HTTPS | `RouterOsWriteClient` com shape fixo |

O gateway de escrita é intencionalmente estreito. Não recebe `HttpMethod`,
path, comando, `.id` ou DTO arbitrário:

- bloquear faz um `PUT /rest/ip/firewall/filter` com `chain`, `action`,
  `src-mac-address`, `comment`, `disabled` e, quando resolvido, `place-before`;
- liberar faz um `DELETE /rest/ip/firewall/filter/<id>` após o serviço resolver
  um único `.id` gerenciado no snapshot imediatamente anterior;
- qualquer erro de transporte pode significar que a requisição alcançou o
  roteador e é tratado como resultado desconhecido, não como autorização para
  repetir.

A REST API oficial descreve `PUT` como criação e `DELETE` como remoção por ID.
[REST API oficial da MikroTik](https://manual.mikrotik.com/docs/developer-guides/rest-api/)

## Snapshot e planejamento

O executor captura uma observação batched antes de cada operação. O snapshot
contém interfaces, DHCP servers/leases, dispositivos correlacionados, Simple
Queues, filtros IPv4 e entradas de address-list observadas. A leitura serve
para validar o alvo, detectar estado existente, FastTrack e conflitos; ela não
autoriza a alteração desses recursos.

```text
intenção { BLOCK_DEVICE | UNBLOCK_DEVICE, MAC }
                 │
                 ▼
snapshot RouterOS novo + metadados SQLite atuais
                 │
                 ▼
ownership + preconditions + replan
                 │
                 ├── conflito/no-op → sem mutação
                 │
                 ▼
           uma PUT ou DELETE fixa
                 │
                 ▼
snapshot RouterOS novo + verificação de resultado
                 │
                 ▼
             auditoria local
```

O fingerprint e o timestamp do snapshot são diagnósticos, não locks nem
tokens de autorização. Um plano de preview pode ficar obsoleto imediatamente;
o executor não aceita do browser um plano, fingerprint, ownership, estado ou
precondition como verdade. Isso trata explicitamente o risco TOCTOU.

## Regra de domínio `FIREWALL_MAC_RULE`

`ManagedDeviceBlockRule` é uma forma de domínio fixa, não um DTO geral de
firewall. Para `AA:BB:CC:DD:EE:FF`, os valores desejados são:

| Campo | Valor |
| --- | --- |
| `chain` | `forward` |
| `action` | `drop` |
| `src-mac-address` | `AA:BB:CC:DD:EE:FF` |
| `comment` | `MTMGR:DEVICE:AA-BB-CC-DD-EE-FF` |
| `disabled` | `false` |
| `dynamic` | `false` |

`dynamic=false` é conferido na leitura. A aplicação não converte uma regra
dinâmica em estática. Nenhum campo de IP, address-list, NAT, IPv6 ou ação
alternativa é acrescentado.

O comentário é produzido por um identificador central e comparado por igualdade
exata. MAC, IP, nome, target, posição ou `.id` isolados nunca dão ownership.
Esse contrato também evita que uma regra manual parecida seja apagada durante
uma liberação.

## Ordem do firewall

O filtro `forward` processa as regras de cima para baixo. Na captura anterior à
criação, o serviço encontra a primeira regra `forward` estática e usa seu `.id`
como `place-before`. Se não existir uma regra forward estática, o campo não é
enviado; ainda assim a ordem é validada na releitura.

Depois da `PUT`, o serviço captura o firewall novamente e exige exatamente uma
regra MTMGR para o MAC, com shape correto, antes de todas as demais regras
forward estáticas. Se não puder provar a posição, responde falha de posição e
não tenta criar outra regra. Regras dinâmicas não são âncoras nem entram nessa
comparação. [Firewall oficial da MikroTik](https://manual.mikrotik.com/docs/firewall-and-quality-of-service/firewall/)
e [Console oficial da MikroTik](https://manual.mikrotik.com/docs/management-tools/console/)

## Ownership e reconciliação de bloqueio

O estado de bloqueio usa estas regras:

| Observação | Decisão arquitetural |
| --- | --- |
| uma regra, comentário exato e shape completo | `MANAGED`, estado desejado |
| comentário exato, mas chain/action/MAC/disabled/dynamic divergente | drift bloqueante; não corrigir |
| comentário ausente ou diferente, bloqueio manual/foreign | conflito; não adotar nem remover |
| duas ou mais regras com comentário exato | ownership ambíguo; não escolher uma |
| nenhum bloqueio gerenciado e nenhum conflito | elegível para criar ou no-op de liberação |

Um filtro `input` é tráfego destinado ao roteador, não um bloqueio do cliente.
O reconhecimento de bloqueio externo é deliberadamente estreito e somente
defensivo: a existência de uma regra manual não se transforma em ownership do
painel. Também não se cria segunda regra quando já há um bloqueio DHCP ou
foreign reconhecido.

Para liberação, o executor só remove o `.id` de uma única regra com comentário
exato e shape aceitável. Não remove por nome, índice, primeiro resultado ou
comentário genérico.

## Idempotência, lock e resultado desconhecido

O serviço mantém lock por MAC e define idempotência pelo estado releído:

- bloquear uma regra gerenciada única, correta e bem posicionada é no-op;
- liberar sem regra gerenciada nem conflito é no-op;
- drift, foreign/manual, duplicidade ou estado ambíguo são bloqueios, não
  oportunidades de reparo;
- um `404` ao remover é relido: se a regra já não existir, a liberação é
  confirmada como no-op.

Após cada possível mutação há uma releitura. Se o transporte falhar após
`PUT`/`DELETE`, o resultado fica `OUTCOME_UNKNOWN`; uma releitura pode confirmar
sucesso somente pelos critérios acima. Duplicidade, shape divergente, ordem
insegura, regra ainda presente ou falha de releitura continuam inconclusivos e
retornam erro operacional. Não há retry cego, compensação automática ou nova
mutação para “tentar mais uma vez”.

## FastTrack

O snapshot detecta regras ativas `action=fasttrack-connection`. Em planos de
bloqueio/liberação, a precondition e o warning usam o código exato
`FASTTRACK_EXISTING_CONNECTIONS`. A mensagem informa que conexões já
FastTracked podem continuar até serem fechadas ou expirarem.

A regra MAC atua sobre pacotes que chegam ao filtro `forward`; FastTrack pode
fazer pacotes de uma conexão já FastTracked ignorarem o firewall. Fase 4 não
desabilita FastTrack, não move a regra, não limpa connection tracking e não
cria exceções. [Packet Flow oficial da MikroTik](https://help.mikrotik.com/docs/spaces/ROS/pages/328227/Packet%2BFlow%2Bin%2BRouterOS/)

## Credenciais e flags

O setup RouterOS cria dois usuários/grupos, com acesso restrito ao backend:

```routeros
/user/group/add name=mtmgr-read policy=read,rest-api
/user/add name=mtmgr-read group=mtmgr-read address=<IP-DO-BACKEND>/32 password=<senha-de-leitura>

/user/group/add name=mtmgr-write policy=read,write,rest-api
/user/add name=mtmgr-write group=mtmgr-write address=<IP-DO-BACKEND>/32 password=<senha-de-escrita>
```

O grupo de escrita é customizado e contém somente `read,write,rest-api`; não
recebe `policy`, `reboot`, `sensitive`, `sniff`, `ftp`, `ssh`, `telnet` ou
`winbox`. `MIKROTIK_USERNAME`/`MIKROTIK_PASSWORD` apontam para leitura;
`MIKROTIK_WRITE_USERNAME`/`MIKROTIK_WRITE_PASSWORD`, para escrita. [User e policies oficiais da MikroTik](https://manual.mikrotik.com/docs/authentication-authorization-accounting/user/)

Os defaults são:

```dotenv
MIKROTIK_WRITE_ENABLED=false
MIKROTIK_DEVICE_BLOCK_WRITES_ENABLED=false
```

Em modo real, ambas precisam ser `true` e o par de escrita precisa estar
configurado. As duas condições são necessárias; uma não substitui a outra.
Mock mode não é evidência de autorização nem validação de um roteador físico.

## Auditoria e falhas

O serviço registra em SQLite `BLOCK_DEVICE`/`UNBLOCK_DEVICE`, alvo `DEVICE`,
MAC normalizado, estado anterior, estado seguinte, sucesso e código de erro.
No-op é registrado; preview gera apenas `OPERATION_PLAN_CREATED`. Não são
persistidos segredos, Authorization, body, snapshot bruto ou dumps RouterOS.

Falha de auditoria não dispara retry da mutação. Falhas de autenticação,
permissão, flags, resultado desconhecido, conflito ou pós-verificação são
devolvidas com mensagens sanitizadas e exigem decisão operacional conforme o
guia de [bloqueio e rollback](routeros-device-blocking.md).

## Fora do escopo

A Fase 4 não altera:

- DHCP ou leases (`block-access`, `make-static`, rate-limit ou remoção);
- Simple Queues, Queue Tree, PCQ ou qualquer limite de velocidade;
- FastTrack;
- NAT, rotas, bridge, interface, VLAN, DNS ou IPv6;
- address-lists, filtros que não sejam a regra MAC allow-listed ou qualquer
  outro menu RouterOS.

Também não há adoção automática de configuração manual, auto-reparo,
reordenação de regra drifted, retry cego ou validação física já realizada.

Physical Phase 4 write validation: NOT RUN

A Fase 5 não foi iniciada.

## Fontes oficiais da MikroTik

- [REST API](https://manual.mikrotik.com/docs/developer-guides/rest-api/)
- [Firewall Filter](https://manual.mikrotik.com/docs/cli-reference/ip/firewall/filter/)
- [Packet Flow — FastTrack](https://help.mikrotik.com/docs/spaces/ROS/pages/328227/Packet%2BFlow%2Bin%2BRouterOS/)
- [User e policies](https://manual.mikrotik.com/docs/authentication-authorization-accounting/user/)
- [Configuration Management](https://manual.mikrotik.com/docs/getting-started/configuration-management/)
