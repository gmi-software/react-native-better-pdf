import type { PdfMethodErrorCode } from './types/PdfMethodErrorCode';

/**
 * Why a `PdfViewRef` or `PdfDocument` method rejected. Match on
 * {@linkcode PdfMethodError.code}: e.g. `'not-loaded'` (call it after
 * `onLoad`), `'closed'` (the document was closed or replaced while the
 * call waited), `'not-supported'` (several sources, or a platform
 * limit), `'not-permitted'`, `'busy'` (a print dialog is open).
 */
export class PdfMethodError extends Error {
  /** Stable reason; match on this. */
  readonly code: PdfMethodErrorCode;
  /** Raw platform error description, for logging only. */
  readonly nativeDescription?: string;

  constructor(
    code: PdfMethodErrorCode,
    message: string,
    nativeDescription?: string,
  ) {
    super(message);
    this.name = 'PdfMethodError';
    this.code = code;
    if (nativeDescription !== undefined) {
      this.nativeDescription = nativeDescription;
    }
  }
}

const codes: ReadonlySet<string> = new Set<PdfMethodErrorCode>([
  'not-loaded',
  'closed',
  'invalid-argument',
  'not-supported',
  'not-permitted',
  'busy',
  'io-error',
  'invalid-pdf',
  'out-of-memory',
  'unknown',
]);

// Android's rejections read `<exception class>: <message>`, then the stack
// trace, the class renamed in minified release builds; iOS's are the
// message itself.
const exceptionClass = /^[\w$.]+: /;
const codedMessage = /^\[([a-z]+(?:-[a-z]+)*)\] (.*)/;

/**
 * A native method's rejection as a `PdfMethodError`: native code rejects
 * with `[code] message` (see `PdfMethodFailure` / `PdfMethodException`);
 * anything else is `'unknown'`.
 */
export function toPdfMethodError(error: unknown): PdfMethodError {
  if (error instanceof PdfMethodError) return error;
  const raw = error instanceof Error ? error.message : String(error);
  const line = (raw.split('\n', 1)[0] ?? '').replace(exceptionClass, '');
  const nativeDescription = raw === '' ? undefined : raw;
  const match = codedMessage.exec(line);
  if (match?.[1] !== undefined && codes.has(match[1])) {
    return new PdfMethodError(
      match[1] as PdfMethodErrorCode,
      match[2] ?? '',
      nativeDescription,
    );
  }
  return new PdfMethodError(
    'unknown',
    line === '' ? 'Unknown error' : line,
    nativeDescription,
  );
}

/** Runs `call`; whatever it throws or rejects with becomes a `PdfMethodError`. */
export async function withMethodErrors<T>(
  call: () => T | Promise<T>,
): Promise<T> {
  try {
    return await call();
  } catch (error) {
    throw toPdfMethodError(error);
  }
}
