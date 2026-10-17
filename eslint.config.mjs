import eslint from '@eslint/js';
import globals from 'globals';
import reactHooks from 'eslint-plugin-react-hooks';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  {
    ignores: [
      '**/node_modules/**',
      '**/lib/**',
      '**/nitrogen/**',
      '**/.expo/**',
      '**/android/**',
      '**/ios/**',
      'benchmarks/fixtures/**',
      'benchmarks/results/**',
      // Agent worktrees: other checkouts of this repository.
      '.claude/**',
    ],
  },
  eslint.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ['**/*.{js,mjs,cjs}'],
    languageOptions: { globals: { ...globals.node } },
    rules: { '@typescript-eslint/no-require-imports': 'off' },
  },
  {
    files: ['**/*.{ts,tsx}'],
    languageOptions: { globals: { ...globals.browser, ...globals.node } },
    plugins: { 'react-hooks': reactHooks },
    rules: {
      ...reactHooks.configs.recommended.rules,
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
      '@typescript-eslint/no-explicit-any': 'error',
    },
  },
  {
    // Type-only imports that exist so JSDoc `{@linkcode}` targets resolve.
    // tsc already counts those as used (`noUnusedLocals`); ESLint does not.
    files: ['package/src/types/**/*.ts'],
    rules: { '@typescript-eslint/no-unused-vars': 'off' },
  },
  {
    // Bundled assets are loaded with require() by design.
    files: ['example/documents.ts'],
    rules: { '@typescript-eslint/no-require-imports': 'off' },
  },
);
