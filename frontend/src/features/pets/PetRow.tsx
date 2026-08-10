// 펫 한 마리 — 보기 / 인라인 수정 / 삭제.
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Pencil, Trash2 } from 'lucide-react'
import { petSchema, type PetInput } from './schema'
import { useDeletePet, useUpdatePet } from './hooks'
import type { Pet } from './api'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Field } from '@/components/common/Field'
import { PetSpecies } from '@/types/enums'
import { ApiError } from '@/lib/api/error'

export function PetRow({ pet }: { pet: Pet }) {
  const [editing, setEditing] = useState(false)
  const deletePet = useDeletePet()
  const updatePet = useUpdatePet(pet.petId)

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<PetInput>({
    resolver: zodResolver(petSchema),
    defaultValues: {
      name: pet.name,
      species: pet.species,
      age: pet.age,
      weight: pet.weight,
      neutered: pet.neutered,
    },
  })

  const startEdit = () => {
    reset({
      name: pet.name,
      species: pet.species,
      age: pet.age,
      weight: pet.weight,
      neutered: pet.neutered,
    })
    setEditing(true)
  }

  const onSave = handleSubmit((values) => {
    updatePet.mutate(values, { onSuccess: () => setEditing(false) })
  })

  if (editing) {
    return (
      <Card>
        <CardContent className="space-y-3 p-5">
          <div className="grid grid-cols-2 gap-3">
            <Field label="이름" htmlFor={`name-${pet.petId}`} error={errors.name?.message}>
              <Input id={`name-${pet.petId}`} {...register('name')} />
            </Field>
            <Field label="종" htmlFor={`species-${pet.petId}`} error={errors.species?.message}>
              <select
                id={`species-${pet.petId}`}
                className="flex h-9 w-full rounded-md border border-input bg-background px-3 text-sm"
                {...register('species')}
              >
                <option value={PetSpecies.DOG}>강아지</option>
                <option value={PetSpecies.CAT}>고양이</option>
              </select>
            </Field>
            <Field label="나이(살)" htmlFor={`age-${pet.petId}`} error={errors.age?.message}>
              <Input
                id={`age-${pet.petId}`}
                type="number"
                min={0}
                {...register('age', { valueAsNumber: true })}
              />
            </Field>
            <Field label="몸무게(kg)" htmlFor={`weight-${pet.petId}`} error={errors.weight?.message}>
              <Input
                id={`weight-${pet.petId}`}
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
          {updatePet.isError && (
            <p className="text-xs text-destructive">
              {updatePet.error instanceof ApiError
                ? updatePet.error.message
                : '수정에 실패했습니다.'}
            </p>
          )}
          <div className="flex gap-2">
            <Button size="sm" onClick={onSave} disabled={updatePet.isPending}>
              {updatePet.isPending ? '저장 중…' : '저장'}
            </Button>
            <Button
              size="sm"
              variant="ghost"
              onClick={() => setEditing(false)}
              disabled={updatePet.isPending}
            >
              취소
            </Button>
          </div>
        </CardContent>
      </Card>
    )
  }

  return (
    <Card>
      <CardContent className="flex items-center justify-between p-5">
        <div className="space-y-1">
          <div className="flex items-center gap-2">
            <span className="font-semibold">{pet.name}</span>
            <Badge variant="secondary">
              {pet.species === 'DOG' ? '강아지' : '고양이'}
            </Badge>
          </div>
          <p className="text-sm text-muted-foreground">
            {pet.age}살 · {pet.weight}kg · {pet.neutered ? '중성화 O' : '중성화 X'}
          </p>
        </div>
        <div className="flex items-center gap-1">
          <Button variant="ghost" size="icon" aria-label="수정" onClick={startEdit}>
            <Pencil className="h-4 w-4" />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            aria-label="삭제"
            disabled={deletePet.isPending}
            onClick={() => deletePet.mutate(pet.petId)}
          >
            <Trash2 className="h-4 w-4 text-destructive" />
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}
