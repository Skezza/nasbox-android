package android.util

class Log {
    companion object {
        const val ASSERT = 7
        const val DEBUG = 3
        const val ERROR = 6
        const val INFO = 4
        const val VERBOSE = 2
        const val WARN = 5

        @JvmStatic
        fun d(tag: String?, msg: String?): Int = DEBUG

        @JvmStatic
        fun d(tag: String?, msg: String?, tr: Throwable?): Int = DEBUG

        @JvmStatic
        fun i(tag: String?, msg: String?): Int = INFO

        @JvmStatic
        fun w(tag: String?, msg: String?): Int = WARN

        @JvmStatic
        fun w(tag: String?, msg: String?, tr: Throwable?): Int = WARN

        @JvmStatic
        fun e(tag: String?, msg: String?): Int = ERROR

        @JvmStatic
        fun e(tag: String?, msg: String?, tr: Throwable?): Int = ERROR
    }
}
