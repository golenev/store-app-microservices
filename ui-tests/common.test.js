import { test } from 'node:test';
import assert from 'node:assert/strict';
import { purchasePrice } from '../store-service/src/main/resources/static/ui-common.js';

/** Given decimal strings, normalization preserves huge values exactly and pads only the fractional digits. */
test('money stays a decimal string', function exactMoney() {
    assert.equal(purchasePrice('9007199254740993.00'), '9007199254740993.00');
    assert.equal(purchasePrice(' 100,5 '), '100.50');
    assert.equal(purchasePrice('0.01'), '0.01');
});

/** Given zero, exponent, negative or excessive precision, normalization rejects input before any supplier publication. */
test('rejects invalid money', function invalidMoney() {
    for (const value of ['0', '0.00', '-1', '1e3', '1.001', '01', '100000000000000000000000000']) {
        /** Exercises the invalid string from this equivalence class without converting it to a floating-point value. */
        function invalid() { purchasePrice(value); }
        assert.throws(invalid, /цена/i);
    }
});
