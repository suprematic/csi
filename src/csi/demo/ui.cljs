(ns csi.demo.ui
  (:require
   [cljs.core.async :as async]
   [reagent.core :as reagent]
   [re-frame.core :as rf]
   [csi.core :as csi])
  (:require-macros
   [cljs.core.async.macros :refer [go alt! go-loop]]))


(rf/reg-event-db ::set-mbox
  (fn [db [_ mbox]]
    (go-loop []
      (when-let [message (<! mbox)]
        (.info js/console "ui-message :: mbox" message)
        (recur))
      
      (.info js/console "ui-exit :: " (csi/exit-reason mbox))) 

    (assoc db ::mbox mbox)))

(rf/reg-event-fx ::connect
  (fn [m _]
    (when-not (-> m :db ::mbox)
      (go
        (when-let [mbox (<! (csi/mbox "ws://localhost:8086"))]
          (.info js/console "ui-connect :: mbox" mbox)
          (rf/dispatch [::set-mbox mbox]))))
    {}))

(rf/reg-event-fx ::disconnect
  (fn [{:keys [db]} _]
    (when-let [mbox (::mbox db)]
      (.info js/console "ui-disconnect :: mbox" mbox)
      (csi/close! mbox))
    {:db (dissoc db ::mbox)}))

(rf/reg-event-fx ::send-message
  (fn [{:keys [db]} [_ message]]
    (when-let [mbox (::mbox db)]
      (let [pid (csi/self mbox)]
        (csi/cast! mbox 'otplike.process/! [pid message])))
    {}))

(rf/reg-event-fx ::call-function
  (fn [{:keys [db]} [_ func args timeout]]
    (when-let [mbox (::mbox db)]
      (let [pid (csi/self mbox)]
        (go
          (if-let [result (<! (csi/call! mbox func args timeout))]
            (.info js/console "ui-call :: result" result)
            (.info js/console "ui-call :: error" (csi/exit-reason mbox))))))
    {}))


(rf/reg-event-fx ::exit
  (fn [{:keys [db]} [_ reason]]
    (when-let [mbox (::mbox db)]
      (let [pid (csi/self mbox)]
        (csi/cast! mbox 'otplike.process/exit [reason])))
    {}))

(defn demonstration []
  [:div.container
   [:nav.navbar.navbar-light.bg-light.navbar-expand-lg
    [:a.navbar-brand {:href "#"} "CSI"]
    [:div.navbar-collaps
     [:ul.navbar-nav
      [:li.nav-item
       [:a.nav-link {:href "#" :on-click #(rf/dispatch [::connect])} "Connect"]]
      [:li.nav-item
       [:a.nav-link {:href "#" :on-click #(rf/dispatch [::disconnect])} "Disconnect"]]
      [:li.nav-item
       [:a.nav-link {:href "#" :on-click #(rf/dispatch [::send-message ::ping])} "Send Message"]]
      [:li.nav-item
       [:a.nav-link {:href "#" :on-click #(rf/dispatch [::call-function 'clojure.core/str ["a" "b" "c"] 1000])} "Call Function"]]

      [:li.nav-item
       [:a.nav-link {:href "#" :on-click #(rf/dispatch [::call-function 'otplike.csi.core/crash-fn [] 1000])} "Call Crash"]]

      [:li.nav-item
       [:a.nav-link {:href "#" :on-click #(rf/dispatch [::call-function 'otplike.csi.core/sleep-fn [1000] 500])} "Call Timeout"]]
      
      [:li.nav-item
       [:a.nav-link {:href "#" :on-click #(rf/dispatch [::exit :exit-reason])} "Exit Process"]]]]]])

(defn render []
  (reagent/render [demonstration]
    (js/document.getElementById "root")))

(render)
