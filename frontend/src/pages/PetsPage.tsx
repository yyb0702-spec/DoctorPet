// 펫 프로필 관리 — 목록 + 등록 + 삭제.
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { petSchema, type PetInput } from '@/features/pets/schema'
import { useCreatePet, usePets } from '@/features/pets/hooks'
import { PetRow } from '@/features/pets/PetRow'
import { Field } from '@/components/common/Field'
import { Input } from '@/components/ui/input'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { EmptyState, ErrorState, PageLoader } from '@/components/common/States'
import { PetSpecies } from '@/types/enums'
import { SPECIES_ORDER, SPECIES_LABEL, speciesEmoji, speciesLabel } from '@/lib/species'

export function PetsPage() {
  const petsQuery = usePets()
  const createPet = useCreatePet()

  const {
    register,
    handleSubmit,
    reset,
    watch,
    formState: { errors },
  } = useForm<PetInput>({
    resolver: zodResolver(petSchema),
    defaultValues: { species: PetSpecies.DOG, neutered: false },
  })

  // 입력값 실시간 미리보기 (와이어프레임 A-4).
  const preview = watch()

  const onSubmit = handleSubmit((values) => {
    createPet.mutate(values, { onSuccess: () => reset() })
  })

  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <div className="space-y-3">
        <h1 className="text-2xl font-bold">펫 프로필</h1>
        {petsQuery.isLoading && <PageLoader />}
        {petsQuery.isError && (
          <ErrorState onRetry={() => petsQuery.refetch()} />
        )}
        {petsQuery.data && petsQuery.data.length === 0 && (
          <EmptyState message="등록된 반려동물이 없습니다." />
        )}
        <div className="grid gap-3">
          {petsQuery.data?.map((pet) => (
            <PetRow key={pet.petId} pet={pet} />
          ))}
        </div>
      </div>

      <div className="space-y-3">
      <Card className="h-fit">
        <CardHeader>
          <CardTitle>새 반려동물 등록</CardTitle>
        </CardHeader>
        <CardContent>
          <form onSubmit={onSubmit} className="space-y-4">
            <Field label="이름" htmlFor="name" error={errors.name?.message}>
              <Input id="name" {...register('name')} />
            </Field>
            <Field label="종" htmlFor="species" error={errors.species?.message}>
              <select
                id="species"
                className="flex h-9 w-full rounded-md border border-input bg-background px-3 text-sm"
                {...register('species')}
              >
                {SPECIES_ORDER.map((s) => (
                  <option key={s} value={s}>
                    {SPECIES_LABEL[s]}
                  </option>
                ))}
              </select>
            </Field>
            <div className="grid grid-cols-2 gap-3">
              <Field label="나이(살)" htmlFor="age" error={errors.age?.message}>
                <Input
                  id="age"
                  type="number"
                  min={0}
                  {...register('age', { valueAsNumber: true })}
                />
              </Field>
              <Field label="몸무게(kg)" htmlFor="weight" error={errors.weight?.message}>
                <Input
                  id="weight"
                  type="number"
                  step="0.01"
                  min={0}
                  {...register('weight', { valueAsNumber: true })}
                />
              </Field>
            </div>
            <label className="flex items-center gap-2 text-sm">
              <input type="checkbox" {...register('neutered')} />
              중성화 완료
            </label>
            <Button type="submit" className="w-full" disabled={createPet.isPending}>
              {createPet.isPending ? '등록 중…' : '프로필 저장'}
            </Button>
          </form>
        </CardContent>
      </Card>

      {/* 입력 미리보기 */}
      <Card className="h-fit border-dashed bg-muted/30">
        <CardContent className="flex items-center gap-4 p-5">
          <span className="flex h-14 w-14 items-center justify-center rounded-full bg-primary/10 text-2xl">
            {speciesEmoji(preview.species)}
          </span>
          <div className="space-y-0.5">
            <p className="font-semibold">{preview.name || '이름 미입력'}</p>
            <p className="text-sm text-muted-foreground">
              {speciesLabel(preview.species)}
              {Number.isFinite(preview.age) && ` · ${preview.age}살`}
              {Number.isFinite(preview.weight) && ` · ${preview.weight}kg`}
            </p>
          </div>
        </CardContent>
      </Card>
      </div>
    </div>
  )
}
