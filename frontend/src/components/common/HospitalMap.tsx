// 주소 기반 지도 임베드. 백엔드가 좌표를 안 주므로 주소로 표시한다.
// Google 지도 임베드(q + output=embed)는 API 키 없이 동작한다. 카카오/네이버 정식
// 지도(좌표 핀)는 JS 키가 필요해 후속으로 둔다.
import { cn } from '@/lib/utils'

export function HospitalMap({
  address,
  name,
  className,
}: {
  address: string
  name?: string
  className?: string
}) {
  const query = encodeURIComponent(name ? `${name} ${address}` : address)
  const src = `https://www.google.com/maps?q=${query}&z=16&hl=ko&output=embed`
  return (
    <iframe
      title={`${name ?? '병원'} 위치 지도`}
      src={src}
      loading="lazy"
      referrerPolicy="no-referrer-when-downgrade"
      className={cn('w-full rounded-lg border', className)}
    />
  )
}
