// 펫 한 마리 — 보기 / 인라인 수정 / 삭제.
import { useRef, useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { ImagePlus, PawPrint, Pencil, Trash2 } from 'lucide-react'
import { petSchema, type PetInput } from './schema'
import { useDeletePet, useUpdatePet, useUploadPetImage } from './hooks'
import { validatePetImageFile, PET_IMAGE_CONTENT_TYPES } from './petImage'
import type { Pet } from './api'
import { Card, CardContent } from '@/components/ui/card'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Field } from '@/components/common/Field'
import { SPECIES_ORDER, SPECIES_LABEL, speciesLabel } from '@/lib/species'
import { ApiError } from '@/lib/api/error'

export function PetRow({ pet }: { pet: Pet }) {
  const [editing, setEditing] = useState(false)
  const deletePet = useDeletePet()
  const updatePet = useUpdatePet(pet.petId)
  const uploadImage = useUploadPetImage(pet.petId)
  const fileInputRef = useRef<HTMLInputElement>(null)
  // 파일 형식·용량처럼 서버에 보내기 전에 걸러낸 이유. 업로드 실패(3단계 중 어디든)는 mutation 에러로 본다.
  const [imageError, setImageError] = useState<string | null>(null)

  const handlePickImage = (file: File | undefined) => {
    if (!file) return
    const reason = validatePetImageFile(file)
    if (reason) {
      setImageError(reason)
      return
    }
    setImageError(null)
    uploadImage.mutate(file)
  }

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
                {SPECIES_ORDER.map((s) => (
                  <option key={s} value={s}>
                    {SPECIES_LABEL[s]}
                  </option>
                ))}
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
        <div className="flex items-center gap-3">
          {/* 프로필 사진. 없으면 발자국 아이콘으로 자리를 지킨다(레이아웃이 흔들리지 않게). */}
          {pet.imageUrl ? (
            <img
              src={pet.imageUrl}
              alt={`${pet.name} 프로필 사진`}
              className="h-12 w-12 rounded-full object-cover"
            />
          ) : (
            <span className="flex h-12 w-12 items-center justify-center rounded-full bg-muted">
              <PawPrint className="h-5 w-5 text-muted-foreground" />
            </span>
          )}
          <div className="space-y-1">
            <div className="flex items-center gap-2">
              <span className="font-semibold">{pet.name}</span>
              <Badge variant="secondary">
                {speciesLabel(pet.species)}
              </Badge>
            </div>
            <p className="text-sm text-muted-foreground">
              {pet.age}살 · {pet.weight}kg · {pet.neutered ? '중성화 O' : '중성화 X'}
            </p>
            {(imageError || uploadImage.isError) && (
              <p className="text-xs text-destructive">
                {imageError ??
                  (uploadImage.error instanceof Error
                    ? uploadImage.error.message
                    : '사진을 올리지 못했어요.')}
              </p>
            )}
          </div>
        </div>
        <div className="flex items-center gap-1">
          {/*
            발급 → presigned PUT → PATCH 확정의 3단계를 이 버튼 하나가 잇는다. 파일 선택창은
            숨긴 input으로 열고, 같은 파일을 다시 골라도 change가 뜨도록 값을 비운다.
          */}
          <input
            ref={fileInputRef}
            type="file"
            accept={PET_IMAGE_CONTENT_TYPES.join(',')}
            className="hidden"
            onChange={(e) => {
              handlePickImage(e.target.files?.[0])
              e.target.value = ''
            }}
          />
          <Button
            variant="ghost"
            size="icon"
            aria-label="사진 변경"
            disabled={uploadImage.isPending}
            onClick={() => fileInputRef.current?.click()}
          >
            <ImagePlus className="h-4 w-4" />
          </Button>
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
