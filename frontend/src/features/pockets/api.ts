import { api } from '@/lib/api-client'
import type { UnnamedPocket, CsvNameSuggestion, CsvNamingResponse } from '@/types/pockets'

export const pocketsApi = {
  /**
   * Returns all detected pockets that have not yet received a user-given name.
   * Used to trigger the onboarding modal and populate its list.
   *
   * Backend: GET /api/revolut-pockets/unnamed → UnnamedPocketResponse[]
   */
  listUnnamed: (): Promise<UnnamedPocket[]> =>
    api.get<UnnamedPocket[]>('/revolut-pockets/unnamed').then((r) => r.data),

  /**
   * Upload a Revolut CSV export (multipart, field name "file"). The backend
   * reconciles pocket names to account ids by matching transfer amount + date,
   * and returns one suggestion per matched pocket wrapped in a CsvNamingResponse
   * envelope. Uncertain matches are flagged — the user must confirm; names are
   * NEVER auto-applied by this endpoint.
   *
   * Backend: POST /api/revolut-pockets/csv-naming → CsvNamingResponse { suggestions }
   */
  uploadCsvForSuggestions: (file: File): Promise<CsvNameSuggestion[]> => {
    const form = new FormData()
    form.append('file', file)
    return api
      .post<CsvNamingResponse>(
        '/revolut-pockets/csv-naming',
        form,
        { headers: { 'Content-Type': 'multipart/form-data' } },
      )
      .then((r) => r.data.suggestions)
  },
}
