import http from 'k6/http';
import {check, sleep} from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = 2;
const CURRENCY = 'USD';
const INITIAL_BALANCE = 1000000;
const TRANSFER_TAG = 'POST /api/transfers';

export const options = {
    vus: VUS,
    duration: '30s',
    setupTimeout: '120s',

        thresholds: {
            [`http_req_failed{name:${TRANSFER_TAG}}`]: ['rate<0.01'],
            [`http_req_duration{name:${TRANSFER_TAG}}`]: ['p(95)<500'],
            checks:['rate>0.99'],
        },
};

const JSON_HEADERS = {'Content-Type': 'application/json'};

function authHeaders(token) {
    return {...JSON_HEADERS, Authorization: `Bearer ${token}`};
}

function expectStatus(response, status, what) {
    if (response.status !== status) {
        throw new Error(`${what} failed: ${response.status} ${response.body}`);
    }
}

function registerUser(runId, index) {
    const password = `LoadTest-${runId}`;
    const response = http.post(
        `${BASE_URL}/api/auth/register`,
        JSON.stringify({
            name: `Load Test ${index}`,
            email: `loadtest-${runId}-${index}@example.com`,
            password,
            confirmPassword: password,
        }),
        {headers: JSON_HEADERS},
    );
    expectStatus(response, 201, `Register user ${index}`);
    return response.json('payload.accessToken');
}

function createAccount(token) {
    const response = http.post(
        `${BASE_URL}/api/accounts`,
        JSON.stringify({currency: CURRENCY}),
        {headers: authHeaders(token)},
    );
    expectStatus(response, 201, 'Create account');
    return response.json('payload.id');
}

function createDeposit(token, accountId) {
    const response = http.post(
        `${BASE_URL}/api/deposits`,
        JSON.stringify({
            accountId,
            amount: INITIAL_BALANCE,
            currency: CURRENCY,
            gateway: 'bank-sim',
        }),
        {headers: authHeaders(token)},
    );
    expectStatus(response, 201, `Deposit to account ${accountId}`);
    return response.json('payload.id');
}

function waitForDeposit(token, depositId) {
    for (let attempt = 0; attempt < 60; attempt++) {
        const response = http.get(`${BASE_URL}/api/deposits/${depositId}`, {headers: authHeaders(token)});
        expectStatus(response, 200, `Get deposit ${depositId}`);

        const status = response.json('payload.status');
        if (status === 'SUCCEEDED') {
            return;
        }
        if (status !== 'PENDING') {
            throw new Error(`Deposit ${depositId} ended ${status}: ${response.json('payload.failureReason')}`);
        }
        sleep(1);
    }
    throw new Error(`Deposit ${depositId} still PENDING; is bank-sim running?`);
}

export function setup() {
    const runId = Date.now();
    const users = [];

    for (let i = 0; i < VUS; i++) {
        const token = registerUser(runId, i);
        const accountId = createAccount(token);
        const depositId = createDeposit(token, accountId);
        users.push({token, accountId, depositId});
    }

    for (const user of users) {
        waitForDeposit(user.token, user.depositId);
    }

    return users.map((user, i) => ({
        token: user.token,
        fromAccountId: user.accountId,
        toAccountId: users[(i + 1) % users.length].accountId,
    }));
}

function transfer(token, fromAccountId, toAccountId) {
    const payload = JSON.stringify({
        idFromAccount: fromAccountId,
        idToAccount: toAccountId,
        amount:1,
        currency:CURRENCY,
    });

    const response = http.post(
            `${BASE_URL}/api/transfers`,
            payload,
            {
                headers: authHeaders(token),
                 tags: { name: TRANSFER_TAG },
            }
    );

    return check(response, {
        'transfer succeeded': (r) =>
            r.status === 201,
        'response under 500ms':(r) =>
            r.timings.duration<500,
    });
}

export default function (sessions) {
    const user = sessions[(__VU - 1) % sessions.length];

    transfer(
        user.token,
        user.fromAccountId,
        user.toAccountId
    );
}
