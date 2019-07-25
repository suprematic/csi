(ns otplike.csi.core
  (:require [goog.string :as gstr]
            [goog.string.format]
            [cljs.core.async :as async]
            [cljs.core.async.impl.protocols :as p]
            [cljs.core.match :refer-macros [match]]
            [cognitect.transit :as transit])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))


;; =================================================================
;; Internal
;; =================================================================


(defrecord Pid [id])


(defrecord TRef [id])


(defprotocol IErlangMBox
  (close! [_])
  (reset-context! [_ ctx])
  (update-context! [_ f])
  (cast! [_ func args])
  (call!
    [_ func params]
    [_ func params timeout])
  (self [_])
  (exit-reason [_]))


(def ^:private default-ping-timeout-ms 20000)


(def ^:private default-transit-read-handlers
  {"pid"
   (fn [{:keys [id]}]
     (->Pid id))

   "otp-ref"
   (fn [{:keys [id]}]
     (->TRef id))})


(defn- make-transit-reader [{:keys [transit-read-handlers]}]
  (transit/reader
    :json
    {:handlers (merge default-transit-read-handlers transit-read-handlers)}))


(def ^:private default-transit-write-handlers
  {Pid
   (transit/write-handler
     (constantly "pid")
     (fn [{:keys [id]}]
       {:id id}))

   TRef
   (transit/write-handler
     (constantly "otp-ref")
     (fn [{:keys [id]}]
       {:id id}))})


(defn- make-transit-writer [{:keys [transit-write-handlers]}]
  (transit/writer
    :json
    {:handlers (merge default-transit-write-handlers transit-write-handlers)}))


(defn- make-ws [url opts]
  (let [result (async/chan)
        in (async/chan)
        out (async/chan)
        ws (js/WebSocket. url)
        transit-reader (make-transit-reader opts)
        transit-writer (make-transit-writer opts)]

    (.addEventListener
      ws "open"
      (fn [event]
        (go-loop []
          (when-let [message (<! in)]
            (.send ws (transit/write transit-writer message))
            (recur)))

        (async/put!
          result
          (reify
            p/Channel
            (close! [_]
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
        (async/close! in)
        (async/close! out)
        (async/put! result out)))

    (.addEventListener
      ws "message"
      (fn [event]
        (let [msg (transit/read transit-reader (.-data event))]
          (async/put! out msg))))
    result))


(defn- make-mbox [pid ws]
  (let [out (async/chan)
        exit-reason (atom nil)
        counter (atom 0)
        returns (atom {})
        context (atom nil)
        terminate!
        (fn [reason]
          (when-not @exit-reason
            (reset! exit-reason reason))
          (async/close! out)
          (doseq [return (vals @returns)]
            (async/close! return)))]

    (go-loop [ping-counter 0]
      (let [timeout (async/timeout default-ping-timeout-ms)]
        (match (async/alts! [ws timeout] :priority true)
          [_ timeout]
          (do
            (async/put! ws [::ping ping-counter])
            (recur (inc ping-counter)))

          [nil ws]
          (do
            (.debug js/console (str "[CSI] mbox :: connection closed"))
            (terminate! :disconnected))

          [[::exit reason] ws]
          (do
            (.debug js/console (str "[CSI] mbox :: exit, reason=" reason))
            (terminate! reason))

          [[::pong payload] ws]
          ;; TODO check the counter
          (recur ping-counter)

          [[::ping payload] ws]
          (do
            (async/put! ws [::pong payload])
            (recur ping-counter))

          [[::message payload] ws]
          (do
            (>! out payload)
            (recur ping-counter))

          [[::return value correlation] ws]
          (do
            (when-let [return (get @returns correlation)]
              (swap! returns dissoc correlation)
              (async/>! return value)
              (async/close! return))
            (recur ping-counter)))))

    (reify
      IErlangMBox
      (close! [_]
        (p/close! ws)
        nil)

      (reset-context! [_ ctx]
        (reset! context ctx))

      (update-context! [_ f]
        (swap! context f))

      (cast! [_ func args]
        (async/put! ws [[::cast func args] @context])
        nil)

      (call! [this func args]
        (call! this func args 5000))

      (call! [this func args timeout]
        (let [correlation (swap! counter inc)
              result-chan (async/chan)
              return-chan (async/chan)
              timeout-chan (async/timeout timeout)]
          (swap! returns assoc correlation result-chan)
          (go
            (match (async/alts! [result-chan timeout-chan])
              [nil result-chan]
              (do
                (terminate! [:disconnected [func args]])
                (close! this))

              [result result-chan]
              (when-not (async/offer! return-chan result)
                (.warn
                  js/console
                  (str
                    "[CSI] mbox :: call, correlation=" correlation
                    " - no receiver for the result, dropping")))

              [nil timeout-chan]
              (do
                (.debug
                  js/console
                  (str
                    "[CSI] mbox :: call, correlation="
                    correlation " - timeout"))
                (terminate! [:timeout [func args] timeout])
                (close! this))))

          (async/put! ws [[::call func args correlation] @context])
          return-chan))

      (self [_]
        pid)

      (exit-reason [_]
        @exit-reason)

      p/ReadPort
      (take! [_ handler]
        (p/take! out handler)))))


;; =================================================================
;; API
;; =================================================================


(defn pid->str [pid]
  (gstr/format "<%d>" (:id pid)))


(defn mbox
  ([url]
   (mbox url {}))
  ([url opts]
   (go
     (let [ws (<! (make-ws url opts))]
       (match (<! ws)
         [::self pid]
         (do
           (.debug js/console
             "[CSI] handshake :: counterparty pid" (pid->str pid))
           (make-mbox pid ws))

         nil
         (do
           (.warn js/console "[CSI] handshake :: unexpected connection close")
           nil)

         unexpected
         (do
           (.warn js/console "[CSI] handshake :: unexpected message" unexpected)
           nil))))))
