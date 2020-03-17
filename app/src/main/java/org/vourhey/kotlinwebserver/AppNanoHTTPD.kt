package org.vourhey.kotlinwebserver

import android.os.Message
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import android.os.Handler
import java.io.FileInputStream

class AppNanoHTTPD : NanoHTTPD {

    private var isFinished = false
    private var myResult : String? = null

    private var mHandler: Handler

    constructor(host: String, port: Int, handler: Handler) : super(host, port) {
        mHandler = handler

    }

    public fun setResult(result: String) {
        myResult = result
        isFinished = true
    }

    override fun serve(session: IHTTPSession?): Response? {
        Log.i("AppNanoHTTPD", session.toString())

        val message = Message()
        message.what = TAKE_PICTURE_ACTION
        mHandler.sendMessage(message)

        while (!isFinished) {
            Log.i("server", "sleepingx")
            Thread.sleep(1000)
        }

        Log.i("Server filepath", myResult!!.substringAfter("file://"))

        val fis = FileInputStream(myResult!!.substringAfter("file://"))

        val mimeType = getMimeTypeForFile(myResult)

        isFinished = false

        return newChunkedResponse(Response.Status.OK, mimeType, fis)
    }

}