// dev:mock 오류 응답이 백엔드 ErrorCode와 어긋나지 않도록 기계적으로 대조한다.
// 목이 실제와 다른 코드·상태·문구를 내면 dev:mock으로 검증한 오류 UX가 실연동에서 그대로 재현되지
// 않는다(PR #180 리뷰 P2 — 영수증 404/409 혼동). 사람 눈 대신 이 테스트가 잡는다.
import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { describe, expect, it } from 'vitest'

// 실행 위치(저장소 루트/frontend 어디서 돌리든)에 의존하지 않도록 위로 올라가며 백엔드 소스를 찾는다.
function findBackendSrc(): string {
  let dir = process.cwd()
  for (let depth = 0; depth < 5; depth += 1) {
    const candidate = join(dir, 'src/main/java')
    if (existsSync(candidate)) return candidate
    dir = dirname(dir)
  }
  throw new Error('백엔드 소스(src/main/java)를 찾지 못했습니다.')
}

const BACKEND_SRC = findBackendSrc()
const MOCK_FILE = join(dirname(BACKEND_SRC), '../../frontend/src/mocks/demoHandlers.ts')

// Spring HttpStatus 이름 → 숫자. 목에서 실제로 쓰는 것만 둔다(모르는 이름은 아래에서 드러난다).
const HTTP_STATUS: Record<string, number> = {
  BAD_REQUEST: 400,
  UNAUTHORIZED: 401,
  FORBIDDEN: 403,
  NOT_FOUND: 404,
  CONFLICT: 409,
  INTERNAL_SERVER_ERROR: 500,
}

function findErrorCodeFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = join(dir, entry.name)
    if (entry.isDirectory()) return findErrorCodeFiles(full)
    return entry.name.endsWith('ErrorCode.java') ? [full] : []
  })
}

interface BackendCode {
  status: string
  message: string
}

// enum 상수는 한 줄이거나 여러 줄로 줄바꿈돼 있다. 둘 다 받는다.
// 예: PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "PAYMENT_005", "결제 정보를 찾을 수 없습니다."),
// 도메인 접두사에 밑줄이 여러 개인 코드(PAYMENT_METHOD_003)도 받는다 — [A-Z]+만 쓰면 그 계열이
// 통째로 map에서 빠져, 목이 그 코드를 써도 "백엔드에 없는 코드"로 오판한다.
const BACKEND_ENTRY = /HttpStatus\.([A-Z_]+),\s*"([A-Z][A-Z_]*_\d{3})",\s*"([^"]*)"/g

function loadBackendCodes(): Map<string, BackendCode> {
  const codes = new Map<string, BackendCode>()
  for (const file of findErrorCodeFiles(BACKEND_SRC)) {
    const source = readFileSync(file, 'utf-8')
    for (const [, status, code, message] of source.matchAll(BACKEND_ENTRY)) {
      codes.set(code, { status, message })
    }
  }
  return codes
}

// 예: fail('PAYMENT_013', '영수증을 발급할 수 있는 결제가 아닙니다.', 409)
const MOCK_FAILURE = /fail\(\s*'([^']*)',\s*'([^']*)',\s*(\d{3}),?\s*\)/g

interface MockFailure {
  code: string
  message: string
  status: number
}

function loadMockFailures(): MockFailure[] {
  const source = readFileSync(MOCK_FILE, 'utf-8')
  return [...source.matchAll(MOCK_FAILURE)].map(([, code, message, status]) => ({
    code,
    message,
    status: Number(status),
  }))
}

describe('dev:mock 오류 계약', () => {
  const backendCodes = loadBackendCodes()
  const mockFailures = loadMockFailures()

  // 경로가 바뀌어 아무것도 못 읽으면 아래 검증이 통째로 무의미해지므로 먼저 막는다.
  it('백엔드 ErrorCode와 목 실패 응답을 모두 읽어온다', () => {
    expect(backendCodes.size).toBeGreaterThan(30)
    expect(mockFailures.length).toBeGreaterThan(5)
  })

  it.each(loadMockFailures())(
    '$code($status)는 백엔드 ErrorCode와 일치한다',
    ({ code, message, status }) => {
      const backend = backendCodes.get(code)
      // enum 이름(RECEIPT_NOT_AVAILABLE)이 아니라 와이어 코드(PAYMENT_013)를 써야 걸린다.
      expect(backend, `백엔드에 없는 코드: ${code}`).toBeDefined()
      const expected = HTTP_STATUS[backend!.status]
      expect(expected, `HTTP_STATUS에 없는 이름: ${backend!.status}`).toBeDefined()
      expect(expected, `${code} HTTP 상태가 백엔드와 다름`).toBe(status)
      expect(backend!.message, `${code} 메시지가 백엔드와 다름`).toBe(message)
    },
  )
})
