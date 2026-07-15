package com.dictation.server

object InklingNative {

    init { System.loadLibrary("inkling_jni") }

    interface ProgressListener {
        fun onProgress(jobId: String, stage: Int, percent: Int)
    }

    @JvmStatic
    external fun nativeConvert(
        inputPath: String,
        outputPath: String,
        optionsJson: String,
        jobId: String,
        listener: ProgressListener
    ): Int

    @JvmStatic
    external fun nativeVersion(): String
}
