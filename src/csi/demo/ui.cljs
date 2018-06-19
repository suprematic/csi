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
        (recur)))

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
       [:a.nav-link {:href "#" :on-click #(rf/dispatch [::exit :exit-reason])} "Exit Process"]]]]]])

(defn render []
  (reagent/render [demonstration]
    (js/document.getElementById "root")))

(render)
