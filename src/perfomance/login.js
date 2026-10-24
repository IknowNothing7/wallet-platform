import http from 'k6/http';
import {check} from 'k6';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const USERS = 10;
const LOGIN_TAG = 'POST /api/auth/login';


export const options = {
    setTimeout: '60s',
    scenarios: {
        logins: {
            executor: 'ramping-arrival-rate',
            starRate: 5,
            timeUnit:'1s',
            preAllocatedVUs: 20,
            maxVUs: 100,
            stages: [
                {target:20, duration: '30ms'},
                {target:20, duration: '1m'},
                {target:40, duration: '30s'},
                {target:0, duration: '10s'},

            ],
        },
    },
    thresholds: {
        [`http_req_failed{name:${LOGIN_TAG}}`]: ['rate<0.01'],
        [`http_req_duration{name:${LOGIN_TAG}}`]: ['p(95)<800'],
        checks: ['rate>0.99'],
        dropped_iterations: ['count<10'],
    },
};

const JSON_HEADERS = {'Content-Type':'application/json'};

function expectStatus(response, status, what) {
    if(response.status !== status) {
        throw new Error(`${what} failed: ${response.status} ${response.body}`);
    }
}

function registerUser(runId, index) {
    const email = `logintest-${runId}-${index}@example.com`;
    const password = `LoadTest-${runId}`;
        const response = http.post(
        `${BASE_URL}/api/auth/register`,
        JSON.stringify({
            name:`Login Test ${index}`,
            email,
            password,
            confirmPassword: password,
        }),
        {header: JSON_HEADERS},
    );
    expectStatus(response,201,`Register user ${index}`);
    return {email,password};
}


export function setup() {
    const runId = Date.now();
    const users = [];

    for(let i = 0; i < USERS; i++) {
        users.push(registerUser(runId,i));
    }
    return users;
}

function login(email, password) {
    const response = http.post(
        `${BASE_URL}/api/auth/login`,
        JSON.stringify({email,password}),
        {
            headers: JSON_HEADERS,
            tags: {name:LOGIN_TAG},
        },
    );

    return check(response, {
        'login succeeded': (r) => r.status ===200,
        'access token returned': (r) =>!!r.json('payload.accessToken'),
    });
}

export default function(users) {
    const user = users[exec.scenario.iterationInTest % users.length];

    login(user.email, user.password);
}
