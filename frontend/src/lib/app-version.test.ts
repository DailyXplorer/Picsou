import { describe, expect, it } from 'vitest'
import { APP_VERSION } from './app-version'

describe('APP_VERSION', () => {
  it('comes from the build-time Vite define', () => {
    expect(APP_VERSION).toBe('test-version')
  })
})
