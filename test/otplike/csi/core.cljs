(ns otplike.csi.core
  (:require [cljs.test :refer-macros [is deftest run-tests]]
            [cognitect.transit :as transit]))


(deftest custom-transit-handlers
  (defrecord R [x])
  (let [r (->R :value)
        write-handlers
        {R (transit/write-handler (constantly "myrec") #(into {} %))}
        read-handlers {"myrec" #(->R (:x %))}
        opts {:transit-write-handlers write-handlers
              :transit-read-handlers read-handlers}
        transit-reader (make-transit-reader opts)
        transit-writer (make-transit-writer opts)
        pid (->Pid 123)
        otp-ref (->TRef 234)
        data {:a 1 :b ["str"] :pid pid :ref otp-ref :r r}]
    (let [t (transit/write transit-writer data)
          res (transit/read transit-reader t)]
      (is (= res data)))))


(run-tests)
