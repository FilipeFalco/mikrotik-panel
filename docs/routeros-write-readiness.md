# RouterOS Write Readiness — Fase 4

## Objetivo

O readiness da Fase 4 responde se a instalação tem informação e capacidade
configuradas para uma futura execução de `FIREWALL_MAC_RULE`. Ele é
diagnóstico, não autorização: mesmo um relatório pronto não permite reutilizar
um preview. O executor sempre captura um snapshot novo, replaneja e revalida
ownership, conflito, shape e ordem antes da mutação.

Physical Phase 4 write validation: NOT RUN

A Fase 5 não foi iniciada.

## Estratégia única

Não há estado `UNDECIDED` para a Fase 4. A estratégia é fixa:

```text
/ip/firewall/filter
chain=forward
action=drop
src-mac-address=<MAC-CANÔNICO>
comment="MTMGR:DEVICE:<MAC-MAIÚSCULO-COM-HÍFENS>"
disabled=false
dynamic=false
```

O readiness confirma que essa estratégia é analisável e que a ordem poderá ser
comprovada. Não prepara ou oferece DHCP `block-access`, Simple Queue,
address-list, FastTrack, NAT, rota, bridge, IPv6 ou velocidade como alternativa.

## O que a análise lê

Uma chamada de análise captura uma única observação coerente de:

```text
GET /rest/interface
GET /rest/ip/dhcp-server
GET /rest/ip/dhcp-server/lease
GET /rest/queue/simple
GET /rest/ip/firewall/filter
GET /rest/ip/firewall/address-list
```

`/rest/system/resource` pode ser lido separadamente para status de conexão. As
coleções são processadas em memória, sem uma chamada por dispositivo. O
snapshot tem fingerprint diagnóstico, mas ele não é lock, precondition
permanente ou autorização.

O readiness nunca chama `PUT`, `PATCH`, `POST` ou `DELETE` RouterOS. Um `POST`
da API local para obter um plano continua sendo apenas uma intenção local.

## Checks de readiness

Os checks relevantes são:

| Check | Resultado esperado | Severidade/efeito |
| --- | --- | --- |
| `ROUTEROS_CONNECTED` | snapshot de leitura disponível | bloqueia se falso |
| `REAL_ROUTER_MODE` | modo real para teste físico | mock gera aviso; não valida equipamento |
| `WRITE_FLAG_DISABLED` | `MIKROTIK_WRITE_ENABLED=false` é o default seguro | se ativa, avisa que a segunda trava ainda é exigida |
| `DEVICE_BLOCK_WRITE_FLAG` | `MIKROTIK_DEVICE_BLOCK_WRITES_ENABLED=false` por default | habilitada só para janela aprovada |
| `WRITE_CREDENTIALS_CONFIGURED` | par separado `MIKROTIK_WRITE_USERNAME/PASSWORD` preenchido | bloqueia execução real se ausente |
| `BLOCKING_STRATEGY` | `FIREWALL_MAC_RULE` | informativo e fixo |
| `FIREWALL_ORDERING_ANALYZABLE` | filtros podem ser relidos e têm `.id` seguro para a âncora quando necessário | bloqueia se a ordem não puder ser comprovada |
| `OWNERSHIP_ANALYZABLE` | comentário esperado pode ser comparado exatamente | informativo; falhas concretas bloqueiam o plano |
| `FASTTRACK_STATUS_KNOWN` | status foi lido no snapshot | informativo |
| `NO_FOREIGN_CONFLICT` | nenhum bloqueio foreign/manual competindo | bloqueia se falso |
| `NO_AMBIGUOUS_OWNERSHIP` | no máximo uma regra MTMGR por MAC | bloqueia se falso |
| drift da regra | shape atual corresponde ao contrato | bloqueia se divergir |

`readyForFutureExecution=true` só pode aparecer quando a capacidade está
configurada e não há findings bloqueantes. Isso não significa que o próximo
request será executado sem nova leitura.

As duas flags de escrita são independentes e necessárias em modo real:

```dotenv
MIKROTIK_WRITE_ENABLED=false
MIKROTIK_DEVICE_BLOCK_WRITES_ENABLED=false
```

Para uma execução real, ambas precisam estar `true` **e** as credenciais
separadas precisam estar configuradas. Com qualquer flag `false`, a execução
retorna bloqueio antes de `PUT`/`DELETE`. Mock mode pode simular o fluxo local,
mas não muda o status de validação física.

## Preconditions do plano de bloqueio

Além do readiness global, o plano de cada MAC deve confirmar:

- MAC válido e normalizado;
- exatamente uma lease DHCP e um dispositivo correlacionado;
- IP observado;
- lease em estado `bound`;
- servidor DHCP conhecido e habilitado;
- ausência de bloqueio foreign/manual, duplicidade ou estado ambíguo;
- regra MTMGR inexistente para criação, ou única regra MTMGR resolvida para
  remoção;
- shape e ordem seguros quando já houver regra gerenciada.

Essas leituras DHCP são apenas correlação e precondition. A Fase 4 não executa
`block-access`, `make-static`, rate-limit ou remoção de lease.

## Ownership e conflito

O único comentário gerenciado é:

```text
MTMGR:DEVICE:AA-BB-CC-DD-EE-FF
```

Ownership não é inferido de prefixo, MAC isolado, IP, nome, posição ou `.id`.

| Observação | Readiness/planejamento |
| --- | --- |
| exatamente uma regra com comment e shape corretos | `MANAGED`, pode ser no-op ou alvo de liberação |
| comment exato com chain/action/MAC/disabled/dynamic divergente | `DRIFT`, bloqueante; não corrigir |
| regra manual/foreign de bloqueio | conflito bloqueante; não adotar nem apagar |
| mais de uma regra com comment exato | `AMBIGUOUS_OWNERSHIP`, bloqueante |
| nenhum bloqueio observado | elegível para criação; a execução ainda revalida |

Um filtro `chain=input` não conta como bloqueio de tráfego do cliente. Um filtro
ativo `forward` de descarte ou uma regra manual por MAC/IP pode representar
conflito; o painel não o converte em recurso próprio. Overlap ou semelhança
nunca concede ownership.

## Ordem e replanejamento

O plano pode descrever `CREATE_FIREWALL_MAC_RULE` ou
`DELETE_FIREWALL_MAC_RULE`, mas a mudança é somente uma intenção abstrata. Na
execução:

1. lock local por MAC;
2. snapshot novo;
3. replan a partir da intenção e do SQLite atual;
4. validação de alvo, ownership, conflito e preconditions;
5. para criação, resolver a primeira regra `forward` estática e passá-la como
   `place-before`;
6. uma única `PUT` ou `DELETE` allowlisted;
7. novo snapshot de verificação;
8. auditoria e resposta.

Após criação, a regra precisa ser única, possuir o shape
`FIREWALL_MAC_RULE` e estar antes de todas as outras regras forward estáticas.
Se a posição não puder ser comprovada, a operação falha. O serviço nunca usa
o índice exibido pela UI como identidade e nunca reaproveita um `.id` de um
preview antigo. [Firewall oficial da MikroTik](https://manual.mikrotik.com/docs/firewall-and-quality-of-service/firewall/)
e [REST API oficial da MikroTik](https://manual.mikrotik.com/docs/developer-guides/rest-api/)

## Idempotência e resultado desconhecido

| Pedido | Estado seguro | Ação |
| --- | --- | --- |
| bloquear | uma regra gerenciada correta e bem posicionada | no-op confirmado; sem `PUT` |
| bloquear | nenhuma regra e nenhum conflito | criar uma regra |
| liberar | nenhuma regra gerenciada e nenhum conflito | no-op confirmado; sem `DELETE` |
| liberar | uma regra gerenciada única, com `.id` seguro | apagar esse `.id` e verificar ausência |
| qualquer | foreign/manual, drift, duplicidade ou ordem insegura | bloqueio; inspeção manual |

Se uma falha de rede ou 5xx ocorrer depois de `PUT`/`DELETE`, a resposta é
`OUTCOME_UNKNOWN`. O serviço relê o RouterOS:

- bloqueio só é considerado concluído com exatamente uma regra desejada e
  ordem segura;
- liberação só é considerada concluída com ausência da regra gerenciada;
- regra duplicada, shape divergente, posição insegura, regra ainda presente ou
  nova falha de leitura continuam inconclusivos.

`404` no delete é relido e pode ser convertido em no-op se a regra já não
existir. Em nenhum desses casos há retry automático ou repetição cega.

## FastTrack e warning obrigatório

Se houver regra ativa `action=fasttrack-connection`, o plano de bloqueio e
liberação inclui:

```text
FASTTRACK_EXISTING_CONNECTIONS
```

O texto explica que conexões já FastTracked podem continuar até serem fechadas
ou expirarem. FastTrack pode ignorar o firewall para pacotes de conexões já
marcadas; o painel não desabilita, move, limpa ou excepciona FastTrack. [Packet Flow oficial da MikroTik](https://help.mikrotik.com/docs/spaces/ROS/pages/328227/Packet%2BFlow%2Bin%2BRouterOS/)

O check de FastTrack para banda pode continuar como aviso diagnóstico, mas Fase
4 não altera Simple Queues nem velocidade. Não confundir warning com falha nem
com autorização para modificar FastTrack.

## Credenciais, auditoria e limites

O usuário de leitura usa `MIKROTIK_USERNAME`/`MIKROTIK_PASSWORD` e grupo
customizado `read,rest-api`. O usuário de escrita usa
`MIKROTIK_WRITE_USERNAME`/`MIKROTIK_WRITE_PASSWORD` e grupo customizado
`read,write,rest-api`, com `address=<IP-DO-BACKEND>/32`. O grupo de escrita não
recebe `policy`, `reboot`, `sensitive`, `sniff`, `ftp`, `ssh`, `telnet` ou
`winbox`. [User e policies oficiais da MikroTik](https://manual.mikrotik.com/docs/authentication-authorization-accounting/user/)

Cada tentativa de `BLOCK_DEVICE`/`UNBLOCK_DEVICE` registra no SQLite o alvo,
estado anterior/seguinte, sucesso e código de erro. No-op é auditado. O plano
registra somente `OPERATION_PLAN_CREATED`. Nenhum segredo, Authorization, body
RouterOS, snapshot bruto ou dump é persistido; falha de auditoria não repete a
mutação.

Não há mutação de DHCP/leases, queues/Simple Queues, FastTrack, NAT, rota,
bridge, IPv6, address-list ou velocidade. Só a regra MAC allow-listed de
`/ip/firewall/filter` pode ser criada/removida pela Fase 4.

## Status e validação física

O checklist de teste exige backup/export, dispositivo descartável, confirmação
de `www-ssl`, credenciais separadas, ambas as flags, shape, `place-before`,
ordem, idempotência, conflitos e rollback manual. Ele está em
[routeros-device-blocking.md](routeros-device-blocking.md#checklist-para-teste-físico-controlado).

Physical Phase 4 write validation: NOT RUN

A Fase 5 não foi iniciada.

## Fontes oficiais da MikroTik

- [REST API](https://manual.mikrotik.com/docs/developer-guides/rest-api/)
- [Firewall Filter](https://manual.mikrotik.com/docs/cli-reference/ip/firewall/filter/)
- [Packet Flow — FastTrack](https://help.mikrotik.com/docs/spaces/ROS/pages/328227/Packet%2BFlow%2Bin%2BRouterOS/)
- [User e policies](https://manual.mikrotik.com/docs/authentication-authorization-accounting/user/)
