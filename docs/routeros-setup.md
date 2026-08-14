# Preparação manual do RouterOS para a Fase 4

Este documento prepara um RouterOS 7 para a estratégia única
`FIREWALL_MAC_RULE`: uma regra IPv4 `chain=forward`, `action=drop` e
`src-mac-address` por dispositivo. A aplicação não escolhe outra estratégia e
não altera DHCP, queues, FastTrack, NAT, rotas, bridge, IPv6 ou velocidade.

Physical Phase 4 write validation: NOT RUN

A Fase 5 adiciona Simple Queues MTMGR; siga também [o guia de bandwidth](routeros-bandwidth-control.md).

Faça a preparação com uma conta administrativa separada e janela controlada.
Antes de qualquer habilitação de escrita, faça backup/export e siga o
[checklist de teste físico](routeros-device-blocking.md#checklist-para-teste-físico-controlado)
com um dispositivo descartável.

## 1. Pré-requisitos e backup

- RouterOS 7 e IP estável do computador que executa o backend;
- acesso administrativo independente do usuário usado pela aplicação;
- certificado confiável para `www-ssl`;
- dispositivo de teste não crítico, com MAC/IP conhecidos;
- backup/export atual, com restauração possível e registrada.

Não comece por um roteador de produção. A Fase 4 pode enviar uma mutação real
quando as duas flags estiverem habilitadas; a confirmação de escrita física
continua pendente neste workspace.

## 2. Restringir o serviço REST a HTTPS

Substitua `<IP-DO-BACKEND>` e `<NOME-DO-CERTIFICADO>` pelos valores locais:

```routeros
/ip/service/set www-ssl disabled=no port=443 address=<IP-DO-BACKEND>/32 certificate=<NOME-DO-CERTIFICADO>
```

Não habilite `www`. Restrinja também o firewall de gerenciamento da topologia.
O campo `address` do serviço limita a origem aceita, e o certificado deve
corresponder ao host/IP usado pelo backend. O RouterOS documenta REST sob
`www-ssl` e alerta que HTTP expõe credenciais Basic a interceptação passiva.
[REST API oficial da MikroTik](https://manual.mikrotik.com/docs/developer-guides/rest-api/)
e [Services oficial da MikroTik](https://manual.mikrotik.com/docs/system-information-and-utilities/services/).

## 3. Criar credenciais separadas

Use um usuário para leituras e outro para a regra de bloqueio. Não use `admin`
no backend e não deixe o usuário de leitura receber `write`.

```routeros
/user/group/add name=mtmgr-read policy=read,rest-api
/user/add name=mtmgr-read group=mtmgr-read address=<IP-DO-BACKEND>/32 password=<SEGREDO-DE-LEITURA>

/user/group/add name=mtmgr-write policy=read,write,rest-api
/user/add name=mtmgr-write group=mtmgr-write address=<IP-DO-BACKEND>/32 password=<SEGREDO-DE-ESCRITA>
```

O grupo `mtmgr-write` é customizado e contém somente `read,write,rest-api`.
Não conceda a ele `policy`, `reboot`, `sensitive`, `sniff`, `ftp`, `ssh`,
`telnet` ou `winbox`. A policy `write` é necessária para modificar a
configuração, e `read` é mantida para a releitura/revalidação; o cliente da
aplicação continua limitado à regra fixa de firewall.

`address=<IP-DO-BACKEND>/32` deve ser aplicado aos dois usuários. Isso não
substitui o firewall de gerenciamento. A referência oficial recomenda grupos
customizados quando o grupo padrão inclui permissões além das necessárias.
[User e policies oficiais da MikroTik](https://manual.mikrotik.com/docs/authentication-authorization-accounting/user/)

Guarde os segredos somente no `.env` local, fora do Git. Nunca os copie para
README, SQLite, frontend, auditoria ou log.

## 4. Configurar o backend

Na raiz do projeto:

```bash
cp .env.example .env
```

Preencha o `.env` local com pares diferentes:

```dotenv
MIKROTIK_HOST=192.168.88.1
MIKROTIK_PORT=443

# somente leitura
MIKROTIK_USERNAME=mtmgr-read
MIKROTIK_PASSWORD=<SEGREDO-DE-LEITURA>

# somente a escrita allow-listed da Fase 4
MIKROTIK_WRITE_USERNAME=mtmgr-write
MIKROTIK_WRITE_PASSWORD=<SEGREDO-DE-ESCRITA>

MIKROTIK_VERIFY_SSL=true
MIKROTIK_CONNECT_TIMEOUT_MS=3000
MIKROTIK_READ_TIMEOUT_MS=5000
MIKROTIK_MOCK_MODE=false

# defaults seguros; mantenha assim até a janela de teste aprovada
MIKROTIK_WRITE_ENABLED=false
MIKROTIK_DEVICE_BLOCK_WRITES_ENABLED=false
```

`MIKROTIK_WRITE_PASSWORD` não pode ser omitida em modo real. O backend nunca
usa `MIKROTIK_PASSWORD` como fallback. Os defaults são finitos e positivos:
3 segundos para conexão e 5 segundos para resposta.

Com `MIKROTIK_VERIFY_SSL=true`, a CA/certificado precisa ser confiável pelo
truststore da JVM que executa o backend e o hostname/IP precisa corresponder ao
certificado. Use `false` somente em desenvolvimento local controlado com
self-signed, não em rede não confiável.

## 5. Entender as duas flags

| Flag | Default | Efeito |
| --- | --- | --- |
| `MIKROTIK_WRITE_ENABLED` | `false` | trava global de mutações RouterOS |
| `MIKROTIK_DEVICE_BLOCK_WRITES_ENABLED` | `false` | trava específica para `FIREWALL_MAC_RULE` |

Em RouterOS real, as duas flags precisam estar `true` e as credenciais de
escrita precisam estar preenchidas. Qualquer flag `false`, mock mode ou falta
de credencial interrompe a operação antes do `PUT`/`DELETE`.

Ativar apenas a primeira flag não habilita bloqueio. A segunda não substitui a
primeira. Recomendação operacional: mantenha ambas `false` durante toda a
preparação, leitura e análise; habilite ambas somente imediatamente antes do
teste físico aprovado e desabilite-as ao terminar.

## 6. Verificar conexão e análise sem escrever

Inicie backend e frontend conforme o [README](../README.md). Em Configurações:

1. use **Testar conexão**;
2. confirme que o backend consegue ler `system/resource` com
   `mtmgr-read`;
3. use **Analisar RouterOS** e confirme snapshot/reconciliation/readiness;
4. confirme que `MIKROTIK_WRITE_ENABLED=false` e
   `MIKROTIK_DEVICE_BLOCK_WRITES_ENABLED=false` aparecem como desabilitadas;
5. verifique que nenhuma regra foi criada ou alterada.

O fluxo de leitura usa, conforme a análise, estes paths:

```text
GET /rest/system/resource
GET /rest/interface
GET /rest/ip/dhcp-server
GET /rest/ip/dhcp-server/lease
GET /rest/queue/simple
GET /rest/ip/firewall/filter
GET /rest/ip/firewall/address-list
```

A UI pode enviar um `POST` à API local para produzir um preview; isso não é um
`POST` RouterOS. No transporte RouterOS, a Fase 4 só usa `GET` para leitura e,
quando as duas flags e o par de escrita estão habilitados, `PUT`/`DELETE` na
coleção `/rest/ip/firewall/filter`.

## 7. Forma criada e ordem

Para o MAC `AA:BB:CC:DD:EE:FF`, a aplicação só pode criar a seguinte forma:

```text
chain=forward action=drop src-mac-address=AA:BB:CC:DD:EE:FF
comment="MTMGR:DEVICE:AA-BB-CC-DD-EE-FF" disabled=false dynamic=false
```

`dynamic=false` é verificado na releitura. O executor resolve a primeira regra
forward estática e envia seu `.id` em `place-before`; depois relê e confirma
shape, unicidade e posição antes das demais regras forward estáticas. Se não
houver âncora estática, não envia `place-before`, mas ainda verifica a ordem.

Não altere manualmente a regra MTMGR durante a janela da operação. Se alguém
alterar chain, action, MAC, comment, disabled, dynamic ou ordem, o resultado é
drift/conflito e a aplicação para.

## 8. Readiness, ownership e segurança operacional

Ownership exige o comentário completo `MTMGR:DEVICE:<MAC-MAIÚSCULO-COM-HÍFENS>`.
Prefixo, nome, IP, MAC isolado e `.id` não bastam. Regra manual/foreign,
duplicidade e drift não são corrigidos. Liberar remove somente o `.id` de uma
única regra gerenciada resolvida imediatamente antes; não usa índice nem
`find` amplo.

Cada execução faz `snapshot → replan → validação → uma mutação → snapshot de
verificação`, com lock por MAC. O preview não é autorização. Se o transporte
falhar depois de possivelmente alcançar o roteador, o estado é relido; não há
retry cego. Ausência confirmada após `DELETE` é no-op bem-sucedido, mas estado
ambíguo exige inspeção manual.

FastTrack ativo acrescenta o warning exato `FASTTRACK_EXISTING_CONNECTIONS`:
conexões já FastTracked podem continuar até fechar ou expirar. Não desabilite,
mova ou limpe FastTrack por causa desta fase. [Packet Flow oficial da MikroTik](https://help.mikrotik.com/docs/spaces/ROS/pages/328227/Packet%2BFlow%2Bin%2BRouterOS/)

## 9. Rollback manual de emergência

Em resultado desconhecido, falha de ordem ou pós-verificação, pare novas
tentativas e use uma sessão administrativa independente. Primeiro execute:

```routeros
/ip/firewall/filter/print detail
/ip/firewall/filter/print detail where comment="MTMGR:DEVICE:AA-BB-CC-DD-EE-FF"
```

Se houver um único `.id` e o shape estiver confirmado, use o ID literal:

```routeros
/ip/firewall/filter/disable *N
/ip/firewall/filter/remove *N
```

Não use `remove [find where comment=...]` com duplicidade ou dúvida. Para
examinar histórico, use `/system/history/print detail`; `/undo` só é aceitável
quando o administrador confirmou que a última ação é a alteração correta e
não houve ação intermediária. [Configuration Management oficial da MikroTik](https://manual.mikrotik.com/docs/getting-started/configuration-management/)

## 10. Checklist físico

O checklist completo, incluindo backup e dispositivo descartável, está em
[routeros-device-blocking.md](routeros-device-blocking.md#checklist-para-teste-físico-controlado).
Ele exige confirmação de shape, `place-before`, ordem, idempotência, conflito
foreign/manual, FastTrack e rollback. O teste não foi executado neste
workspace.

Physical Phase 4 write validation: NOT RUN

Para Simple Queue e a flag independente de banda, consulte [o guia da Fase 5](routeros-bandwidth-control.md).

## Fora do escopo

Esta preparação não autoriza nem implementa alteração de DHCP/leases,
Queue Tree, FastTrack, NAT, rota, bridge, IPv6, address-list,
interface ou velocidade. A Fase 4 só escreve a regra MAC allow-listed de
`/ip/firewall/filter`.

## Fontes oficiais da MikroTik

- [REST API](https://manual.mikrotik.com/docs/developer-guides/rest-api/)
- [Services](https://manual.mikrotik.com/docs/system-information-and-utilities/services/)
- [User e policies](https://manual.mikrotik.com/docs/authentication-authorization-accounting/user/)
- [Firewall Filter](https://manual.mikrotik.com/docs/cli-reference/ip/firewall/filter/)
- [Packet Flow — FastTrack](https://help.mikrotik.com/docs/spaces/ROS/pages/328227/Packet%2BFlow%2Bin%2BRouterOS/)
- [Configuration Management](https://manual.mikrotik.com/docs/getting-started/configuration-management/)
- [Console](https://manual.mikrotik.com/docs/management-tools/console/)
