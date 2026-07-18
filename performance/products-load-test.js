import http from 'k6/http';
import exec from 'k6/execution';
import { check, fail } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://app:8080';
const WARMUP_DURATION = __ENV.WARMUP_DURATION || '10s';
const RAMP_UP_DURATION = __ENV.RAMP_UP_DURATION || '15s';
const TEST_DURATION = __ENV.TEST_DURATION || '30s';
const COOLDOWN_DURATION = __ENV.COOLDOWN_DURATION || '10s';
const TARGET_RATE = numberFromEnvironment('TARGET_RATE', 20, 4);

const historyRate = Math.max(1, Math.round(TARGET_RATE * 0.15));
const createProductRate = Math.max(1, Math.round(TARGET_RATE * 0.05));
const addPriceRate = Math.max(1, Math.round(TARGET_RATE * 0.05));
const currentPriceRate = TARGET_RATE - historyRate - createProductRate - addPriceRate;

if (currentPriceRate < 1) {
    throw new Error('TARGET_RATE must leave at least one request per second for current-price');
}

const totalDurationSeconds = [
    WARMUP_DURATION,
    RAMP_UP_DURATION,
    TEST_DURATION,
    COOLDOWN_DURATION,
].map(durationToSeconds).reduce((total, duration) => total + duration, 0);

const addPriceProductCount = Math.ceil(addPriceRate * totalDurationSeconds * 1.25) + 10;

export const unexpected_status_codes = new Counter('unexpected_status_codes');
export const server_errors = new Counter('server_errors');
export const business_success = new Rate('business_success');
export const current_price_duration = new Trend('current_price_duration', true);
export const history_duration = new Trend('history_duration', true);
export const write_duration = new Trend('write_duration', true);

export const options = {
    discardResponseBodies: false,
    summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
    scenarios: {
        current_price: scenario('currentPrice', currentPriceRate),
        history: scenario('history', historyRate),
        create_product: scenario('createProduct', createProductRate),
        add_price: scenario('addPrice', addPriceRate),
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],
        checks: ['rate>0.99'],
        business_success: ['rate>0.99'],
        server_errors: ['count==0'],
        unexpected_status_codes: ['count==0'],
        dropped_iterations: ['count==0'],
        current_price_duration: ['p(95)<500'],
        history_duration: ['p(95)<750'],
        write_duration: ['p(95)<1000'],
    },
};

export function setup() {
    const runId = `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
    const health = http.get(`${BASE_URL}/actuator/health`, requestTags(
        'GET /actuator/health', 'health', 'setup'));
    const healthBody = requiredJson(health, 'health response');
    requireSetup(health.status === 200 && healthBody.status === 'UP',
        `health check failed: status=${health.status}, body=${health.body}`);

    const readProducts = [];
    for (let index = 0; index < 8; index += 1) {
        const product = createSetupProduct(runId, `read-${index}`);
        createSetupPrice(product.id, '99.99', '2024-01-01', '2024-06-30');
        createSetupPrice(product.id, '129.99', '2024-07-01', '2024-12-31');
        createSetupPrice(product.id, '199.99', '2025-01-01', null);
        readProducts.push({
            id: product.id,
            name: product.name,
            description: product.description,
        });
    }

    const addPriceProductIds = [];
    for (let index = 0; index < addPriceProductCount; index += 1) {
        addPriceProductIds.push(createSetupProduct(runId, `price-${index}`).id);
    }

    console.log(`setup completed: runId=${runId}, readProducts=${readProducts.length}, `
        + `addPriceProducts=${addPriceProductIds.length}, targetRate=${TARGET_RATE}`);
    return { runId, readProducts, addPriceProductIds };
}

export function currentPrice(data) {
    const product = data.readProducts[exec.scenario.iterationInTest % data.readProducts.length];
    const dates = ['2024-04-15', '2024-09-15', '2030-01-01'];
    const date = dates[exec.scenario.iterationInTest % dates.length];
    const response = http.get(
        `${BASE_URL}/products/${product.id}/prices?date=${date}`,
        requestTags('GET /products/{id}/prices?date', 'current-price', 'load'),
    );
    current_price_duration.add(response.timings.duration, { endpoint: 'current-price' });

    const body = optionalJson(response);
    const valid = evaluateResponse(response, 200, 'current-price')
        && body !== null
        && exactFields(body, ['value'])
        && typeof body.value === 'number';
    check(response, {
        'current-price: status is 200': (result) => result.status === 200,
        'current-price: content type is JSON': hasJsonContentType,
        'current-price: body is valid JSON': () => body !== null,
        'current-price: body contains only numeric value': () => body !== null
            && exactFields(body, ['value']) && typeof body.value === 'number',
        'current-price: no server error': (result) => result.status < 500,
    }, { endpoint: 'current-price', phase: 'load' });
    business_success.add(valid, { endpoint: 'current-price' });
}

export function history(data) {
    const product = data.readProducts[exec.scenario.iterationInTest % data.readProducts.length];
    const response = http.get(
        `${BASE_URL}/products/${product.id}/prices`,
        requestTags('GET /products/{id}/prices', 'history', 'load'),
    );
    history_duration.add(response.timings.duration, { endpoint: 'history' });

    const body = optionalJson(response);
    const valid = evaluateResponse(response, 200, 'history')
        && body !== null
        && typeof body.name === 'string'
        && Object.prototype.hasOwnProperty.call(body, 'description')
        && Array.isArray(body.prices);
    check(response, {
        'history: status is 200': (result) => result.status === 200,
        'history: content type is JSON': hasJsonContentType,
        'history: body is valid JSON': () => body !== null,
        'history: body contains name, description and prices': () => body !== null
            && typeof body.name === 'string'
            && Object.prototype.hasOwnProperty.call(body, 'description')
            && Array.isArray(body.prices),
        'history: no server error': (result) => result.status < 500,
    }, { endpoint: 'history', phase: 'load' });
    business_success.add(valid, { endpoint: 'history' });
}

export function createProduct(data) {
    const iteration = exec.scenario.iterationInTest;
    const name = `benchmark-product-${data.runId}-load-${iteration}`;
    const description = `Created by k6 run ${data.runId}`;
    const response = http.post(
        `${BASE_URL}/products`,
        JSON.stringify({ name, description }),
        jsonRequestTags('POST /products', 'create-product', 'load'),
    );
    write_duration.add(response.timings.duration, { endpoint: 'create-product' });

    const body = optionalJson(response);
    const valid = evaluateResponse(response, 201, 'create-product')
        && body !== null
        && Number.isInteger(body.id) && body.id > 0
        && body.name === name
        && body.description === description;
    check(response, {
        'create-product: status is 201': (result) => result.status === 201,
        'create-product: content type is JSON': hasJsonContentType,
        'create-product: body is valid JSON': () => body !== null,
        'create-product: body contains expected fields': () => body !== null
            && Number.isInteger(body.id) && body.id > 0
            && body.name === name && body.description === description,
        'create-product: no server error': (result) => result.status < 500,
    }, { endpoint: 'create-product', phase: 'load' });
    business_success.add(valid, { endpoint: 'create-product' });
}

export function addPrice(data) {
    const iteration = exec.scenario.iterationInTest;
    if (iteration >= data.addPriceProductIds.length) {
        fail(`add-price exhausted its product pool at iteration ${iteration}`);
    }
    const productId = data.addPriceProductIds[iteration];
    const payload = { value: 149.99, initDate: '2025-01-01', endDate: null };
    const response = http.post(
        `${BASE_URL}/products/${productId}/prices`,
        JSON.stringify(payload),
        jsonRequestTags('POST /products/{id}/prices', 'add-price', 'load'),
    );
    write_duration.add(response.timings.duration, { endpoint: 'add-price' });

    const body = optionalJson(response);
    const valid = evaluateResponse(response, 201, 'add-price')
        && body !== null
        && body.value === payload.value
        && body.initDate === payload.initDate
        && Object.prototype.hasOwnProperty.call(body, 'endDate')
        && body.endDate === null;
    check(response, {
        'add-price: status is 201': (result) => result.status === 201,
        'add-price: content type is JSON': hasJsonContentType,
        'add-price: body is valid JSON': () => body !== null,
        'add-price: body contains expected fields': () => body !== null
            && body.value === payload.value && body.initDate === payload.initDate
            && Object.prototype.hasOwnProperty.call(body, 'endDate') && body.endDate === null,
        'add-price: no server error': (result) => result.status < 500,
    }, { endpoint: 'add-price', phase: 'load' });
    business_success.add(valid, { endpoint: 'add-price' });
}

function scenario(execFunction, targetRate) {
    const warmupRate = Math.max(1, Math.ceil(targetRate * 0.2));
    return {
        executor: 'ramping-arrival-rate',
        exec: execFunction,
        startRate: warmupRate,
        timeUnit: '1s',
        preAllocatedVUs: Math.max(2, Math.ceil(targetRate / 2)),
        maxVUs: Math.max(4, targetRate * 2),
        gracefulStop: '5s',
        stages: [
            { duration: WARMUP_DURATION, target: warmupRate },
            { duration: RAMP_UP_DURATION, target: targetRate },
            { duration: TEST_DURATION, target: targetRate },
            { duration: COOLDOWN_DURATION, target: 0 },
        ],
        tags: { phase: 'load' },
    };
}

function createSetupProduct(runId, suffix) {
    const name = `benchmark-product-${runId}-${suffix}`;
    const description = `Prepared by k6 run ${runId}`;
    const response = http.post(
        `${BASE_URL}/products`,
        JSON.stringify({ name, description }),
        jsonRequestTags('POST /products [setup]', 'create-product', 'setup'),
    );
    const body = requiredJson(response, `create product ${suffix}`);
    const valid = response.status === 201
        && hasJsonContentType(response)
        && Number.isInteger(body.id) && body.id > 0
        && body.name === name && body.description === description;
    check(response, {
        'setup product: status is 201': (result) => result.status === 201,
        'setup product: response contract is valid': () => valid,
    }, { endpoint: 'create-product', phase: 'setup' });
    requireSetup(valid, `product setup failed for ${suffix}: status=${response.status}, body=${response.body}`);
    return body;
}

function createSetupPrice(productId, value, initDate, endDate) {
    const payload = { value: Number(value), initDate, endDate };
    const response = http.post(
        `${BASE_URL}/products/${productId}/prices`,
        JSON.stringify(payload),
        jsonRequestTags('POST /products/{id}/prices [setup]', 'add-price', 'setup'),
    );
    const body = requiredJson(response, `add price to product ${productId}`);
    const valid = response.status === 201
        && hasJsonContentType(response)
        && body.value === payload.value
        && body.initDate === initDate
        && Object.prototype.hasOwnProperty.call(body, 'endDate')
        && body.endDate === endDate;
    check(response, {
        'setup price: status is 201': (result) => result.status === 201,
        'setup price: response contract is valid': () => valid,
    }, { endpoint: 'add-price', phase: 'setup' });
    requireSetup(valid,
        `price setup failed for product ${productId}: status=${response.status}, body=${response.body}`);
}

function evaluateResponse(response, expectedStatus, endpoint) {
    if (response.status >= 500) {
        server_errors.add(1, { endpoint });
    }
    if (response.status !== expectedStatus) {
        unexpected_status_codes.add(1, { endpoint, status: String(response.status) });
    }
    return response.status === expectedStatus && response.status < 500 && hasJsonContentType(response);
}

function optionalJson(response) {
    try {
        return response.json();
    }
    catch (error) {
        return null;
    }
}

function requiredJson(response, context) {
    const body = optionalJson(response);
    requireSetup(body !== null, `${context} is not valid JSON: ${response.body}`);
    return body;
}

function requireSetup(condition, message) {
    if (!condition) {
        fail(message);
    }
}

function hasJsonContentType(response) {
    const contentType = response.headers['Content-Type'] || '';
    return contentType.toLowerCase().includes('application/json');
}

function exactFields(body, expectedFields) {
    const actualFields = Object.keys(body).sort();
    const expected = [...expectedFields].sort();
    return actualFields.length === expected.length
        && actualFields.every((field, index) => field === expected[index]);
}

function requestTags(name, endpoint, phase) {
    return { tags: { name, endpoint, phase } };
}

function jsonRequestTags(name, endpoint, phase) {
    return {
        headers: { 'Content-Type': 'application/json' },
        tags: { name, endpoint, phase },
    };
}

function numberFromEnvironment(name, defaultValue, minimum) {
    const raw = __ENV[name];
    const value = raw === undefined || raw === '' ? defaultValue : Number(raw);
    if (!Number.isInteger(value) || value < minimum) {
        throw new Error(`${name} must be an integer greater than or equal to ${minimum}`);
    }
    return value;
}

function durationToSeconds(duration) {
    const match = /^(\d+(?:\.\d+)?)(ms|s|m|h)$/.exec(duration);
    if (match === null) {
        throw new Error(`Unsupported duration '${duration}'. Use ms, s, m or h.`);
    }
    const value = Number(match[1]);
    const multipliers = { ms: 0.001, s: 1, m: 60, h: 3600 };
    return value * multipliers[match[2]];
}
