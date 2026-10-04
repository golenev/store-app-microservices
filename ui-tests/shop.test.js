import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdir } from 'node:fs/promises';
import { chromium } from 'playwright';

const base = process.env.UI_BASE_URL || 'http://localhost:6789';
let browser;

/** Launches the isolated headless browser against running real services; no production API is stubbed for normal scenarios. */
before(async function launch() { await mkdir('test-results', { recursive: true }); browser = await chromium.launch(); });
/** Releases only the test browser and its isolated contexts. */
after(async function close() { await browser?.close(); });

/** Polls an async invariant with a bounded deadline; the caller must supply a state read, never a fixed correctness sleep. */
async function eventually(read, check, timeout = 30000) {
    const deadline = Date.now() + timeout; let value;
    while (Date.now() < deadline) {
        value = await read(); if (check(value)) return value;
        await new Promise(pause);
        /** Schedules the next state check without delaying application business logic. */
        function pause(resolve) { setTimeout(resolve, 150); }
    }
    assert.fail(`Timed out waiting for state: ${JSON.stringify(value)}`);
}

/** Reads the real catalog and resolves a unique test product, isolating scenarios from shared store fixtures. */
async function stock(product, store = 'S-1') {
    const response = await fetch(`${base}/stores/${store}/catalog`); assert.equal(response.status, 200);
    const catalog = await response.json();
    /** Selects only this scenario's UUID product. */
    return catalog.items.find(function match(item) { return item.productId === product; });
}

/** Supplies one product through the actual HTML/warehouse/Kafka/tariff/STORE chain and waits for committed inventory. */
async function supply(page, { product = `P-${randomUUID()}`, quantity = 5, price = '100.00', name = 'Учебный товар', description = 'Описание', store = 'S-1', expectedQuantity = quantity } = {}) {
    await page.goto(`${base}/send-to-kafka.html`);
    await page.getByRole('button', { name: 'Отправить поставку', exact: true }).waitFor();
    await eventually(readEnabled, Boolean);
    if (!(await page.getByRole('button', { name: 'Отправить поставку', exact: true }).isEnabled())) await page.getByRole('button', { name: 'Новая поставка', exact: true }).click();
    /** Waits for discovery/configuration to enable the form or restore a known supplier operation. */
    async function readEnabled() { return await page.getByRole('button', { name: 'Отправить поставку', exact: true }).isEnabled() || await page.getByRole('button', { name: 'Новая поставка', exact: true }).isEnabled(); }
    if (store !== 'S-1') await page.getByRole('combobox', { name: 'Магазин', exact: true }).selectOption(store);
    await page.getByLabel('Код продукта', { exact: true }).fill(product);
    await page.getByLabel('Название', { exact: true }).fill(name);
    await page.getByLabel('Описание', { exact: true }).fill(description);
    await page.getByLabel('Количество', { exact: true }).fill(String(quantity));
    await page.getByLabel('Закупочная цена, ₽', { exact: true }).fill(price);
    await page.getByRole('button', { name: 'Отправить поставку', exact: true }).click();
    await eventually(readStock, received);
    /** Reads only this supplied product from the real backend. */
    async function readStock() { return stock(product, store); }
    /** Waits for STORE consumption rather than treating warehouse publication as an inventory commit. */
    function received(item) { return item?.availableQuantity === expectedQuantity; }
    return product;
}

/** Opens this context's catalog and waits for its independent cart to become usable. */
async function catalog(page) {
    await page.goto(`${base}/products.html`); await page.getByRole('button', { name: 'Новая корзина', exact: true }).waitFor();
    await eventually(enabled, Boolean);
    /** Observes completed cart initialization through the visible UI. */
    async function enabled() { return page.getByRole('button', { name: 'Новая корзина', exact: true }).isEnabled(); }
}

/** Places an absolute quantity in this browser context's cart through the product card. */
async function add(page, product, quantity) {
    const card = page.locator(`[data-product-id="${product}"]`);
    await card.getByLabel('Количество в корзине').fill(String(quantity));
    await card.getByRole('button', { name: 'В корзину', exact: true }).click();
    await eventually(enabled, Boolean);
    /** Waits for the PUT response and server-rendered cart before continuing. */
    async function enabled() { return page.getByRole('button', { name: 'Оформить заявку', exact: true }).isEnabled(); }
}

/** Accepts through the UI and waits for broker acknowledgement without equating it to payment. */
async function checkout(page) {
    await page.getByRole('button', { name: 'Оформить заявку', exact: true }).click();
    await page.locator('#operation').getByText('PUBLISHED — заявка передана в Kafka.', { exact: true }).waitFor({ timeout: 30000 });
}

/** Given independent contexts holding the full stock, only the first checkout succeeds and the loser sees refreshed insufficiency. */
test('independent carts do not reserve and checkout checks stock again', { timeout: 90000 }, async function independentCarts() {
    const first = await browser.newContext(), second = await browser.newContext();
    try {
        const a = await first.newPage(), b = await second.newPage();
        const product = await supply(a, { quantity: 3 }); await catalog(a); await catalog(b);
        await add(a, product, 3); await add(b, product, 3);
        assert.equal((await stock(product)).availableQuantity, 3);
        await checkout(a); await b.getByRole('button', { name: 'Оформить заявку', exact: true }).click();
        await b.getByRole('alert').getByText(/Товара уже недостаточно/).waitFor();
        assert.equal((await stock(product)).availableQuantity, 0);
        assert.match(await b.locator('#cart-items').innerText(), /3 × 120.00/);
    } finally { await first.close(); await second.close(); }
});

/** Given an open cart and newer deliveries, current prices change until checkout; its accepted snapshot then stays immutable. */
test('replenishment updates open cart price but preserves accepted snapshot', { timeout: 90000 }, async function priceSnapshot() {
    const context = await browser.newContext(), supplier = await browser.newContext();
    try {
        const page = await context.newPage(), source = await supplier.newPage();
        const product = await supply(source); await catalog(page); await add(page, product, 2);
        await supply(source, { product, quantity: 5, price: '200.00', expectedQuantity: 10 });
        await page.getByRole('button', { name: 'Обновить', exact: true }).click();
        await page.locator('#cart-total').getByText('Итого: 480.00 ₽', { exact: true }).waitFor();
        await checkout(page);
        await supply(source, { product, quantity: 1, price: '300.00', expectedQuantity: 9 });
        await page.getByRole('button', { name: 'Обновить', exact: true }).click();
        assert.equal(await page.locator('#cart-total').innerText(), 'Итого: 480.00 ₽');
    } finally { await context.close(); await supplier.close(); }
});

/** Given a committed submit whose reply is lost, reload replays the same key/original version despite the cart now being closed. */
test('lost acceptance reply reuses persisted request after reload', { timeout: 90000 }, async function lostReply() {
    const context = await browser.newContext();
    try {
        const page = await context.newPage(), requests = [];
        const product = await supply(page); await catalog(page); await add(page, product, 2);
        await page.route('**/submit', loseCommittedReply);
        /** Sends to the real backend, records the original identity, then drops only its browser response after 202. */
        async function loseCommittedReply(route) {
            const request = route.request(); requests.push({ key: request.headers()['idempotency-key'], body: request.postData() });
            const response = await route.fetch(); assert.equal(response.status(), 202); await route.abort('failed');
        }
        await page.getByRole('button', { name: 'Оформить заявку', exact: true }).click();
        await page.locator('#operation').getByText(/Результат оформления неизвестен/).waitFor();
        await page.unroute('**/submit', loseCommittedReply); await page.route('**/submit', replay);
        /** Records the real reloaded retry and passes it through unchanged. */
        async function replay(route) { const r = route.request(); requests.push({ key: r.headers()['idempotency-key'], body: r.postData() }); await route.continue(); }
        await page.reload(); await page.locator('#operation').getByText('PUBLISHED — заявка передана в Kafka.', { exact: true }).waitFor({ timeout: 30000 });
        assert.equal(requests.length, 2); assert.deepEqual(requests[1], requests[0]); assert.equal((await stock(product)).availableQuantity, 3);
        await page.screenshot({ path: 'test-results/accepted-reload.png', fullPage: true });
    } finally { await context.close(); }
});

/** Given a commit followed by a proxy 503, the browser keeps the key and retries rather than producing another expense. */
test('503 after commit keeps request identity', { timeout: 90000 }, async function ambiguous503() {
    const context = await browser.newContext();
    try {
        const page = await context.newPage(); const product = await supply(page); await catalog(page); await add(page, product, 1);
        let original, repeated;
        await page.route('**/submit', proxyFailure);
        /** Commits the real request and substitutes an infrastructure error only for its response. */
        async function proxyFailure(route) { original = route.request().headers()['idempotency-key']; assert.equal((await route.fetch()).status(), 202); await route.fulfill({ status: 503, contentType: 'application/json', body: '{"code":"DEPENDENCY_UNAVAILABLE"}' }); }
        await page.getByRole('button', { name: 'Оформить заявку', exact: true }).click(); await page.getByRole('button', { name: 'Повторить оформление', exact: true }).waitFor();
        await page.unroute('**/submit', proxyFailure); await page.route('**/submit', retry);
        /** Captures retry identity while allowing the real idempotent backend response. */
        async function retry(route) { repeated = route.request().headers()['idempotency-key']; await route.continue(); }
        await page.getByRole('button', { name: 'Повторить оформление', exact: true }).click();
        await page.locator('#operation').getByText('PUBLISHED — заявка передана в Kafka.', { exact: true }).waitFor({ timeout: 30000 });
        assert.equal(repeated, original); assert.equal((await stock(product)).availableQuantity, 4);
    } finally { await context.close(); }
});

/** Given a request lost before reaching STORE, reload preserves its identity and produces exactly one accepted expense. */
test('lost request before commit also reuses the original key', { timeout: 90000 }, async function lostRequest() {
    const context = await browser.newContext();
    try {
        const page = await context.newPage(), requests = [];
        const product = await supply(page); await catalog(page); await add(page, product, 1);
        await page.route('**/submit', dropRequest);
        /** Records the attempted operation and drops it before the real backend is reached. */
        async function dropRequest(route) { requests.push({ key: route.request().headers()['idempotency-key'], body: route.request().postData() }); await route.abort('failed'); }
        await page.getByRole('button', { name: 'Оформить заявку', exact: true }).click();
        await page.locator('#operation').getByText(/Результат оформления неизвестен/).waitFor(); assert.equal((await stock(product)).availableQuantity, 5);
        await page.unroute('**/submit', dropRequest); await page.route('**/submit', retry);
        /** Captures the reloaded request and forwards it to the real acceptance transaction. */
        async function retry(route) { requests.push({ key: route.request().headers()['idempotency-key'], body: route.request().postData() }); await route.continue(); }
        await page.reload(); await page.locator('#operation').getByText('PUBLISHED — заявка передана в Kafka.', { exact: true }).waitFor({ timeout: 30000 });
        assert.deepEqual(requests[1], requests[0]); assert.equal((await stock(product)).availableQuantity, 4);
    } finally { await context.close(); }
});

/** Given an external versioned edit, stale submit is rejected and the UI reloads the actual composition before a new operation. */
test('version conflict refreshes cart before next acceptance', { timeout: 90000 }, async function versionConflict() {
    const context = await browser.newContext();
    try {
        const page = await context.newPage(); let cartUrl;
        /** Observes the real PUT endpoint to identify this scenario's cart without reading browser storage. */
        function capture(request) { if (request.method() === 'PUT') cartUrl = request.url().replace(/\/items\/.+$/, ''); }
        page.on('request', capture); const product = await supply(page); await catalog(page); await add(page, product, 1);
        const item = await stock(product);
        const response = await fetch(`${cartUrl}/items/${item.stockItemId}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: '{"quantity":2,"expectedCartVersion":1}' }); assert.equal(response.status, 200);
        await page.getByRole('button', { name: 'Оформить заявку', exact: true }).click();
        await page.getByRole('alert').getByText(/Корзина изменилась/).waitFor();
        assert.equal(await page.locator('#cart-total').innerText(), 'Итого: 240.00 ₽'); assert.equal((await stock(product)).availableQuantity, 5);
        await checkout(page); assert.equal((await stock(product)).availableQuantity, 3);
    } finally { await context.close(); }
});

/** Given untrusted text and prices above Number precision, rendering remains literal and server totals remain exact. */
test('untrusted product text and large decimal prices render safely', { timeout: 90000 }, async function safeRendering() {
    const context = await browser.newContext();
    try {
        const page = await context.newPage();
        const name = '<img src=x onerror="window.compromised=true">', description = '<script>window.compromised=true</script>';
        const product = await supply(page, { price: '9007199254740993.00', name, description }); await catalog(page);
        const card = page.locator(`[data-product-id="${product}"]`);
        assert.equal(await card.locator('h3').innerText(), name); assert.equal(await card.locator('img,script').count(), 0);
        await add(page, product, 2); assert.equal(await page.locator('#cart-total').innerText(), 'Итого: 23418718062326581.80 ₽');
        assert.equal(await page.evaluate(noInjection), undefined);
        /** Observes the test-only injection sentinel without mutating application state. */
        function noInjection() { return window.compromised; }
    } finally { await context.close(); }
});

/** Given a lost warehouse publication reply, reload reuses the complete envelope and STORE receives the quantity only once. */
test('supplier reload preserves event and delivery identifiers', { timeout: 90000 }, async function supplierRetry() {
    const context = await browser.newContext();
    try {
        const page = await context.newPage(), bodies = [];
        await page.route('**/technical/deliveries', loseReply);
        /** Drops the response only after Kafka acknowledges the real original event. */
        async function loseReply(route) { bodies.push(route.request().postData()); assert.equal((await route.fetch()).status(), 202); await route.abort('failed'); }
        const product = await supply(page, { quantity: 7 });
        await page.locator('#delivery-result').getByText(/Результат отправки неизвестен/).waitFor();
        await page.unroute('**/technical/deliveries', loseReply); await page.route('**/technical/deliveries', replay);
        /** Captures the repeated immutable event and lets actual warehouse deduplication handle it. */
        async function replay(route) { bodies.push(route.request().postData()); await route.continue(); }
        await page.reload(); await page.locator('#delivery-result').getByText(/POSTED — цена рассчитана/).waitFor({ timeout: 30000 });
        assert.equal(bodies.length, 2); assert.equal(bodies[1], bodies[0]); assert.equal((await stock(product)).availableQuantity, 7);
    } finally { await context.close(); }
});

/** Given S-1/S-2 selection and mobile navigation, carts stay store-scoped and all preserved pages avoid removed auth/global APIs. */
test('store isolation, preserved pages and mobile layout', { timeout: 90000 }, async function storesAndPages() {
    const context = await browser.newContext({ viewport: { width: 390, height: 844 } });
    try {
        const page = await context.newPage(), requests = [];
        /** Records network contracts without intercepting responses. */
        function observe(request) { requests.push({ url: new URL(request.url()).pathname, headers: request.headers() }); }
        page.on('request', observe);
        const product = await supply(page); await catalog(page); await add(page, product, 1);
        await page.getByRole('combobox', { name: 'Магазин', exact: true }).selectOption('S-2');
        await page.locator('#cart-items').getByText('Корзина пуста.', { exact: true }).waitFor();
        await page.getByRole('combobox', { name: 'Магазин', exact: true }).selectOption('S-1');
        await page.locator('#cart-items').getByText('1 × 120.00 ₽ = 120.00 ₽', { exact: true }).waitFor();
        assert.ok(await page.evaluate(fitsScreen));
        /** Reads the rendered document width to detect horizontal overflow on a narrow viewport. */
        function fitsScreen() { return document.documentElement.scrollWidth <= window.innerWidth; }
        await page.screenshot({ path: 'test-results/mobile-cart.png', fullPage: true });
        for (const path of ['/index.html', '/login.html']) { await page.goto(`${base}${path}`); assert.equal(await page.locator('input[type=password]').count(), 0); }
        for (const request of requests) { assert.ok(!request.headers.authorization); assert.ok(!/^\/(cart|order|products|login|auth)(\/|$)/.test(request.url)); }
    } finally { await context.close(); }
});
