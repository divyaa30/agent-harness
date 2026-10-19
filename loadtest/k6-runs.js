// Fires investigation requests at a fixed rate. Run through Docker, no install needed:
//   docker run --rm -i -e BASE_URL=http://host.docker.internal:8080 -e RATE=5 -e DURATION=60s \
//     --add-host=host.docker.internal:host-gateway grafana/k6:0.54.0 run - < loadtest/k6-runs.js
// k6 measures the API side (POST /runs). How fast runs actually finish is read from Postgres
// afterwards by run-load-test.sh, because the work happens asynchronously in the workers.
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    runs: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.RATE || 5),
      timeUnit: '1s',
      duration: __ENV.DURATION || '60s',
      preAllocatedVUs: 20,
      maxVUs: 100,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:create_run}': ['p(95)<250'],
  },
};

export default function () {
  const res = http.post(
    `${BASE}/runs`,
    JSON.stringify({ incident: 'Load test: customers could not open accounts on 15 October 2026 in the morning.' }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'create_run' } },
  );
  check(res, { 'accepted (202)': (r) => r.status === 202 });
}
