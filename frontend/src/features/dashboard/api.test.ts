import { describe, expect, it, vi } from 'vitest'

vi.mock('@/lib/api-client', () => ({
  api: { get: vi.fn().mockResolvedValue({ data: [] }) },
}))

import { api } from '@/lib/api-client'
import { dashboardApi } from './api'

describe('dashboardApi param serialization', () => {
  it('sends accountIds as a comma-joined string to /history/pnl', async () => {
    await dashboardApi.getPnl([9, 12], '2026-01-01')
    expect(api.get).toHaveBeenCalledWith('/history/pnl', {
      params: { accountIds: '9,12', from: '2026-01-01' },
    })
  })

  it('sends accountIds as a comma-joined string to intraday', async () => {
    await dashboardApi.getIntraday([9])
    expect(api.get).toHaveBeenCalledWith('/history/net-worth/intraday', {
      params: { accountIds: '9' },
    })
  })
})
