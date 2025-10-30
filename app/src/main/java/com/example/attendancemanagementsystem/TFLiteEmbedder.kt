package com.example.attendancemanagementsystem

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.lang.IllegalStateException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

class TFLiteEmbedder private constructor(
    private val interpreter: Interpreter,
    private val inputShape: IntArray,
    private val outputShape: IntArray
) {
    // lock to ensure only one thread calls interpreter.run at a time
    private val inferLock = Any()

    companion object {
        private const val TAG = "TFLiteEmbedder"

        /**
         * Load interpreter from assets file name, returns a TFLiteEmbedder instance.
         */
        fun createFromAssets(context: Context, assetName: String): TFLiteEmbedder {
            val fd = context.assets.openFd(assetName)
            val stream = FileInputStream(fd.fileDescriptor)
            val channel = stream.channel
            val startOffset = fd.startOffset
            val declaredLength = fd.declaredLength
            val modelBuffer = channel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
            val options = Interpreter.Options()

            // use a single thread for the native interpreter to avoid concurrent native issues
            options.setNumThreads(1)

            // create interpreter
            val interpreter = Interpreter(modelBuffer, options)

            val inputShape = interpreter.getInputTensor(0).shape() // e.g. [1,112,112,3]
            val outputShape = interpreter.getOutputTensor(0).shape() // e.g. [1,128]
            Log.i(TAG, "Interpreter loaded. inputShape=${inputShape.joinToString()} outputShape=${outputShape.joinToString()}")
            return TFLiteEmbedder(interpreter, inputShape, outputShape)
        }
    }

    /**
     * Runs inference for a given face bitmap (should match model input dimensions).
     * Returns L2-normalized embedding float array.
     *
     * This method synchronizes on a lock to prevent concurrent native calls which can cause SIGSEGV.
     */
    fun getEmbedding(faceBitmap: Bitmap): FloatArray {
        val h = inputShape.getOrNull(1) ?: throw IllegalStateException("Invalid input shape")
        val w = inputShape.getOrNull(2) ?: throw IllegalStateException("Invalid input shape")

        // scale bitmap to model input size
        val input = Bitmap.createScaledBitmap(faceBitmap, w, h, true)

        // prepare input buffer (float32)
        val inputBuffer = ByteBuffer.allocateDirect(4 * 1 * h * w * 3).order(ByteOrder.nativeOrder())
        val intValues = IntArray(h * w)
        input.getPixels(intValues, 0, w, 0, 0, w, h)
        var pixel = 0
        for (i in 0 until h) {
            for (j in 0 until w) {
                val value = intValues[pixel++]
                // normalize to [-1,1] - match how the model was trained
                val r = ((value shr 16 and 0xFF) - 127.5f) / 128f
                val g = ((value shr 8 and 0xFF) - 127.5f) / 128f
                val b = ((value and 0xFF) - 127.5f) / 128f
                inputBuffer.putFloat(r)
                inputBuffer.putFloat(g)
                inputBuffer.putFloat(b)
            }
        }
        inputBuffer.rewind()

        // output buffer
        val embeddingDim = outputShape.getOrNull(1) ?: throw IllegalStateException("Invalid output shape")
        val outputBuffer = Array(1) { FloatArray(embeddingDim) }

        // synchronize native invocation to avoid concurrent access crashes
        synchronized(inferLock) {
            try {
                interpreter.run(inputBuffer, outputBuffer)
            } catch (e: Throwable) {
                // log error and rethrow as exception so callers can handle appropriately
                Log.e("TFLiteEmbedder", "Interpreter run failed: ${e.message}", e)
                throw e
            }
        }

        val emb = outputBuffer[0]
        // L2 normalize
        var sum = 0f
        for (v in emb) sum += v * v
        val norm = sqrt(sum)
        if (norm == 0f) throw IllegalStateException("Embedding norm is zero")
        val normalized = FloatArray(emb.size)
        for (i in emb.indices) normalized[i] = emb[i] / norm
        return normalized
    }

    fun close() {
        try {
            interpreter.close()
        } catch (e: Exception) {
            Log.w("TFLiteEmbedder", "close error: ${e.message}")
        }
    }
}
