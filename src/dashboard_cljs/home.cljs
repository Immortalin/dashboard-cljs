(ns dashboard-cljs.home
  (:require [reagent.core :as r]
            [dashboard-cljs.datastore :as datastore]
            [dashboard-cljs.utils :refer [base-url unix-epoch->fmt
                                          unix-epoch->hrf cents->$dollars
                                          markets]]
            [dashboard-cljs.components :refer [StaticTable TableHeadSortable
                                               RefreshButton CountPanel]]
            [clojure.string :as s]))


(def state (r/atom {:current-order nil}))

(defn orders-count-panel
  "Component for displaying today's complete orders count"
  []
  (fn []
    ;; todays order count panel
    (let [new-orders (fn [orders]
                       (let [today-begin (-> (js/moment)
                                             (.startOf "day")
                                             (.unix))
                             complete-time (fn [order]
                                             (-> (str
                                                  "kludgeFix 1|"
                                                  (:event_log order))
                                                 (s/split #"\||\s")
                                                 (->> (apply hash-map))
                                                 (get "complete")))]
                         (->> orders
                              (filter #(= (:status %) "complete"))
                              (map
                               #(assoc % :time-completed
                                       (complete-time %)))
                              (filter #(>= (:time-completed %)
                                           today-begin)))))]
      [CountPanel {:data (new-orders @datastore/orders)
                   :description "completed orders today!"
                   :panel-class "panel-primary"
                   :icon-class  "fa-shopping-cart"}])))

(defn dash-map-link-panel
  "A panel for displaying a link to the dash map"
  []
  (fn []
    ;; a link to the dash map
    [:div {:class "panel panel-default"}
     [:div {:class "panel-body"}
      [:h2
       [:a {:href (str base-url "dash-map-couriers")
            :target "_blank"}
        "Open real-time map in new window"]]]]))

(defn order-row
  "A table row for an order in a table. current-order is the one currently being
  viewed"
  [current-order]
  (fn [order]
    [:tr {:class (when (= (:id order)
                          (:id @current-order))
                   "active")
          :on-click #(reset! current-order order)}
     ;; order status
     [:td (:status order)]
     ;; courier assigned
     [:td (:courier_name order)]
     ;; order placed
     [:td (unix-epoch->hrf
           (:target_time_start order))]
     ;; delivery time
     [:td (str (.diff (js/moment.unix (:target_time_end order))
                      (js/moment.unix (:target_time_start order))
                      "hours")
               " Hr")]
     ;; username
     [:td (:customer_name order)]
     ;; phone #
     [:td (:customer_phone_number order)]
     ;; email #
     [:td (:email order)]
     ;; street address
     [:td
      [:i {:class "fa fa-circle"
           :style {:color (:zone-color order)}}]
      (:address_street order)]]))

(defn order-table-header
  "props is:
  {
  :sort-keyword   ; reagent atom keyword used to sort table
  :sort-reversed? ; reagent atom boolean for determing if the sort is reversed
  }"
  [props]
  (fn [props]
    [:thead
     [:tr
      [TableHeadSortable
       (conj props {:keyword :status})
       "Status"]
      [TableHeadSortable
       (conj props {:keyword :courier_name})
       "Courier"
       ]
      [TableHeadSortable
       (conj props {:keyword :target_time_start})
       "Order Placed"]
      [:th {:style {:font-size "16px"
                    :font-weight "normal"}} "Delivery Time"]
      [TableHeadSortable
       (conj props {:keyword :customer_name})
       "Username"] 
      [TableHeadSortable
       (conj props {:keyword :customer_phone_number})
       "Phone #"] 
      [:th {:style {:font-size "16px"
                    :font-weight "normal"}} "Email"]
      [TableHeadSortable
       (conj props {:keyword :address_street})
       "Street Address"]]]))

(defn ongoing-jobs-panel
  "Display the jobs that have yet to be completed or cancelled"
  [orders]
  (let [current-order (r/cursor state [:current-order])
        sort-keyword (r/atom :target_time_start)
        sort-reversed? (r/atom true)]
    (fn [orders]
      (let [sort-fn (if @sort-reversed?
                      (partial sort-by @sort-keyword)
                      (comp reverse (partial sort-by @sort-keyword)))
            filter-fn (fn [order]
                        (contains? #{"unassigned"
                                     "assigned"
                                     "accepted"
                                     "enroute"
                                     "servicing"} (:status order)))
            displayed-orders orders
            sorted-orders (->> displayed-orders
                               (filter filter-fn)
                               sort-fn)]
        (if (nil? @current-order)
          (reset! current-order (first sorted-orders)))
        [:div {:class "panel panel-default"}
         [:div {:class "panel-body"}
          [:h3 "Ongoing Jobs"]]
         [:div {:class "table-responsive"}
          [StaticTable
           {:table-header [order-table-header {:sort-keyword sort-keyword
                                               :sort-reversed? sort-reversed?}]
            :table-row (order-row current-order)}
           sorted-orders]]]))))