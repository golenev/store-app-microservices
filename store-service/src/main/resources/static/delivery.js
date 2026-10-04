import { api, element, purchasePrice, readState, writeState, selectedStore } from './ui-common.js';

const form = document.querySelector('#delivery-form');
const picker = document.querySelector('#store-id');
const message = document.querySelector('#message');
const result = document.querySelector('#delivery-result');
let store, record, warehouse, busy = true, timer;

/** Returns the supplier workflow key for this store in this tab. */
function storageKey() { return `shop:v1:delivery:${store}`; }

/** Saves the immutable event before publication so response loss cannot generate another delivery. */
function save(next) { writeState(storageKey(), next); record = next; }

/** Displays only plain text, including rejected supplier descriptions and dependency errors. */
function notice(text = '', error = false) { message.textContent = text; message.classList.toggle('error', error); }

/** Prevents editing an uncertain event; an acknowledged delivery may be followed by a new one. */
function controls() {
    for (const input of form.querySelectorAll('input, textarea, select, button')) input.disabled = busy || Boolean(record);
    picker.disabled = busy;
    document.querySelector('#retry').hidden = !record || Boolean(record.published);
    document.querySelector('#retry').disabled = busy;
    document.querySelector('#new-delivery').disabled = busy || Boolean(record && !record.published);
}

/** Shows publication separately from receiving; POSTED does not guarantee STORE has consumed GoodsPosted yet. */
function render() {
    result.replaceChildren();
    if (!record) return;
    result.append(element('p', `Поставка: ${record.event.payload.deliveryId}`));
    if (!record.published) result.append(element('p', 'Результат отправки неизвестен. Повтор сохранит эту поставку.'));
    else result.append(element('p', 'PUBLISHED — поставка передана в Kafka.'));
    const view = record.view;
    if (!view) { if (record.published) result.append(element('p', 'Ожидается приёмка.')); return; }
    if (view.state === 'WAITING_PRICING') result.append(element('p', 'WAITING_PRICING — ожидается расчёт цены. Повтор выполняется автоматически.'));
    if (view.state === 'POSTED') result.append(element('p', 'POSTED — цена рассчитана. Зачисление в магазин выполняется автоматически; обновите каталог.'));
    if (view.state === 'REJECTED') result.append(element('p', 'REJECTED — поставка отклонена.'));
    if (view.lastError) result.append(element('p', typeof view.lastError === 'string' ? view.lastError : JSON.stringify(view.lastError)));
    for (const item of view.items || []) result.append(element('p', `${item.shortName}: ${item.quantity} шт., продажная цена ${item.salePrice} ₽`));
}

/** Restores the selected store's exact envelope and retries only an unknown publication result. */
async function openStore() {
    clearTimeout(timer); busy = true; controls(); notice();
    try {
        store = picker.value; writeState('shop:v1:store', store); record = readState(storageKey()); render();
        if (record && !record.published) await publish();
        else if (record) await poll();
    } catch (failure) { notice(failure.message, true); }
    finally { busy = false; controls(); render(); }
}

/** Validates supplier input and creates one immutable DeliveryReceived envelope; prices are normalized without floating point. */
function envelope() {
    const values = new FormData(form);
    const quantity = Number(values.get('quantity'));
    const productId = values.get('productId').trim(), shortName = values.get('shortName').trim(), description = values.get('description');
    if (!/^[A-Za-z0-9._:-]{1,64}$/.test(productId)) throw new Error('Код продукта: 1–64 латинских символа, цифры, . _ : -');
    if (!Number.isInteger(quantity) || quantity < 1 || quantity > 2147483647) throw new Error('Количество: целое число от 1 до 2147483647.');
    if ([...shortName].length < 1 || [...shortName].length > 255 || [...description].length > 2000) throw new Error('Название: 1–255 символов. Описание: до 2000 символов.');
    return { eventId: crypto.randomUUID(), eventType: 'DeliveryReceived', schemaVersion: 1, occurredAt: new Date().toISOString(), storeId: store,
        payload: { deliveryId: `D-${crypto.randomUUID()}`, items: [{ lineId: 'L-1', productId, productType: values.get('type'), shortName, description,
            quantity, purchasePrice: purchasePrice(values.get('purchasePrice')), currency: 'RUB' }] } };
}

/** Persists a new delivery before POST; uncertain failures keep its eventId, deliveryId, timestamp and payload intact. */
async function onSend(event) {
    event.preventDefault(); if (busy || record) return;
    notice();
    try { const eventData = envelope(); save({ event: eventData, published: null, view: null }); busy = true; controls(); await publish(); }
    catch (failure) { notice(failure.message, true); }
    finally { busy = false; controls(); render(); }
}

/** Publishes the exact saved envelope; definitive rejection releases the form, whereas network/5xx keeps the retry identity. */
async function publish() {
    try {
        const published = await api(`${warehouse}/technical/deliveries`, 'POST', record.event);
        save({ ...record, published }); await poll();
    } catch (failure) {
        if (!record.published && failure.status >= 400 && failure.status < 500) save(null);
        throw failure;
    }
}

/** Retries only the unknown publication using the saved event rather than current form inputs. */
async function onRetry() {
    if (busy || !record || record.published) return;
    busy = true; controls(); notice();
    try { await publish(); } catch (failure) { notice(failure.message, true); }
    finally { busy = false; controls(); render(); }
}

/** Polls receiving state with bounded requests; initial 404 is expected before Kafka ingress has committed. */
async function poll() {
    clearTimeout(timer); if (!record?.published) return;
    const currentStore = store, id = record.event.payload.deliveryId;
    try {
        const view = await api(`${warehouse}/stores/${currentStore}/deliveries/${id}`);
        if (store !== currentStore || record?.event.payload.deliveryId !== id) return;
        save({ ...record, view }); render();
    } catch (failure) {
        if (store !== currentStore || record?.event.payload.deliveryId !== id) return;
        if (failure.status !== 404) notice(failure.message, true);
    }
    if (store === currentStore && record?.event.payload.deliveryId === id && !['POSTED', 'REJECTED'].includes(record.view?.state)) timer = setTimeout(poll, 1500);
}

/** Allows the next supplier event only when the previous publication outcome is known. */
function onNew() {
    if (busy || (record && !record.published)) return;
    try { clearTimeout(timer); save(null); notice(); render(); controls(); }
    catch (failure) { notice(failure.message, true); }
}

/** Obtains the public warehouse URL from STORE and binds this educational supplier UI without credentials. */
async function start() {
    try {
        picker.value = selectedStore(); controls();
        warehouse = (await api('/ui/config')).warehouseBaseUrl;
        form.addEventListener('submit', onSend); picker.addEventListener('change', openStore);
        document.querySelector('#retry').addEventListener('click', onRetry); document.querySelector('#new-delivery').addEventListener('click', onNew);
        await openStore();
    } catch (failure) { notice(failure.message, true); controls(); }
}
start();
