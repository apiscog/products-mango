import http from 'k6/http';
import exec from 'k6/execution';
import { check, fail, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://app:8080';
const MODE = __ENV.MODE || 'setup';
const ACCESS_TOKEN = __ENV.ACCESS_TOKEN || '';

const PRODUCT_CREATION_ITERATIONS = 1000;
const PRICE_QUERY_ITERATIONS = 20000;
const HISTORY_QUERY_ITERATIONS = 15000;

const SETUP_PRODUCT_NAME = 'Zapatillas deportivas';
const SETUP_PRODUCT_DESCRIPTION = 'Modelo 2025 edición limitada';
const SETUP_PRICES = [
    { value: 99.99, initDate: '2024-01-01', endDate: '2024-06-30' },
    { value: 129.99, initDate: '2024-07-01', endDate: '2024-12-31' },
    { value: 199.99, initDate: '2025-01-01', endDate: null },
];

export const unexpected_status_codes = new Counter('unexpected_status_codes');
export const server_errors = new Counter('server_errors');
export const invalid_contracts = new Counter('invalid_contracts');
export const business_success = new Rate('business_success');
export const setup_business_requests = new Counter('setup_business_requests');
export const setup_health_success = new Counter('setup_health_success');
export const product_creation_requests = new Counter('product_creation_requests');
export const price_query_requests = new Counter('price_query_requests');
export const history_query_requests = new Counter('history_query_requests');
export const product_creation_duration = new Trend('product_creation_duration', true);
export const price_query_duration = new Trend('price_query_duration', true);
export const history_query_duration = new Trend('history_query_duration', true);

export const options = optionsForMode(MODE);

export function setupFlow() {
    waitForHealthyApplication();

    const productResponse = http.post(
        `${BASE_URL}/products`,
        JSON.stringify({ name: SETUP_PRODUCT_NAME, description: SETUP_PRODUCT_DESCRIPTION }),
        jsonRequest('POST /products [setup]', 'setup'),
    );
    setup_business_requests.add(1, { phase: 'setup' });
    const product = parseJson(productResponse);
    const validProduct = product !== null
        && Number.isInteger(product.id) && product.id > 0
        && product.name === SETUP_PRODUCT_NAME
        && product.description === SETUP_PRODUCT_DESCRIPTION;
    validateResponse(productResponse, 201, validProduct, 'setup', 'setup product');
    requireSetup(validProduct, `could not create setup product: ${productResponse.body}`);

    for (const price of SETUP_PRICES) {
        const response = http.post(
            `${BASE_URL}/products/${product.id}/prices`,
            JSON.stringify(price),
            jsonRequest('POST /products/{id}/prices [setup]', 'setup'),
        );
        setup_business_requests.add(1, { phase: 'setup' });
        const body = parseJson(response);
        const valid = body !== null
            && body.value === price.value
            && body.initDate === price.initDate
            && Object.prototype.hasOwnProperty.call(body, 'endDate')
            && body.endDate === price.endDate;
        validateResponse(response, 201, valid, 'setup', `setup price ${price.initDate}`);
        requireSetup(valid && response.status === 201,
            `could not create setup price ${price.initDate}: ${response.body}`);
    }

    const expectedPrices = [99.99, 129.99, 199.99];
    const dates = ['2024-04-15', '2024-08-15', '2025-03-01'];
    for (let index = 0; index < dates.length; index += 1) {
        const date = dates[index];
        const response = http.get(
            `${BASE_URL}/products/${product.id}/prices?date=${date}`,
            request('GET /products/{id}/prices?date [setup]', 'setup'),
        );
        setup_business_requests.add(1, { phase: 'setup' });
        const body = parseJson(response);
        const valid = body !== null
            && exactFields(body, ['value'])
            && body.value === expectedPrices[index];
        validateResponse(response, 200, valid, 'setup', `setup price query ${date}`);
        requireSetup(valid && response.status === 200,
            `setup price query failed for ${date}: ${response.body}`);
    }

    const historyResponse = http.get(
        `${BASE_URL}/products/${product.id}/prices`,
        request('GET /products/{id}/prices [setup]', 'setup'),
    );
    setup_business_requests.add(1, { phase: 'setup' });
    const history = parseJson(historyResponse);
    const validHistory = matchesExpectedHistory(history);
    validateResponse(historyResponse, 200, validHistory, 'setup', 'setup history');
    requireSetup(validHistory && historyResponse.status === 200,
        `setup history query failed: ${historyResponse.body}`);

    console.log(`SETUP_PRODUCT_ID=${product.id}`);
    console.log('SETUP_BUSINESS_REQUESTS=8');
}

export function createProduct() {
    const sequence = exec.scenario.iterationInTest + 1;
    const payload = {
        name: `Producto Test ${sequence}`,
        description: `Descripción del producto ${sequence}`,
    };
    const response = http.post(
        `${BASE_URL}/products`,
        JSON.stringify(payload),
        jsonRequest('POST /products [product-creation]', 'product-creation'),
    );
    product_creation_requests.add(1, { phase: 'product-creation' });
    product_creation_duration.add(response.timings.duration, { phase: 'product-creation' });
    const body = parseJson(response);
    const valid = body !== null
        && Number.isInteger(body.id) && body.id > 0
        && body.name === payload.name
        && body.description === payload.description;
    validateResponse(response, 201, valid, 'product-creation', 'product creation');
}

export function queryPrice() {
    const productId = requiredProductId();
    const response = http.get(
        `${BASE_URL}/products/${productId}/prices?date=2024-04-15`,
        request('GET /products/{id}/prices?date [price-query]', 'price-query'),
    );
    price_query_requests.add(1, { phase: 'price-query' });
    price_query_duration.add(response.timings.duration, { phase: 'price-query' });
    const body = parseJson(response);
    const valid = body !== null && exactFields(body, ['value']) && body.value === 99.99;
    validateResponse(response, 200, valid, 'price-query', 'price query');
}

export function queryHistory() {
    const productId = requiredProductId();
    const response = http.get(
        `${BASE_URL}/products/${productId}/prices`,
        request('GET /products/{id}/prices [history-query]', 'history-query'),
    );
    history_query_requests.add(1, { phase: 'history-query' });
    history_query_duration.add(response.timings.duration, { phase: 'history-query' });
    const body = parseJson(response);
    validateResponse(response, 200, matchesExpectedHistory(body), 'history-query', 'history query');
}

function waitForHealthyApplication() {
    for (let attempt = 1; attempt <= 60; attempt += 1) {
        const response = http.get(
            `${BASE_URL}/actuator/health`,
            request('GET /actuator/health [setup]', 'setup'),
        );
        const body = parseJson(response);
        if (response.status === 200 && hasJsonContentType(response) && body !== null && body.status === 'UP') {
            setup_health_success.add(1, { phase: 'setup' });
            check(response, {
                'setup health: status is 200': (result) => result.status === 200,
                'setup health: content type is JSON': hasJsonContentType,
                'setup health: body is valid JSON': () => body !== null,
                'setup health: status is UP': () => body.status === 'UP',
            }, { phase: 'setup' });
            return;
        }
        sleep(1);
    }
    fail('application did not become healthy within 60 seconds');
}

function validateResponse(response, expectedStatus, validContract, phase, label) {
    if (response.status >= 500) {
        server_errors.add(1, { phase });
    }
    if (response.status !== expectedStatus) {
        unexpected_status_codes.add(1, { phase, status: String(response.status) });
    }
    if (!validContract) {
        invalid_contracts.add(1, { phase });
    }

    const jsonBody = parseJson(response);
    const success = response.status === expectedStatus
        && response.status < 500
        && hasJsonContentType(response)
        && jsonBody !== null
        && validContract;
    business_success.add(success, { phase });

    check(response, {
        [`${label}: expected status`]: (result) => result.status === expectedStatus,
        [`${label}: content type is JSON`]: hasJsonContentType,
        [`${label}: body is valid JSON`]: () => jsonBody !== null,
        [`${label}: response contract is valid`]: () => validContract,
        [`${label}: no server error`]: (result) => result.status < 500,
    }, { phase });
}

function matchesExpectedHistory(body) {
    if (body === null
        || !exactFields(body, ['name', 'description', 'prices'])
        || body.name !== SETUP_PRODUCT_NAME
        || body.description !== SETUP_PRODUCT_DESCRIPTION
        || !Array.isArray(body.prices)
        || body.prices.length !== SETUP_PRICES.length) {
        return false;
    }
    return body.prices.every((price, index) => exactFields(price, ['value', 'initDate', 'endDate'])
        && price.value === SETUP_PRICES[index].value
        && price.initDate === SETUP_PRICES[index].initDate
        && price.endDate === SETUP_PRICES[index].endDate);
}

function optionsForMode(mode) {
    const common = {
        discardResponseBodies: false,
        summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
        thresholds: {
            http_req_failed: ['rate<0.01'],
            checks: ['rate>0.99'],
            business_success: ['rate>0.99'],
            server_errors: ['count==0'],
            unexpected_status_codes: ['count==0'],
            invalid_contracts: ['count==0'],
            dropped_iterations: ['count==0'],
        },
    };

    if (mode === 'setup') {
        delete common.thresholds.http_req_failed;
        common.scenarios = {
            setup: sharedIterations('setupFlow', 1, 1, 'setup'),
        };
        common.thresholds.setup_business_requests = ['count==8'];
        common.thresholds.setup_health_success = ['count==1'];
        return common;
    }
    if (mode === 'product-creation') {
        common.scenarios = {
            product_creation: sharedIterations(
                'createProduct', PRODUCT_CREATION_ITERATIONS,
                integerFromEnvironment('PRODUCT_CREATION_VUS', 100), 'product-creation'),
        };
        common.thresholds.product_creation_requests = [`count==${PRODUCT_CREATION_ITERATIONS}`];
        return common;
    }
    if (mode === 'price-query') {
        common.scenarios = {
            price_query: sharedIterations(
                'queryPrice', PRICE_QUERY_ITERATIONS,
                integerFromEnvironment('PRICE_QUERY_VUS', 500), 'price-query'),
        };
        common.thresholds.price_query_requests = [`count==${PRICE_QUERY_ITERATIONS}`];
        return common;
    }
    if (mode === 'history-query') {
        common.scenarios = {
            history_query: sharedIterations(
                'queryHistory', HISTORY_QUERY_ITERATIONS,
                integerFromEnvironment('HISTORY_QUERY_VUS', 500), 'history-query'),
        };
        common.thresholds.history_query_requests = [`count==${HISTORY_QUERY_ITERATIONS}`];
        return common;
    }
    throw new Error(`unsupported MODE '${mode}'`);
}

function sharedIterations(execFunction, iterations, vus, phase) {
    if (vus > iterations) {
        throw new Error(`${phase} VUs (${vus}) cannot exceed iterations (${iterations})`);
    }
    return {
        executor: 'shared-iterations',
        exec: execFunction,
        vus,
        iterations,
        maxDuration: '30m',
        gracefulStop: '0s',
        tags: { phase },
    };
}

function integerFromEnvironment(name, defaultValue) {
    const raw = __ENV[name];
    const value = raw === undefined || raw === '' ? defaultValue : Number(raw);
    if (!Number.isInteger(value) || value < 1) {
        throw new Error(`${name} must be a positive integer`);
    }
    return value;
}

function requiredProductId() {
    const value = Number(__ENV.SETUP_PRODUCT_ID);
    if (!Number.isInteger(value) || value < 1) {
        throw new Error('SETUP_PRODUCT_ID must be a positive integer');
    }
    return value;
}

function parseJson(response) {
    try {
        return response.json();
    }
    catch (error) {
        return null;
    }
}

function requireSetup(condition, message) {
    if (!condition) {
        fail(message);
    }
}

function hasJsonContentType(response) {
    const contentType = response.headers['Content-Type'] || '';
    const normalized = contentType.toLowerCase();
    return normalized.includes('application/json') || normalized.includes('+json');
}

function exactFields(body, expectedFields) {
    const actual = Object.keys(body).sort();
    const expected = [...expectedFields].sort();
    return actual.length === expected.length
        && actual.every((field, index) => field === expected[index]);
}

function request(name, phase) {
    return {
        headers: { Authorization: `Bearer ${ACCESS_TOKEN}` },
        tags: { name, phase },
    };
}

function jsonRequest(name, phase) {
    return {
        headers: {
            Authorization: `Bearer ${ACCESS_TOKEN}`,
            'Content-Type': 'application/json',
        },
        tags: { name, phase },
    };
}
