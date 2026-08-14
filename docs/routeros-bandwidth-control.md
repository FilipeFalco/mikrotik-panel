# Controle de banda RouterOS — Fase 5

A Fase 5 usa exclusivamente **Simple Queue** (`/queue/simple`) para limites MIR de porta e dispositivo. Não cria Queue Tree, PCQ, mangle, regras DHCP, exceções FastTrack, NAT, rotas, VLANs ou IPv6 bandwidth.

## Decisões confirmadas

- A [referência oficial de Simple Queue](https://manual.mikrotik.com/docs/cli-reference/queue/simple/) define `target`, `name`, `parent`, `max-limit`, `limit-at`, `priority`, `queue`, burst, `bucket-size`, `time`, `packet-marks`, `dst-address`, `dynamic`, `disabled` e `invalid`.
- `max-limit` é `{upload,download}`; o domínio é `SpeedLimit(download, upload)`. Logo 100 Mbps down / 20 Mbps up é serializado como `20M/100M`. O parser faz o caminho inverso.
- [Simple Queues](https://manual.mikrotik.com/docs/firewall-and-quality-of-service/queues/) são estritamente ordenadas; uma sobreposição foreign pode capturar o tráfego antes da queue MTMGR. Não movemos recursos foreign.
- Em [HTB](https://help.mikrotik.com/docs/spaces/ROS/pages/137986076/HTB%2BHierarchical%2BToken%2BBucket), `parent=<nome>` cria a relação child; o parent precisa capturar o tráfego dos filhos. `max-limit` é MIR e `limit-at` é CIR. Esta fase não usa CIR: cada filho finito deve caber no parent finito, mas a soma de MIRs dos filhos pode exceder o MIR do parent.
- [FastTrack](https://help.mikrotik.com/docs/spaces/ROS/pages/328227/Packet%2BFlow%2Bin%2BRouterOS) ignora Simple Queues. FastTrack ativo bloqueia execução de banda. A Fase 5 não o altera.
- Pela [REST API](https://help.mikrotik.com/docs/spaces/ROS/pages/47579162/REST%2BAPI), `PUT` cria, `PATCH` altera um registro e `DELETE` remove um registro. A única allowlist nova é `PUT /rest/queue/simple`, `PATCH /rest/queue/simple/{fresh-id}` e `DELETE /rest/queue/simple/{fresh-id}`. POST nunca é usado.
- `place-before` pertence ao comando RouterOS `add`; como PUT REST mapeia para `add`, é usado somente ao criar o parent antes de filhos MTMGR, após revalidação. A ligação é uma inferência documentada, a confirmar fisicamente.

Não há declaração oficial atual suficientemente inequívoca para serializar `max-limit` parcialmente ilimitado (`0` em apenas uma direção). Por isso, `0/0` significa remover a queue; limite parcial com zero é recusado fail-closed até validação física controlada.

## Convenções e segurança

- Porta: `mtmgr-port-<interface>`, `target=<network>`, `parent=none`, `comment=MTMGR:PORT:<interface>`.
- Dispositivo: `mtmgr-device-<MAC sem separadores>`, `target=<ip>/32`, `comment=MTMGR:DEVICE:<MAC com hífens>`, `parent=<queue da porta>` quando ela existe.
- O comentário exato é a única prova de ownership. Nome, target, IP, parent, limite, ordem e `.id` nunca adotam uma fila.
- Colisão de nome, ownership duplicado, `dynamic=true`, fila foreign sobreposta, target inesperado, `packet-marks`, `time`, `limit-at`, burst, `dst-address`, queue type/priority/bucket não padrão ou campo semântico inseguro bloqueiam PATCH/DELETE.
- Counters documentados como read-only (`bytes`, `packets`, `rate`, `packet-rate`, `queued-bytes`, `queued-packets`, `dropped`, `borrows`, `lends` e equivalentes `total-*`) não são drift. Qualquer outra propriedade desconhecida é semantic drift por padrão (fail-closed), nunca é descartada pelo DTO.
- `total-limit-at`, `total-max-limit`, `total-priority`, `total-queue`, `total-burst-*` e `total-bucket-size` são modelados. Fora do valor default/unset, bloqueiam a gestão; a Fase 5 não tenta removê-los por PATCH.
- Bandwidth ownership is derived from the exact DEVICE_QUEUE comment, not from DHCP lease comments.
- `PORT_QUEUE` com name drift é bloqueante: ownership exato pelo comment mantém a fila como gerenciada, mas não autoriza renomear, PATCH ou DELETE+CREATE automáticos.
- Um target de device que divergiu da lease atual é drift. Só `SET_DEVICE_SPEED` com limite finito pode atualizá-lo, com ownership único, shape seguro, lease bound atual e sem conflito foreign. `TARGET_DRIFT` com `0/0` permanece bloqueante porque a remoção por DELETE exige a forma completa, inclusive o target esperado, exatamente observada.
- O desired-state é completo. `PORT_QUEUE` compara `name`, `comment`, `target=<network>`, `parent=none`, `max-limit` e o shape semântico seguro. `DEVICE_QUEUE` compara os mesmos campos, com `target=<lease bound>/32` e `parent` igual ao PORT_QUEUE somente se este parent já for válido; caso contrário `none`.
- Assim, `freshPlan.changeRequired=false` implica **zero mutações RouterOS**. O executor retorna o read model antes de qualquer `PUT`, `PATCH` ou `DELETE`; ele nunca transforma um preview `NO_CHANGE` em reparo contextual.
- `QUEUE_NAME_DRIFT`, `QUEUE_PARENT_DRIFT`, semantic drift e target drift de PORT_QUEUE são bloqueantes e não são corrigidos automaticamente. Para DEVICE_QUEUE, `TARGET_DRIFT` é a única exceção contextual: `SET_DEVICE_SPEED` pode convergir somente o target, e somente quando todos os demais campos e a lease atual forem seguros.
- `queue` é um par upload/download (`default-small/default-small` ou outro par explicitamente aceito); `total-queue` é um tipo único (`default-small`). Um par em `total-queue`, queue type customizado ou campo RouterOS desconhecido continua sendo drift fail-closed.

## Fluxo operacional

Cada execução toma o lock global `ROUTEROS:SIMPLE_QUEUE`, valida flags e credenciais separadas e executa exatamente: `fresh snapshot → OperationPlanningService.planFromSnapshot(intent, snapshot) → validate → mutate → fresh snapshot → verify`. Preview, fingerprint, `.id`, ownership e `ready` vindos do navegador nunca autorizam escrita. Antes de criar um parent, **todos** os DEVICE_QUEUE children candidatos são validados quanto a ownership único, name/target/shape seguro, lease bound, parent aceitável, conflito foreign e limite individual; só então ocorre `PUT parent → PATCH children`. Ao remover parent, filhos MTMGR seguros são reparentados para `none` antes do DELETE.

RouterOS não oferece transação para a sequência. Em falha parcial, a aplicação interrompe imediatamente os passos restantes, relê o estado e reconcilia; não faz rollback cego. Em outcome desconhecido, também não faz retry cego. PUT/PATCH/DELETE só são considerados recuperados quando a leitura confirma o estado desejado (ou ausência, no DELETE).

Os testes incluem executor real com dependências mockadas e fluxo HTTP stateful `MockMvc → controller → executor → planner → RouterOsQueueWriteClient → FakeRouterOsServer`, cobrindo create/update/no-op/remove, hierarchy e `TARGET_DRIFT` sem mockar o gateway de mutação.

## Flags

Modo real requer simultaneamente `MIKROTIK_WRITE_ENABLED=true`, `MIKROTIK_BANDWIDTH_WRITES_ENABLED=true` e `MIKROTIK_WRITE_USERNAME`/`MIKROTIK_WRITE_PASSWORD`. A flag de block é independente. A Fase 5 nunca cai para credenciais de leitura.

## Checklist físico (não executado automaticamente)

1. Fazer export/backup RouterOS e confirmar a estabilidade da Fase 4.
2. Consultar `GET /queue/simple`, inspecionar os campos retornados e confirmar ausência de conflito.
3. Confirmar FastTrack; se ativo, **não habilitar** bandwidth write.
4. Usar dispositivo de teste, ativar a flag global e a flag de banda, e fazer preview de porta/dispositivo.
5. Aplicar um limite, conferir a Simple Queue e testar download/upload.
6. Alterar o limite, testar novamente, remover o limite e confirmar a remoção da queue.
7. Retornar ambas as flags para `false`.

Política física: a Fase 5 não modifica FastTrack. Se FastTrack ativo for detectado, a execução de banda é bloqueada. Qualquer exceção FastTrack deve ser um projeto separado.

Physical Phase 5 bandwidth validation: NOT RUN
