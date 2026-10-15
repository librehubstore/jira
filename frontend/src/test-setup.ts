import '@testing-library/jest-dom/vitest'
// Tests assert French labels (the source language); i18n reads this before the first t() call.
localStorage.setItem('fluxo.locale', 'fr')
