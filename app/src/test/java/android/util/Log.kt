package android.util

object Log {
    const val ASSERT = 7
    const val DEBUG = 3
    const val ERROR = 6
    const val INFO = 4
    const val VERBOSE = 2
    const val WARN = 5

    fun d(tag: String?, msg: String?): Int = DEBUG
    fun d(tag: String?, msg: String?, tr: Throwable?): Int = DEBUG
    fun i(tag: String?, msg: String?): Int = INFO
    fun w(tag: String?, msg: String?): Int = WARN
    fun w(tag: String?, msg: String?, tr: Throwable?): Int = WARN
    fun e(tag: String?, msg: String?): Int = ERROR
    fun e(tag: String?, msg: String?, tr: Throwable?): Int = ERROR
}
