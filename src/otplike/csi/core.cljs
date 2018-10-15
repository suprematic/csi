(ns otplike.csi.core
  (:require
   [goog.string :as gstr]
   [goog.string.format]
   [cljs.core.async :as async]
   [cljs.core.async.impl.protocols :as p]
   [cljs.core.match :refer-macros [match]]
   [cognitect.transit :as transit])
  (:require-macros
   [cljs.core.async.macros :refer [go alt! go-loop]]))

(defrecord Pid [id])

(defn pid->str [pid]
  (gstr/format "<%d>" (:id pid)))

(defrecord TRef [id])

(defprotocol IErlangMBox
  (close! [_])
  (cast! [_ func args])
  (call!
   [_ func params]
   [_ func params timeout])
  (self [_])
  (exit-reason [_]))

(def transit-reader
  (transit/reader
   :json
   {:handlers
    {"pid"
     (fn [{:keys [id]}]
       (->Pid id))

     "otp-ref"
     (fn [{:keys [id]}]
       (->TRef id))}}))

(def transit-writer
  (transit/writer
   :json
   {:handlers
    {Pid
     (transit/write-handler
      (constantly "pid")
      (fn [{:keys [id]}]
        {:id id}))

     TRef
     (transit/write-handler
      (constantly "otp-ref")
      (fn [{:keys [id]}]
        {:id id}))}}))

(defn- make-ws [url]
  (let [result (async/chan)
        in (async/chan)
        out (async/chan)
        ws (js/WebSocket. url)]

    (.addEventListener
     ws "open"
     (fn [event]
       (.debug js/console "websocket :: on open" event)

       (go-loop []
         (when-let [message (<! in)]
           (.debug js/console "ws-channel :: 'in' message, sending to WebSocket" message)
           (.send ws (transit/write transit-writer message))
           (recur)))

       (async/put!
        result
        (reify
          p/Channel
          (close! [_]
            (.debug js/console "ws-channel :: close requested, closing WebSocket")
            (.close ws))

          (closed? [_]
            (= (.-readyState ws) 3))

          p/ReadPort
          (take! [_ handler]
            (p/take! out handler))

          p/WritePort
          (put! [_ val handler]
            (p/put! in val handler))))))

    (.addEventListener
     ws "close"
     (fn [event]
       (.debug js/console "websocket :: on close" event)
       (async/close! in)
       (async/close! out)
       (async/put! result out)))

    (.addEventListener
     ws "message"
     (fn [event]
       (.debug js/console "websocket :: on message" event)
       (let [msg (transit/read transit-reader (.-data event))]
         (async/put! out msg))))
    result))

(defn make-mbox [pid ws]
  (let [out (async/chan)
        exit-reason (atom nil)
        counter (atom 0)
        returns (atom {})
        terminate!
        (fn [reason]
          (when-not @exit-reason
            (reset! exit-reason reason))
          (async/close! out)
          (doseq [return (vals @returns)]
            (async/close! return)))]

    (go-loop []
      (match (<! ws)
        nil
        (do
          (.debug js/console (str "mbox :: disconnect"))
          (terminate! :disconnected))

        [::exit reason]
        (do
          (.debug js/console (str "mbox :: exit, reason=" reason))
          (terminate! reason))

        [::ping payload]
        (do
          (.debug js/console (str "mbox :: ping, payload=" payload))
          (async/put! ws [::pong payload])
          (recur))

        [::message payload]
        (do
          (.debug js/console (str "mbox :: message, payload=" payload))
          (>! out payload)
          (recur))

        [::return value correlation]
        (do
          (.debug js/console (str "mbox :: return, correlation=" correlation ", value=" (pr-str value)))
          (when-let [return (get @returns correlation)]
            (swap! returns dissoc correlation)
            (async/>! return value)
            (async/close! return))
          (recur))))

    (reify
      IErlangMBox
      (close! [_]
        (.debug js/console "mbox :: close requested, closing ws-channel")
        (p/close! ws)
        nil)

      (cast! [_ func args]
        (async/put! ws [::cast func args])
        nil)

      (call! [this func args]
        (call! this func args 5000))

      (call! [this func args timeout]
        (let [correlation (swap! counter inc)
              result-chan (async/chan)
              return-chan (async/chan)
              timeout-chan (async/timeout timeout)]
          (swap! returns assoc correlation result-chan)
          (.debug js/console (str "mbox :: call! correlation=" correlation ", args" (pr-str args)))
          (go
            (match (async/alts! [result-chan timeout-chan])
              [nil result-chan]
              (do
                (.debug js/console (str "mbox :: call! correlation=" correlation " - disconnected"))
                (terminate! [:disconnected [func args]])
                (close! this))

              [result result-chan]
              (when-not (async/offer! return-chan result)
                (.warn js/console (str "mbox :: call! correlation=" correlation " - no receiver for the result, dropping")))

              [nil timeout-chan]
              (do
                (.debug js/console (str "mbox :: call! correlation=" correlation " - timeout"))
                (terminate! [:timeout [func args] timeout])
                (close! this))))

          (async/put! ws [::call func args correlation])
          return-chan))

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
        [::self pid]
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
