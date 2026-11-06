# Feature Specification: Submissão de Lance em Leilão Concorrente

**Feature Branch**: `001-submissao-de-lance`
**Created**: 2026-11-02
**Status**: Draft
**Input**: Processar lances em leilões de alta concorrência garantindo ordem
determinística, idempotência e rejeição de lances inválidos/duplicados,
conforme discutido na arquitetura (single writer via Kafka, lock otimista
como safety net, `risk_tier` no cadastro).

## Clarifications

### Session 2026-11-03
- Q: Como o cliente recebe o resultado do lance (síncrono ou assíncrono)? → A: Assíncrono: resposta imediata "recebido" com `bid_id`; resultado final (aceito/rejeitado + motivo) enviado ao próprio usuário pela conexão em tempo real.
- Q: O que o usuário recebe ao reenviar um `bid_id` já processado? → A: O resultado original já registrado (aceito/rejeitado + motivo), sem reprocessar.
- Q: Qual a regra para o primeiro lance de um leilão sem lances? → A: Cada leilão tem `starting_price` no cadastro; o primeiro lance deve ser >= `starting_price`, os seguintes > `highest_bid`.
- Q: Qual horário define se um lance chegou antes do encerramento? → A: Horário de chegada ao log (registrado pelo sistema); lance no log antes do `end_time` é válido mesmo se processado depois.
- Alinhamento com a constituição: `bid_id` globalmente único (PK de `bids`); lance para leilão inexistente (`AUCTION_NOT_FOUND`) não gera linha em `bids`, só log; horário de chegada ao log = timestamp atribuído pelo broker.
- Q: Vendedor e atual líder podem dar lances no próprio leilão? → A: Não; rejeitar lance do vendedor (`SELLER_CANNOT_BID`) e do atual líder (`ALREADY_LEADING`).

## Execution Flow (main)
```
1. Cliente autenticado envia lance (auction_id, valor, bid_id)
2. Gateway valida autenticação e aplica rate limit
3. Sistema consulta risk_tier do auction_id
4. Lance é publicado no pipeline de processamento (Kafka, key=auction_id)
5. Consumer único da partição processa o lance em ordem de chegada
6. Sistema verifica se bid_id já foi processado (idempotência)
7. Sistema compara valor com highest_bid atual
8. Se válido: grava com lock otimista (version) → sucesso
9. Se inválido ou colisão de versão: rejeita → retry ou erro ao cliente
10. Sistema envia o resultado (aceito/rejeitado + motivo) ao usuário autor
    pela conexão em tempo real e, se aceito, notifica todos os clientes
    conectados via broadcast (novo líder)
```

## User Scenarios & Testing

### Primary User Story
Como participante de um leilão, quero enviar um lance e saber rapidamente
se ele foi aceito como o novo lance líder, mesmo quando centenas de outros
usuários enviam lances para o mesmo item no mesmo instante — sem que meu
lance seja perdido, duplicado, ou processado fora de ordem.

### Acceptance Scenarios

1. **Dado** um leilão ativo com `highest_bid = 100`, **quando** um usuário
   envia um lance de `150`, **então** o sistema aceita o lance, atualiza o
   `highest_bid` para `150` e notifica todos os clientes conectados.

2. **Dado** um leilão ativo com `highest_bid = 150`, **quando** um usuário
   envia um lance de `100`, **então** o sistema rejeita o lance sem alterar
   o estado, e informa ao cliente o motivo (valor abaixo do lance atual).

3. **Dado** dois lances concorrentes para o mesmo `auction_id`
   (`150` e `160`) enviados dentro do mesmo milissegundo, **quando** ambos
   chegam ao sistema, **então** apenas um é processado por vez (ordem
   determinística pela partição), e o resultado final reflete o maior
   valor válido recebido, sem perda do outro lance (registrado como
   rejeitado, não descartado silenciosamente).

4. **Dado** um lance já processado com sucesso (`bid_id = X`), **quando**
   o mesmo `bid_id` é reenviado (retry de rede, por exemplo), **então** o
   sistema reconhece a duplicata, não processa o lance uma segunda vez e
   reenvia ao usuário o resultado original.

5. **Dado** um usuário sem token de autenticação válido, **quando** ele
   tenta enviar um lance, **então** o gateway rejeita a requisição antes
   dela alcançar o pipeline de processamento.

6. **Dado** um usuário enviando lances acima do limite de taxa configurado,
   **quando** o limite é excedido, **então** requisições adicionais são
   rejeitadas pelo gateway até a janela de limite ser renovada.

### Edge Cases

- O que acontece quando dois lances de **valor igual** chegam para o mesmo
  item? → O sistema MUST aceitar apenas o primeiro na ordem do log
  (determinismo por chegada, não por valor).
- O que acontece se o consumer falhar **após** validar mas **antes** de
  confirmar a escrita? → O lance MUST ser reprocessado ao reiniciar
  (at-least-once), e a checagem de `bid_id` MUST evitar duplicação.
- O que acontece se uma colisão de `version` ocorrer mesmo dentro do
  pipeline single-writer (ex: escrita fora de banda)? → O sistema MUST
  tratar como anomalia, logar, e reprocessar com estado atualizado.
- O que acontece com um lance para um `auction_id` que **não existe**? →
  O sistema MUST rejeitar com motivo `AUCTION_NOT_FOUND`, sem criar estado
  novo e sem gravar linha em `bids` (não há leilão ao qual associá-lo). A
  rejeição MUST ser registrada no log estruturado (FR-010) e comunicada
  ao usuário (FR-006a).
- O que acontece com um lance para um `auction_id` que existe mas já foi
  **encerrado**? → O sistema MUST rejeitar com motivo `AUCTION_CLOSED`,
  sem alterar o leilão, e MUST gravar a rejeição em `bids`.
- O que acontece com um lance enviado perto do `end_time`? → A validade é
  definida pelo horário de chegada ao log, que MUST ser o timestamp
  atribuído pelo broker no momento em que o lance é anexado ao log (nunca
  um horário informado pelo produtor/cliente, como `timestamp_cliente`):
  lance registrado no log antes do `end_time`
  MUST ser processado normalmente, mesmo que processado após o término;
  lance registrado em ou após o `end_time` MUST ser rejeitado como
  leilão encerrado.

## Requirements

### Functional Requirements

- **FR-001**: O sistema MUST aceitar lances contendo `auction_id`, `valor`,
  `bid_id` (identificador único gerado pelo cliente) e identidade do
  usuário autenticado.
- **FR-002**: O sistema MUST garantir que todos os lances de um mesmo
  `auction_id` sejam processados por um único consumer por vez, na ordem
  de chegada ao log.
- **FR-003**: O sistema MUST rejeitar um lance cujo valor seja menor ou
  igual ao `highest_bid` atual do item, sem alterar o estado. Enquanto o
  leilão não tiver lance aceito, o sistema MUST rejeitar lances com valor
  menor que o `starting_price` (um lance igual ao `starting_price` é
  aceito como primeiro lance).
- **FR-003a**: O sistema MUST rejeitar, sem alterar o estado, lances do
  vendedor do item (motivo `SELLER_CANNOT_BID`) e lances do usuário que já
  é o atual líder (`highest_user`) do leilão (motivo `ALREADY_LEADING`).
- **FR-004**: O `bid_id` MUST ser globalmente único (independente do
  `auction_id`). O sistema MUST rejeitar um `bid_id` já processado
  anteriormente, em qualquer leilão, sem reaplicar a mudança de estado nem criar
  novo registro; o sistema MUST reenviar ao usuário o resultado original
  já registrado para esse `bid_id`.
- **FR-005**: O sistema MUST persistir o resultado de cada lance aceito de
  forma durável, usando escrita condicional (`version`) que rejeite
  escrita sobre estado desatualizado.
- **FR-006**: O sistema MUST notificar clientes conectados ao leilão sobre
  o novo lance líder em tempo real, após confirmação da escrita.
- **FR-006a**: O sistema MUST responder à submissão de lance imediatamente
  com confirmação de recebimento (contendo `bid_id`), sem aguardar o
  processamento, e MUST entregar o resultado final (aceito/rejeitado e
  motivo) ao usuário autor pela conexão em tempo real.
- **FR-007**: O sistema MUST autenticar toda requisição de lance antes de
  encaminhá-la ao pipeline de processamento.
- **FR-008**: O sistema MUST aplicar limite de taxa por usuário/IP antes do
  pipeline de processamento, rejeitando excesso sem afetar outros usuários.
- **FR-009**: Cada `auction_id` MUST ter um `risk_tier` associado
  (`normal` | `hot`), definido no cadastro do leilão, propagado junto com
  o lance ao longo do pipeline [conforme Princípio IV da constituição —
  sem efeito de roteamento de infraestrutura nesta fase].
- **FR-010**: O sistema MUST registrar, para cada lance processado, um log
  estruturado com `auction_id`, `bid_id`, `user_id`, decisão (aceito/
  rejeitado), motivo da rejeição quando aplicável, e sequência de
  processamento.
- **FR-011**: O sistema MUST manter um histórico append-only de todos os
  lances de leilões existentes (aceitos e rejeitados; exclui-se apenas
  `AUCTION_NOT_FOUND`) em tabela própria, sem alteração ou remoção
  de registros pela aplicação. Event sourcing não é requisito desta fase
  (ver constitution.md, Princípio VII).
- **FR-012**: O código desta feature MUST ter cobertura de testes unitários
  superior a 80% (medida em linhas), verificada automaticamente na
  execução da suíte de testes; abaixo desse limite a verificação MUST
  falhar.

### Key Entities

- **Auction (Leilão)**: `auction_id`, `item_name`, `seller_id`, `risk_tier`,
  `starting_price`, `end_time`, `highest_bid` (vazio até o primeiro lance aceito),
  `highest_user`, `version`, `status` (ativo/encerrado).
- **Bid (Lance)**: `bid_id`, `auction_id`, `user_id`, `valor`,
  `timestamp_cliente`, `sequence` (atribuída pelo sistema no processamento).
- **BidDecision (resultado)**: `bid_id`, `decision` (aceito/rejeitado),
  `reason` (quando rejeitado), `processed_at`. Persistido como linha da
  tabela `bids` (append-only, `bid_id` como chave primária).

## Review & Acceptance Checklist

- [x] Sem detalhes de implementação (linguagem, framework, biblioteca)
- [x] Focado no valor para o usuário e nas regras de negócio
- [x] Toda seção obrigatória preenchida
- [ ] Nenhum `[NEEDS CLARIFICATION]` pendente
- [x] Requisitos testáveis e não-ambíguos
- [x] Critérios de sucesso mensuráveis (cenários Given/When/Then)
- [x] Escopo delimitado (não inclui anti-sniping, Redis dedicado — ver
      constitution.md)
- [x] Dependências e suposições identificadas (gateway autentica e
      limita taxa antes do pipeline; `risk_tier` existe no cadastro)

## Suposições
- O cadastro do leilão (criação do `auction_id`, definição do `risk_tier`)
  é tratado como funcionalidade externa a esta spec (assume-se já existente
  ou cadastrada manualmente para fins de demonstração).
- "Tempo real" para notificação é definido como melhor esforço via conexão
  persistente (WebSocket/SSE), sem SLA de latência formalizado nesta fase.
