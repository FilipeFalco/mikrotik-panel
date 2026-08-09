# MikroTik Local Manager

Gerenciador web local para visualizar portas/clientes, dispositivos DHCP, bloqueios e limites de banda de um MikroTik RouterOS 7, sem expor credenciais ao navegador.

> Estado atual: **Fase 1 concluída — fundação e mock mode**. O painel é funcional em dados simulados, com SQLite, dashboard, busca/filtros, limites, bloqueio/liberação e auditoria local. A integração REST de leitura com um RouterOS real começa na Fase 2 e ainda não foi habilitada nesta entrega.

## Requisitos

- Java 21 LTS (ou JDK mais novo que suporte compilação com `--release 21`);
- Node.js 20+ LTS e npm;
- Maven (o projeto inclui um lançador `mvnw` simples);
- RouterOS 7 somente quando a Fase 2 for utilizada.

Docker não é necessário.

## Estrutura

```text
.
├── backend/       Spring Boot, SQLite, Flyway e gateway RouterOS/mock
├── frontend/      React, TypeScript e Vite
├── data/          Banco SQLite local (ignorado pelo Git)
├── docs/          Arquitetura e preparação manual do RouterOS
├── .env.example   Modelo de variáveis locais
└── mvnw           Lançador Maven local
```

## Executar em mock mode

Em dois terminais, a partir da raiz do repositório:

```bash
cp .env.example .env
```

Terminal 1 — backend:

```bash
cd backend
set -a
. ../.env
set +a
./mvnw spring-boot:run
```

Terminal 2 — frontend:

```bash
cd frontend
npm install
npm run dev
```

Abra [http://localhost:3000](http://localhost:3000). O backend atende somente em `127.0.0.1:8080` e o Vite faz o proxy de `/api`.

O modo mock vem ativado por padrão e inclui `ether1` a `ether5`, três clientes configurados e 12 dispositivos com combinações de online, offline, bloqueado, com limite e sem limite. Bloquear, liberar e editar limites atualiza apenas o estado em memória desta execução e gera auditoria no SQLite.

Para zerar os nomes e histórico locais durante desenvolvimento, pare o backend e remova manualmente o arquivo `data/mikrotik-manager.db`. Ele é recriado na próxima execução em mock mode.

## Executar com MikroTik real

A integração real **não está ativa na Fase 1**. Definir `MIKROTIK_MOCK_MODE=false` é seguro: o backend informa que o adaptador REST ainda não foi disponibilizado e não tenta modificar o equipamento.

Prepare o RouterOS e as variáveis para a próxima fase seguindo [docs/routeros-setup.md](docs/routeros-setup.md). Em especial:

- use HTTPS (`www-ssl`) limitado ao IP do computador local;
- use usuário dedicado, não `admin`;
- comece com `read,rest-api` para validar somente leitura;
- não desabilite FastTrack nem altere queues manualmente em nome da aplicação.

## Configurar variáveis

O backend usa variáveis de ambiente; `.env` é apenas uma conveniência para ser carregada pelo shell e está ignorado pelo Git.

| Variável | Padrão | Finalidade |
| --- | --- | --- |
| `MIKROTIK_HOST` | `10.0.0.1` | Host do RouterOS. |
| `MIKROTIK_PORT` | `443` | Porta de `www-ssl`. |
| `MIKROTIK_USERNAME` | vazio | Usuário exclusivo da aplicação. |
| `MIKROTIK_PASSWORD` | vazio | Segredo somente do backend; não entra no SQLite. |
| `MIKROTIK_VERIFY_SSL` | `true` | Validar certificado do RouterOS. Deixe `false` apenas para desenvolvimento com certificado self-signed. |
| `MIKROTIK_MOCK_MODE` | `true` | Usa dados simulados e não acessa o roteador. |
| `SERVER_ADDRESS` | `127.0.0.1` | Endereço de escuta do backend. |
| `SERVER_PORT` | `8080` | Porta do backend. |
| `APP_FRONTEND_ORIGIN` | `http://localhost:3000` | Única origem CORS permitida. |
| `APP_DATA_DIR` | `../data` | Diretório do SQLite ao iniciar dentro de `backend/`. |

## Testes

Backend:

```bash
cd backend
./mvnw test
```

Frontend:

```bash
cd frontend
npm test
```

Os testes do backend cobrem mock mode, idempotência de bloqueio, identificadores gerenciados, validação de CIDR e regra de limite individual menor ou igual ao limite da porta. Os testes do frontend cobrem cartões de portas, filtros de dispositivos e confirmação de bloqueio.

## Build

```bash
cd backend
./mvnw package

cd ../frontend
npm run build
```

## Decisões técnicas relevantes

- O backend usa uma API própria, DTOs e uma interface `MikrotikGateway`; o restante do código não depende de JSON cru do RouterOS.
- SQLite é fonte de verdade somente para nomes amigáveis, configurações locais de portas e auditoria. RouterOS será fonte de verdade para estado de rede.
- Recursos futuros criados pela aplicação usarão comentários `MTMGR:PORT:*` e `MTMGR:DEVICE:*`; nada desconhecido será removido ou alterado.
- A estratégia documentada para banda é fila simples pai por sub-rede e filas filhas por dispositivo, para manter o teto agregado da porta.
- FastTrack será apenas detectado e avisado; o aplicativo não o altera automaticamente.

Leia os detalhes em [docs/architecture.md](docs/architecture.md).

## Troubleshooting

### O frontend não inicia

Confirme Node.js e npm:

```bash
node --version
npm --version
```

Depois execute `npm install` dentro de `frontend/`.

### O backend não inicia ou o SQLite não abre

Inicie a aplicação a partir de `backend/`, como mostrado acima, para que o padrão `APP_DATA_DIR=../data` aponte para `data/`. Confirme também que há permissão de escrita nesse diretório.

### MikroTik não conecta / credenciais inválidas / REST desabilitada

Na Fase 1 isso é esperado se `MIKROTIK_MOCK_MODE=false`, pois a integração REST ainda não está habilitada. Para a Fase 2, confira serviço `www-ssl`, usuário com `rest-api`, endereço permitido no serviço/usuário e a documentação de preparação.

### Certificado SSL self-signed

Prefira confiar na CA localmente. Em ambiente de desenvolvimento controlado, use `MIKROTIK_VERIFY_SSL=false`; essa exceção será isolada no cliente HTTP do RouterOS, nunca aplicada globalmente.

### Dispositivo não aparece

No mock mode, os dados já são fornecidos. Em RouterOS real (Fase 2), o dispositivo precisará aparecer como lease DHCP e ser correlacionável ao DHCP server/interface ou CIDR cadastrado. Dispositivos com IP estático ou atrás de NAT do cliente exigirão análise adicional.

### Queue não limita velocidade / FastTrack

Ainda não há queues reais nesta fase. Na Fase 5, FastTrack será diagnosticado porque pode contornar Simple Queues; nenhuma regra existente será desabilitada automaticamente.

## Próximo passo

Fase 2: implementar e validar o adaptador REST **somente leitura** contra RouterOS 7 — status, interfaces, DHCP servers e leases — antes de liberar qualquer escrita no equipamento.
