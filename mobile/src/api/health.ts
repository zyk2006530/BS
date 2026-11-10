export interface HealthResponse {
  status: 'UP';
  stage: 'SKELETON';
  businessReady: false;
}
export interface HealthHttpResponse {
  statusCode: number;
  data: unknown;
}
export type HealthTransport = (url: string) => Promise<HealthHttpResponse>;

export function healthEndpoint(base: string | undefined): string {
  const value = base?.trim() ?? '';
  if (!value)
    throw new Error(
      '尚未配置 VITE_API_BASE_URL。请配置已备案且加入微信 request 合法域名的 HTTPS 域名后重新构建。',
    );
  if (
    !/^https:\/\/(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}\/?$/i.test(
      value,
    )
  ) {
    throw new Error(
      'VITE_API_BASE_URL 必须是 HTTPS 域名（不含路径、端口、账号或查询参数），不支持 localhost/IP。',
    );
  }
  return `${value.replace(/\/$/, '')}/api/v1/health`;
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
export const requestHealth: HealthTransport = (url) =>
  new Promise((resolve, reject) => {
    uni.request({
      url,
      method: 'GET',
      timeout: 10000,
      success: (response) =>
        resolve({ statusCode: response.statusCode, data: response.data }),
      fail: () =>
        reject(
          new Error(
            '健康检查网络失败，请核实 HTTPS、微信合法域名及网络连接；不要关闭域名校验。',
          ),
        ),
    });
  });
export async function getHealth(
  base: string | undefined,
  transport: HealthTransport = requestHealth,
): Promise<HealthResponse> {
  const response = await transport(healthEndpoint(base));
  if (response.statusCode !== 200)
    throw new Error(`健康检查失败（HTTP ${response.statusCode}）`);
  return parseHealth(response.data);
}
