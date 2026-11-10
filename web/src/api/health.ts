export interface HealthResponse {
  status: 'UP';
  stage: 'SKELETON';
  businessReady: false;
}

export function parseHealth(value: unknown): HealthResponse {
  if (
    typeof value !== 'object' ||
    value === null ||
    !('status' in value) ||
    value.status !== 'UP' ||
    !('stage' in value) ||
    value.stage !== 'SKELETON' ||
    !('businessReady' in value) ||
    value.businessReady !== false
  ) {
    throw new Error('健康响应不符合工程骨架契约');
  }
  return { status: 'UP', stage: 'SKELETON', businessReady: false };
}

export async function getHealth(
  request: typeof fetch = fetch,
): Promise<HealthResponse> {
  const response = await request('/api/v1/health', {
    method: 'GET',
    credentials: 'omit',
    cache: 'no-store',
    signal: AbortSignal.timeout(10000),
  });
  if (!response.ok) throw new Error(`健康检查失败（HTTP ${response.status}）`);
  return parseHealth(await response.json());
}
