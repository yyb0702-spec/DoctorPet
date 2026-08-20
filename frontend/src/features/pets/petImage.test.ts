// 업로드 전 검증과 presigned PUT의 계약을 고정한다.
// presigned URL에 Authorization 헤더가 붙으면 S3가 서명 불일치로 거절하므로, 공용 axios 대신
// fetch로 Content-Type만 실어 보내는 것이 이 흐름의 핵심 제약이다.
import { afterEach, describe, expect, it, vi } from 'vitest'
import { uploadPetImageFile } from './api'
import {
  PET_IMAGE_MAX_BYTES,
  validatePetImageFile,
} from './petImage'

function file(type: string, size = 1024): File {
  const f = new File(['x'], 'pet.img', { type })
  // File 크기를 직접 만들지 않고 정의만 바꿔 대용량 케이스를 흉내낸다.
  Object.defineProperty(f, 'size', { value: size })
  return f
}

describe('validatePetImageFile', () => {
  it('서버 허용 목록(jpeg·png·webp)만 통과시킨다', () => {
    expect(validatePetImageFile(file('image/jpeg'))).toBeNull()
    expect(validatePetImageFile(file('image/png'))).toBeNull()
    expect(validatePetImageFile(file('image/webp'))).toBeNull()
    // 서버 @Pattern이 거절하는 형식은 발급 요청을 보내기 전에 막는다.
    expect(validatePetImageFile(file('image/gif'))).not.toBeNull()
    expect(validatePetImageFile(file('application/pdf'))).not.toBeNull()
  })

  it('상한을 넘는 크기는 이유를 돌려준다', () => {
    expect(validatePetImageFile(file('image/png', PET_IMAGE_MAX_BYTES))).toBeNull()
    expect(
      validatePetImageFile(file('image/png', PET_IMAGE_MAX_BYTES + 1)),
    ).not.toBeNull()
  })
})

describe('uploadPetImageFile', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('Content-Type만 실어 PUT한다(Authorization 미포함)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200 })
    vi.stubGlobal('fetch', fetchMock)
    const target = file('image/png')

    await uploadPetImageFile('https://bucket.example/key?sig=abc', target)

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe('https://bucket.example/key?sig=abc')
    expect(init.method).toBe('PUT')
    expect(init.headers).toEqual({ 'Content-Type': 'image/png' })
    expect(init.body).toBe(target)
  })

  it('업로드가 실패하면 저장 확정으로 넘어가지 않도록 에러를 던진다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 403 }))

    await expect(
      uploadPetImageFile('https://bucket.example/key', file('image/png')),
    ).rejects.toThrow(/403/)
  })
})

describe('uploadPetImageFile 네트워크 실패', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  // 로컬 백엔드(fake 스토리지)는 존재하지 않는 주소를 주므로 이 경로를 실제로 자주 탄다.
  it('fetch 자체가 실패하면 영어 TypeError 대신 한국어 안내로 바꿔 던진다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))

    await expect(
      uploadPetImageFile('http://localhost:9000/fake-bucket/x.png', file('image/png')),
    ).rejects.toThrow('이미지 저장소에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.')
  })
})
