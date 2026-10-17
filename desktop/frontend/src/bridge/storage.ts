import { exactFields, isObject } from './contracts'

const connections = ['NOT_CONFIGURED', 'CONFIGURATION_INVALID', 'CONNECTED', 'CONNECTION_FAILED', 'AUTHENTICATION_FAILED', 'ACCESS_DENIED', 'ROLE_REJECTED'] as const
const schemas = ['NOT_CHECKED', 'INITIALIZATION_REQUIRED', 'INCOMPATIBLE', 'ACCESS_DENIED'] as const
export const storageRequirements = ['WINDOWS_PROFILE_APPROVAL_REQUIRED', 'VOLUME_PROTECTION_REQUIRED', 'FINANCE_IDENTITY_CONFIRMATION_REQUIRED', 'CROSS_DEVICE_VERIFICATION_REQUIRED'] as const
export interface StorageStatus {
  checkedAt: string; backend: 'SQLITE' | 'POSTGRESQL' | 'UNSUPPORTED'; postgresqlConfigured: boolean
  connection: typeof connections[number]; workspaceSchema: typeof schemas[number] | 'V1_READY' | 'V1_V2_READY'
  knowledgeSchema: typeof schemas[number] | 'READY'; financeEnabled: boolean
  financeSchema: typeof schemas[number] | 'DISABLED' | 'NOT_CONFIGURED' | 'READY' | 'CONNECTION_FAILED' | 'AUTHENTICATION_FAILED' | 'ROLE_REJECTED'
  migration: 'EXPLICIT_APPROVAL_REQUIRED'; encryptedBackup: 'MAINTENANCE_ONLY'; restore: 'BLOCKED_SECURITY_REQUIREMENTS'
  securityRequirements: typeof storageRequirements[number][]
  errorCode: 'NONE' | 'CONFIGURATION_INCOMPLETE' | 'CONFIGURATION_INVALID' | 'CONNECTION_FAILED' | 'AUTHENTICATION_FAILED' | 'PERMISSION_DENIED' | 'SCHEMA_INCOMPATIBLE' | 'ROLE_REJECTED'
}
export function isStorageStatus(value: unknown): value is StorageStatus {
  return isObject(value) && exactFields(value, ['checkedAt', 'backend', 'postgresqlConfigured', 'connection', 'workspaceSchema', 'knowledgeSchema', 'financeEnabled', 'financeSchema', 'migration', 'encryptedBackup', 'restore', 'securityRequirements', 'errorCode'])
    && typeof value.checkedAt === 'string' && value.checkedAt.length <= 40 && Number.isFinite(Date.parse(value.checkedAt))
    && ['SQLITE', 'POSTGRESQL', 'UNSUPPORTED'].includes(String(value.backend)) && typeof value.postgresqlConfigured === 'boolean'
    && (connections as readonly unknown[]).includes(value.connection)
    && [...schemas, 'V1_READY', 'V1_V2_READY'].includes(value.workspaceSchema as never)
    && [...schemas, 'READY'].includes(value.knowledgeSchema as never)
    && typeof value.financeEnabled === 'boolean' && [...schemas, 'DISABLED', 'NOT_CONFIGURED', 'READY', 'CONNECTION_FAILED', 'AUTHENTICATION_FAILED', 'ROLE_REJECTED'].includes(value.financeSchema as never)
    && (value.financeEnabled || value.financeSchema === 'DISABLED') && (value.knowledgeSchema !== 'READY' || value.workspaceSchema === 'V1_V2_READY')
    && value.migration === 'EXPLICIT_APPROVAL_REQUIRED' && value.encryptedBackup === 'MAINTENANCE_ONLY' && value.restore === 'BLOCKED_SECURITY_REQUIREMENTS'
    && Array.isArray(value.securityRequirements) && value.securityRequirements.length === storageRequirements.length && value.securityRequirements.every((item, index) => item === storageRequirements[index])
    && ['NONE', 'CONFIGURATION_INCOMPLETE', 'CONFIGURATION_INVALID', 'CONNECTION_FAILED', 'AUTHENTICATION_FAILED', 'PERMISSION_DENIED', 'SCHEMA_INCOMPATIBLE', 'ROLE_REJECTED'].includes(String(value.errorCode))
}
