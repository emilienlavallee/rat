import {Chunk, Effect, Either, Fiber, Queue} from "effect"
import * as zmq from "zeromq"
import type {AppMessage} from "./messages"
import {validateAppMessage} from "./validation"
import {logger} from "./logger"

/*
 * Generated senders emit explicit nulls for absent optional fields, which
 * `additionalProperties: false` then rejects. Stripping them first keeps a
 * well-meaning sender from being dropped as invalid.
 */
function stripNulls<T extends Record<string, unknown>>(obj: T): T {
  for (const [key, value] of Object.entries(obj)) {
    if (value === null) {
      delete obj[key]
    } else if (typeof value === "object") {
      stripNulls(value as Record<string, unknown>)
    }
  }
  return obj
}

export class SocketMessage {
  readonly receivedAt: number

  constructor(
    readonly source: string,
    readonly payload: string,
    readonly message: AppMessage,
  ) {
    this.receivedAt = Date.now()
  }

  toString(): string {
    return this.payload
  }
}

export function parseSocketMessage(
  source: string,
  data: Buffer,
): SocketMessage | null {
  const payload = data.toString("utf8")
  try {
    const parsed: unknown = stripNulls(JSON.parse(payload))
    if (!validateAppMessage(parsed)) {
      logger.warn(
        `Invalid message on ${source}:`,
        validateAppMessage.errors,
      )
      logger.warn({payload: parsed}, "Invalid payload")
      return null
    }
    return new SocketMessage(source, payload, parsed)
  } catch {
    return null
  }
}

export interface PolledSocket {
  name: string
  sock: zmq.Pull
}

export class SocketPoller {
  private readonly queue: Queue.Queue<SocketMessage>
  private readonly fibers: Array<Fiber.RuntimeFiber<never, never>>

  constructor(sockets: PolledSocket[]) {
    this.queue = Effect.runSync(Queue.unbounded<SocketMessage>())
    this.fibers = sockets.map(({name, sock}) =>
      Effect.runFork(this.ingest(name, sock)),
    )
  }

  private ingest(
    name: string,
    sock: zmq.Pull,
  ): Effect.Effect<never, never, never> {
    const receiveLoop = Effect.promise(() => sock.receive()).pipe(
      Effect.map((parts) =>
        parseSocketMessage(name, parts[0] ?? Buffer.alloc(0)),
      ),
      Effect.flatMap((msg) =>
        msg
          ? Queue.offer(this.queue, msg)
          : Effect.logWarning(`Skipping invalid message on ${name}`).pipe(
            Effect.as(false),
          ),
      ),
      Effect.forever,
    )
    return Effect.acquireUseRelease(
      () => receiveLoop,
      () => Effect.sync(() => sock.close()),
    )(Effect.sync(() => {}))
  }

  poll(timeoutMs: number): Effect.Effect<SocketMessage[], never, never> {
    const queue = this.queue
    return Effect.gen(function* () {
      const first = yield* Queue.take(queue).pipe(
        Effect.timeout(timeoutMs),
        Effect.either,
      )
      if (Either.isLeft(first)) {
        return []
      }
      const rest = yield* Queue.takeAll(queue)
      return [first.right, ...Chunk.toArray(rest)]
    })
  }

  stop(): Effect.Effect<void, never, never> {
    return Effect.forEach(this.fibers, Fiber.interrupt).pipe(Effect.asVoid)
  }
}
