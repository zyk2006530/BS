import { describe, expect, it, vi } from 'vitest';
import { getHealth, parseHealth } from '../src/api/health';
const body = { status: 'UP', stage: 'SKELETON', businessReady: false };
describe('desktop health contract', () => {
  it('accepts only the non-business skeleton', () => {
    expect(parseHealth(body)).toEqual(body);
  });
  it.each([
    null,
    {},
    { ...body, businessReady: true },
    { ...body, stage: 'BUSINESS' },
    { ...body, status: 'DOWN' },
  ])('rejects invalid response %j', (value) => {
    expect(() => parseHealth(value)).toThrow('骨架契约');
  });
  it('only issues GET to the same-origin health endpoint', async () => {
    const request = vi
      .fn<typeof fetch>()
      .mockResolvedValue(new Response(JSON.stringify(body), { status: 200 }));
    expect(await getHealth(request)).toEqual(body);
    expect(request).toHaveBeenCalledOnce();
    expect(request).toHaveBeenCalledWith(
      '/api/v1/health',
      expect.objectContaining({
        method: 'GET',
        credentials: 'omit',
        cache: 'no-store',
      }),
    );
  });
  it('rejects HTTP failures', async () => {
    const request = vi
      .fn<typeof fetch>()
      .mockResolvedValue(new Response('', { status: 503 }));
    await expect(getHealth(request)).rejects.toThrow('503');
  });
  it('propagates network failures without fake UP', async () => {
    const request = vi
      .fn<typeof fetch>()
      .mockRejectedValue(new Error('offline'));
    await expect(getHealth(request)).rejects.toThrow('offline');
  });
});
