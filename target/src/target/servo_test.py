#!/usr/bin/env python3
"""Step both servos through 0–270° in 30° increments, pausing between each step.

Usage:
  uv run servo_test.py
"""

import time
import lgpio

SERVO_A = 18
SERVO_B = 12
STEP    = 30
DELAY   = 1.0  # seconds between steps


def angle_to_pw(angle: int) -> int:
    # DS3218: 500–2500μs over 0–270°
    return 500 + int((angle / 270) * 2000)


h = lgpio.gpiochip_open(0)
lgpio.gpio_claim_output(h, SERVO_A)
lgpio.gpio_claim_output(h, SERVO_B)

try:
    for angle in range(0, 271, STEP):
        print(f"Angle: {angle}°")
        lgpio.tx_servo(h, SERVO_A, angle_to_pw(angle), 50, 0, 0)
        lgpio.tx_servo(h, SERVO_B, angle_to_pw(angle), 50, 0, 0)
        time.sleep(DELAY)
finally:
    lgpio.tx_servo(h, SERVO_A, 0, 0, 0, 0)
    lgpio.tx_servo(h, SERVO_B, 0, 0, 0, 0)
    lgpio.gpiochip_close(h)
    print("Done.")
