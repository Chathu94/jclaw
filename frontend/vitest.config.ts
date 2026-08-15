import { defineVitestConfig } from '@nuxt/test-utils/config'

export default defineVitestConfig({
  test: {
    environment: 'nuxt',
    environmentOptions: {
      nuxt: {
        domEnvironment: 'jsdom',
      },
    },
    // jsdom lacks a few DOM APIs the app calls (matchMedia, scrollIntoView,
    // DataTransfer); test/setup.ts polyfills them globally.
    setupFiles: ['./test/setup.ts'],
    // Playwright E2E specs live under tests/e2e/ and use @playwright/test, not
    // Vitest. Exclude them so `pnpm test` only runs the Vitest unit suite.
    exclude: ['**/node_modules/**', '**/dist/**', '**/.{idea,git,cache,output,temp}/**', 'tests/e2e/**'],
    // `junit` feeds the Jenkinsfile's junit step so the frontend suite reaches the
    // Test Result Trend; without it a frontend regression showed only as a red build
    // with no test detail. `default` stays first because ./jclaw.sh test parses its
    // "Test Files"/"Tests"/"Duration" lines for the summary it prints.
    reporters: ['default', 'junit'],
    outputFile: { junit: 'test-report/junit.xml' },
    coverage: {
      // v8 is the native Vitest coverage provider (istanbul requires a
      // separate Babel transform); both emit Sonar-compatible lcov but v8
      // is lighter and ships in @vitest/coverage-v8 matching the vitest
      // major. Activated by `pnpm test --coverage` in the Jenkinsfile — no `--`
      // separator, or pnpm ends the flags and vitest reads `--coverage` as a
      // test-file pattern, producing a silent pass with no coverage at all.
      //
      // `lcov` is what sonar.javascript.lcov.reportPaths consumes; `text`
      // keeps a human-readable summary in the test log; `html` lets us
      // open coverage/index.html locally when chasing a missed branch.
      provider: 'v8',
      reporter: ['text', 'lcov', 'html'],
      reportsDirectory: 'coverage',
      // layouts/ must stay listed: sonar.coverage.exclusions does not exclude
      // it, so anything omitted here is counted by Sonar with no coverage data
      // and reports as 0% however well it is tested.
      include: ['components/**', 'composables/**', 'layouts/**', 'pages/**', 'plugins/**', 'utils/**'],
      exclude: [
        'test/**',
        'tests/**',
        '.nuxt/**',
        '.output/**',
        'dist/**',
        'public/**',
        'node_modules/**',
      ],
    },
  },
})
