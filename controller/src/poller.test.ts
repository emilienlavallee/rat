import {describe, expect, it} from "vitest"
import {parseSocketMessage} from "./poller"

const resultPayload = (overrides: Record<string, unknown> = {}) => ({
  kind: "inference-result",
  x: 320.5,
  y: 240.5,
  ...overrides,
})

const parse = (value: unknown) =>
  parseSocketMessage("test", Buffer.from(JSON.stringify(value), "utf8"))

describe("parseSocketMessage with inference-result", () => {
  it("accepts a well-formed result", () => {
    const msg = parse(resultPayload())
    expect(msg).not.toBeNull()
    const message = msg!.message
    if (message.kind !== "inference-result") throw new Error("wrong kind")
    expect(message.x).toBe(320.5)
    expect(message.y).toBe(240.5)
  })

  it("preserves the no-detection sentinel", () => {
    const msg = parse(resultPayload({x: -1, y: -1}))
    expect(msg).not.toBeNull()
    const message = msg!.message
    if (message.kind !== "inference-result") throw new Error("wrong kind")
    expect(message).toEqual({kind: "inference-result", x: -1, y: -1})
  })

  it("does not let stripNulls eat a legitimate zero coordinate", () => {
    const msg = parse(resultPayload({x: 0, y: 0}))
    expect(msg).not.toBeNull()
    const message = msg!.message
    if (message.kind !== "inference-result") throw new Error("wrong kind")
    expect(message).toEqual({kind: "inference-result", x: 0, y: 0})
  })

  it("rejects a result missing required fields", () => {
    const payload = resultPayload()
    delete (payload as Record<string, unknown>).y
    expect(parse(payload)).toBeNull()
  })

  it("rejects unknown properties", () => {
    expect(parse(resultPayload({seq: 3}))).toBeNull()
  })

  it("rejects an unknown kind", () => {
    expect(parse({kind: "inference-batch", points: []})).toBeNull()
  })
})

describe("parseSocketMessage with lifecycle messages", () => {
  it("accepts the bare im-alive and setup-done objects", () => {
    expect(parse({kind: "im-alive"})?.message).toEqual({kind: "im-alive"})
    expect(parse({kind: "setup-done"})?.message).toEqual({kind: "setup-done"})
  })

  it("rejects the old envelope form", () => {
    expect(parse({type: "bluetooth", data: {message: "im_alive"}})).toBeNull()
  })
})
