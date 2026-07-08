import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip'
import { formatTimeAgo } from '@/lib/utils'

interface PriceFreshnessDotProps {
  priceUpdatedAt: string | null
}

const LIVE_THRESHOLD_MS = 2 * 60 * 1000 // 2 minutes
const TICK_MS = 30 * 1000

export function PriceFreshnessDot({ priceUpdatedAt }: PriceFreshnessDotProps) {
  const { t } = useTranslation()
  // Lazy initializer keeps the impure Date.now() out of render; the interval
  // keeps the clock ticking so a tab left open cannot show "live" forever on
  // a timestamp that stopped moving.
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), TICK_MS)
    return () => clearInterval(id)
  }, [])

  if (!priceUpdatedAt) return null

  const age = now - new Date(priceUpdatedAt).getTime()
  const isLive = age < LIVE_THRESHOLD_MS

  if (isLive) {
    return (
      <TooltipProvider>
        <Tooltip>
          <TooltipTrigger asChild>
            <span className="size-1.5 shrink-0 rounded-full bg-emerald-500" />
          </TooltipTrigger>
          <TooltipContent>{t('accounts.priceLive')}</TooltipContent>
        </Tooltip>
      </TooltipProvider>
    )
  }

  const timeAgo = formatTimeAgo(priceUpdatedAt)

  return (
    <TooltipProvider>
      <Tooltip>
        <TooltipTrigger asChild>
          <span className="size-1.5 shrink-0 rounded-full bg-amber-500" />
        </TooltipTrigger>
        <TooltipContent>{t('accounts.priceStale', { time: timeAgo })}</TooltipContent>
      </Tooltip>
    </TooltipProvider>
  )
}
