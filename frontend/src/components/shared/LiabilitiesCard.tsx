import { useTranslation } from 'react-i18next'
import { CurrencyDisplay } from '@/components/shared/CurrencyDisplay'
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card'
import {
  Item,
  ItemContent,
  ItemGroup,
  ItemMedia,
  ItemTitle,
} from '@/components/ui/item'
import type { DashboardData } from '@/types/api'

interface LiabilitiesCardProps {
  liabilities: DashboardData['liabilities']
  totalLiabilities: number
}

/**
 * Renders the dashboard's liabilities (loans) as their own reading, separate
 * from assets and portfolio performance (issue #18). Each row shows the
 * outstanding amount in red and its share of total liabilities.
 */
export function LiabilitiesCard({ liabilities, totalLiabilities }: LiabilitiesCardProps) {
  const { t } = useTranslation()

  if (liabilities.length === 0 || totalLiabilities <= 0) return null

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t('dashboard.liabilitiesTitle')}</CardTitle>
        <CardDescription>{t('dashboard.liabilitiesDescription')}</CardDescription>
      </CardHeader>
      <CardContent>
        <ItemGroup className="gap-2">
          {liabilities.map(item => {
            const share = (item.balanceEur / totalLiabilities) * 100
            return (
              <Item key={item.accountId} variant="muted">
                <ItemMedia>
                  <div
                    className="size-3 shrink-0 rounded-full"
                    style={{ backgroundColor: item.color }}
                  />
                </ItemMedia>
                <ItemContent>
                  <ItemTitle>{item.name}</ItemTitle>
                </ItemContent>
                <div className="flex shrink-0 items-center gap-4">
                  <span className="text-xs tabular-nums text-muted-foreground">
                    {share.toFixed(1)}%
                  </span>
                  <CurrencyDisplay
                    value={item.balanceEur}
                    className="font-medium tabular-nums text-red-500"
                  />
                </div>
              </Item>
            )
          })}
        </ItemGroup>
        <div className="mt-3 flex items-center justify-between border-t border-border pt-3 text-sm">
          <span className="text-muted-foreground">{t('dashboard.totalLiabilities')}</span>
          <CurrencyDisplay value={totalLiabilities} className="font-semibold tabular-nums text-red-500" />
        </div>
      </CardContent>
    </Card>
  )
}
