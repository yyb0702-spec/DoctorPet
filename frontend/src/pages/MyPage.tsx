// 마이페이지 — 회원 정보(조회·닉네임 수정) + 반려동물·결제수단 요약 및 관리 진입 + 회원 탈퇴.
import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ChevronRight, PawPrint, CreditCard, Heart } from 'lucide-react'
import { useMe, useUpdateNickname, useWithdraw } from '@/features/members/hooks'
import { usePets } from '@/features/pets/hooks'
import { usePaymentMethods } from '@/features/payments/hooks'
import { useFavoriteHospitals } from '@/features/hospitals/hooks'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { PageLoader } from '@/components/common/States'
import { ApiError } from '@/lib/api/error'
import { speciesLabel } from '@/lib/species'

const NICKNAME_MAX = 255

// 닉네임 인라인 수정 폼.
function NicknameEditor({
  current,
  onDone,
}: {
  current: string
  onDone: () => void
}) {
  const [value, setValue] = useState(current)
  const update = useUpdateNickname()
  const trimmed = value.trim()
  const invalid =
    trimmed.length === 0 || trimmed.length > NICKNAME_MAX
  const unchanged = trimmed === current

  const handleSave = () => {
    if (invalid || unchanged) return
    update.mutate(trimmed, { onSuccess: onDone })
  }

  return (
    <div className="mt-4 space-y-2">
      <Label htmlFor="nickname">닉네임</Label>
      <Input
        id="nickname"
        value={value}
        maxLength={NICKNAME_MAX}
        autoFocus
        onChange={(e) => setValue(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter') handleSave()
          if (e.key === 'Escape') onDone()
        }}
      />
      {trimmed.length === 0 && (
        <p className="text-sm text-destructive">닉네임을 입력해 주세요.</p>
      )}
      {update.isError && (
        <p className="text-sm text-destructive">
          {update.error instanceof ApiError
            ? update.error.message
            : '닉네임 수정에 실패했습니다.'}
        </p>
      )}
      <div className="flex gap-2">
        <Button
          size="sm"
          disabled={invalid || unchanged || update.isPending}
          onClick={handleSave}
        >
          {update.isPending ? '저장 중…' : '저장'}
        </Button>
        <Button
          size="sm"
          variant="outline"
          disabled={update.isPending}
          onClick={onDone}
        >
          취소
        </Button>
      </div>
    </div>
  )
}

// 회원 탈퇴 섹션 — 확인 단계를 거쳐 DELETE. 보류(409) 시 서버 메시지를 안내.
function WithdrawSection() {
  const navigate = useNavigate()
  const withdraw = useWithdraw()
  const [confirming, setConfirming] = useState(false)

  const handleWithdraw = () => {
    withdraw.mutate(undefined, {
      onSuccess: () => navigate('/', { replace: true }),
    })
  }

  return (
    <Card className="border-destructive/40">
      <CardHeader>
        <CardTitle className="text-destructive">회원 탈퇴</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3">
        <p className="text-sm text-muted-foreground">
          탈퇴하면 계정과 회원 정보가 삭제됩니다. 활성 예약(예약 확정·진료 중)이나
          미수금이 있으면 탈퇴가 보류됩니다.
        </p>
        {withdraw.isError && (
          <p className="text-sm text-destructive">
            {withdraw.error instanceof ApiError
              ? withdraw.error.message
              : '탈퇴 처리에 실패했습니다.'}
          </p>
        )}
        {confirming ? (
          <div className="space-y-2 rounded-md border border-destructive/40 bg-destructive/5 p-3">
            <p className="text-sm font-medium">정말 탈퇴하시겠어요?</p>
            <div className="flex gap-2">
              <Button
                size="sm"
                variant="destructive"
                disabled={withdraw.isPending}
                onClick={handleWithdraw}
              >
                {withdraw.isPending ? '처리 중…' : '탈퇴하기'}
              </Button>
              <Button
                size="sm"
                variant="outline"
                disabled={withdraw.isPending}
                onClick={() => setConfirming(false)}
              >
                취소
              </Button>
            </div>
          </div>
        ) : (
          <Button
            size="sm"
            variant="outline"
            className="border-destructive/40 text-destructive hover:bg-destructive/10"
            onClick={() => setConfirming(true)}
          >
            회원 탈퇴
          </Button>
        )}
      </CardContent>
    </Card>
  )
}

export function MyPage() {
  const me = useMe()
  const pets = usePets()
  const methods = usePaymentMethods()
  // 요약 카드는 개수만 쓰므로 첫 페이지 1건만 받아 totalElements를 읽는다.
  const favorites = useFavoriteHospitals(1, 1)
  const [editingNickname, setEditingNickname] = useState(false)

  return (
    <div className="mx-auto max-w-2xl space-y-5">
      <h1 className="text-2xl font-bold">마이페이지</h1>

      {/* 회원 정보 */}
      <Card>
        <CardHeader>
          <CardTitle>회원 정보</CardTitle>
        </CardHeader>
        <CardContent>
          {me.isLoading ? (
            <PageLoader />
          ) : me.data ? (
            <>
              <dl className="grid grid-cols-[80px_1fr] gap-y-2 text-sm">
                <dt className="text-muted-foreground">이름</dt>
                <dd className="font-medium">{me.data.nickname}</dd>
                <dt className="text-muted-foreground">이메일</dt>
                <dd>{me.data.email}</dd>
                <dt className="text-muted-foreground">역할</dt>
                <dd>{me.data.role === 'GUARDIAN' ? '보호자' : me.data.role}</dd>
              </dl>
              {editingNickname ? (
                <NicknameEditor
                  current={me.data.nickname}
                  onDone={() => setEditingNickname(false)}
                />
              ) : (
                <div className="mt-4">
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => setEditingNickname(true)}
                  >
                    닉네임 수정
                  </Button>
                </div>
              )}
            </>
          ) : (
            <p className="text-sm text-muted-foreground">
              회원 정보를 불러오지 못했습니다.
            </p>
          )}
        </CardContent>
      </Card>

      {/* 반려동물 요약 */}
      <Card>
        <CardHeader className="flex-row items-center justify-between space-y-0">
          <CardTitle className="flex items-center gap-2">
            <PawPrint className="h-5 w-5 text-primary" />
            내 반려동물
          </CardTitle>
          <Button asChild size="sm" variant="ghost">
            <Link to="/pets">
              관리 <ChevronRight className="h-4 w-4" />
            </Link>
          </Button>
        </CardHeader>
        <CardContent>
          {pets.isLoading ? (
            <PageLoader />
          ) : pets.data && pets.data.length > 0 ? (
            <ul className="space-y-2">
              {pets.data.map((p) => (
                <li
                  key={p.petId}
                  className="flex items-center justify-between rounded-md border p-3 text-sm"
                >
                  <span className="flex items-center gap-2 font-medium">
                    {p.name}
                    <Badge variant="secondary">
                      {speciesLabel(p.species)}
                    </Badge>
                  </span>
                  <span className="text-muted-foreground">
                    {p.age}살 · {p.weight}kg
                  </span>
                </li>
              ))}
            </ul>
          ) : (
            <div className="flex items-center justify-between">
              <p className="text-sm text-muted-foreground">
                등록된 반려동물이 없습니다.
              </p>
              <Button asChild size="sm">
                <Link to="/pets">펫 등록</Link>
              </Button>
            </div>
          )}
        </CardContent>
      </Card>

      {/* 결제수단 요약 */}
      <Card>
        <CardHeader className="flex-row items-center justify-between space-y-0">
          <CardTitle className="flex items-center gap-2">
            <CreditCard className="h-5 w-5 text-primary" />
            결제수단
          </CardTitle>
          <Button asChild size="sm" variant="ghost">
            <Link to="/payment-methods">
              관리 <ChevronRight className="h-4 w-4" />
            </Link>
          </Button>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-muted-foreground">
            {methods.isLoading
              ? '불러오는 중…'
              : `등록된 결제수단 ${methods.data?.length ?? 0}개`}
          </p>
        </CardContent>
      </Card>

      {/* 관심 병원 요약 */}
      <Card>
        <CardHeader className="flex-row items-center justify-between space-y-0">
          <CardTitle className="flex items-center gap-2">
            <Heart className="h-5 w-5 text-primary" />
            관심 병원
          </CardTitle>
          <Button asChild size="sm" variant="ghost">
            <Link to="/favorites">
              관리 <ChevronRight className="h-4 w-4" />
            </Link>
          </Button>
        </CardHeader>
        <CardContent>
          {/* 개수만 보여준다 — 첫 페이지만 받아도 totalElements로 전체를 알 수 있다. */}
          <p className="text-sm text-muted-foreground">
            {favorites.isLoading
              ? '불러오는 중…'
              : `찜한 병원 ${favorites.data?.totalElements ?? 0}곳`}
          </p>
        </CardContent>
      </Card>

      {/* 회원 탈퇴 */}
      <WithdrawSection />
    </div>
  )
}
