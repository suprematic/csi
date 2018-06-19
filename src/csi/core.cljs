(ns csi.core
  (:require
   [cljs.core.async :as async]
   [cljs.core.async.impl.protocols :as p]
   [cljs.core.match :refer-macros [match]]
   [cognitect.transit :as transit])

  (:require-macros
   [cljs.core.async.macros :refer [go alt! go-loop]]))

(defrecord Pid [id pname]) 

(defprotocol IErlangMBox
  (close! [_])
  (cast! [_ func args])
  (call! [_ func params])
  (self  [_])
  (exit-reason [_]))

(def transit-reader
  (transit/reader :json
    {:handlers
     {"pid"
      (fn [{:keys [id pname]}]
        (->Pid id pname))}}))

(def transit-writer
  (transit/writer :json
    {:handlers
     {Pid
      (transit/write-handler (constantly "pid")
        (fn [{:keys [id pname]}]
          {:id id :pname pname}))}}))

(defn- make-ws [url]
  (let [result (async/chan)
        in (async/chan)
        out (async/chan)
        ws (js/WebSocket. url)]

    (.addEventListener ws "open"
      (fn [event]
        (.debug js/console "websocket :: on open" event)

        (go-loop []
          (when-let [message (<! in)]
            (.debug js/console "ws-channel :: 'in' message, sending to WebSocket" message)
            (.send ws (transit/write transit-writer message))
            (recur)))

        (async/put! result
          (reify
            p/Channel
            (close! [_]
              (.debug js/console "ws-channel :: close requested, closing WebSocket")
              (.close ws))

            (closed? [_]
              (= (.-readyState ws) 3)) ;;; closed state

            p/ReadPort
            (take! [_ handler]
              (p/take! out handler))

            p/WritePort
            (put! [_ val handler]
              (p/put! in val handler))))))

    (.addEventListener ws "close"
      (fn [event]
        (.debug js/console "websocket :: on close, closing channels" event)
        (async/close! in)
        (async/close! out)))

    (.addEventListener ws "message"
      (fn [message]
        (.debug js/console "websocket :: on message" message)
        (async/put! out
          (transit/read transit-reader (.-data message)))))

    result))

(defn make-mbox [pid ws]
  (let [out (async/chan)
        exit-reason (atom nil)]
    (go-loop []
      (match (<! ws)
        nil
        (do
          (.debug js/console (str "mbox :: disconnect"))
          (reset! exit-reason :disconnected)
          (async/close! out))

        [:otplike.csi.core/exit reason]
        (do
          (.debug js/console (str "mbox :: exit, reason=" reason))
          (reset! exit-reason reason)
          (async/close! out))

        [:otplike.csi.core/message payload]
        (do
          (.debug js/console (str "mbox :: message, payload=" payload))
          (>! out payload)
          (recur))))

    (reify
      IErlangMBox
      (close! [_]
        (.debug js/console "mbox :: close requested, closing ws-channel")
        (p/close! ws) 
        nil)
      
      (cast!  [_ fn args]
        (async/put! ws [:otplike.csi.core/cast fn args])
        nil)

      (call! [_ func args]
        nil)
      
      (self [_]
        pid)

      (exit-reason [_]
        @exit-reason)

      p/ReadPort
      (take! [_ handler]
        (p/take! out handler)))))


(defn mbox [url]
  (.debug js/console "handshake :: create mbox with url=" url)
  (go
    (let [ws (<! (make-ws url))]
      (match (<! ws)
        [:otplike.csi.core/self pid]
        (do
          (.debug js/console "handshake :: counterparty self" pid)
          (make-mbox pid ws))

        nil
        (do
          (.warn js/console "handshake :: unexpected connection close")
          nil)

        unexpected
        (do

          (.warn js/console "handshake :: unexpected message" unexpected)
          nil)))))

