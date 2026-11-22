// React Native's global, missing elsewhere (tests); the library builds without
// React Native's global types.
declare const __DEV__: boolean | undefined;

export function isDevelopment(): boolean {
  return typeof __DEV__ !== 'undefined' && __DEV__ === true;
}
