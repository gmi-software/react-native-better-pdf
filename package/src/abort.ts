/** What an aborted operation rejects with: the signal's reason, or an `AbortError`. */
export function abortReason(signal: AbortSignal): unknown {
  // React Native's AbortSignal (types and polyfill) has no `reason`.
  if ('reason' in signal && signal.reason !== undefined) return signal.reason;
  const error = new Error('The operation was aborted');
  error.name = 'AbortError';
  return error;
}

/**
 * `work`, or once `signal` aborts, `cancel()` and a rejection with
 * {@linkcode abortReason}.
 */
export function abortable<T>(
  work: Promise<T>,
  signal: AbortSignal | undefined,
  cancel?: () => void,
): Promise<T> {
  if (signal === undefined) return work;
  return new Promise<T>((resolve, reject) => {
    const onAbort = () => {
      cancel?.();
      reject(abortReason(signal));
    };
    signal.addEventListener('abort', onAbort, { once: true });
    work
      .finally(() => signal.removeEventListener('abort', onAbort))
      .then(resolve, reject);
  });
}

/** `listener`, silenced once `signal` aborts: native events can reach JS after the cancel. */
export function untilAborted<T>(
  listener: ((event: T) => void) | undefined,
  signal: AbortSignal | undefined,
): ((event: T) => void) | undefined {
  if (listener === undefined || signal === undefined) return listener;
  return (event) => {
    if (!signal.aborted) listener(event);
  };
}
