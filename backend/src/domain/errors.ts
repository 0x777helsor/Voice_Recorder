/**
 * Typed errors and their HTTP mapping.
 *
 * Every route throws one of these rather than returning ad-hoc status codes, so
 * that the error surface is auditable in one file and an unhandled error can never
 * leak a stack trace or an internal message to a client.
 */

export type ErrorCode =
  | 'INVALID_REQUEST'
  | 'UNAUTHENTICATED'
  | 'INVALID_CREDENTIALS'
  | 'FORBIDDEN'
  | 'NOT_FOUND'
  | 'CONFLICT'
  | 'IDEMPOTENCY_MISMATCH'
  | 'CHECKSUM_MISMATCH'
  | 'MANIFEST_INCOMPLETE'
  | 'RATE_LIMITED'
  | 'SYNC_DISABLED'
  | 'INTERNAL';

export class SafeSignalError extends Error {
  readonly statusCode: number;
  readonly code: ErrorCode;
  readonly details?: Record<string, unknown>;

  constructor(statusCode: number, code: ErrorCode, message: string, details?: Record<string, unknown>) {
    super(message);
    this.name = 'SafeSignalError';
    this.statusCode = statusCode;
    this.code = code;
    this.details = details;
  }

  toJSON(): Record<string, unknown> {
    return {
      error: {
        code: this.code,
        message: this.message,
        ...(this.details ? { details: this.details } : {}),
      },
    };
  }
}

export const badRequest = (message: string, details?: Record<string, unknown>) =>
  new SafeSignalError(400, 'INVALID_REQUEST', message, details);

export const unauthenticated = (message = 'Authentication required.') =>
  new SafeSignalError(401, 'UNAUTHENTICATED', message);

export const invalidCredentials = () =>
  // Deliberately identical wording for "no such user" and "wrong password", so the
  // endpoint cannot be used to enumerate registered accounts.
  new SafeSignalError(401, 'INVALID_CREDENTIALS', 'Email or password is incorrect.');

export const forbidden = (message = 'You do not have access to this resource.') =>
  new SafeSignalError(403, 'FORBIDDEN', message);

/**
 * Used for both "does not exist" and "belongs to someone else".
 *
 * Returning 403 for the second case would confirm that the id is real, which is a
 * user-enumeration oracle. A caller cannot distinguish the two cases.
 */
export const notFound = () =>
  new SafeSignalError(404, 'NOT_FOUND', 'Recording not found.');

export const conflict = (message: string) => new SafeSignalError(409, 'CONFLICT', message);

export const idempotencyMismatch = (message: string) =>
  new SafeSignalError(409, 'IDEMPOTENCY_MISMATCH', message);

export const checksumMismatch = (message: string, details?: Record<string, unknown>) =>
  new SafeSignalError(422, 'CHECKSUM_MISMATCH', message, details);

export const manifestIncomplete = (message: string, details?: Record<string, unknown>) =>
  new SafeSignalError(422, 'MANIFEST_INCOMPLETE', message, details);

export const internal = (message = 'Internal error.') => new SafeSignalError(500, 'INTERNAL', message);
