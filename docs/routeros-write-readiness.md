# RouterOS Write Readiness — Fase 3

## Objetivo e limite inegociável

A Fase 3 prepara decisões futuras de escrita com observação, comparação e
simulação. Ela **não implementa execução** e a aplicação continua incapaz de
alterar o RouterOS.

~~~text
RouterOS
   │ GET
   ▼
Router Snapshot
   ├── Ownership analyzer
   ├── Reconciliation
   ├── Write readiness
   └── Operation planner
             │
             ▼
          Dry-run
             │
             X  sem executor e sem request mutável ao RouterOS
~~~

O RouterOsRestClient aceita somente leituras GET. Não há cliente genérico por
verbo HTTP, nem métodos post, put, patch ou delete para /rest. Isso é uma
escolha deliberada: a documentação REST do RouterOS mapeia PATCH para alteração,
PUT para criação, DELETE para remoção e POST para comandos arbitrários; todos
permanecem fora desta fase. A API HTTP **local** pode receber um POST de intenção
para produzir uma simulação, mas nunca o repassa ao RouterOS.
[REST API oficial](https://manual.mikrotik.com/docs/developer-guides/rest-api/)

MIKROTIK_WRITE_ENABLED=false continua sendo o padrão. Mesmo se alguém configurar
o valor como true, os métodos legados setPortSpeed, setDeviceSpeed, blockDevice e
unblockDevice falham antes de qualquer I/O mutável. Não existe OperationExecutor,
auto-reparo, adoção de recurso ou botão de executar nesta fase.

## Decisões confirmadas na documentação RouterOS

| Tema | Decisão na Fase 3 | Base oficial |
| --- | --- | --- |
| REST e autenticação | Usar HTTPS em www-ssl, Basic Auth apenas no backend e coleções lidas com GET. Respostas JSON representam valores como strings; a fronteira de transporte faz o parsing controlado. | [REST API](https://manual.mikrotik.com/docs/developer-guides/rest-api/) |
| IDs .id | A REST API expõe .id e permite consultá-lo no path de um registro. Nesta fase ele é somente detalhe diagnóstico/correlação; operador nenhum informa .id para uma ação e ele nunca prova ownership. | [REST API — GET](https://manual.mikrotik.com/docs/developer-guides/rest-api/#get) |
| Policies do usuário | O grupo dedicado permanece com read,rest-api, sem write. read concede acesso de consulta e write concede alteração de configuração; não se pede nem se tenta conceder write na Fase 3. | [Users e policies](https://manual.mikrotik.com/docs/authentication-authorization-accounting/user/) |
| DHCP leases | Leases são observadas em /ip/dhcp-server/lease, inclusive MAC, IP, server, status, dynamic e block-access. A documentação registra block-access como capacidade do lease, mas a Fase 3 não faz set, make-static, rate-limit ou remoção. | [DHCP — leases](https://manual.mikrotik.com/docs/network-management/dhcp/#leases) |
| Simple Queues | São observadas em /queue/simple por name, target, comment, max-limit, disabled e dynamic. Queue é um mecanismo possível de QoS futuro, não uma operação desta fase. | [Queues](https://manual.mikrotik.com/docs/firewall-and-quality-of-service/queues/), [referência queue/simple](https://manual.mikrotik.com/docs/cli-reference/queue/simple/) |
| Firewall e address lists | Filtros são observados para detectar FastTrack. No dry-run de liberação, somente `chain=forward` com `action=drop` ou `reject`, não desabilitado nem dinâmico, e que referencia exatamente o IP do dispositivo (ou uma address list que contém exatamente esse IP) é tratado como candidato externo de bloqueio. `chain=input`, `output`, `raw`, `mangle` e `nat` não representam bloqueio do tráfego do cliente. Sem o comentário exato esperado, o candidato é `FOREIGN` e nunca será removido. | [Filter](https://manual.mikrotik.com/docs/firewall-and-quality-of-service/firewall/filter/), [Address lists](https://manual.mikrotik.com/docs/firewall-and-quality-of-service/firewall/address-lists/) |
| FastTrack | Uma regra ativa action=fasttrack-connection gera alerta forte para planos de banda. O RouterOS documenta que FastTrack pode contornar Simple Queues; o painel não desabilita, move nem cria exceções de FastTrack. | [Packet Flow — FastTrack](https://manual.mikrotik.com/docs/firewall-and-quality-of-service/packet-flow-in-routeros/#fasttrack) |

Campos como comment, .id, disabled e dynamic são preservados porque afetam a
interpretação segura do recurso observado. Um item dinâmico ou desabilitado não
deve ser tratado como um recurso estático plenamente seguro para uma futura
mutação.

## Snapshot RouterOS: uma leitura por análise

Uma análise explícita obtém um RouterSnapshot imutável com os dados da mesma
janela de observação:

~~~text
GET /rest/interface
GET /rest/ip/dhcp-server
GET /rest/ip/dhcp-server/lease
GET /rest/queue/simple
GET /rest/ip/firewall/filter
GET /rest/ip/firewall/address-list
~~~

Cada coleção é buscada no máximo uma vez por snapshot e depois é processada em
memória. Portanto, analisar um ou cem leases não cria uma chamada por
dispositivo (sem N+1). Firewall é usado para o status de FastTrack e, de forma
deliberadamente estreita, para impedir que um dry-run de liberação trate um
filtro manual `forward` `drop`/`reject` de IP exato como removível. Um filtro
`input` continua sendo tráfego destinado ao próprio MikroTik e não bloqueia o
cliente para esta análise. Address lists só participam dessa proteção quando
são referenciadas por um filtro `forward` elegível e contêm a entrada exata;
isoladamente, não definem bloqueio. Ambas as leituras continuam sob demanda e
não entram no polling normal do dashboard.

O snapshot recebe capturedAt e uma impressão digital diagnóstica
(snapshotFingerprint) sem credenciais ou dump bruto. A impressão digital serve
para tornar visível que o plano foi criado sobre um estado observado; ela não é
uma autorização de escrita nem um lock.

Na tela de análise, `GET /api/write-analysis` captura uma única observação e
produz `reconciliation` e `readiness` a partir dela. O fingerprint no envelope
e o fingerprint da reconciliação identificam essa mesma observação; o status de
conexão pode acrescentar no máximo uma leitura de `/rest/system/resource`.

## Ownership explícito e conservador

Ownership é um contrato de comentário completo, centralizado em
ManagedResourceIdentifier:

~~~text
MTMGR:DEVICE:<MAC-canônico-hifenizado>
MTMGR:PORT:<nome-exato-da-interface>
~~~

O helper é a fonte da forma canônica: expectedDeviceComment(mac) normaliza o MAC
para maiúsculas e usa hífens no identificador, por exemplo
MTMGR:DEVICE:AA-BB-CC-DD-EE-01. expectedPortComment(interfaceName) preserva o
nome de interface como identidade local. A comparação é sempre pelo comentário
**esperado exato**. Por exemplo, a presença isolada de MTMGR:, um nome parecido,
um .id, MAC, IP ou target coincidente não comprova que o recurso é do painel.

| Ownership | Significado |
| --- | --- |
| MANAGED | O comentário corresponde exatamente ao identificador esperado daquele recurso. |
| FOREIGN | O recurso tem comentário não vazio, mas ele não comprova propriedade para o alvo em análise. Pode ser configuração manual ou de outro gerenciador. |
| UNKNOWN | Não há prova suficiente, por exemplo comentário ausente ou alvo inválido. |

Uma convenção futura de nome, como mtmgr-port-<interface-segura>, ajuda a
detectar colisão, mas **não é evidência de ownership**. Uma queue manual com
esse nome continua FOREIGN e conflita se o painel precisar desse nome. Da mesma
maneira, uma queue manual cujo target CIDR seja igual, subnet, supernet ou
qualquer outra rede/IP que se sobreponha ao CIDR local é `FOREIGN` e produz
`CONFLICT` bloqueante. A comparação usa intervalos IPv4 determinísticos, sem
DNS; target em sintaxe não suportada nunca é adotado e só é marcado como
relacionado quando há evidência conservadora, como interface, target amplo ou
token literal correspondente.

Não existe operação de “adotar”, “assumir”, “corrigir” ou “sincronizar” um
recurso manual nesta fase. Esse tipo de fluxo exigirá desenho, autorização e
auditoria próprios se algum dia for considerado.

## Reconciliation: ler, comparar e reportar

O estado desejado vem de metadados locais no SQLite e o estado observado vem do
snapshot. A reconciliação é observacional:

~~~text
ler → comparar → reportar
~~~

Ela não salva correções no SQLite, não cria queue, não altera DHCP e não toca
firewall. Para uma porta local CLIENT habilitada com CIDR, calcula-se a
identidade conceitual da futura Simple Queue (nome convencionado, target/CIDR e
comentário de ownership). Isso permite preparar regras de segurança sem criar o
recurso. A Fase 3 ainda não persiste uma baseline global de velocidade desejada
para a porta: max-limit é verificado quanto à legibilidade/segurança na
reconciliação e comparado a um limite solicitado apenas no respectivo dry-run.

| Status | Semântica |
| --- | --- |
| IN_SYNC | Há um recurso MANAGED único e os atributos para os quais existe estado desejado local, como identidade/nome/target/flags aplicáveis, coincidem. |
| DRIFTED | Há ownership confirmado, mas target, nome convencionado ou flags divergem do estado local/esperado; max-limit ilegível também é inseguro e produz drift. Uma diferença numérica de max-limit não é classificada globalmente sem baseline persistida. |
| MISSING | Nenhum recurso com ownership exato foi observado para um recurso local aplicável. Não é uma instrução para criar um. |
| FOREIGN | Um recurso relevante tem ownership não comprovado/foreign, mas ainda não colide com uma identidade operacional requerida. Ele não é adotado. Quando compete por nome ou target, o status é CONFLICT. |
| CONFLICT | Um recurso foreign/não comprovado usa identidade operacional que o painel pretende usar, como nome ou target. Bloqueia futura execução. |
| AMBIGUOUS_OWNERSHIP | Mais de um recurso possui o mesmo comentário exato esperado. Nunca se escolhe o primeiro; a condição é bloqueante. |
| NOT_APPLICABLE | O recurso local não é elegível para aquela análise, por exemplo porta sem papel CLIENT ou CLIENT desabilitada. Um CLIENT habilitado sem CIDR é contado no readiness, mas falha a validação de porta. |

DRIFTED e CONFLICT têm tratamentos diferentes. Uma queue MTMGR:PORT:ether2 com
target errado ainda é MANAGED e está DRIFTED; uma queue manual no mesmo target é
FOREIGN e produz CONFLICT. Recursos sem relação com o estado desejado são
ignorados. A UI mostra tipo, nome, target, ownership e motivo, sem JSON bruto
RouterOS.

## Write readiness

O relatório global de prontidão consolida uma observação sob demanda, sem
prometer que a escrita está habilitada. Ele cobre conectividade RouterOS, modo
mock/real, estado da flag, disponibilidade de interfaces/DHCP/queues, análise
de ownership, conhecimento do FastTrack e contagens de recursos gerenciados, em
sincronia, com drift, ausentes, conflitos e ownership ambíguo.

Para a validação de futuras operações de banda, `managedPorts` (mantido por
compatibilidade no contrato) significa somente portas locais `CLIENT` habilitadas.
WAN e CLIENT desabilitada podem gerar `NOT_APPLICABLE` e não bloqueiam readiness.
`validManagedPorts` conta as CLIENT habilitadas cujo CIDR está presente e é
válido; uma CLIENT habilitada sem CIDR ou com CIDR inválido permanece na primeira
contagem e torna `MANAGED_PORTS_VALID` `BLOCKING`.

Mesmo quando todos os checks forem bons, a mensagem correta é:

> Infraestrutura preparada para futura habilitação de escrita. A execução
> RouterOS permanece desabilitada nesta fase.

O relatório não tenta ler ou elevar as policies do usuário RouterOS e não pede
a policy write somente para diagnosticar. Falta de uma permissão que não é
necessária para leitura não deve virar uma solicitação de privilégio adicional.

## Operation plan e dry-run

O backend recebe somente uma intenção tipada e reconstrói o plano a partir de:

~~~text
intent do usuário + estado RouterOS atual + metadados SQLite atuais
~~~

O navegador nunca envia ownership, estado atual, preconditions satisfeitas ou um
planId como verdade confiável. O plano contém, conforme a operação, alvo, estado
observado, estado desejado, ownership, preconditions, avisos, conflitos, mudanças
abstratas, generatedAt e fingerprint do snapshot.

Os dry-runs preparados são:

- BLOCK_DEVICE;
- UNBLOCK_DEVICE;
- SET_PORT_SPEED;
- SET_DEVICE_SPEED.

Todos retornam executable=false, invariavelmente. Um plano pode ser
readyForFutureExecution=true caso os checks atuais estejam bons, mas isso não
altera a regra anterior. Ele apenas indica que seria candidato à revalidação
numa fase posterior. Um no-op, por exemplo um limite já igual ao solicitado ou
dispositivo já no estado pretendido, é válido e pode indicar
changeRequired=false; não é erro e tampouco executa algo.

### API local de diagnóstico

Estes endpoints pertencem ao backend local; os POSTs abaixo não correspondem a
POST RouterOS e não levam body de estado confiável ao roteador:

| Endpoint local | Intenção aceita |
| --- | --- |
| GET /api/reconciliation | Executa a comparação observacional sob demanda. |
| GET /api/write-readiness | Obtém o diagnóstico global de prontidão. |
| GET /api/write-analysis | Captura um snapshot e devolve readiness e reconciliation coerentes, derivados da mesma observação. |
| POST /api/plans/block | macAddress do dispositivo a analisar. |
| POST /api/plans/unblock | macAddress do dispositivo a analisar. |
| POST /api/plans/port-speed | interfaceName, downloadBps e uploadBps solicitados. |
| POST /api/plans/device-speed | macAddress, downloadBps e uploadBps solicitados. |

As respostas usam DTOs próprios e seguros. Um planId, caso retornado, identifica
o diagnóstico efêmero; não é autorização para uma futura escrita. Campos como
ownership, estado atual, resultado de preconditions, .id RouterOS ou decisão de
conflito não são aceitos do navegador como verdade.

Preconditions usam severidade INFO, WARNING ou BLOCKING. Exemplos:

| Área | Exemplos de checks |
| --- | --- |
| Dispositivo | existe, MAC válida, lease/IP disponível, lease bound, DHCP server conhecido e estado de bloqueio observado. |
| Porta | existe, é localmente gerenciada, tem CIDR e papel aplicável. |
| Ownership | recurso requerido tem ownership confirmado, não há duplicidade e não há conflito foreign. |
| Banda | limites solicitados são válidos e o limite do dispositivo não supera o limite da porta, quando a porta é limitada. |
| Rede | FastTrack conhecido; FastTrack ativo é warning forte para banda, não uma alteração automática. |

Um valor inválido de entrada é uma falha de validação. Uma queue manual com o
target igual, subnet, supernet ou CIDR sobreposto é um conflito de segurança;
overlap nunca concede ownership. Os dois conceitos permanecem separados para a
UI e para uma futura política de execução.

### Bloqueio permanece UNDECIDED

A Fase 3 analisa, mas não escolhe silenciosamente, o mecanismo de bloqueio. As
alternativas candidatas são block-access de DHCP e firewall por
address-list/filtro. O DHCP documenta block-access no lease, enquanto os filtros
podem consumir address lists; ambos têm implicações diferentes de topologia,
persistência, tráfego não-DHCP, ordem de regras e ownership. Nesta fase, somente
um filtro ativo, não dinâmico, em `chain=forward`, com `action=drop` ou `reject`,
`src-address` igual ao IP (ou `/32`), ou `src-address-list` contendo a entrada
exata, é somente um sinal defensivo: se não tiver o comentário exato
`MTMGR:DEVICE:<MAC>`, o dry-run de liberação o declara `FOREIGN` e não propõe
removê-lo. `chain=input` não entra nessa inferência. Essa leitura estreita não
declara que qualquer address list isolada bloqueia o dispositivo e não decide
qual recurso a Fase 4 deverá criar. A decisão e a implementação pertencem à
Fase 4, após revisão da topologia real e da documentação aplicável.

Por isso o dry-run de bloqueio/liberação descreve somente mudança abstrata e
segurança: lease existe, MAC/IP/server podem ser correlacionados, já há ou não
um estado candidato de bloqueio, e recursos foreign nunca podem ser removidos.
Nenhuma lease recebe make-static, block-access, rate-limit ou remoção; nenhuma
address list ou regra de firewall é criada, alterada ou removida.

## TOCTOU e regras obrigatórias para execução futura

Um plano é efêmero: RouterOS ou SQLite podem mudar entre a análise e qualquer
operação futura. Isso é o risco de **Time Of Check → Time Of Use (TOCTOU)**.
planId, timestamp e fingerprint são identificadores diagnósticos; eles não
autorizam escrita e não permitem executar cegamente um plano antigo.

Antes de qualquer mutação em uma fase futura, a camada de execução deverá:

1. receber a intenção;
2. reler o estado atual;
3. reconstruir o snapshot;
4. verificar ownership;
5. reconciliar;
6. validar preconditions;
7. verificar conflitos;
8. verificar a flag de escrita;
9. executar a mutação mínima;
10. reler o estado;
11. confirmar o resultado;
12. auditar o resultado.

Os passos 8 a 11 não existem na Fase 3. Não há lock distribuído nem uma garantia
de serialização de RouterOS nesta fase; o objetivo é explicitar a revalidação
obrigatória antes de uma futura escrita.

## Auditoria, logs e segredos

A criação de um dry-run registra a auditoria funcional resumida
OPERATION_PLAN_CREATED. O registro guarda a operação, um alvo seguro, se há
mudança candidata e a prontidão/execução da Fase 3, sem body de intenção, dump
RouterOS, fingerprint completo, credential ou conteúdo bruto de leases, queues
e firewall. A reconciliation permanece efêmera para não poluir o histórico
operacional. Logs técnicos podem registrar totais, por exemplo managed=3 e
conflicts=1, mas auditoria e log não são o mesmo mecanismo.

Senha MIKROTIK_PASSWORD, Basic Auth, header Authorization, bodies HTTP remotos e
stack traces não podem aparecer em planos, readiness, reconciliação, auditoria,
erro HTTP ou logs formatados. DTOs RouterOS, JsonNode e Map<String,Object> não
são contrato da API pública.

## Limitações e próximo passo

- Não há POST/PUT/PATCH/DELETE para RouterOS.
- Não há executor, confirmação de execução, auto-reparo ou adoção de recursos.
- Não há criação/alteração/remoção de DHCP, firewall, address list, FastTrack,
  Simple Queue, Queue Tree ou PCQ.
- FastTrack é somente um aviso para planos de banda.
- Metadados locais no SQLite continuam editáveis; essa escrita local não muda
  RouterOS.

Após code review e aprovação desta fase, a Fase 4 tratará de bloqueio/liberação
real. Ela deverá decidir formalmente entre DHCP block-access e
firewall/address-list com base na topologia real, documentação RouterOS e
revalidação descrita acima. A Fase 3 não antecipa essa decisão.

## Fontes oficiais consultadas

- [REST API — RouterOS Manual](https://manual.mikrotik.com/docs/developer-guides/rest-api/)
- [User — RouterOS Manual](https://manual.mikrotik.com/docs/authentication-authorization-accounting/user/)
- [DHCP — RouterOS Manual](https://manual.mikrotik.com/docs/network-management/dhcp/)
- [Queues — RouterOS Manual](https://manual.mikrotik.com/docs/firewall-and-quality-of-service/queues/)
- [Queue Simple — CLI Reference](https://manual.mikrotik.com/docs/cli-reference/queue/simple/)
- [Firewall Filter — RouterOS Manual](https://manual.mikrotik.com/docs/firewall-and-quality-of-service/firewall/filter/)
- [Address Lists — RouterOS Manual](https://manual.mikrotik.com/docs/firewall-and-quality-of-service/firewall/address-lists/)
- [Packet Flow in RouterOS — FastTrack](https://manual.mikrotik.com/docs/firewall-and-quality-of-service/packet-flow-in-routeros/#fasttrack)
