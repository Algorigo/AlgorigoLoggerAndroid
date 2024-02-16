package com.algorigo.logger.formatter

import android.util.Log
import android.util.Printer
import java.io.IOException
import java.io.OutputStream
import java.io.PrintWriter
import java.io.Writer
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetEncoder
import java.nio.charset.CodingErrorAction

/**
 * This is clone of com.android.internal.util.FastPrintWriter
 */
internal class FastPrintWriter : PrintWriter {
    private class DummyWriter : Writer() {
        @Throws(IOException::class)
        override fun close() {
            val ex = UnsupportedOperationException("Shouldn't be here")
            throw ex
        }

        @Throws(IOException::class)
        override fun flush() {
            close()
        }

        @Throws(IOException::class)
        override fun write(buf: CharArray, offset: Int, count: Int) {
            close()
        }
    }

    private val mBufferLen: Int
    private val mText: CharArray
    private var mPos = 0
    private val mOutputStream: OutputStream?
    private val mAutoFlush: Boolean
    private val mSeparator: String
    private val mWriter: Writer?
    private val mPrinter: Printer?
    private lateinit var mCharset: CharsetEncoder
    private val mBytes: ByteBuffer?
    private var mIoError = false

    /**
     * Constructs a new {@code PrintWriter} with {@code out} as its target
     * stream and a custom buffer size. The parameter {@code autoFlush} determines
     * if the print writer automatically flushes its contents to the target stream
     * when a newline is encountered.
     *
     * @param out
     *            the target output stream.
     * @param autoFlush
     *            indicates whether contents are flushed upon encountering a
     *            newline sequence.
     * @param bufferLen
     *            specifies the size of the FastPrintWriter's internal buffer; the
     *            default is 8192.
     * @throws NullPointerException
     *             if {@code out} is {@code null}.
     */
    @JvmOverloads
    constructor(out: OutputStream?, autoFlush: Boolean = false, bufferLen: Int = 8192) : super(
        DummyWriter(), autoFlush
    ) {
        if (out == null) {
            throw NullPointerException("out is null")
        }
        mBufferLen = bufferLen
        mText = CharArray(bufferLen)
        mBytes = ByteBuffer.allocate(mBufferLen)
        mOutputStream = out
        mWriter = null
        mPrinter = null
        mAutoFlush = autoFlush
        mSeparator = System.lineSeparator()
        initDefaultEncoder()
    }
    /**
     * Constructs a new {@code PrintWriter} with {@code wr} as its target
     * writer and a custom buffer size. The parameter {@code autoFlush} determines
     * if the print writer automatically flushes its contents to the target writer
     * when a newline is encountered.
     *
     * @param wr
     *            the target writer.
     * @param autoFlush
     *            indicates whether to flush contents upon encountering a
     *            newline sequence.
     * @param bufferLen
     *            specifies the size of the FastPrintWriter's internal buffer; the
     *            default is 8192.
     * @throws NullPointerException
     *             if {@code wr} is {@code null}.
     */
    @JvmOverloads
    constructor(wr: Writer?, autoFlush: Boolean = false, bufferLen: Int = 8192) : super(
        DummyWriter(), autoFlush
    ) {
        if (wr == null) {
            throw NullPointerException("wr is null")
        }
        mBufferLen = bufferLen
        mText = CharArray(bufferLen)
        mBytes = null
        mOutputStream = null
        mWriter = wr
        mPrinter = null
        mAutoFlush = autoFlush
        mSeparator = System.lineSeparator()
        initDefaultEncoder()
    }

    /**
     * Flushes this writer and returns the value of the error flag.
     *
     * @return `true` if either an `IOException` has been thrown
     * previously or if `setError()` has been called;
     * `false` otherwise.
     * @see .setError
     */
    override fun checkError(): Boolean {
        flush()
        synchronized(lock) { return mIoError }
    }

    /**
     * Sets the error state of the stream to false.
     * @since 1.6
     */
    override fun clearError() {
        synchronized(lock) { mIoError = false }
    }

    /**
     * Sets the error flag of this writer to true.
     */
    override fun setError() {
        synchronized(lock) { mIoError = true }
    }

    private fun initDefaultEncoder() {
        mCharset = Charset.defaultCharset().newEncoder()
        mCharset.onMalformedInput(CodingErrorAction.REPLACE)
        mCharset.onUnmappableCharacter(CodingErrorAction.REPLACE)
    }

    @Throws(IOException::class)
    private fun appendLocked(c: Char) {
        var pos = mPos
        if (pos >= mBufferLen - 1) {
            flushLocked()
            pos = mPos
        }
        mText[pos] = c
        mPos = pos + 1
    }

    @Throws(IOException::class)
    private fun appendLocked(str: String, i: Int, length: Int) {
        var i = i
        val BUFFER_LEN = mBufferLen
        if (length > BUFFER_LEN) {
            val end = i + length
            while (i < end) {
                val next = i + BUFFER_LEN
                appendLocked(str, i, if (next < end) BUFFER_LEN else end - i)
                i = next
            }
            return
        }
        var pos = mPos
        if (pos + length > BUFFER_LEN) {
            flushLocked()
            pos = mPos
        }
        str.toCharArray(mText, pos, i, i + length)
        mPos = pos + length
    }

    @Throws(IOException::class)
    private fun appendLocked(buf: CharArray, i: Int, length: Int) {
        var i = i
        val BUFFER_LEN = mBufferLen
        if (length > BUFFER_LEN) {
            val end = i + length
            while (i < end) {
                val next = i + BUFFER_LEN
                appendLocked(buf, i, if (next < end) BUFFER_LEN else end - i)
                i = next
            }
            return
        }
        var pos = mPos
        if (pos + length > BUFFER_LEN) {
            flushLocked()
            pos = mPos
        }
        System.arraycopy(buf, i, mText, pos, length)
        mPos = pos + length
    }

    @Throws(IOException::class)
    private fun flushBytesLocked() {
        if (!mIoError) {
            var position: Int
            if (mBytes!!.position().also { position = it } > 0) {
                mBytes.flip()
                mOutputStream!!.write(mBytes.array(), 0, position)
                mBytes.clear()
            }
        }
    }

    @Throws(IOException::class)
    private fun flushLocked() {
        //Log.i("PackageManager", "flush mPos=" + mPos);
        if (mPos > 0) {
            if (mOutputStream != null) {
                val charBuffer = CharBuffer.wrap(mText, 0, mPos)
                var result = mCharset!!.encode(charBuffer, mBytes, true)
                while (!mIoError) {
                    if (result.isError) {
                        throw IOException(result.toString())
                    } else if (result.isOverflow) {
                        flushBytesLocked()
                        result = mCharset!!.encode(charBuffer, mBytes, true)
                        continue
                    }
                    break
                }
                if (!mIoError) {
                    flushBytesLocked()
                    mOutputStream.flush()
                }
            } else if (mWriter != null) {
                if (!mIoError) {
                    mWriter.write(mText, 0, mPos)
                    mWriter.flush()
                }
            } else {
                var nonEolOff = 0
                val sepLen = mSeparator.length
                val len = if (sepLen < mPos) sepLen else mPos
                while (nonEolOff < len && mText[mPos - 1 - nonEolOff]
                    == mSeparator[mSeparator.length - 1 - nonEolOff]
                ) {
                    nonEolOff++
                }
                if (nonEolOff >= mPos) {
                    mPrinter!!.println("")
                } else {
                    mPrinter!!.println(String(mText, 0, mPos - nonEolOff))
                }
            }
            mPos = 0
        }
    }

    /**
     * Ensures that all pending data is sent out to the target. It also
     * flushes the target. If an I/O error occurs, this writer's error
     * state is set to `true`.
     */
    override fun flush() {
        synchronized(lock) {
            try {
                flushLocked()
                if (!mIoError) {
                    mOutputStream?.flush() ?: mWriter?.flush()
                }
            } catch (e: IOException) {
                Log.w("FastPrintWriter", "Write failure", e)
                setError()
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            try {
                flushLocked()
                mOutputStream?.close() ?: mWriter?.close()
            } catch (e: IOException) {
                Log.w("FastPrintWriter", "Write failure", e)
                setError()
            }
        }
    }

    /**
     * Prints the string representation of the specified character array
     * to the target.
     *
     * @param charArray
     * the character array to print to the target.
     * @see .print
     */
    override fun print(charArray: CharArray) {
        synchronized(lock) {
            try {
                appendLocked(charArray, 0, charArray.size)
            } catch (e: IOException) {
                Log.w("FastPrintWriter", "Write failure", e)
                setError()
            }
        }
    }

    /**
     * Prints the string representation of the specified character to the
     * target.
     *
     * @param ch
     * the character to print to the target.
     * @see .print
     */
    override fun print(ch: Char) {
        synchronized(lock) {
            try {
                appendLocked(ch)
            } catch (e: IOException) {
                Log.w("FastPrintWriter", "Write failure", e)
                setError()
            }
        }
    }

    /**
     * Prints a string to the target. The string is converted to an array of
     * bytes using the encoding chosen during the construction of this writer.
     * The bytes are then written to the target with `write(int)`.
     *
     *
     * If an I/O error occurs, this writer's error flag is set to `true`.
     *
     * @param str
     * the string to print to the target.
     * @see .write
     */
    override fun print(str: String?) {
        val strNonNull = str ?: (null as Any?).toString()
        synchronized(lock) {
            try {
                appendLocked(strNonNull, 0, strNonNull.length)
            } catch (e: IOException) {
                Log.w("FastPrintWriter", "Write failure", e)
                setError()
            }
        }
    }

    override fun print(inum: Int) {
        if (inum == 0) {
            print("0")
        } else {
            super.print(inum)
        }
    }

    override fun print(lnum: Long) {
        if (lnum == 0L) {
            print("0")
        } else {
            super.print(lnum)
        }
    }

    /**
     * Prints a newline. Flushes this writer if the autoFlush flag is set to `true`.
     */
    override fun println() {
        synchronized(lock) {
            try {
                appendLocked(mSeparator, 0, mSeparator.length)
                if (mAutoFlush) {
                    flushLocked()
                }
            } catch (e: IOException) {
                Log.w("FastPrintWriter", "Write failure", e)
                setError()
            }
        }
    }

    override fun println(inum: Int) {
        if (inum == 0) {
            println("0")
        } else {
            super.println(inum)
        }
    }

    override fun println(lnum: Long) {
        if (lnum == 0L) {
            println("0")
        } else {
            super.println(lnum)
        }
    }

    /**
     * Prints the string representation of the character array `chars` followed by a newline.
     * Flushes this writer if the autoFlush flag is set to `true`.
     */
    override fun println(chars: CharArray) {
        print(chars)
        println()
    }

    /**
     * Prints the string representation of the char `c` followed by a newline.
     * Flushes this writer if the autoFlush flag is set to `true`.
     */
    override fun println(c: Char) {
        print(c)
        println()
    }

    /**
     * Writes `count` characters from `buffer` starting at `offset` to the target.
     *
     *
     * This writer's error flag is set to `true` if this writer is closed
     * or an I/O error occurs.
     *
     * @param buf
     * the buffer to write to the target.
     * @param offset
     * the index of the first character in `buffer` to write.
     * @param count
     * the number of characters in `buffer` to write.
     * @throws IndexOutOfBoundsException
     * if `offset < 0` or `count < 0`, or if `offset + count` is greater than the length of `buf`.
     */
    override fun write(buf: CharArray, offset: Int, count: Int) {
        synchronized(lock) {
            try {
                appendLocked(buf, offset, count)
            } catch (e: IOException) {
                Log.w("FastPrintWriter", "Write failure", e)
                setError()
            }
        }
    }

    /**
     * Writes one character to the target. Only the two least significant bytes
     * of the integer `oneChar` are written.
     *
     *
     * This writer's error flag is set to `true` if this writer is closed
     * or an I/O error occurs.
     *
     * @param oneChar
     * the character to write to the target.
     */
    override fun write(oneChar: Int) {
        synchronized(lock) {
            try {
                appendLocked(oneChar.toChar())
            } catch (e: IOException) {
                Log.w("FastPrintWriter", "Write failure", e)
                setError()
            }
        }
    }

    /**
     * Writes the characters from the specified string to the target.
     *
     * @param str
     * the non-null string containing the characters to write.
     */
    override fun write(str: String) {
        synchronized(lock) {
            try {
                appendLocked(str, 0, str.length)
            } catch (e: IOException) {
                Log.w("FastPrintWriter", "Write failure", e)
                setError()
            }
        }
    }

    /**
     * Writes `count` characters from `str` starting at `offset` to the target.
     *
     * @param str
     * the non-null string containing the characters to write.
     * @param offset
     * the index of the first character in `str` to write.
     * @param count
     * the number of characters from `str` to write.
     * @throws IndexOutOfBoundsException
     * if `offset < 0` or `count < 0`, or if `offset + count` is greater than the length of `str`.
     */
    override fun write(str: String, offset: Int, count: Int) {
        synchronized(lock) {
            try {
                appendLocked(str, offset, count)
            } catch (e: IOException) {
                Log.w("FastPrintWriter", "Write failure", e)
                setError()
            }
        }
    }

    /**
     * Appends a subsequence of the character sequence `csq` to the
     * target. This method works the same way as `PrintWriter.print(csq.subsequence(start, end).toString())`. If `csq` is `null`, then the specified subsequence of the string "null"
     * will be written to the target.
     *
     * @param csq
     * the character sequence appended to the target.
     * @param start
     * the index of the first char in the character sequence appended
     * to the target.
     * @param end
     * the index of the character following the last character of the
     * subsequence appended to the target.
     * @return this writer.
     * @throws StringIndexOutOfBoundsException
     * if `start > end`, `start < 0`, `end < 0` or
     * either `start` or `end` are greater or equal than
     * the length of `csq`.
     */
    override fun append(csq: CharSequence?, start: Int, end: Int): PrintWriter {
        val csqNonNull = csq ?: "null"
        val output = csqNonNull.subSequence(start, end).toString()
        write(output, 0, output.length)
        return this
    }
}