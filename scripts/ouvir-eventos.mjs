#!/usr/bin/env node
// Ouve os eventos WebSocket (STOMP) do bid-service e imprime cada um como JSON.
//
// Requer Node 22+ (WebSocket nativo). Sem dependências.
//
// Uso:
//   TOKEN=<jwt> node scripts/ouvir-eventos.mjs
//   TOKEN=<jwt> AUCTION_ID=<uuid> node scripts/ouvir-eventos.mjs
//   TOKEN=<jwt> BASE_URL=http://localhost:8080 node scripts/ouvir-eventos.mjs
//
// Sem AUCTION_ID, assina só o resultado do próprio usuário (/user/queue/bid-results).
// Com AUCTION_ID, também assina o líder do leilão (/topic/auctions/{id}).
// Encerre com Ctrl+C.

const BASE_URL = process.env.BASE_URL ?? 'http://localhost:8080';
const TOKEN = process.env.TOKEN;
const AUCTION_ID = process.env.AUCTION_ID;
const NUL = '\0';

if (!TOKEN) {
  console.error('Defina TOKEN com um JWT válido (ex.: ./load-tests/gen-jwt.sh u1).');
  process.exit(1);
}

const wsUrl = BASE_URL.replace(/^http/, 'ws') + `/ws?access_token=${encodeURIComponent(TOKEN)}`;
const ws = new WebSocket(wsUrl);

const destinos = ['/user/queue/bid-results'];
if (AUCTION_ID) destinos.push(`/topic/auctions/${AUCTION_ID}`);

function enviar(frame) {
  ws.send(frame + NUL);
}

// Um frame STOMP: COMANDO, cabeçalhos, linha em branco, corpo, NUL.
// O servidor pode mandar vários frames numa mesma mensagem WebSocket.
function processar(texto) {
  for (const bruto of texto.split(NUL)) {
    const frame = bruto.replace(/^\n+/, '');
    if (!frame) continue;

    const [cabecalhoBruto, ...resto] = frame.split('\n\n');
    const [comando, ...linhas] = cabecalhoBruto.split('\n');
    const cabecalhos = Object.fromEntries(
      linhas.map((l) => {
        const i = l.indexOf(':');
        return [l.slice(0, i), l.slice(i + 1)];
      }),
    );
    const corpo = resto.join('\n\n');

    if (comando === 'CONNECTED') {
      console.log(`[conectado] usuário=${cabecalhos['user-name'] ?? '?'}`);
      destinos.forEach((destino, i) => {
        enviar(`SUBSCRIBE\nid:sub-${i}\ndestination:${destino}\n\n`);
        console.log(`[assinado] ${destino}`);
      });
    } else if (comando === 'MESSAGE') {
      const hora = new Date().toISOString();
      let dados;
      try {
        dados = JSON.parse(corpo);
      } catch {
        dados = corpo;
      }
      console.log(JSON.stringify({ recebido_em: hora, destino: cabecalhos.destination, evento: dados }, null, 2));
    } else if (comando === 'ERROR') {
      console.error(`[erro do servidor] ${corpo || cabecalhos.message || ''}`);
    }
  }
}

ws.onopen = () => {
  enviar('CONNECT\naccept-version:1.2\nhost:localhost\n\n');
};

ws.onmessage = (evento) => processar(String(evento.data));

ws.onerror = () => {
  console.error('Falha na conexão. Verifique se o serviço está no ar e se o TOKEN não expirou.');
};

ws.onclose = (evento) => {
  console.log(`[desconectado] código=${evento.code}`);
};
