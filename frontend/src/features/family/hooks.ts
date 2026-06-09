import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { familyApi } from './api'

/**
 * Lists family members. The underlying `/family/members` endpoint is admin-only
 * (`requireAdmin()` → 403 for non-admins), so callers rendered for every user
 * (e.g. the sidebar) MUST pass `enabled: isAdmin` to avoid a spurious 403 that
 * the global interceptor would turn into an `/error/403` redirect.
 */
export function useFamilyMembers({ enabled = true }: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: ['family', 'members'],
    queryFn: () => familyApi.listMembers(),
    enabled,
  })
}

export function useFamilyDashboard() {
  return useQuery({
    queryKey: ['family', 'dashboard'],
    queryFn: () => familyApi.getDashboard(),
  })
}

export function useSharingSettings(resourceType: string) {
  return useQuery({
    queryKey: ['family', 'sharing', resourceType],
    queryFn: () => familyApi.getSharingSettings(resourceType),
  })
}

export function useCreateMember() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (data: { displayName: string; avatarColor?: string }) =>
      familyApi.createMember(data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['family', 'members'] }),
  })
}

/**
 * Creates a managed profile AND provisions its login in one step, returning the
 * activation link to share. Chains the two existing endpoints so the caller gets
 * a single loading/error state.
 */
export function useCreateUserWithLogin() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: async (data: { displayName: string; avatarColor?: string }) => {
      const member = await familyApi.createMember(data)
      const { activationLink } = await familyApi.generateActivationLink(member.id)
      return { member, activationLink }
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ['family', 'members'] }),
  })
}

export function useUpdateMember() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, data }: { id: number; data: { displayName: string } }) =>
      familyApi.updateMember(id, data),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['family', 'members'] }),
  })
}

export function useDeleteMember() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => familyApi.deleteMember(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['family', 'members'] }),
  })
}

export function useGenerateActivationLink() {
  return useMutation({
    mutationFn: (id: number) => familyApi.generateActivationLink(id),
  })
}

export function useResetMemberPassword() {
  return useMutation({
    mutationFn: (id: number) => familyApi.resetMemberPassword(id),
  })
}

export function useUpdateSharingSettings() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (data: { resourceType: string; sharingLevel: string; sharedResourceIds?: number[] }) =>
      familyApi.updateSharingSettings(data),
    onSuccess: (_, variables) => qc.invalidateQueries({ queryKey: ['family', 'sharing', variables.resourceType] }),
  })
}

export function useGoalContributions(goalId: number) {
  return useQuery({
    queryKey: ['family', 'goals', goalId, 'contributions'],
    queryFn: () => familyApi.getGoalContributions(goalId),
    enabled: !!goalId,
  })
}
