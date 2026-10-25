import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'

export default defineConfig([
  globalIgnores(['dist']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      globals: globals.browser,
    },
    rules: {
      // typescript-eslint's no-unused-vars ships stricter defaults than ESLint's core rule:
      // `ignoreRestSiblings` is false and there are no ignore patterns. These options only relax
      // the rule, restoring the conventional behavior: `const { omitted, ...rest } = obj` to drop
      // a property, and a leading `_` to mark a binding as deliberately unused.
      '@typescript-eslint/no-unused-vars': ['error', {
        ignoreRestSiblings: true,
        varsIgnorePattern: '^_',
        argsIgnorePattern: '^_',
        caughtErrorsIgnorePattern: '^_',
      }],
    },
  },
  {
    // shadcn/ui-generated components: several export a *Variants cva() helper alongside the
    // component itself (button.tsx, badge.tsx, tabs.tsx) — the standard, upstream shadcn pattern.
    // Disabling this rule here (not project-wide) keeps fast-refresh strictness everywhere else.
    files: ['src/components/ui/**/*.{ts,tsx}'],
    rules: {
      'react-refresh/only-export-components': 'off',
    },
  },
  {
    // PROD-14a: the Playwright suite runs under Node, not in the browser bundle. The React rules don't
    // apply there, and rules-of-hooks would flag the `use(...)` that Playwright fixtures call.
    files: ['e2e/**/*.ts', 'playwright.config.ts'],
    languageOptions: {
      globals: globals.node,
    },
    rules: {
      'react-hooks/rules-of-hooks': 'off',
      'react-refresh/only-export-components': 'off',
    },
  },
])
