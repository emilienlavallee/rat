from rpi_hardware_pwm import HardwarePWM

from .env import TARGET_SERVO_BOTTOM_PIN, TARGET_SERVO_TOP_PIN

# MG996R: 500–2500 over 0–180°, 50 Hz (period = 20 ms)
MIN_ANGLE = 0
MAX_ANGLE = 180
MIN_PULSE_WIDTH = 500
MAX_PULSE_WIDTH = 2500
PERIOD_US = 20000


def angle_to_duty(angle: int) -> float:
    clamped = max(MIN_ANGLE, min(MAX_ANGLE, angle))
    pw_us = MIN_PULSE_WIDTH + (clamped / MAX_ANGLE) * (MAX_PULSE_WIDTH - MIN_PULSE_WIDTH)
    return pw_us / PERIOD_US * 100


# GPIO 18 = PWM channel 0, GPIO 19 = PWM channel 1
_PIN_TO_CHANNEL = {18: 0, 19: 1}


class Servos:
    def __init__(self) -> None:
        bottom_ch = _PIN_TO_CHANNEL[TARGET_SERVO_BOTTOM_PIN]
        top_ch = _PIN_TO_CHANNEL[TARGET_SERVO_TOP_PIN]
        self.bottom = HardwarePWM(pwm_channel=bottom_ch, hz=50)
        self.top = HardwarePWM(pwm_channel=top_ch, hz=50)
        self.bottom.start(angle_to_duty(0))
        self.top.start(angle_to_duty(0))

    def set_horizontal_angle(self, angle: int) -> None:
        self.bottom.change_duty_cycle(angle_to_duty(angle))

    def set_vertical_angle(self, angle: int) -> None:
        self.top.change_duty_cycle(angle_to_duty(angle))

    def close(self) -> None:
        self.bottom.stop()
        self.top.stop()
