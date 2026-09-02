package com.rayneo.innercosmos

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2

/**
 * 3DoF head look-around from the glasses' IMU (game rotation vector: gyro + accelerometer, no
 * compass, so no magnetic jumps). Produces a smoothed yaw/pitch offset, relative to a reference
 * captured when the tour boards or on [recenter], that the renderer applies to the camera's
 * look direction only. The rail, the ship and the cinematic reframings are untouched, so the
 * guest can look around the passage without ever steering.
 *
 * A held offset drifts back to centre over about a minute, so slow gyro drift never leaves the
 * guest looking at a wall.
 */
class GazeCamera(context: Context) : SensorEventListener {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val rot = FloatArray(9)
    private var refYaw = Float.NaN
    private var refPitch = 0f

    /** Smoothed offsets in radians: yaw positive = looking right, pitch positive = looking up. */
    @Volatile var yaw = 0f
    @Volatile var pitch = 0f
    /** Absolute heading / pitch of the glasses (radians), for the debug HUD. */
    @Volatile var rawYaw = 0f
    @Volatile var rawPitch = 0f
    @Volatile var enabled = true

    fun available(): Boolean = sensor != null

    fun start() {
        val s = sensor ?: run { Log.w(TAG, "no rotation-vector sensor: head look-around disabled"); return }
        sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        Log.i(TAG, "gaze from ${s.name}")
    }

    fun stop() = sm.unregisterListener(this)

    /** Make the current head pose "straight ahead". */
    fun recenter() { refYaw = Float.NaN; yaw = 0f; pitch = 0f }

    override fun onSensorChanged(e: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rot, e.values)
        // The display faces the eyes, so the glasses' -Z axis (out of the lenses, away from the
        // face) is the gaze direction; columns of R are the device axes in world (east, north, up).
        val gx = -rot[2]; val gy = -rot[5]; val gz = -rot[8]
        val heading = atan2(gx, gy)
        val elevation = asin(gz.coerceIn(-1f, 1f))
        rawYaw = heading; rawPitch = elevation
        if (refYaw.isNaN()) { refYaw = heading; refPitch = elevation }
        var dy = heading - refYaw
        while (dy > PI) dy -= (2 * PI).toFloat()
        while (dy < -PI) dy += (2 * PI).toFloat()
        val dp = elevation - refPitch
        if (!enabled) {
            // Keep the reference glued to the head so re-enabling starts straight ahead.
            refYaw = heading; refPitch = elevation
            yaw += (0f - yaw) * 0.1f; pitch += (0f - pitch) * 0.1f
            return
        }
        // Positive = right / up, as documented (the renderer's right vector is f x up).
        yaw += (dy.coerceIn(-1.75f, 1.75f) - yaw) * 0.25f
        pitch += (dp.coerceIn(-1.0f, 1.0f) - pitch) * 0.25f
        // Soft re-centre: a held offset walks the reference toward the head (~1 minute).
        refYaw += dy * 0.0015f
        refPitch += dp * 0.0015f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private companion object { const val TAG = "ICGaze" }
}
