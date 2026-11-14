package dev.ronova.celestia.core.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.FloatBuffer
import java.nio.LongBuffer

enum class F0Mode { RMVPE, FCPE }

data class Params(
    val pitchShift: Int = 0,          // полутоны, -24..+24
    val indexRate: Float = 0f,        // 0..1
    val protect: Float = 0.33f,       // 0..0.5
    val rmsMixRate: Float = 0.25f,    // 0..1
    val temperature: Float = 0.667f,  // шум потока (noise scale), 0..1: больше = «живее», меньше = чище
    val f0Mode: F0Mode = F0Mode.RMVPE,
    val threads: Int = 4,             // 1..8
    val windowSec: Float = 6f,        // 4..8
    val overlapSec: Float = 1f,
    val xnnpack: Boolean = false,     // экспериментально, по умолчанию выключено
)

object Ort {
    val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    /**
     * ТОЛЬКО CPU Execution Provider. Здесь намеренно нет addNnapi()/addQnn()/GPU-делегатов:
     * на MediaTek (Mali) они вешают устройство.
     */
    fun open(path: String, threads: Int, xnnpack: Boolean): OrtSession {
        val o = OrtSession.SessionOptions()
        o.setIntraOpNumThreads(threads.coerceIn(1, 8))
        o.setInterOpNumThreads(1)
        o.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        o.setMemoryPatternOptimization(false)   // длины чанков меняются
        if (xnnpack) runCatching { o.addXnnpack(mapOf("intra_op_num_threads" to threads.toString())) }
        return env.createSession(path, o)
    }

    fun f32(data: FloatArray, vararg shape: Long): OnnxTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)
    fun i64(data: LongArray, vararg shape: Long): OnnxTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(data), shape)

    fun shapeOf(t: OnnxTensor): LongArray = (t.info as TensorInfo).shape
    fun floats(t: OnnxTensor): FloatArray { val fb = t.floatBuffer; return FloatArray(fb.remaining()).also { fb.get(it) } }
}
