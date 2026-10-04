import { api, element, readState, writeState, selectedStore } from './ui-common.js';

const picker = document.querySelector('#store-id');
const message = document.querySelector('#message');
const products = document.querySelector('#product-list');
const cartItems = document.querySelector('#cart-items');
const submit = document.querySelector('#submit');
const retry = document.querySelector('#retry');
const fresh = document.querySelector('#new-cart');
const refresh = document.querySelector('#refresh');
let store, record, cart, catalog, busy = true, timer;

/** Returns this store's tab-local record key; another context never shares its cart implicitly. */
function storageKey() { return `shop:v1:cart:${store}`; }

/** Persists the next workflow state before exposing it in memory; write failure prevents outgoing mutations. */
function save(next) { writeState(storageKey(), next); record = next; }

/** Builds a scoped cart URL from persisted identifiers, without legacy global endpoints. */
function cartUrl() { return `/stores/${store}/carts/${record.cartId}`; }

/** Displays a text-only error or notice; supplier data never enters an HTML parser. */
function notice(text = '', error = false) { message.textContent = text; message.classList.toggle('error', error); }

/** Locks all mutation controls while a request runs or an acceptance result remains unknown. */
function controls() {
    const locked = busy || Boolean(record?.operation && !record?.submission) || cart?.state !== 'OPEN';
    for (const input of document.querySelectorAll('#product-list input, #product-list button, #cart-items button')) input.disabled = locked || input.dataset.unavailable === 'true';
    picker.disabled = busy;
    refresh.disabled = busy;
    fresh.disabled = busy || Boolean(record?.operation && !record?.submission);
    submit.disabled = locked || !cart?.items.length;
    retry.hidden = !record?.operation || Boolean(record?.submission);
    retry.disabled = busy;
}

/** Renders server prices and totals verbatim; the browser only selects integer quantities. */
function render() {
    products.replaceChildren(); cartItems.replaceChildren();
    for (const item of catalog?.items || []) {
        const card = element('article', '', 'product-item'); card.dataset.productId = item.productId;
        card.append(element('h3', item.shortName), element('p', item.description), element('p', `${item.unitPrice} ₽`, 'price'), element('p', `Доступно: ${item.availableQuantity}`, 'stock'));
        const form = element('form'); form.dataset.stock = item.stockItemId;
        const label = element('label', 'Количество в корзине'); const input = element('input');
        input.name = 'quantity'; input.type = 'number'; input.min = '1'; input.max = String(item.availableQuantity); input.required = true;
        input.value = String(cart?.items.find(matchingItem)?.quantity || 1);
        /** Matches the catalog position against this cart, never against another customer's contents. */
        function matchingItem(line) { return line.stockItemId === item.stockItemId; }
        const button = element('button', 'В корзину'); button.type = 'submit';
        input.dataset.unavailable = button.dataset.unavailable = String(item.availableQuantity === 0);
        label.append(input); form.append(label, button); card.append(form); products.append(card);
    }
    if (!catalog?.items.length) products.append(element('p', 'Товаров пока нет. Добавьте поставку через форму поставщика.'));
    for (const line of cart?.items || []) {
        const row = element('article', '', 'cart-item'); row.dataset.stock = line.stockItemId;
        row.append(element('h3', line.shortName), element('p', `${line.quantity} × ${line.unitPrice} ₽ = ${line.lineTotal} ₽`));
        const remove = element('button', 'Удалить', 'secondary'); remove.type = 'button'; remove.dataset.stock = line.stockItemId; row.append(remove); cartItems.append(row);
    }
    if (!cart?.items.length) cartItems.append(element('p', 'Корзина пуста.'));
    document.querySelector('#cart-total').textContent = `Итого: ${cart?.totalAmount || '0.00'} ₽`;
    document.querySelector('#cart-state').textContent = cart?.state === 'SUBMITTED' ? 'Корзина оформлена. Цены зафиксированы.' : 'Корзина открыта. Цена уточняется при оформлении.';
    renderOperation(); controls();
}

/** Separates committed acceptance from broker acknowledgement; neither state promises payment or fulfillment. */
function renderOperation() {
    const node = document.querySelector('#operation'); node.replaceChildren();
    if (record?.submission) {
        node.append(element('p', 'Заявка принята. Остаток списан.'), element('p', record.submission.publicationStatus === 'PUBLISHED' ? 'PUBLISHED — заявка передана в Kafka.' : 'PENDING — ожидается передача заявки.'), element('p', `Номер: ${record.submission.submissionId}`));
    } else if (record?.operation) node.append(element('p', 'Результат оформления неизвестен. Повтор использует сохранённый ключ и исходные параметры.'));
}

/** Refreshes current prices and cart composition; a closed cart is returned as its immutable accepted snapshot. */
async function load() {
    catalog = await api(`/stores/${store}/catalog`);
    cart = await api(cartUrl());
    render();
}

/** Creates an independent empty cart and saves its identity; accepted/uncertain operations are never silently replaced. */
async function createCart() {
    const created = await api(`/stores/${store}/carts`, 'POST');
    save({ cartId: created.cartId, operation: null, submission: null }); cart = created;
}

/** Opens a fixture store, restores its tab-local operation and replays an uncertain submit with the original version. */
async function openStore() {
    clearTimeout(timer); busy = true; controls(); notice();
    try {
        store = picker.value; writeState('shop:v1:store', store); record = readState(storageKey());
        cart = null; catalog = null; render();
        if (!record) await createCart();
        await load();
        if (record.operation && !record.submission) await sendSubmission();
        else if (record.submission) await pollSubmission();
    } catch (failure) { notice(failure.message, true); }
    finally { busy = false; controls(); }
}

/** Executes a versioned cart edit; definitive conflicts refresh the server view without reserving stock. */
async function changeCart(stock, quantity) {
    if (busy || record.operation || cart.state !== 'OPEN') return;
    busy = true; controls(); notice();
    try {
        cart = quantity === null
            ? await api(`${cartUrl()}/items/${stock}?expectedCartVersion=${cart.version}`, 'DELETE')
            : await api(`${cartUrl()}/items/${stock}`, 'PUT', { quantity, expectedCartVersion: cart.version });
        await load();
    } catch (failure) {
        notice(failure.message, true);
        try { await load(); } catch (refreshFailure) { notice(`${failure.message}\n${refreshFailure.message}`, true); }
    } finally { busy = false; controls(); }
}

/** Sends an absolute quantity selected in this card; form constraints and the server both validate available stock. */
async function onProduct(event) {
    event.preventDefault(); const form = event.target;
    if (!form.matches('form')) return;
    const quantity = Number(form.elements.quantity.value);
    if (!Number.isInteger(quantity) || quantity < 1 || quantity > 2147483647) return notice('Количество должно быть целым положительным числом.', true);
    await changeCart(form.dataset.stock, quantity);
}

/** Removes only the selected line of this cart through a versioned DELETE. */
async function onRemove(event) {
    const button = event.target.closest('button[data-stock]');
    if (button) await changeCart(button.dataset.stock, null);
}

/** Persists a new key and original expected version before the first submit; storage failure prevents the request. */
async function onSubmit() {
    if (busy || cart.state !== 'OPEN' || record.operation) return;
    busy = true; controls(); notice();
    try {
        save({ ...record, operation: { key: crypto.randomUUID(), expectedCartVersion: cart.version } });
        await sendSubmission();
    } catch (failure) { notice(failure.message, true); }
    finally { busy = false; controls(); renderOperation(); }
}

/** Repeats exactly the persisted submit; only definitive 4xx rejection permits discarding its unused key. */
async function sendSubmission() {
    try {
        const accepted = await api(`${cartUrl()}/submit`, 'POST', { expectedCartVersion: record.operation.expectedCartVersion }, { 'Idempotency-Key': record.operation.key });
        save({ ...record, submission: accepted });
    } catch (failure) {
        if (failure.status >= 400 && failure.status < 500) {
            save({ ...record, operation: null });
            await load();
        }
        throw failure;
    } finally { renderOperation(); }
    await load(); await pollSubmission();
}

/** Retries an unknown result without reading a newer version into the saved request or clearing the cart. */
async function onRetry() {
    if (busy || !record.operation || record.submission) return;
    busy = true; controls(); notice();
    try { await sendSubmission(); } catch (failure) { notice(failure.message, true); }
    finally { busy = false; controls(); }
}

/** Polls committed operation status with bounded requests; stale store/cart responses cannot replace another workflow. */
async function pollSubmission() {
    clearTimeout(timer);
    if (!record?.submission) return;
    const currentStore = store, currentCart = record.cartId, id = record.submission.submissionId;
    try {
        const status = await api(`/stores/${currentStore}/submissions/${id}`);
        if (store !== currentStore || record.cartId !== currentCart) return;
        save({ ...record, submission: status }); renderOperation();
    } catch (failure) { if (store === currentStore && record.cartId === currentCart) notice(failure.message, true); }
    if (store === currentStore && record.cartId === currentCart && record.submission.publicationStatus !== 'PUBLISHED') timer = setTimeout(pollSubmission, 1500);
}

/** Refreshes prices and composition on demand without creating a new cart or operation. */
async function onRefresh() {
    if (busy) return; busy = true; controls(); notice();
    try { await load(); } catch (failure) { notice(failure.message, true); }
    finally { busy = false; controls(); }
}

/** Starts another independent cart only after any previous acceptance outcome is known. */
async function onNewCart() {
    if (busy || (record?.operation && !record.submission)) return;
    busy = true; controls(); clearTimeout(timer); notice();
    try { await createCart(); await load(); } catch (failure) { notice(failure.message, true); }
    finally { busy = false; controls(); }
}

/** Binds delegated actions once and fails closed if session persistence is unavailable. */
async function start() {
    try {
        picker.value = selectedStore(); picker.addEventListener('change', openStore);
        products.addEventListener('submit', onProduct); cartItems.addEventListener('click', onRemove);
        submit.addEventListener('click', onSubmit); retry.addEventListener('click', onRetry);
        fresh.addEventListener('click', onNewCart); refresh.addEventListener('click', onRefresh);
        await openStore();
    } catch (failure) { notice(failure.message, true); controls(); }
}
start();
