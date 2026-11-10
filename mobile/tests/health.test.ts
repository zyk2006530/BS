import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  getHealth,
  healthEndpoint,
  parseHealth,
  requestHealth,
  type HealthTransport,
} from '../src/api/health.ts';
const body = { status: 'UP', stage: 'SKELETON', businessReady: false };

test('missing domain stops before network and explains setup', async () => {
  let calls = 0;
  const request: HealthTransport = async () => {
    calls++;
    return { statusCode: 200, data: body };
  };
  await assert.rejects(getHealth('', request), /尚未配置/);
  assert.equal(calls, 0);
});
for (const value of [
  'http://api.example.com',
  'https://localhost',
  'https://127.0.0.1',
  'https://api.example.com/path',
  'https://api.example.com:443',
  'https://u:p@api.example.com',
  'https://api.example.com?x=1',
]) {
  test(`rejects unsafe URL ${value}`, () => {
    assert.throws(() => healthEndpoint(value), /HTTPS/);
  });
}
test('normalizes trailing slash and calls only health', async () => {
  const urls: string[] = [];
  const request: HealthTransport = async (url) => {
    urls.push(url);
    return { statusCode: 200, data: body };
  };
  assert.deepEqual(await getHealth('https://api.example.com/', request), body);
  assert.deepEqual(urls, ['https://api.example.com/api/v1/health']);
});
test('rejects non-200 response', async () => {
  const request: HealthTransport = async () => ({
    statusCode: 503,
    data: body,
  });
  await assert.rejects(getHealth('https://api.example.com', request), /503/);
});
for (const value of [
  null,
  {},
  { ...body, businessReady: true },
  { ...body, stage: 'READY' },
  { ...body, status: 'DOWN' },
]) {
  test(`rejects invalid skeleton ${JSON.stringify(value)}`, () => {
    assert.throws(() => parseHealth(value), /骨架契约/);
  });
}
test('uni.request uses GET with timeout and no auth headers', async () => {
  let captured: UniApp.RequestOptions | undefined;
  const original = Object.getOwnPropertyDescriptor(globalThis, 'uni');
  Object.defineProperty(globalThis, 'uni', {
    configurable: true,
    value: {
      request: (options: UniApp.RequestOptions) => {
        captured = options;
        options.success?.({
          statusCode: 200,
          data: body,
          header: {},
          cookies: [],
        });
      },
    },
  });
  try {
    assert.deepEqual(
      await requestHealth('https://api.example.com/api/v1/health'),
      { statusCode: 200, data: body },
    );
    assert.equal(captured?.method, 'GET');
    assert.equal(captured?.timeout, 10000);
    assert.equal(Object.hasOwn(captured ?? {}, 'header'), false);
  } finally {
    if (original) Object.defineProperty(globalThis, 'uni', original);
    else Reflect.deleteProperty(globalThis, 'uni');
  }
});
test('network failure explains HTTPS and domain validation', async () => {
  const original = Object.getOwnPropertyDescriptor(globalThis, 'uni');
  Object.defineProperty(globalThis, 'uni', {
    configurable: true,
    value: {
      request: (options: UniApp.RequestOptions) =>
        options.fail?.({ errMsg: 'offline' }),
    },
  });
  try {
    await assert.rejects(
      requestHealth('https://api.example.com/api/v1/health'),
      /不要关闭域名校验/,
    );
  } finally {
    if (original) Object.defineProperty(globalThis, 'uni', original);
    else Reflect.deleteProperty(globalThis, 'uni');
  }
});
