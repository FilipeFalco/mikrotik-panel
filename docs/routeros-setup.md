# Preparação manual do RouterOS para a Fase 2

Esta etapa prepara manualmente acesso REST restrito para o backend local. A
aplicação da Fase 2 não modifica RouterOS: depois desta preparação ela emite
somente HTTPS `GET` para `/rest`.

Antes de futuras fases de escrita, mantenha um backup/export atualizado do
RouterOS. Mesmo sendo leitura nesta fase, a preparação de serviço, certificado
e usuário é uma alteração administrativa feita conscientemente pelo operador.

## 1. Pré-requisitos

- RouterOS 7 e um IP estável para o computador que executa o backend;
- DHCP servers já configurados nas interfaces/redes que se pretende observar;
- certificado para `www-ssl`, preferencialmente emitido por uma CA confiável
  pelo computador local;
- acesso administrativo separado para realizar apenas a preparação abaixo.

O RouterOS expõe REST por `www-ssl` (HTTPS) ou `www` (HTTP). Use somente
`www-ssl`: a documentação alerta que HTTP permite interceptação passiva das
credenciais Basic. [REST API oficial](https://manual.mikrotik.com/docs/developer-guides/rest-api/)

## 2. Habilitar `www-ssl` de forma restrita

Substitua `192.168.88.10` pelo IP do computador local e `mtmgr-cert` pelo nome
do certificado já criado/importado no RouterOS:

```routeros
/ip/service/set www-ssl disabled=no port=443 address=192.168.88.10/32 certificate=mtmgr-cert
```

Não habilite `www` para esta aplicação. Restrinja também o firewall da sua
topologia conforme necessário; `address` em `/ip/service` limita as origens
aceitas pelo serviço, mas a documentação recomenda firewall para bloquear redes
externas/não confiáveis. `www-ssl` usa o certificado indicado no serviço.
[Services oficial](https://manual.mikrotik.com/docs/system-information-and-utilities/services/)

Confirme que a REST API segura está disponível na versão RouterOS em uso. O
RouterOS documenta `rest-secure` como suporte REST para o web service seguro.

## 3. Criar um usuário de leitura dedicado

Não use `admin` e não reutilize usuário de operação. Crie grupo específico com
as policies mínimas `read,rest-api` e sem `write`:

```routeros
/user/group/add name=mtmgr-read policy=read,rest-api
/user/add name=mtmgr group=mtmgr-read address=192.168.88.10/32 password=<senha-local-forte>
```

Não conceda `write`, `policy`, `reboot`, `password`, `ssh`, `winbox`, `test`,
`sniff` ou `sensitive` para a Fase 2. A documentação explica que `read` permite
consulta da configuração e `rest-api` permite REST; `write` concede alteração.
Ela também recomenda grupo customizado, pois o grupo padrão `read` inclui mais
policies do que esta aplicação precisa. A restrição `address` do usuário reduz
as origens que podem autenticar. [Users e policies oficiais](https://manual.mikrotik.com/docs/authentication-authorization-accounting/user/)

Guarde a senha apenas no arquivo local `.env`; não a copie para documentação,
SQLite, frontend, log ou Git.

## 4. Certificado e TLS

O padrão da aplicação é:

```dotenv
MIKROTIK_VERIFY_SSL=true
```

Com esse valor, o backend valida a cadeia e o hostname/IP do certificado. Para
certificado self-signed, a CA ou o certificado precisa ser confiável pelo
truststore da JVM/JDK que executa o backend. Em uma instalação Java padrão,
isso normalmente corresponde ao `cacerts` do JDK/JRE usado pelo processo; o
repositório de certificados do navegador ou do sistema operacional, sozinho,
não necessariamente é usado pelo Apache HttpClient da aplicação. Outra
estratégia de implantação é iniciar a JVM com um truststore explicitamente
configurado. Não há truststore customizado configurável pela aplicação nesta
fase. Essa orientação mantém `MIKROTIK_VERIFY_SSL=true` e a validação normal de
cadeia e hostname/IP.

`MIKROTIK_VERIFY_SSL=false` existe somente para desenvolvimento local
controlado, quando um self-signed ainda não é confiável. Ele mantém HTTPS, mas
desabilita a verificação somente no `RouterOsRestClient` dedicado. Não use isso
em rede não confiável e não trate como configuração de produção. A exceção não
é global à JVM nem altera outros clientes HTTP.

## 5. Configurar o backend

Na raiz do repositório:

```bash
cp .env.example .env
```

Preencha apenas o arquivo `.env` local:

```dotenv
MIKROTIK_HOST=192.168.88.1
MIKROTIK_PORT=443
MIKROTIK_USERNAME=mtmgr
MIKROTIK_PASSWORD=<senha-local-forte>
MIKROTIK_VERIFY_SSL=true
MIKROTIK_CONNECT_TIMEOUT_MS=3000
MIKROTIK_READ_TIMEOUT_MS=5000
MIKROTIK_MOCK_MODE=false
MIKROTIK_WRITE_ENABLED=false
```

Os timeouts devem ser positivos. Três segundos de conexão e cinco segundos de
resposta são os padrões de rede local. A aplicação monta internamente
`https://<host>:<port>` e o frontend não pode fornecer URL, credenciais ou
headers RouterOS.

## 6. Testar conexão sem escrever

Inicie backend e frontend como descrito no [README](../README.md), abra
Configurações e use **Testar conexão**. O endpoint da aplicação pode ser `POST`
local, mas a chamada correspondente ao RouterOS é apenas
`GET /rest/system/resource`.

Com conexão bem-sucedida, a topbar mostra RouterOS 7.x e modo somente leitura.
Falhas de autenticação, TLS, rede e payload retornam mensagens sanitizadas; elas
não interrompem SQLite, histórico ou configurações locais.

Durante a Fase 2, os únicos recursos RouterOS lidos são:

```text
GET /rest/system/resource
GET /rest/interface
GET /rest/ip/dhcp-server
GET /rest/ip/dhcp-server/lease
GET /rest/queue/simple
GET /rest/ip/firewall/filter
```

Não use `POST`, `PUT`, `PATCH` ou `DELETE` para testes desta integração. As
leituras de firewall/FastTrack ocorrem apenas no diagnóstico sob demanda, não a
cada polling.

## 7. Conferir DHCP e configurar metadados locais das portas

Para checagem manual, sem alterar nada:

```routeros
/ip/dhcp-server/print detail
/ip/dhcp-server/lease/print detail
```

O painel correlaciona `lease.server` → `dhcp-server.name` →
`dhcp-server.interface`. Cadastre CIDR e preferências de porta no SQLite apenas
como configuração local; o CIDR não substitui uma associação DHCP confiável.
Lease sem MAC utilizável ou sem server correspondente é ignorado isoladamente,
sem impedir que os outros dispositivos apareçam. [DHCP oficial](https://manual.mikrotik.com/docs/network-management/dhcp/)

Em **Configurações → Interfaces descobertas**, uma interface apresentada no
fluxo de portas e ainda não cadastrada localmente aparece como não gerenciada.
O operador pode selecionar a interface e salvar nome amigável, descrição, CIDR,
DHCP server, visibilidade e papel `WAN` ou `CLIENT`. A interface física é
somente leitura nessa tela: esses dados ficam exclusivamente no SQLite e
alimentam o dashboard. O request é `PUT /api/ports/{interface}` para o backend
local; ele não envia `PUT` ao RouterOS.

O papel `WAN` define qual interface o dashboard apresenta como Internet, e
`CLIENT` identifica as portas de cliente habilitadas. Não existe a regra de que
`ether1` seja WAN: ele é apenas a escolha declarada pelo fixture de mock. Mudar
o papel local não altera rota padrão, NAT, DHCP client, firewall ou interface
list no equipamento.

## 8. Queues e FastTrack: observar, não alterar

Os diagnósticos podem ler Simple Queues e firewall filters. Eles não criam,
adotam, alteram, removem, movem ou reordenam queues, leases ou regras. Uma queue
manual só é considerada gerenciada se o comentário for exatamente
`MTMGR:PORT:<interface>`.

FastTrack ativo é reportado porque pode contornar Simple Queues. Não desabilite
nem mova a regra por causa desta fase; avalie manualmente a topologia antes de
qualquer fase futura de limite. [Queues](https://manual.mikrotik.com/docs/firewall-and-quality-of-service/queues/)
e [Packet Flow](https://manual.mikrotik.com/docs/firewall-and-quality-of-service/packet-flow-in-routeros/)
oficiais.

## 9. O que permanece fora do escopo

Mesmo se `MIKROTIK_WRITE_ENABLED=true` for definido por engano, a Fase 2 não
implementa escrita RouterOS. Não há bloqueio real, alteração de velocidade,
`make-static`, queue creation/update/removal, mudança de FastTrack, firewall,
bridge, IP, DHCP, NAT, rota, VLAN, DNS ou interface.

`MIKROTIK_MOCK_MODE=true` continua totalmente local e não precisa de rede. Em
modo real, nomes amigáveis, observações e configuração de portas no SQLite
continuam permitidos.

Physical RouterOS validation: **not performed in this workspace.**

## Fontes oficiais

- [REST API — RouterOS Manual](https://manual.mikrotik.com/docs/developer-guides/rest-api/)
- [Services — RouterOS Manual](https://manual.mikrotik.com/docs/system-information-and-utilities/services/)
- [User — RouterOS Manual](https://manual.mikrotik.com/docs/authentication-authorization-accounting/user/)
- [DHCP — RouterOS Manual](https://manual.mikrotik.com/docs/network-management/dhcp/)
- [Queues — RouterOS Manual](https://manual.mikrotik.com/docs/firewall-and-quality-of-service/queues/)
- [Packet Flow in RouterOS — RouterOS Manual](https://manual.mikrotik.com/docs/firewall-and-quality-of-service/packet-flow-in-routeros/)
