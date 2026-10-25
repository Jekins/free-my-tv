package io.freemytv;

/** Damped spring, like macOS animations: response (s) and damping (1 = no bounce). */
final class Spring {
    float value, velocity, target;
    private final float response, damping;
    // With `back` (s): as above up to its target, then, from when it passes it, critically damped with that response,
    // so it overshoots exactly once and comes back softly without swinging again. `aimed` is the target it last set off
    // for, `side` where it started from.
    private final float back;
    private float aimed;
    private boolean past = true;
    private int side;

    Spring(float response, float damping, float initial) {
        this(response, damping, initial, 0);
    }

    Spring(float response, float damping, float initial, float back) {
        this.response = response;
        this.damping = damping;
        this.back = back;
        value = target = aimed = initial;
    }

    void snap(float v) {
        value = target = aimed = v;
        velocity = 0;
        past = true;
    }

    /** Advances `dt` seconds; returns true while still moving. */
    boolean step(float dt) {
        if (settled()) {
            value = target;
            velocity = 0;
            return false;
        }
        if (back > 0 && target != aimed) {
            aimed = target;
            side = (int) Math.signum(value - target);
            past = side == 0;
        }
        float omega = (float) (2 * Math.PI / response), omegaBack = back > 0 ? (float) (2 * Math.PI / back) : omega;
        int steps = Math.max(1, (int) Math.ceil(dt / 0.004f));
        float h = dt / steps;
        for (int i = 0; i < steps; i++) {
            if (!past && (int) Math.signum(value - target) != side) past = true;
            boolean returning = back > 0 && past;
            float w = returning ? omegaBack : omega, friction = 2 * (returning ? 1 : damping) * w;
            float accel = -w * w * (value - target) - friction * velocity;
            velocity += accel * h;
            value += velocity * h;
        }
        return true;
    }

    boolean settled() {
        return Math.abs(value - target) < 0.0008f && Math.abs(velocity) < 0.004f;
    }
}
