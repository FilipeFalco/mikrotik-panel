# Arquitetura — MikroTik Local Manager

## Escopo atual

Esta entrega implementa a Fase 1: monorepo, API local, SQLite, mock mode, dashboard e fluxos completos de desenvolvimento para editar limites e bloquear/liberar dispositivos **somente em memória**. Ela não abre conexão com um MikroTik real e, portanto, não faz alterações no roteador.

## Visão geral

```text
Browser (localhost:3000)
        │ HTTP /api
        ▼
React + Vite
        │ proxy local
        ▼
Spring Boot (127.0.0.1:8080)
        ├── SQLite + Flyway ── nomes, preferências e auditoria
        └── MikrotikGateway
              ├── MockMikrotikGateway (Fase 1)
              └── UnavailableMikrotikGateway (quando mock=false na Fase 1)
```

O frontend nunca recebe credenciais e nunca fala diretamente com o RouterOS. O processo do backend, por padrão, está vinculado a `127.0.0.1`.

Não há `RouterOsRestGateway` funcional nesta fase e não há chamadas para `/rest`. Quando `MIKROTIK_MOCK_MODE=false`, o gateway indisponível devolve um estado de conexão amigável; ele não abre conexão de rede com um equipamento.

## Responsabilidades

| Camada | Responsabilidade |
| --- | --- |
| `api` | DTOs e API HTTP própria; entidades de persistência não são expostas. |
| `service` | Regras de limite, auditoria, associação de dados e serialização local por dispositivo/porta. |
| `gateway` | Contrato estável com o RouterOS e conversão para modelos internos. |
| `persistence` | SQLite apenas para estado local intencional. |
| `frontend` | Dashboard, filtros, confirmação de bloqueio, estados de carregamento e erros. |

`MikrotikGateway` é a fronteira que impede JSON cru do RouterOS de vazar para o resto da aplicação. Os modelos internos representam interfaces, leases/dispositivos, tráfego, limites e estado de conexão.

Para o carregamento do dashboard, `PortService` obtém interfaces, dispositivos e velocidades por operações agregadas (`listInterfaces`, `listDevices` e `listPortSpeeds`) e agrupa os dispositivos por interface em memória. Assim, não há uma nova consulta de dispositivos ou velocidade para cada porta, o que prepara a fronteira para um gateway HTTP futuro sem criar N+1.

## Estado e banco local

O RouterOS será a fonte de verdade para interfaces, leases, IP atual, status, queues, bloqueios e tráfego. O SQLite (`data/mikrotik-manager.db`) armazena apenas:

- `managed_port`: nome amigável, descrição, rede, servidor DHCP configurado e visibilidade no painel;
- `managed_device`: nome amigável e observações locais por MAC;
- `audit_log`: operações administrativas, sem segredos.

O mock inicial só faz o seed dessas portas quando o banco está vazio. Nenhum dado dinâmico do RouterOS é duplicado no banco.

O projeto permanece na linha Spring Boot 3.5.x. O BOM do Spring Boot gerencia Flyway, sem pin manual de versão, e as migrations SQLite continuam executadas por `flyway-core` gerenciado.

As redes de `managed_port` são CIDRs de IP literal: hostnames como `router.local/24` são rejeitados sem resolução DNS. Antes de persistir, o endereço é normalizado para o endereço de rede; por exemplo, `10.10.10.17/24` é armazenado como `10.10.10.0/24`. IPv4 e IPv6 literais são aceitos quando o prefixo é válido.

## Escrita, auditoria e limites

`MIKROTIK_WRITE_ENABLED=false` é o kill switch padrão. A decisão é centralizada no `MikrotikWriteGuard`:

- em mock mode, mutações são permitidas somente no estado simulado, para que a UI possa ser desenvolvida e testada;
- em modo real futuro, `MIKROTIK_WRITE_ENABLED=false` recusa somente mutações que alterariam o RouterOS antes de chegar ao gateway;
- em modo real futuro, escrita somente poderá ser considerada quando a variável for habilitada explicitamente e houver um adaptador real revisado.

Nomes amigáveis, observações e configurações de `managed_port` pertencem ao SQLite local e permanecem editáveis em modo real somente leitura; o guard não é aplicado a essas operações. Mutações concluídas e falhas que ocorram durante uma tentativa são gravadas em `audit_log`, sem credenciais ou headers. Falhas de validação, regras de domínio e recusas do guard de escrita RouterOS acontecem antes da tentativa e não são auditadas. Isso evita registrar uma operação que não chegou a ser tentada e mantém a política consistente.

O serviço verifica download e upload separadamente: o limite individual deve ser menor ou igual ao da porta e uma redução de porta é recusada se algum dispositivo ficaria acima do novo limite. O valor `0` significa sem limite; portanto, uma porta ilimitada não invalida um limite individual.

## Decisões RouterOS confirmadas

A documentação oficial atual informa que a REST API é o wrapper JSON da API, fica em `/rest`, usa HTTP Basic Auth e usa `GET`, `PATCH`, `PUT` e `DELETE` para leitura, alteração, criação e remoção. Ela também informa que todos os valores de respostas JSON são strings e que comandos contínuos de monitoramento não são suportados; será usado `monitor once` com polling nas fases de métricas. [REST API do RouterOS](https://manual.mikrotik.com/docs/developer-guides/rest-api/)

Em uma futura Fase 2, um adaptador somente leitura poderá consultar menus correspondentes à CLI, sem endpoints inventados: `/rest/system/resource`, `/rest/interface`, `/rest/ip/dhcp-server`, `/rest/ip/dhcp-server/lease`, `/rest/queue/simple` e `/rest/ip/firewall/filter`. Esta é apenas uma decisão de desenho: nenhum desses endpoints é chamado pela Fase 1. Alterações futuras serão feitas somente em recursos individuais identificados por `.id` retornado pelo RouterOS.

A associação lease → porta será obtida pelo servidor DHCP e sua interface, e só então confirmada pela rede CIDR cadastrada. Não haverá associação baseada em nomes fixos como `dhcp-cliente1`.

## Controle de velocidade (decisão para Fase 5)

O mecanismo inicialmente escolhido é uma hierarquia de **Simple Queues** gerenciadas pela aplicação:

```text
Simple Queue pai: MTMGR:PORT:ether2
target=10.10.10.0/24
max-limit=<total da porta>
       └── Simple Queue filha: MTMGR:DEVICE:AA-BB-CC-DD-EE-02
           target=<IP atual>/32
           parent=<fila pai>
           max-limit=<limite individual>
```

Assim, a fila pai contém o tráfego agregado da rede e as filas filhas só estabelecem o teto individual. A soma dos tetos das filhas pode exceder o teto do pai sem exceder o limite total da porta. A documentação confirma que Simple Queues suportam `target`, `max-limit` e relação `parent`, e alerta que o pai precisa capturar o tráfego necessário. A implementação real validará a ordem upload/download do campo RouterOS com um teste controlado antes de habilitar escrita. [Queues do RouterOS](https://manual.mikrotik.com/docs/firewall-and-quality-of-service/queues/)

Não será introduzido Queue Tree ou mangle no MVP sem necessidade. Eles aumentariam o risco operacional e exigiriam marcação de pacotes.

## Bloqueio (decisão para Fase 4)

O RouterOS expõe `block-access` em DHCP leases. A Fase 4 verificará em equipamento real se o lease alvo pode ser tornado estático e marcado com `MTMGR:DEVICE:<MAC>` sem alterar configuração manual existente. Só então esse mecanismo será usado para bloquear de forma reversível. Caso esse fluxo não seja seguro para o lease observado, a interface exibirá uma limitação em vez de criar regras de firewall desconhecidas.

Esta decisão é deliberadamente conservadora: não haverá remoção/edição de leases, filas ou regras sem confirmação explícita e exata de propriedade. Para um dispositivo, somente `MTMGR:DEVICE:<MAC-normalizado-com-hífens>` correspondente exatamente ao MAC é aceito; para uma porta, somente `MTMGR:PORT:<interface>` correspondente exatamente à interface é aceito. Um comentário `MTMGR:` genérico, MAC de outro dispositivo ou outra porta não concede propriedade. A documentação do DHCP lista `block-access` e o comando `make-static` no menu de leases. [DHCP do RouterOS](https://manual.mikrotik.com/docs/network-management/dhcp/)

## FastTrack

FastTrack será detectado em diagnóstico, nunca desabilitado automaticamente. A documentação diz que conexões FastTrack podem contornar Simple Queues e outras facilidades L3. Antes de qualquer limite real, a aplicação mostrará a regra detectada, o impacto e a orientação manual. [Packet Flow do RouterOS](https://manual.mikrotik.com/docs/firewall-and-quality-of-service/packet-flow-in-routeros/)

## Segurança

- `MIKROTIK_PASSWORD` é lida apenas pelo backend; não existe no código do frontend nem no SQLite.
- `MikrotikProperties.toString()` não revela a senha, reduzindo o risco de exposição acidental em logs futuros.
- `MIKROTIK_VERIFY_SSL=true` é o padrão. A opção `false` será tratada somente no cliente HTTP dedicado ao RouterOS da Fase 2 e só deve ser usada conscientemente em desenvolvimento controlado com certificado self-signed ainda não confiável. Nenhuma validação TLS global será desabilitada.
- `MIKROTIK_WRITE_ENABLED=false` é o padrão para escrita no RouterOS; mock mode permanece mutável apenas em memória, enquanto configurações locais do SQLite continuam editáveis em modo real somente leitura.
- CORS permite somente a origem local configurada.
- Logs usam identificadores operacionais (MAC/porta), nunca senha, header Authorization ou cookie.
- `OperationLockManager` serializa alterações concorrentes por MAC ou interface no processo local.
- Operações de mock já são idempotentes; a Fase 4/5 preservará a mesma propriedade no RouterOS por meio de busca prévia de comentários gerenciados.

## Limitações conhecidas da Fase 1

- Tráfego individual é dado simulado; o RouterOS pode não fornecer uma métrica individual confiável pela REST API sem instrumentação adicional.
- `MIKROTIK_MOCK_MODE=false` retorna status de integração ainda indisponível; não tenta acesso à rede.
- Não há qualquer mudança real de DHCP, firewall, queue, FastTrack, bridge, NAT, rota ou interface nesta fase.
