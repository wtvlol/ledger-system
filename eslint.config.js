import stylistic from '@stylistic/eslint-plugin';

/** Defines the project's checked subset of the JavaScript standard. */
export default [
  {ignores: ['build/**', 'node_modules/**', '.gradle/**']},
  {
    files: ['**/*.js'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'module',
      globals: {
        document: 'readonly',
        localStorage: 'readonly',
        crypto: 'readonly',
        fetch: 'readonly',
      },
    },
    plugins: {'@stylistic': stylistic},
    rules: {
      'no-undef': 'error',
      'no-unused-vars': ['error', {caughtErrors: 'none'}],
      'no-redeclare': 'error',
      'no-unreachable': 'error',
      'eqeqeq': ['error', 'always'],
      'curly': ['error', 'all'],
      'prefer-const': 'error',
      'no-var': 'error',
      '@stylistic/indent': ['error', 2, {SwitchCase: 1}],
      '@stylistic/max-len': ['error', {code: 80, ignoreUrls: true}],
      '@stylistic/quotes': ['error', 'single', {avoidEscape: true}],
      '@stylistic/semi': ['error', 'always'],
      '@stylistic/brace-style': ['error', '1tbs'],
      '@stylistic/comma-dangle': ['error', 'always-multiline'],
      '@stylistic/comma-spacing': 'error',
      '@stylistic/keyword-spacing': 'error',
      '@stylistic/key-spacing': 'error',
      '@stylistic/space-infix-ops': 'error',
      '@stylistic/space-before-blocks': 'error',
      '@stylistic/arrow-parens': ['error', 'always'],
      '@stylistic/object-curly-spacing': ['error', 'never'],
      '@stylistic/eol-last': ['error', 'always'],
      '@stylistic/no-trailing-spaces': 'error',
      '@stylistic/no-multi-spaces': 'error',
      '@stylistic/max-statements-per-line': ['error', {max: 1}],
    },
  },
];
