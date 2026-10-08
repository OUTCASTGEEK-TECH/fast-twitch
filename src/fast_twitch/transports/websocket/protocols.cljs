(ns fast-twitch.transports.websocket.protocols)

(defprotocol Listener
  (on-open [listener socket])
  (on-message [listener socket message])
  (on-pong [listener socket data])
  (on-error [listener socket error])
  (on-close [listener socket code reason]))

(defprotocol PingListener
  (on-ping [listener socket data]))

(defprotocol Socket
  (-open? [socket])
  (-send [socket message])
  (-ping [socket data])
  (-pong [socket data])
  (-close [socket code reason]))

(defprotocol AsyncSocket
  (-send-async [socket message succeed fail]))
