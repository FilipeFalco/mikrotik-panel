# Bloqueio de dispositivos no RouterOS — Fase 4

## Estado e limite

A Fase 4 usa exclusivamente a estratégia `FIREWALL_MAC_RULE`. Bloquear um
dispositivo significa criar uma única regra IPv4 em `/ip/firewall/filter`;
liberar significa remover somente essa regra quando o ownership e o `.id`
forem comprovados novamente. Não há escolha entre estratégias e não há
fallback para DHCP, address-list ou qualquer outro mecanismo.

Physical Phase 4 write validation: NOT RUN

A Fase 5 não foi iniciada.

## Contrato fixo da regra

O formato desejado é uma allow-list deliberadamente pequena:

| Campo RouterOS | Valor exigido |
| --- | --- |
| `chain` | `forward` |
| `action` | `drop` |
| `src-mac-address` | MAC do dispositivo em forma canônica, por exemplo `AA:BB:CC:DD:EE:FF` |
| `comment` | exatamente `MTMGR:DEVICE:AA-BB-CC-DD-EE-FF`, com MAC maiúsculo e hífens |
| `disabled` | `false` |
| `dynamic` | `false` |

Em termos conceituais, a regra é:

```text
chain=forward action=drop src-mac-address=AA:BB:CC:DD:EE:FF
comment="MTMGR:DEVICE:AA-BB-CC-DD-EE-FF" disabled=false dynamic=false
```

`dynamic=false` é uma propriedade observada e verificada; não é uma intenção
para transformar uma regra dinâmica em estática. A escrita envia somente os
campos permitidos para criar essa forma e nunca aceita do navegador um verbo,
path, body ou `.id` arbitrário. A REST API documenta `PUT` para criar um
registro e `DELETE` para remover um registro por ID; nesta fase, o cliente usa
somente esses métodos na coleção `/rest/ip/firewall/filter`.
[REST API oficial da MikroTik](https://manual.mikrotik.com/docs/developer-guides/rest-api/)

`forward` é importante: nessa chain passam os pacotes encaminhados pelo
roteador. Uma regra `input` protege o próprio MikroTik e não é interpretada
como bloqueio do cliente. [Filter oficial da MikroTik](https://manual.mikrotik.com/docs/cli-reference/ip/firewall/filter/)

Não são criadas address-lists nem regras IPv6. O matcher por MAC é a única
identidade de tráfego usada pela Fase 4.

## Ownership, conflito foreign/manual e drift

Ownership é provado somente pelo comentário completo esperado para o MAC
normalizado:

```text
MTMGR:DEVICE:<MAC-MAIÚSCULO-COM-HÍFENS>
```

Prefixo `MTMGR:`, nome parecido, MAC coincidente, IP coincidente, target,
posição na lista ou `.id` isolado não provam ownership. O painel não adota,
substitui, corrige ou remove uma regra manual/foreign.

As regras são classificadas de forma conservadora:

- uma única regra com comentário exato, `chain`, `action`, MAC, `disabled` e
  `dynamic` corretos é `MANAGED` e desejada;
- uma regra manual, sem comentário exato ou com outra identidade é `FOREIGN`
  (ou não comprovada) e bloqueia a operação se representar um bloqueio do
  dispositivo;
- duas ou mais regras com o mesmo comentário exato são
  `AMBIGUOUS_OWNERSHIP`; nenhuma é escolhida pelo primeiro resultado;
- comentário exato com semântica divergente é `DRIFT`, por exemplo chain ou
  action diferentes, MAC de origem diferente, regra desabilitada ou dinâmica.
  Drift é bloqueante: a Fase 4 não faz autocorreção, reordenação ou remoção.

Um bloqueio manual/foreign reconhecido por MAC ou por um filtro ativo de
`forward` que descarte o tráfego do dispositivo também é conflito. O resultado
seguro é não criar uma segunda regra e não apagar a configuração do operador.
O mesmo vale para estado de bloqueio DHCP já observado: ele não é convertido
nem removido pela Fase 4.

## Ordem e `place-before`

As regras de uma chain são processadas na ordem em que aparecem, de cima para
baixo. Antes de criar uma regra, o executor captura um snapshot novo e procura
a primeira regra **estática** (`dynamic=false`) da chain `forward`. Quando há
uma âncora válida, o `PUT` inclui `place-before=<id-da-âncora>`; assim a regra
MTMGR fica antes da primeira regra forward estática. Regras dinâmicas não são
usadas como âncora nem entram na comprovação de ordem.
[Firewall oficial da MikroTik](https://manual.mikrotik.com/docs/firewall-and-quality-of-service/firewall/)
e [Console oficial da MikroTik](https://manual.mikrotik.com/docs/management-tools/console/)

Se não houver regra forward estática, o campo `place-before` não é enviado. A
ordem ainda é verificada depois da escrita. O executor recaptura o snapshot e
confirma, simultaneamente:

1. existe exatamente uma regra com o comentário de ownership esperado;
2. a regra tem a forma completa `FIREWALL_MAC_RULE`;
3. a regra está antes de todas as outras regras forward estáticas observadas.

Falha de qualquer item gera erro de verificação/posição; não há retry cego e a
situação deve ser resolvida manualmente.

## Fluxo de bloqueio e liberação

Cada execução mantém um lock local por MAC, verifica as flags e as credenciais,
captura um snapshot RouterOS novo e reconstrói o plano. O preview gerado pela
UI, seu `planId`, fingerprint, ownership e preconditions nunca são aceitos
como autorização.

### Bloquear

1. Validar MAC, lease DHCP única, dispositivo correlacionado, IP, lease
   `bound`, servidor DHCP conhecido/habilitado e ausência de conflito.
2. Se já houver exatamente uma regra gerenciada na forma desejada e na ordem
   segura, tratar como no-op, reler e confirmar o estado; nenhuma escrita é
   enviada.
3. Se não houver regra de bloqueio, criar exatamente uma `FIREWALL_MAC_RULE`
   com `PUT /rest/ip/firewall/filter` e o `place-before` resolvido.
4. Reler o firewall inteiro e verificar shape, unicidade e ordem antes de
   retornar sucesso.

Se o estado observado for foreign, manual, duplicado ou drifted, o bloqueio é
interrompido antes do `PUT`.

### Liberar

1. Revalidar o mesmo conjunto de preconditions e localizar uma única regra
   gerenciada pelo comentário exato.
2. Usar somente o `.id` dessa regra recém-resolvida em
   `DELETE /rest/ip/firewall/filter/<id>`.
3. Reler o firewall e confirmar que não resta regra MTMGR de bloqueio para o
   MAC.

Sem regra gerenciada e sem bloqueio foreign/manual conflitante, liberar é um
no-op. Se houver regra foreign, duplicidade, drift ou um `.id` ausente/inseguro,
nenhuma remoção é feita.

## Idempotência, TOCTOU e resultado desconhecido

Idempotência não significa repetir a mesma requisição: significa reler e
decidir a partir do estado atual. Assim:

| Situação observada | Comportamento |
| --- | --- |
| Bloquear e já existir uma regra gerenciada única, correta e bem posicionada | no-op confirmado, sem novo `PUT` |
| Liberar e não existir regra gerenciada nem conflito | no-op confirmado, sem `DELETE` |
| Regra gerenciada única com drift | bloquear a operação; não corrigir automaticamente |
| Regra foreign/manual ou múltiplas regras | bloquear a operação; não adotar nem apagar |
| Mesmo MAC em chamadas concorrentes | serializar pelo lock por dispositivo |

Há risco TOCTOU entre qualquer leitura e a escrita. Por isso o executor sempre
faz `snapshot → replan → validação → uma mutação → snapshot de verificação`.
Uma alteração no RouterOS entre essas etapas pode transformar a operação em
conflito; o executor não reaproveita o plano antigo.

Se `PUT` ou `DELETE` falhar depois de possivelmente alcançar o roteador, o
resultado é `OUTCOME_UNKNOWN`. A aplicação faz uma releitura:

- para bloqueio, sucesso somente se encontrar exatamente uma regra desejada e
  em ordem segura;
- para liberação, sucesso somente se a regra gerenciada não existir;
- duplicidade, shape/ordem insegura, presença inesperada ou impossibilidade de
  reler mantêm o resultado inconclusivo e exigem inspeção manual.

Um `404` durante uma remoção também é confirmado por releitura: ausência da
regra torna o resultado um no-op idempotente; não há segunda tentativa cega.
Nenhum erro de transporte ou resultado desconhecido dispara retry automático.

## FastTrack

Quando o snapshot encontra uma regra ativa `action=fasttrack-connection`, o
plano de bloqueio/liberação inclui o warning com o código exato
`FASTTRACK_EXISTING_CONNECTIONS`:

> Conexões que já estavam FastTracked podem continuar até serem encerradas ou
> expirarem no RouterOS.

FastTrack pode fazer pacotes de uma conexão já FastTracked ignorarem firewall e
outras facilidades L3; portanto a regra nova não promete interromper uma
conexão existente imediatamente. A Fase 4 não desabilita, move, excepciona,
limpa conexões ou altera qualquer regra FastTrack. [Packet Flow oficial da MikroTik](https://help.mikrotik.com/docs/spaces/ROS/pages/328227/Packet%2BFlow%2Bin%2BRouterOS/)

## Credenciais, flags e escopo de escrita

As credenciais de leitura e escrita são sempre separadas:

| Uso | Variáveis | Permissão esperada |
| --- | --- | --- |
| Snapshot, diagnóstico e replanejamento | `MIKROTIK_USERNAME` / `MIKROTIK_PASSWORD` | usuário customizado `read,rest-api`, sem `write` |
| `FIREWALL_MAC_RULE` | `MIKROTIK_WRITE_USERNAME` / `MIKROTIK_WRITE_PASSWORD` | usuário customizado `read,write,rest-api` |

O usuário de escrita não herda nem reutiliza o par de leitura. No RouterOS,
crie um grupo customizado com exatamente `read,write,rest-api` para essa
capacidade. Ele não deve receber `policy`, `reboot`, `sensitive`, `sniff`,
`ftp`, `ssh`, `telnet` ou `winbox`; o usuário deve limitar `address` ao IP do
backend, idealmente `<IP-DO-BACKEND>/32`. A aplicação ainda restringe o cliente
de escrita à forma fixa de firewall acima; a policy RouterOS `write` é uma
permissão do usuário, não uma autorização para ampliar o escopo da aplicação.
[User e policies oficiais da MikroTik](https://manual.mikrotik.com/docs/authentication-authorization-accounting/user/)

As flags têm estes defaults seguros:

```dotenv
MIKROTIK_WRITE_ENABLED=false
MIKROTIK_DEVICE_BLOCK_WRITES_ENABLED=false
```

Em RouterOS real, **as duas** precisam estar `true`, além de as duas
credenciais de escrita estarem preenchidas. Se qualquer flag estiver `false`,
ou se o par de escrita estiver ausente, não há mutação RouterOS. `MIKROTIK_MOCK_MODE=true`
continua local e não valida um roteador físico.

## Auditoria

Cada tentativa de `BLOCK_DEVICE` ou `UNBLOCK_DEVICE` registra auditoria local
no SQLite com tipo `DEVICE`, MAC normalizado, estado anterior, estado seguinte,
sucesso e código de erro quando houver. No-op também é registrado. A criação de
preview registra apenas o resumo `OPERATION_PLAN_CREATED`.

Auditoria e logs não armazenam senha, `Authorization`, body JSON RouterOS,
snapshot bruto ou dump de configuração. Falha ao persistir a auditoria não
repete a mutação nem muda o resultado RouterOS; fica como aviso operacional
para investigação.

## Rollback manual de emergência

Se a aplicação indicar posição insegura, resultado desconhecido ou falha de
verificação, pare novas tentativas e inspecione o equipamento por uma sessão
administrativa independente. Primeiro imprima a regra e a ordem completa:

```routeros
/ip/firewall/filter/print detail
/ip/firewall/filter/print detail where comment="MTMGR:DEVICE:AA-BB-CC-DD-EE-FF"
```

Só depois de encontrar **um único** `.id`, confirmar MAC, comentário e shape,
use o ID literal. Para uma contenção imediata, desabilite a regra; para desfazer
uma regra MTMGR confirmada, remova somente esse ID:

```routeros
/ip/firewall/filter/disable *N
/ip/firewall/filter/remove *N
```

Não use `remove [find where comment=...]` quando houver duplicidade ou qualquer
dúvida. Se a alteração for a última ação e a administração confirmar esse
contexto, `/system/history/print detail` e `/undo` são alternativas manuais;
não aplique undo sem conferir as ações intermediárias. O RouterOS documenta
Safe Mode, histórico e undo/redo, mas a decisão de rollback pertence ao
administrador do equipamento. [Configuration Management oficial da MikroTik](https://manual.mikrotik.com/docs/getting-started/configuration-management/)
e [Console oficial da MikroTik](https://manual.mikrotik.com/docs/management-tools/console/)

## Checklist para teste físico controlado

O teste só deve ser feito depois de revisão e janela de manutenção:

- [ ] Fazer backup/export do RouterOS, confirmar que o artefato pode ser
      recuperado e registrar a configuração inicial de
      `/ip/firewall/filter`.
- [ ] Usar um roteador isolado ou autorizado e um dispositivo descartável de
      teste; nunca começar por um cliente crítico. Registrar MAC, IP, lease e
      interface do dispositivo.
- [ ] Confirmar `www-ssl` com certificado válido, `address=<IP-DO-BACKEND>/32`,
      usuário de leitura separado, usuário de escrita separado e políticas
      mínimas.
- [ ] Manter as duas flags `false` durante a checagem read-only; confirmar que
      o snapshot e o preview não alteram o firewall.
- [ ] Em uma janela aprovada, usar `MIKROTIK_MOCK_MODE=false`, preencher os
      dois pares de credenciais e habilitar **ambas** as flags.
- [ ] Capturar o baseline com `print detail`; bloquear o dispositivo e
      confirmar a única regra com shape exato, comentário exato e posição antes
      das regras forward estáticas.
- [ ] Confirmar o efeito em novos fluxos encaminhados. Se FastTrack estiver
      ativo, registrar `FASTTRACK_EXISTING_CONNECTIONS`, encerrar/aguardar as
      conexões antigas e não alterar FastTrack como parte deste teste.
- [ ] Repetir o bloqueio e confirmar no-op, ausência de regra duplicada e
      auditoria de no-op.
- [ ] Liberar e confirmar remoção somente do `.id` resolvido, ausência da regra
      MTMGR e auditoria de sucesso; repetir a liberação para confirmar no-op.
- [ ] Verificar que regras foreign/manual, duplicidade e drift impedem a ação
      e não são alteradas.
- [ ] Exercitar o procedimento de `print detail` e rollback manual com o
      dispositivo descartável, e confirmar que nenhuma regra não relacionada
      foi modificada.
- [ ] Desabilitar novamente as flags, guardar os registros do teste e manter o
      backup/export associado.

O teste acima ainda não foi executado neste workspace. O estado oficial é:

Physical Phase 4 write validation: NOT RUN

Não há mutação de DHCP, leases, Simple Queues, queues, FastTrack, NAT, rotas,
bridge, IPv6 ou velocidade nesta Fase 4. A Fase 5 não foi iniciada.

## Fontes oficiais da MikroTik

- [REST API](https://manual.mikrotik.com/docs/developer-guides/rest-api/)
- [Firewall Filter](https://manual.mikrotik.com/docs/cli-reference/ip/firewall/filter/)
- [Common Firewall Matchers and Actions](https://manual.mikrotik.com/docs/firewall-and-quality-of-service/firewall/common-firewall-matchers-and-actions/)
- [Packet Flow in RouterOS — FastTrack](https://help.mikrotik.com/docs/spaces/ROS/pages/328227/Packet%2BFlow%2Bin%2BRouterOS/)
- [User e policies](https://manual.mikrotik.com/docs/authentication-authorization-accounting/user/)
- [Configuration Management](https://manual.mikrotik.com/docs/getting-started/configuration-management/)
- [Console](https://manual.mikrotik.com/docs/management-tools/console/)
## Prefixo seguro e concorrência

Regras MTMGR válidas de bloqueio formam um prefixo seguro da chain `forward`. A ordem relativa entre dispositivos gerenciados não importa: todas devem ficar antes da primeira regra `forward` estática externa (FastTrack, accept ou regra manual). Uma regra MTMGR com drift não conta para esse prefixo.

As operações de block/unblock mantêm o lock por MAC para idempotência e também serializam `ROUTEROS:FIREWALL_FILTER`. Esse segundo lock cobre releitura, planejamento, escrita e verificação da lista ordenada, evitando uma corrida de `place-before` entre MACs diferentes.

## Exact desired-state

Ownership MTMGR prova quem criou ou possui o recurso, mas não que a regra ainda tenha a semântica esperada. Qualquer matcher adicional — incluindo protocol, ports, addresses, interfaces, `limit` ou `time` — torna a regra `DRIFTED`. Campos desconhecidos também são tratados conservadoramente como drift; somente `.id`, counters (`bytes`, `packets`) e logging (`log`, `log-prefix`) são aceitos como não restritivos.

## Semântica exata

Ownership continua sendo apenas o comentário MTMGR exato. Para ser um bloqueio válido, a regra também precisa ser `forward/drop`, MAC e comentário esperados, `disabled=false`, `dynamic=false` e sem matcher adicional. Entre os matchers verificados estão protocolo, endereços/listas origem e destino, portas, interfaces/listas, connection/packet/routing marks, connection state/NAT state, layer7, TCP flags, ICMP options e address type. Campos operacionais como `.id`, bytes, packets e creation-time não causam drift. Um matcher restritivo desconhecido é tratado conservadoramente como drift.

## Teste integrado

O teste local stateful percorre `MockMvc → DeviceController → DeviceBlockExecutionService → RouterOsWriteClient → Fake RouterOS`. Ele valida credenciais de leitura para GET e de escrita para PUT/DELETE, sem registrar senhas, e confere que a allowlist permanece limitada ao firewall filter.
