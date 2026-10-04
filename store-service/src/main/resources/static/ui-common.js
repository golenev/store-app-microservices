/** Named HTTP failure; status zero/5xx represents an uncertain mutation outcome and requires reuse of persisted identifiers. */
export class ApiError extends Error {
    /** Receives a safe display message and protocol status/code without leaking response bodies or credentials. */
    constructor(message, status = 0, code = 'NETWORK_ERROR') {
        super(message); this.status = status; this.code = code;
    }
}

const messages = {
    INSUFFICIENT_STOCK: 'Товара уже недостаточно. Остаток обновлён; уменьшите количество в корзине.',
    CART_VERSION_CONFLICT: 'Корзина изменилась. Показана её актуальная версия; проверьте состав перед повтором.',
    CART_ALREADY_SUBMITTED: 'Эта корзина уже оформлена. Откройте новую корзину для следующей покупки.',
    IDEMPOTENCY_KEY_REUSED: 'Ключ операции связан с другим запросом. Проверьте состояние заявки.',
    VALIDATION_ERROR: 'Запрос отклонён: проверьте данные и допустимые значения.',
    NOT_FOUND: 'Данные не найдены в выбранном магазине.',
    DEPENDENCY_UNAVAILABLE: 'Сервис временно недоступен. Результат отправки может быть неизвестен.'
};

/** Sends bounded JSON requests without auth/cookies, preserving numeric money strings and rejecting unreadable successful responses as uncertain. */
export async function api(url, method = 'GET', body, extraHeaders = {}) {
    const controller = new AbortController();
    const timer = setTimeout(abortRequest, 15000);
    /** Aborts only this request; cancellation does not imply that a server-side mutation rolled back. */
    function abortRequest() { controller.abort(); }
    try {
        const response = await fetch(url, {
            method, signal: controller.signal, credentials: 'omit', cache: 'no-store',
            headers: { ...(body === undefined ? {} : { 'Content-Type': 'application/json' }), ...extraHeaders },
            ...(body === undefined ? {} : { body: JSON.stringify(body) })
        });
        const data = await response.json();
        if (!response.ok) throw new ApiError(messages[data.code] || 'Сервис отклонил запрос.', response.status, data.code);
        return data;
    } catch (failure) {
        if (failure instanceof ApiError) throw failure;
        throw new ApiError('Не удалось получить ответ сервиса. Результат отправки неизвестен.');
    } finally { clearTimeout(timer); }
}

/** Reads only this tab's scoped workflow state; unreadable or unavailable storage fails closed instead of discarding an uncertain operation. */
export function readState(key) {
    try {
        const raw = sessionStorage.getItem(key);
        return raw === null ? null : JSON.parse(raw);
    } catch (failure) { throw new Error('Не удалось прочитать сохранённую операцию. Хранилище вкладки должно быть доступно.'); }
}

/** Persists identifiers before network mutations; write failure must prevent creation of an unrepeatable request. */
export function writeState(key, value) {
    try { sessionStorage.setItem(key, JSON.stringify(value)); }
    catch (failure) { throw new Error('Не удалось сохранить операцию во вкладке. Отправка остановлена, чтобы сохранить возможность повтора.'); }
}

/** Creates text-only DOM nodes; supplier names/descriptions never become executable HTML. */
export function element(tag, text = '', className = '') {
    const node = document.createElement(tag); node.textContent = text;
    if (className) node.className = className;
    return node;
}

/** Selects a fixture store from this tab, falling back to S-1; no cart identity is shared through localStorage. */
export function selectedStore() {
    const saved = readState('shop:v1:store');
    return saved === 'S-2' ? 'S-2' : 'S-1';
}

/** Normalizes a decimal input to two digits using strings only; money never passes through Number/parseFloat. */
export function purchasePrice(value) {
    const match = /^(0|[1-9][0-9]{0,25})(?:[.,]([0-9]{1,2}))?$/.exec(value.trim());
    if (!match) throw new Error('Закупочная цена: положительное число, до 26 цифр и двух знаков после запятой.');
    const result = `${match[1]}.${(match[2] || '').padEnd(2, '0')}`;
    if (result === '0.00') throw new Error('Закупочная цена должна быть больше нуля.');
    return result;
}
