import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

// Custom metric tracking non-expected HTTP errors
// (201 Created and 409 Insufficient Capacity are expected business outcomes)
const unexpectedErrorRate = new Rate('unexpected_errors');

export const options = {
  stages: [
    { duration: '10s', target: 20 },  // Ramp-up to 20 VUs
    { duration: '20s', target: 50 },  // Contention peak with 50 concurrent VUs
    { duration: '10s', target: 0 },   // Graceful ramp-down
  ],
  thresholds: {
    // 95% of reservation requests must complete within 500ms
    http_req_duration: ['p(95)<500'],
    // Unexpected errors (e.g. 500, unhandled failures) must stay below 1%
    unexpected_errors: ['rate<0.01'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// Offline UUID v4 generator to avoid external dependencies
function generateUuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

export function setup() {
  // 1. Create a Resource for the test run
  const resPayload = JSON.stringify({
    name: 'Load Test Venue',
    description: 'High concurrency reservation load test',
  });
  const resParams = { headers: { 'Content-Type': 'application/json' } };
  const resResponse = http.post(`${BASE_URL}/api/resources`, resPayload, resParams);

  if (resResponse.status !== 201) {
    throw new Error(`Failed to create test resource: ${resResponse.status} ${resResponse.body}`);
  }
  const resourceId = JSON.parse(resResponse.body).id;

  // 2. Create a Slot with limited capacity (e.g. 100 seats)
  const startTime = new Date(Date.now() + 24 * 3600 * 1000).toISOString();
  const endTime = new Date(Date.now() + 26 * 3600 * 1000).toISOString();
  const slotPayload = JSON.stringify({
    startTime: startTime,
    endTime: endTime,
    capacity: 100,
  });
  const slotResponse = http.post(`${BASE_URL}/api/resources/${resourceId}/slots`, slotPayload, resParams);

  if (slotResponse.status !== 201) {
    throw new Error(`Failed to create test slot: ${slotResponse.status} ${slotResponse.body}`);
  }
  const slotId = JSON.parse(slotResponse.body).id;

  console.log(`Setup complete: Resource=${resourceId}, Slot=${slotId}, Capacity=100`);
  return { resourceId, slotId };
}

export default function (data) {
  const idempotencyKey = generateUuid();
  const userId = generateUuid();

  const payload = JSON.stringify({
    slotId: data.slotId,
    userId: userId,
    quantity: 1,
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
    },
  };

  const res = http.post(`${BASE_URL}/api/reservations`, payload, params);

  // 201 = Successfully reserved
  // 409 = Insufficient capacity (expected once 100 capacity is exhausted)
  // 503 = Pool exhausted under extreme load (fails fast with Retry-After)
  const isExpected = res.status === 201 || res.status === 409;
  unexpectedErrorRate.add(!isExpected);

  check(res, {
    'status is 201 or 409': (r) => r.status === 201 || r.status === 409,
    'not a 500 error': (r) => r.status !== 500,
  });

  sleep(0.05);
}

export function teardown(data) {
  console.log(`Load test finished for Resource=${data.resourceId}, Slot=${data.slotId}`);
}
