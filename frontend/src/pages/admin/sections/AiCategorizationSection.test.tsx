import '@testing-library/jest-dom'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { AiCategorizationSection } from './AiCategorizationSection'

// Mutable state the mocked hooks read, so each test can vary returned data.
const state = vi.hoisted(() => ({
  testAiData: undefined as { ok: boolean; message: string } | undefined,
  testAiIsError: false,
  testAiError: null as Error | null,
  testAiMutate: vi.fn(),
  updateMutateAsync: vi.fn(),
  updateIsSuccess: false,
  updateError: null as Error | null,
}))

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

vi.mock('@/features/admin/hooks', () => ({
  useUpdateAi: () => ({
    mutateAsync: state.updateMutateAsync,
    isPending: false,
    isError: !!state.updateError,
    isSuccess: state.updateIsSuccess,
    error: state.updateError,
  }),
  useTestAi: () => ({
    mutate: state.testAiMutate,
    isPending: false,
    isError: state.testAiIsError,
    data: state.testAiData,
    error: state.testAiError,
  }),
}))

const DEFAULT_SETTINGS = {
  provider: 'none',
  model: '',
  baseUrl: '',
  apiKeyPresent: false,
}

describe('AiCategorizationSection', () => {
  beforeEach(() => {
    state.testAiData = undefined
    state.testAiIsError = false
    state.testAiError = null
    state.testAiMutate = vi.fn()
    state.updateMutateAsync = vi.fn()
    state.updateIsSuccess = false
    state.updateError = null
  })

  it('renders all five provider options (none, anthropic, openrouter, openai, ollama)', () => {
    render(<AiCategorizationSection settings={DEFAULT_SETTINGS} />)

    const select = screen.getByRole('combobox') as HTMLSelectElement
    const values = Array.from(select.options).map((o) => o.value)

    expect(values).toContain('none')
    expect(values).toContain('anthropic')
    expect(values).toContain('openrouter')
    expect(values).toContain('openai')
    expect(values).toContain('ollama')
    expect(select.options).toHaveLength(5)
  })

  it('does not render an API key input when provider is ollama', () => {
    render(<AiCategorizationSection settings={{ ...DEFAULT_SETTINGS, provider: 'ollama' }} />)
    // No password input for Ollama
    expect(screen.queryByLabelText('admin.ai.apiKey')).not.toBeInTheDocument()
  })

  it('renders a password input for API key when provider is anthropic', () => {
    render(<AiCategorizationSection settings={{ ...DEFAULT_SETTINGS, provider: 'anthropic' }} />)
    const apiKeyInput = screen.getByLabelText('admin.ai.apiKey')
    expect(apiKeyInput).toBeInTheDocument()
    expect(apiKeyInput).toHaveAttribute('type', 'password')
  })

  it('clicking Test calls testAi.mutate with current form values', () => {
    render(<AiCategorizationSection settings={{ ...DEFAULT_SETTINGS, provider: 'anthropic' }} />)
    fireEvent.click(screen.getByText('admin.ai.test'))
    expect(state.testAiMutate).toHaveBeenCalledOnce()
  })

  it('renders the success message when testAi returns ok: true', () => {
    state.testAiData = { ok: true, message: 'Connected.' }
    render(<AiCategorizationSection settings={{ ...DEFAULT_SETTINGS, provider: 'anthropic' }} />)
    expect(screen.getByText('Connected.')).toBeInTheDocument()
  })

  it('renders an error alert when testAi returns ok: false', () => {
    state.testAiData = { ok: false, message: 'Invalid API key.' }
    render(<AiCategorizationSection settings={{ ...DEFAULT_SETTINGS, provider: 'anthropic' }} />)
    expect(screen.getByRole('alert')).toHaveTextContent('Invalid API key.')
  })

  it('hides all fields and shows only the hint when provider is none', () => {
    render(<AiCategorizationSection settings={DEFAULT_SETTINGS} />)
    expect(screen.getByText('admin.ai.disabledHint')).toBeInTheDocument()
    expect(screen.queryByLabelText('admin.ai.model')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('admin.ai.baseUrl')).not.toBeInTheDocument()
    // No Test button for disabled provider
    expect(screen.queryByText('admin.ai.test')).not.toBeInTheDocument()
  })

  it('shows the apiKeyHintPresent hint when apiKeyPresent is true', () => {
    render(
      <AiCategorizationSection
        settings={{ ...DEFAULT_SETTINGS, provider: 'openai', apiKeyPresent: true }}
      />,
    )
    expect(screen.getByText('admin.ai.apiKeyHintPresent')).toBeInTheDocument()
  })
})
