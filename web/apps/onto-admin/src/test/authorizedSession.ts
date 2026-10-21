import { createPinia, setActivePinia } from 'pinia'
import { useSession } from '../stores/session'
/** 原业务组件测试显式声明已授权前提；生产会话仍默认无权限，拒绝路径由 IAM 专项覆盖。 */
export function setAuthorizedSession() {
  setActivePinia(createPinia())
  useSession().permissions = [
    ...[
      'space',
      'model',
      'rule',
      'operation',
      'knowledge',
      'datasource',
      'mapping',
      'ingest',
      'candidate',
      'test',
      'release',
      'grant',
    ].flatMap((group) => [`${group}.read`, `${group}.write`]),
    'space.create',
    'candidate.execute',
    'test.execute',
    'service.execute',
    'trace.read',
  ]
}
