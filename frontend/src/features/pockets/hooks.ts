import { useQuery, useMutation } from '@tanstack/react-query'
import { pocketsApi } from './api'
import { QUERY_STALE_TIMES } from '@/lib/constants'

/** Fetches all pockets that have not yet been given a user-friendly name. */
export function useUnnamedPockets() {
  return useQuery({
    queryKey: ['pockets', 'unnamed'],
    queryFn: pocketsApi.listUnnamed,
    staleTime: QUERY_STALE_TIMES.accounts,
  })
}

/**
 * Uploads a Revolut CSV export and returns name suggestions per pocket.
 * Ambiguous suggestions are flagged; the user must confirm — never auto-applied.
 */
export function useCsvNameSuggestions() {
  return useMutation({
    mutationFn: (file: File) => pocketsApi.uploadCsvForSuggestions(file),
  })
}
