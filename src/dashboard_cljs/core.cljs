(ns dashboard-cljs.core
  (:require [clojure.browser.repl :as repl]
            [crate.core :as crate]
            [pikaday]
            [moment]
            ))

(defn send-xhr
  "Send a xhr to url using callback and HTTP method."
  [url callback method & [data headers]]
  (.send goog.net.XhrIo url callback method data headers))

(def orders-points nil)

(def default-date "2015-05-01")

(def google-map nil)

;; the base url to use for server calls
(def base-server-url (-> (.getElementById js/document "base-url")
                          (.getAttribute "value")))

(defn create-point
  [google-map lat lng]
  (js/google.maps.Circle.
   (js-obj "strokeColor" "#ff0000"
           "strokeOpacity" 1.0
           "strokeWeight" 2
           "fillColor" "#ff0000"
           "fillOpacity" 1
           "map" google-map
           "center" (js-obj "lat" lat "lng" lng)
           "radius" 10)))

(defn clear-points
  []
  (if (not (nil? (seq orders-points)))
    (mapv #(.setMap % nil) orders-points)))

(defn show-points
  []
  (do (mapv #(.setMap % google-map) orders-points)))

(defn set-orders-points!
  [date]
  ;; clear out the current orders-points
  (let [url (str base-server-url "orders-since-date")
        data (js/JSON.stringify (clj->js {:date date}))
        header (clj->js {"Content-Type" "application/json"})
        orders (send-xhr url
                         #(let [xhrio (.-target %)
                                response (.getResponseJson xhrio)
                                orders (aget response "orders")]
                            (set!
                             orders-points
                             (mapv
                              (fn [order]
                                (create-point google-map (.-lat order) (.-lng order)))
                              orders)))
                         "POST" data header)]))

(defn orders-control
  "A button for viewing orders on the map"
  []
  (let [containing-div (crate/html [:div])
        control-ui (crate/html [:div
                                {:class "setCenterUI"
                                 :title "Click to recenter the map"}])
        checkbox     (crate/html [:input {:type "checkbox"
                                          :name "orders"
                                          :value "orders"
                                          :class "orders-checkbox"
                                          :checked true}])
        orders-date-input (crate/html [:input {:type "text"
                                               :name "orders-date"
                                               :value default-date}])
        date-picker  (js/Pikaday. (js-obj "field" orders-date-input
                                          "format" "YYYY-MM-DD"
                                          "onSelect"
                                          #(do
                                             (clear-points)
                                             (set-orders-points!
                                              orders-date-input.value))))
        control-text (crate/html [:div {:class "setCenterText"}
                                  checkbox "Orders"
                                  " since date " orders-date-input])]
    (.appendChild containing-div control-ui)
    (.appendChild control-ui control-text)
    (.addEventListener checkbox "click" #(if (aget checkbox "checked")
                                           (show-points)
                                           (clear-points)))
    ;; the line below does not seem to be needed when adding only one control to
    ;; the map. Left in as a reminder in case adding more than one control has
    ;; an affect. If so, than index management should be handled in add-control!
    ;;(aset containing-div "index" 1)
    containing-div))

(defn add-control!
  "Add control to g-map using position"
  [g-map control position]
  (.push  (aget g-map "controls" position)
          control))

(defn ^:export init-map
  []
  (do
      (set! google-map
            (js/google.maps.Map.
             (.getElementById js/document "map")
             (js-obj "center"
                     (js-obj "lat" 34.0714522 "lng" -118.40362)
                     "zoom" 16) ))
      ;; add the orders control to the map
      (add-control! google-map (orders-control)
                    js/google.maps.ControlPosition.LEFT_TOP)
      ;; initialize the point
      (set-orders-points! default-date)))
