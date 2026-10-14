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
