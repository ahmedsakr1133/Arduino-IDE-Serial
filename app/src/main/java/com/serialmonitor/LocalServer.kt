package com.serialmonitor

import fi.iki.elonen.NanoHTTPD
import java.io.IOException

class LocalServer(port: Int, private val onCommand: (String) -> Unit) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val method = session.method
        val uri = session.uri
        
        // Handle OPTIONS for CORS preflight
        if (method == Method.OPTIONS) {
            val response = newFixedLengthResponse(Response.Status.OK, "text/plain", "")
            addCORSHeaders(response)
            return response
        }

        val response = try {
            if (method == Method.POST) {
                val files = mutableMapOf<String, String>()
                session.parseBody(files)
            }

            val params = session.parameters
            val command = params["cmd"]?.get(0) ?: params["command"]?.get(0)

            if (command != null) {
                val decodedCmd = java.net.URLDecoder.decode(command, "UTF-8")
                onCommand(decodedCmd)
                
                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    "{\"status\":\"success\",\"message\":\"Command sent: $decodedCmd\"}"
                )
            } else if (uri == "/" || uri == "") {
                newFixedLengthResponse(
                    Response.Status.OK,
                    "text/plain",
                    "Serial Monitor Server Running\nUse /send?cmd=YOUR_COMMAND"
                )
            } else {
                newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    "application/json",
                    "{\"status\":\"error\",\"message\":\"Missing command parameter (cmd or command)\"}"
                )
            }
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                "{\"status\":\"error\",\"message\":\"${e.message}\"}"
            )
        }

        addCORSHeaders(response)
        return response
    }

    private fun addCORSHeaders(response: Response) {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept, Authorization")
        response.addHeader("Access-Control-Max-Age", "3600")
    }
}
