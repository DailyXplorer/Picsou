import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { accessKeysApi, type CreateAccessKeyInput } from './api'

/** Lists the current member's own access-keys (the endpoint is scoped to the caller). */
export function useAccessKeys() {
  return useQuery({
    queryKey: ['accessKeys'],
    queryFn: () => accessKeysApi.list(),
  })
}

export function useCreateAccessKey() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (data: CreateAccessKeyInput) => accessKeysApi.create(data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['accessKeys'] }),
  })
}

export function useRevokeAccessKey() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => accessKeysApi.revoke(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['accessKeys'] }),
  })
}
