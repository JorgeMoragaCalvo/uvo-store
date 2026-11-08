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
    // GAP-13: el token del panel vive en localStorage, en el mismo origen que la tienda. Que hoy no haya ningún
    // punto por donde inyectar HTML o código es lo que sostiene esa decisión, así que deja de ser costumbre y
    // pasa a ser regla. Ver docs/decision-sesion-panel.md. Solo src/: los dobles del E2E arman HTML en Node.
    files: ['src/**/*.{ts,tsx}'],
    rules: {
      'no-eval': 'error',
      'no-implied-eval': 'error',
      'no-new-func': 'error',
      'no-restricted-syntax': ['error',
        {
          selector: "JSXAttribute[name.name='dangerouslySetInnerHTML']",
          message: 'GAP-13: no se inserta HTML crudo; React ya escapa el texto. Ver docs/decision-sesion-panel.md.',
        },
        {
          selector: "AssignmentExpression > MemberExpression.left[property.name=/^(innerHTML|outerHTML)$/]",
          message: 'GAP-13: no se asigna innerHTML/outerHTML; usa textContent o JSX. Ver docs/decision-sesion-panel.md.',
        },
        {
          selector: "CallExpression[callee.property.name='insertAdjacentHTML']",
          message: 'GAP-13: no se inserta HTML crudo. Ver docs/decision-sesion-panel.md.',
        },
        {
          selector: "CallExpression[callee.object.name='document'][callee.property.name=/^(write|writeln)$/]",
          message: 'GAP-13: document.write inserta HTML crudo. Ver docs/decision-sesion-panel.md.',
        },
      ],
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
