import tseslint from 'typescript-eslint';

export default [
    ...tseslint.configs.recommended,
    { files: ['src/**/*.ts'], rules: { 'prefer-const': 'error' } },
];
