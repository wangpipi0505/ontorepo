export * from './types'
export * from './supply'
export * from './data'
export * from './release'
export * from './iam'
export type { components, paths } from './generated'

/** 所有 HTTP、信封与网络失败共用此错误，页面据此提供刷新或重新登录。 */
export class ApiError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly status: number,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}
export interface Envelope<T> {
  code: string
  message?: string
  data: T
  meta?: unknown
}
export class ApiClient {
  constructor(
    private readonly token: () => string | null,
    private readonly expired: () => void,
    private readonly fetcher: typeof fetch = fetch,
  ) {}
  /** 令牌仅在请求时读取内存；不接触 localStorage/sessionStorage。 */
  async request<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
    return (await this.requestEnvelope<T>(path, method, body)).data
  }
  /** 内部候选调用需保留 preview 与摘要元数据，业务结果仍独立置于 data。 */
  async requestEnvelope<T>(path: string, method = 'GET', body?: unknown): Promise<Envelope<T>> {
    const headers = new Headers({ Accept: 'application/json' })
    const token = this.token()
    if (token) headers.set('Authorization', `Bearer ${token}`)
    // multipart boundary 由浏览器生成；否则受管文件会被误序列化为空 JSON。
    const multipart = body instanceof FormData
    if (body !== undefined && !multipart) headers.set('Content-Type', 'application/json')
    let response: Response
    try {
      // 原生 fetch 按全局函数调用，避免实例代理改变浏览器要求的调用上下文。
      const fetcher = this.fetcher
      response = await fetcher(`/onto${path}`, {
        method,
        headers,
        body: body === undefined ? undefined : multipart ? body : JSON.stringify(body),
      })
    } catch {
      throw new ApiError('NETWORK_ERROR', '无法连接服务，请检查网络后重试。', 0)
    }
    // 旧会话的迟到响应只能使旧请求失败，不能注销用户重新登录后的新会话。
    if (response.status === 401 && token === this.token()) this.expired()
    let envelope: Envelope<T>
    try {
      const parsed: unknown = await response.json()
      if (
        typeof parsed !== 'object' ||
        parsed === null ||
        !('code' in parsed) ||
        typeof parsed.code !== 'string' ||
        ('message' in parsed && typeof parsed.message !== 'string') ||
        (parsed.code !== 'OK' && !('message' in parsed)) ||
        !('data' in parsed)
      )
        throw new Error('Invalid envelope')
      envelope = parsed as Envelope<T>
    } catch {
      throw new ApiError('INVALID_RESPONSE', '服务响应无法读取，请稍后重试。', response.status)
    }
    if (!response.ok || envelope.code !== 'OK')
      throw new ApiError(
        envelope.code || 'HTTP_ERROR',
        envelope.message ||
          (response.status === 403 ? '当前账号没有此操作权限。' : '操作失败，请重试。'),
        response.status,
      )
    return envelope
  }
  get<T>(path: string) {
    return this.request<T>(path)
  }
  post<T>(path: string, body: unknown) {
    return this.request<T>(path, 'POST', body)
  }
  put<T>(path: string, body: unknown) {
    return this.request<T>(path, 'PUT', body)
  }
  delete<T>(path: string) {
    return this.request<T>(path, 'DELETE')
  }
}
