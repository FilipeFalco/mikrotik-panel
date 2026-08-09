# Preparação manual do RouterOS

> Estado desta entrega: a Fase 1 funciona exclusivamente em mock mode. Não aplique alterações de produção apenas para esta fase. Este guia prepara uma futura validação controlada da Fase 2 e não é executado automaticamente pela aplicação.

## 1. Pré-requisitos

- RouterOS 7 atualizado e backup exportado antes de qualquer mudança.
- Um IP estável para o computador que executa o backend.
- A topologia deve permitir que o MikroTik enxergue os dispositivos finais (AP/roteador em bridge quando aplicável).
- DHCP Server configurado por rede/interface que se deseja administrar.

## 2. Habilitar REST de modo restrito

A REST API é disponibilizada pelo serviço `www-ssl` (HTTPS) ou `www` (HTTP). Use `www-ssl`; HTTP deixa credenciais Basic expostas no tráfego. [Documentação REST oficial](https://manual.mikrotik.com/docs/developer-guides/rest-api/)

Substitua `192.168.88.10` pelo IP do computador local que roda o backend e `mtmgr-cert` pelo certificado já criado/importado no RouterOS:

```routeros
/ip/service/set www-ssl disabled=no port=443 address=192.168.88.10/32 certificate=mtmgr-cert
```

Não abra o serviço para `0.0.0.0/0`. A propriedade `address` de `/ip service` restringe quais endereços podem usar o serviço. [IP Services oficial](https://manual.mikrotik.com/docs/system-information-and-utilities/services/)

O padrão é `MIKROTIK_VERIFY_SSL=true`. Se usar certificado self-signed, prefira importar a CA no computador. A opção `MIKROTIK_VERIFY_SSL=false` existe somente para desenvolvimento local controlado, quando o certificado ainda não for confiável, e será aplicada isoladamente ao futuro cliente RouterOS.

## 3. Criar usuário dedicado

Não use `admin`. Para a fase de leitura, comece com um grupo limitado a `read,rest-api`:

```routeros
/user/group/add name=mtmgr-read policy=read,rest-api
/user/add name=mtmgr group=mtmgr-read address=192.168.88.10/32 password=<gere-uma-senha-forte>
```

Quando as fases de bloqueio e queues tiverem sido testadas no seu equipamento, o grupo precisará também de `write`, pois essas operações alteram recursos pertencentes ao aplicativo. Não conceda `policy`, `sensitive`, `reboot`, `password`, `ssh` ou `winbox` sem necessidade.

O RouterOS documenta as policies `read`, `write` e `rest-api`, bem como a restrição de endereço por usuário. [Users e policies oficiais](https://manual.mikrotik.com/docs/authentication-authorization-accounting/user/)

## 4. Configurar o backend

Copie `.env.example` para `.env`, preencha os valores e mantenha `MIKROTIK_MOCK_MODE=true` para esta entrega. Não coloque o arquivo no Git.

```dotenv
MIKROTIK_HOST=192.168.88.1
MIKROTIK_PORT=443
MIKROTIK_USERNAME=mtmgr
MIKROTIK_PASSWORD=uma-senha-forte
MIKROTIK_VERIFY_SSL=true
MIKROTIK_MOCK_MODE=true
MIKROTIK_WRITE_ENABLED=false
```

`MIKROTIK_WRITE_ENABLED=false` é o padrão e é o kill switch para a futura integração real. O mock mode continua permitindo as mutações simuladas da UI, porque elas existem apenas em memória. Em contraste, com `MIKROTIK_MOCK_MODE=false` e `MIKROTIK_WRITE_ENABLED=false`, somente operações que alterariam o RouterOS são recusadas antes do gateway; nomes amigáveis, observações e configurações locais de portas continuam editáveis no SQLite.

Na Fase 1, não mude `MIKROTIK_MOCK_MODE=false` para tentar integrar o equipamento: ainda não existe `RouterOsRestGateway` funcional, não há chamadas a `/rest` e o gateway indisponível não acessa a rede. Em uma futura Fase 2, essa variável será usada somente para validar chamadas de leitura; essa fase não modificará a configuração RouterOS.

## 5. DHCP e associação com portas

Verifique manualmente que cada DHCP server possui a interface/rede esperada:

```routeros
/ip/dhcp-server/print detail
/ip/dhcp-server/lease/print detail
```

Cadastre no painel o nome da interface, a rede CIDR e — quando necessário — o nome do DHCP server. A rede deve usar um IP literal, nunca hostname; `10.10.10.17/24` será normalizado e persistido como `10.10.10.0/24`. O adaptador real correlacionará lease → DHCP server → interface e usará CIDR como conferência, não um nome hardcoded.

## 6. FastTrack e queues (não altere ainda)

Antes de qualquer controle de banda, inspecione as regras FastTrack existentes:

```routeros
/ip/firewall/filter/print detail where action=fasttrack-connection
/queue/simple/print detail
```

Não desabilite, mova ou apague regras por causa deste aplicativo. FastTrack pode contornar Simple Queues, por isso a Fase 5 mostrará um aviso e exigirá validação explícita da regra e da topologia. [Queues](https://manual.mikrotik.com/docs/firewall-and-quality-of-service/queues/) e [Packet Flow](https://manual.mikrotik.com/docs/firewall-and-quality-of-service/packet-flow-in-routeros/) oficiais.

## 7. Recursos que serão gerenciados no futuro

Quando cada recurso for criado pela aplicação, ele terá comentário identificável:

```text
MTMGR:PORT:ether2
MTMGR:DEVICE:AA-BB-CC-DD-EE-FF
```

O aplicativo só atualizará/removerá recursos cujo comentário confirme exatamente essa propriedade: o MAC ou a interface precisam corresponder ao valor no comentário. Um `MTMGR:` genérico, MAC de outro dispositivo ou outra porta não é suficiente. Regras, queues, NAT, bridges, rotas e leases existentes que não tenham esse identificador não serão alterados.
