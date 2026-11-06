// k6 — carga de lances concorrentes no mesmo auction_id (R12).
//
// N usuários (u<VU>) enviam valores crescentes e únicos ao leilão AUCTION_ID.
// Execução (com o stack no ar, seed.sql aplicado e JWT_SECRET igual ao da app):
//   JWT_SECRET=... AUCTION_ID=6f1c2a3e-1111-4a6b-9c0d-000000000001 k6 run load-tests/last-seconds.js
//
// Verificação final (após o k6 terminar; todas devem valer):
//   -- 1) highest_bid = maior valor aceito no log
//   SELECT a.highest_bid = (SELECT max(valor) FROM bids WHERE auction_id = a.auction_id AND decision = 'aceito')
//     FROM auctions a WHERE a.auction_id = '<AUCTION_ID>';
//   -- 2) zero bid_id duplicado (PK garante; a consulta comprova que não houve falha silenciosa)
//   SELECT count(*) - count(DISTINCT bid_id) FROM bids WHERE auction_id = '<AUCTION_ID>';   -- deve ser 0
//   -- 3) sequence crescente por partição (ordem de offset = ordem de decisão)
//   SELECT count(*) FROM (
//     SELECT sequence, lag(sequence) OVER (PARTITION BY partition ORDER BY sequence) AS prev
//       FROM bids WHERE auction_id = '<AUCTION_ID>') t
//    WHERE prev IS NOT NULL AND sequence <= prev;                                         -- deve ser 0
//   -- 4) version = número de lances aceitos
//   SELECT a.version = (SELECT count(*) FROM bids WHERE auction_id = a.auction_id AND decision = 'aceito')
//     FROM auctions a WHERE a.auction_id = '<AUCTION_ID>';

import http from 'k6/http';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const AUCTION_ID = __ENV.AUCTION_ID;
const JWT_SECRET = __ENV.JWT_SECRET;
const VUS = parseInt(__ENV.VUS || '100', 10);
const ITERATIONS_PER_VU = parseInt(__ENV.ITERATIONS_PER_VU || '20', 10);

if (!AUCTION_ID || !JWT_SECRET) {
  throw new Error('defina AUCTION_ID e JWT_SECRET');
}

export const options = {
  scenarios: {
    concorrencia: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: ITERATIONS_PER_VU,
      maxDuration: '5m',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<2000'],
  },
};

function b64url(input) {
  return encoding.b64encode(input, 'rawurl');
}

// JWT HS256 com sub = u<VU>; mesmo formato de load-tests/gen-jwt.sh
function tokenFor(sub) {
  const header = b64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const now = Math.floor(Date.now() / 1000);
  const payload = b64url(JSON.stringify({ sub: sub, iat: now, exp: now + 3600 }));
  const data = `${header}.${payload}`;
  const sig = crypto.hmac('sha256', JWT_SECRET, data, 'base64rawurl');
  return `${data}.${sig}`;
}

// UUID v4 simples (k6 não tem gerador embutido)
function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

export default function () {
  const vu = __VU;
  const iter = __ITER;
  // valores únicos e crescentes por VU: 101 + vu*1000 + iter (ordem de chegada é que decide)
  const valor = 101 + vu * 1000 + iter;
  const res = http.post(
    `${BASE_URL}/bids`,
    JSON.stringify({ auction_id: AUCTION_ID, valor: valor.toFixed(2), bid_id: uuid() }),
    {
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${tokenFor(`u${vu}`)}`,
      },
    },
  );
  check(res, { 'lance aceito pelo HTTP (202)': (r) => r.status === 202 });
  // abaixo de 20 req/s por usuário (rate limit de RateLimitFilter)
  sleep(0.1);
}
