package stove.ktor.bff.fixtures

import io.ktor.websocket.*

suspend fun WebSocketSession.receiveCloseReason(): CloseReason = checkNotNull((incoming.receive() as Frame.Close).readReason())
